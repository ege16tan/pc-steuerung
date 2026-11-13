//! Systemmesswerte fuer die Web-Oberflaeche: CPU-Auslastung, Arbeitsspeicher,
//! Laufzeit. Ohne zusaetzliche Crate – `kernel32` reicht.
//!
//! Die CPU-Auslastung ergibt sich aus zwei Zeitstichproben
//! (`GetSystemTimes`). Die erste Anfrage liefert deshalb `null`; ab der
//! zweiten (nach ~2 s) steht ein Wert.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Serialize;

/// Zwischengespeicherte Stichprobe fuer die CPU-Berechnung.
#[derive(Default)]
pub struct MetricsState {
    previous: Option<Sample>,
}

struct Sample {
    at: Instant,
    idle: u64,
    kernel: u64,
    user: u64,
}

/// Antwort von `GET /metrics`.
#[derive(Debug, Default, Clone, Serialize)]
pub struct Metrics {
    /// CPU-Auslastung in Prozent (eine Nachkommastelle); `None` bei der ersten Anfrage.
    pub cpu_percent: Option<f64>,
    /// Logische Prozessoren.
    pub cpu_cores: u32,
    pub ram_used_mb: u64,
    pub ram_total_mb: u64,
    pub ram_percent: f64,
    /// Laufzeit seit dem letzten Start von Windows (Sekunden).
    pub uptime_s: u64,
}

/// Rechnet aus zwei Stichproben die Auslastung.
/// `kernel` enthaelt auch die Leerlaufzeit, deshalb wird `idle` abgezogen.
pub fn cpu_percent_from(prev: (u64, u64, u64), cur: (u64, u64, u64)) -> Option<f64> {
    let idle = cur.0.saturating_sub(prev.0);
    let kernel = cur.1.saturating_sub(prev.1);
    let user = cur.2.saturating_sub(prev.2);
    let total = kernel + user;
    if total == 0 {
        return None;
    }
    let busy = total.saturating_sub(idle);
    let percent = busy as f64 / total as f64 * 100.0;
    Some((percent * 10.0).round() / 10.0)
}

/// Aktuelle Messwerte lesen und die Stichprobe fortschreiben.
pub fn read(state: &Mutex<MetricsState>) -> Metrics {
    read_impl(state)
}

#[cfg(windows)]
fn read_impl(state: &Mutex<MetricsState>) -> Metrics {
    let mut metrics = Metrics {
        cpu_cores: std::thread::available_parallelism()
            .map(|value| value.get() as u32)
            .unwrap_or(0),
        ..Default::default()
    };

    let mut memory = win::MemoryStatusEx {
        length: std::mem::size_of::<win::MemoryStatusEx>() as u32,
        ..Default::default()
    };
    if unsafe { win::GlobalMemoryStatusEx(&mut memory) } != 0 {
        metrics.ram_total_mb = memory.total_phys / (1024 * 1024);
        metrics.ram_used_mb = memory.total_phys.saturating_sub(memory.avail_phys) / (1024 * 1024);
        if memory.total_phys > 0 {
            let used = memory.total_phys.saturating_sub(memory.avail_phys) as f64;
            let percent = used / memory.total_phys as f64 * 100.0;
            metrics.ram_percent = (percent * 10.0).round() / 10.0;
        }
    }

    metrics.uptime_s = unsafe { win::GetTickCount64() } / 1000;

    let mut idle = win::FileTime::default();
    let mut kernel = win::FileTime::default();
    let mut user = win::FileTime::default();
    let sampled = unsafe { win::GetSystemTimes(&mut idle, &mut kernel, &mut user) } != 0;
    if sampled {
        let current = (idle.value(), kernel.value(), user.value());
        let mut guard = state.lock().expect("Metrik-Sperre");
        metrics.cpu_percent = match guard.previous.as_ref() {
            // Nach Standby/Resume sind alte Stichproben wertlos.
            Some(previous) if previous.at.elapsed() < Duration::from_secs(120) => {
                cpu_percent_from((previous.idle, previous.kernel, previous.user), current)
            }
            _ => None,
        };
        guard.previous = Some(Sample {
            at: Instant::now(),
            idle: current.0,
            kernel: current.1,
            user: current.2,
        });
    }

    metrics
}

#[cfg(not(windows))]
fn read_impl(_state: &Mutex<MetricsState>) -> Metrics {
    Metrics::default()
}

#[cfg(windows)]
mod win {
    #[repr(C)]
    #[derive(Default)]
    pub struct MemoryStatusEx {
        pub length: u32,
        pub memory_load: u32,
        pub total_phys: u64,
        pub avail_phys: u64,
        pub total_page_file: u64,
        pub avail_page_file: u64,
        pub total_virtual: u64,
        pub avail_virtual: u64,
        pub avail_extended_virtual: u64,
    }

    /// FILETIME in 100-ns-Einheiten.
    #[repr(C)]
    #[derive(Default)]
    pub struct FileTime {
        pub low: u32,
        pub high: u32,
    }

    impl FileTime {
        pub fn value(&self) -> u64 {
            ((self.high as u64) << 32) | self.low as u64
        }
    }

    #[link(name = "kernel32")]
    extern "system" {
        pub fn GetSystemTimes(
            idle: *mut FileTime,
            kernel: *mut FileTime,
            user: *mut FileTime,
        ) -> i32;
        pub fn GlobalMemoryStatusEx(buffer: *mut MemoryStatusEx) -> i32;
        pub fn GetTickCount64() -> u64;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn erste_stichprobe_hat_keine_auslastung() {
        assert_eq!(cpu_percent_from((0, 0, 0), (0, 0, 0)), None);
    }

    #[test]
    fn halbe_auslastung_wird_erkannt() {
        // 100 Kernel-Einheiten, davon 50 Leerlauf, kein User-Anteil = 50 %.
        assert_eq!(cpu_percent_from((0, 0, 0), (50, 100, 0)), Some(50.0));
    }

    #[test]
    fn volle_und_leere_auslastung() {
        assert_eq!(cpu_percent_from((0, 0, 0), (0, 200, 0)), Some(100.0));
        assert_eq!(cpu_percent_from((0, 0, 0), (200, 200, 0)), Some(0.0));
    }

    #[test]
    fn nur_unterschiede_zaehlen() {
        // Deltas: 10 Leerlauf, 20 Kernel, 30 User -> 50 gesamt, 40 davon beschaeftigt.
        assert_eq!(cpu_percent_from((100, 100, 100), (110, 120, 130)), Some(80.0));
    }

    #[test]
    fn messwerte_sind_plausibel() {
        let state = Mutex::new(MetricsState::default());
        let first = read(&state);
        assert!(first.cpu_percent.is_none(), "erste Anfrage liefert noch keinen Wert");
        assert!(first.ram_percent >= 0.0 && first.ram_percent <= 100.0);
        let second = read(&state);
        if let Some(percent) = second.cpu_percent {
            assert!(percent >= 0.0 && percent <= 100.0);
        }
        assert!(second.cpu_cores > 0 || cfg!(not(windows)));
    }
}
