//! Erweiterte Messwerte fuer `GET /sensors`: Prozessor, Arbeitsspeicher,
//! Grafikkarte, Netzwerk, Datentraeger, Luefter und Temperaturen.
//!
//! Wie in [`crate::metrics`] kommt alles ohne zusaetzliche Crate aus:
//! `kernel32` liefert Prozessor und Arbeitsspeicher (ueber `metrics`) sowie die
//! Datentraeger (`GetDiskFreeSpaceExW`), `iphlpapi` die Netzwerkzaehler
//! (`GetIfTable2`/`FreeMibTable`) und `nvidia-smi` – falls vorhanden – die
//! Grafikkarte.
//!
//! Zwei Werte brauchen zwei Messungen und sind deshalb beim ersten Aufruf
//! `null`: die CPU-Auslastung (siehe `metrics`) und die Netzwerkraten.
//! Luefter und Temperaturen lassen sich ohne Zusatzbibliothek
//! (WMI/OpenHardwareMonitor) nicht lesen und werden bewusst **nicht**
//! geschaetzt: `available: false` mit Begruendung.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Serialize;

use crate::app::App;

/// Grafikkarte hoechstens alle 5 s neu abfragen (Prozessstart ist teuer).
const GPU_TTL: Duration = Duration::from_secs(5);

/// Zeitlimit fuer `nvidia-smi`; der Endpunkt darf nie haengen bleiben.
#[cfg(windows)]
const GPU_TIMEOUT: Duration = Duration::from_millis(1500);

/// Zwei Netzwerk-Messungen dichter als das sind wertlos (Rundungsrauschen).
const NET_MIN_GAP: Duration = Duration::from_millis(200);

/// IF_OPER_STATUS-Wert "Up" (`ifmib.h`).
const IF_OPER_STATUS_UP: u32 = 1;
/// IF_TYPE_SOFTWARE_LOOPBACK (`ifmib.h`) – zaehlt nicht als echter Traffic.
const IF_TYPE_SOFTWARE_LOOPBACK: u32 = 24;
/// Bit `FilterInterface` in `InterfaceAndOperStatusFlags` (`ifmib.h`).
const IF_FLAG_FILTER_INTERFACE: u8 = 0x02;

/// Zaehlt dieses Interface fuer Summe und Raten?
///
/// Nur aktive, echte Schnittstellen. Loopback zaehlt nie, und NDIS-Filter
/// (z. B. "Ethernet-WFP Native MAC Layer LightWeight Filter-0000") spiegeln die
/// Bytes der darunterliegenden Karte – mitgezaehlt waeren die Raten doppelt.
pub fn counts_traffic(oper_status: u32, if_type: u32, flags: u8) -> bool {
    oper_status == IF_OPER_STATUS_UP
        && if_type != IF_TYPE_SOFTWARE_LOOPBACK
        && (flags & IF_FLAG_FILTER_INTERFACE) == 0
}

// ---------------- Antwort von GET /sensors ----------------

/// Antwort von `GET /sensors` (Feldnamen sind Vertrag).
#[derive(Debug, Clone, Serialize)]
pub struct Sensors {
    pub cpu: Cpu,
    pub ram: Ram,
    pub gpu: Gpu,
    pub network: Network,
    pub disks: Vec<Disk>,
    pub fans: Unavailable,
    pub temperatures: Unavailable,
}

/// Prozessor: `percent` ist bei der ersten Stichprobe `null` (siehe `metrics`).
#[derive(Debug, Clone, Serialize)]
pub struct Cpu {
    pub available: bool,
    pub percent: Option<f64>,
    pub cores: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

/// Arbeitsspeicher in MiB.
#[derive(Debug, Clone, Serialize)]
pub struct Ram {
    pub available: bool,
    pub used_mb: Option<u64>,
    pub total_mb: Option<u64>,
    pub percent: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

/// Grafikkarte (nur NVIDIA ueber `nvidia-smi`, keine WMI-Abhaengigkeit).
#[derive(Debug, Clone, Serialize)]
pub struct Gpu {
    pub available: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub percent: Option<f64>,
    pub memory_used_mb: Option<u64>,
    pub memory_total_mb: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl Gpu {
    /// Nicht messbar: nur `available` und `reason`, keine Fantasiewerte.
    pub fn unavailable(reason: &str) -> Gpu {
        Gpu {
            available: false,
            name: None,
            percent: None,
            memory_used_mb: None,
            memory_total_mb: None,
            reason: Some(reason.to_string()),
        }
    }
}

/// Netzwerk: Summe ueber alle aktiven Schnittstellen, Raten aus zwei Messungen.
#[derive(Debug, Clone, Serialize)]
pub struct Network {
    pub available: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub rx_bps: Option<f64>,
    pub tx_bps: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl Network {
    pub fn unavailable(reason: &str) -> Network {
        Network {
            available: false,
            name: None,
            rx_bps: None,
            tx_bps: None,
            reason: Some(reason.to_string()),
        }
    }
}

/// Ein Datentraeger mit Laufwerksbuchstaben (`C:`).
#[derive(Debug, Clone, Serialize)]
pub struct Disk {
    pub name: String,
    pub total_gb: f64,
    pub free_gb: f64,
    /// Belegter Anteil in Prozent.
    pub percent: f64,
}

/// Teil, der ohne Zusatzbibliothek nicht lesbar ist (Luefter, Temperaturen).
#[derive(Debug, Clone, Serialize)]
pub struct Unavailable {
    pub available: bool,
    pub reason: String,
}

impl Unavailable {
    pub fn new(reason: &str) -> Unavailable {
        Unavailable { available: false, reason: reason.to_string() }
    }
}

// ---------------- Zwischengespeicherter Zustand ----------------

/// Zwischengespeicherte Messungen fuer `/sensors`.
///
/// Netzwerk: Summenstand der letzten Messung (Zeitpunkt, gelesene und gesendete
/// Bytes), damit die Rate aus zwei Stichproben entsteht.
/// Grafikkarte: letztes Ergebnis, damit `nvidia-smi` maximal alle 5 s startet.
#[derive(Default)]
pub struct SensorsState {
    network: Option<(Instant, u64, u64)>,
    gpu: Option<(Gpu, Instant)>,
}

// ---------------- Reine Rechenwege (testbar ohne Win32) ----------------

/// Prozentwert mit einer Nachkommastelle.
fn round1(value: f64) -> f64 {
    (value * 10.0).round() / 10.0
}

/// Belegter Anteil in Prozent; `None` ohne Gesamtgroesse (Division durch 0).
pub fn percent_used(total: u64, free: u64) -> Option<f64> {
    if total == 0 {
        return None;
    }
    let free = free.min(total);
    let used = total - free;
    Some(round1(used as f64 / total as f64 * 100.0))
}

/// Bytes in GiB (eine Nachkommastelle).
pub fn gb_from_bytes(bytes: u64) -> f64 {
    round1(bytes as f64 / (1024.0 * 1024.0 * 1024.0))
}

/// Raten aus zwei Summenstaenden in Bytes pro Sekunde.
///
/// `previous` ist `None` bei der ersten Messung (dann gibt es ehrlich `null`),
/// eine Zeitspanne von 0 ergibt ebenfalls `null` (Division durch 0).
/// Zaehler, die zurueckgesprungen sind (Adapter neu gestartet), ergeben 0.
pub fn rates_from_samples(
    previous: Option<(u64, u64)>,
    elapsed_s: f64,
    current: (u64, u64),
) -> (Option<f64>, Option<f64>) {
    let Some((prev_rx, prev_tx)) = previous else {
        return (None, None);
    };
    if !(elapsed_s > 0.0) {
        return (None, None);
    }
    let rx = current.0.saturating_sub(prev_rx) as f64 / elapsed_s;
    let tx = current.1.saturating_sub(prev_tx) as f64 / elapsed_s;
    (Some(round1(rx)), Some(round1(tx)))
}

/// Ein aktives, nicht-Loopback-Interface (nur die Werte, die wir brauchen).
#[derive(Debug, Clone)]
pub struct IfSample {
    pub name: String,
    pub rx: u64,
    pub tx: u64,
}

/// Summe ueber alle Schnittstellen plus Name des Interfaces mit dem meisten
/// Traffic. `None` bei leerer Tabelle (kein aktives Interface).
pub fn sum_interfaces(rows: &[IfSample]) -> Option<(u64, u64, String)> {
    let mut rx = 0u64;
    let mut tx = 0u64;
    let mut busiest: Option<&IfSample> = None;
    for row in rows {
        rx = rx.saturating_add(row.rx);
        tx = tx.saturating_add(row.tx);
        let traffic = row.rx.saturating_add(row.tx);
        let known = busiest
            .map(|best| best.rx.saturating_add(best.tx))
            .unwrap_or(0);
        if busiest.is_none() || traffic > known {
            busiest = Some(row);
        }
    }
    let name = busiest?.name.clone();
    Some((rx, tx, name))
}

/// Eine Zahl aus der `nvidia-smi`-Ausgabe; `N/A`, `[N/A]` und leere Felder
/// werden zu `None` (lieber `null` als ein geratener Wert).
fn number(field: &str) -> Option<f64> {
    let trimmed = field.trim();
    if trimmed.is_empty() || trimmed.contains("N/A") {
        return None;
    }
    trimmed.parse::<f64>().ok()
}

/// Erste Datenzeile von
/// `nvidia-smi --query-gpu=name,utilization.gpu,memory.used,memory.total`.
///
/// Von rechts zerlegt, damit ein Komma im Namen nicht stoert.
pub fn parse_nvidia_smi(text: &str) -> Option<Gpu> {
    let line = text.lines().map(str::trim).find(|line| !line.is_empty())?;
    let mut parts = line.rsplitn(4, ',');
    let total = parts.next()?;
    let used = parts.next()?;
    let percent = parts.next()?;
    let name = parts.next()?.trim();
    if name.is_empty() {
        return None;
    }
    Some(Gpu {
        available: true,
        name: Some(name.to_string()),
        percent: number(percent).map(round1),
        memory_used_mb: number(used).map(|value| value as u64),
        memory_total_mb: number(total).map(|value| value as u64),
        reason: None,
    })
}

// ---------------- Einstiegspunkt ----------------

/// Aktuelle Messwerte lesen und den Zustand fortschreiben.
pub fn read(state: &Mutex<SensorsState>, app: &App) -> Sensors {
    read_impl(state, app)
}

#[cfg(windows)]
fn read_impl(state: &Mutex<SensorsState>, app: &App) -> Sensors {
    let metrics = crate::metrics::read(&app.metrics);

    let cpu = if metrics.cpu_cores > 0 {
        Cpu {
            available: true,
            percent: metrics.cpu_percent,
            cores: metrics.cpu_cores,
            reason: None,
        }
    } else {
        Cpu {
            available: false,
            percent: None,
            cores: 0,
            reason: Some("Prozessor nicht lesbar".to_string()),
        }
    };

    let ram = if metrics.ram_total_mb > 0 {
        Ram {
            available: true,
            used_mb: Some(metrics.ram_used_mb),
            total_mb: Some(metrics.ram_total_mb),
            percent: Some(metrics.ram_percent),
            reason: None,
        }
    } else {
        Ram {
            available: false,
            used_mb: None,
            total_mb: None,
            percent: None,
            reason: Some("Arbeitsspeicher nicht lesbar".to_string()),
        }
    };

    Sensors {
        cpu,
        ram,
        gpu: gpu_cached(state),
        network: network_now(state),
        disks: read_disks(),
        fans: Unavailable::new(FAN_REASON),
        temperatures: Unavailable::new(FAN_REASON),
    }
}

/// Begruendung fuer Teile, die eine Zusatzbibliothek brauchen wuerden.
const FAN_REASON: &str = "ohne Zusatzbibliothek nicht lesbar";

#[cfg(not(windows))]
fn read_impl(_state: &Mutex<SensorsState>, _app: &App) -> Sensors {
    // Ohne Windows keine Win32-Zaehler: alles ehrlich als nicht verfuegbar.
    let reason = "nur unter Windows lesbar";
    Sensors {
        cpu: Cpu {
            available: false,
            percent: None,
            cores: 0,
            reason: Some(reason.to_string()),
        },
        ram: Ram {
            available: false,
            used_mb: None,
            total_mb: None,
            percent: None,
            reason: Some(reason.to_string()),
        },
        gpu: Gpu::unavailable(reason),
        network: Network::unavailable(reason),
        disks: Vec::new(),
        fans: Unavailable::new(FAN_REASON),
        temperatures: Unavailable::new(FAN_REASON),
    }
}

// ---------------- Netzwerk ----------------

/// Netzwerkteil aus der aktuellen Zaehlertabelle und der Vormessung.
#[cfg(windows)]
fn network_now(state: &Mutex<SensorsState>) -> Network {
    let rows = read_interfaces();
    let Some((rx, tx, name)) = sum_interfaces(&rows) else {
        return Network::unavailable("keine aktiven Schnittstellen");
    };
    let now = Instant::now();
    let mut guard = state.lock().expect("Sensor-Sperre");
    if let Some((at, prev_rx, prev_tx)) = guard.network {
        let elapsed = now.saturating_duration_since(at);
        if elapsed < NET_MIN_GAP {
            // Zu kurz fuer eine ehrliche Rate: Vormessung stehen lassen.
            return Network {
                available: true,
                name: Some(name),
                rx_bps: None,
                tx_bps: None,
                reason: None,
            };
        }
        let (rx_bps, tx_bps) =
            rates_from_samples(Some((prev_rx, prev_tx)), elapsed.as_secs_f64(), (rx, tx));
        guard.network = Some((now, rx, tx));
        return Network {
            available: true,
            name: Some(name),
            rx_bps,
            tx_bps,
            reason: None,
        };
    }
    // Erste Messung: nur merken, noch keine Rate.
    guard.network = Some((now, rx, tx));
    Network {
        available: true,
        name: Some(name),
        rx_bps: None,
        tx_bps: None,
        reason: None,
    }
}

/// Alle aktiven, echten Interfaces mit ihren Zaehlerstaenden
/// (ohne Loopback und ohne NDIS-Filter, siehe [`counts_traffic`]).
#[cfg(windows)]
fn read_interfaces() -> Vec<IfSample> {
    let mut table: *mut win::MibIfTable2 = std::ptr::null_mut();
    if unsafe { win::GetIfTable2(&mut table) } != 0 || table.is_null() {
        return Vec::new();
    }
    let rows = unsafe {
        let count = (*table).num_entries as usize;
        if count == 0 {
            Vec::new()
        } else {
            // `Table` ist im C-Header ein Feld der Laenge 1 (ANY_SIZE_ARRAY).
            std::slice::from_raw_parts((*table).table.as_ptr(), count)
                .iter()
                .filter(|row| {
                    counts_traffic(
                        row.oper_status,
                        row.if_type,
                        row.interface_and_oper_status_flags,
                    )
                })
                .map(|row| IfSample {
                    name: alias_of(row),
                    rx: row.in_octets,
                    tx: row.out_octets,
                })
                .collect()
        }
    };
    unsafe { win::FreeMibTable(table as *const core::ffi::c_void) };
    rows
}

/// `Alias` (FriendlyName) eines Interfaces als Text.
#[cfg(windows)]
fn alias_of(row: &win::MibIfRow2) -> String {
    let length = row
        .alias
        .iter()
        .position(|unit| *unit == 0)
        .unwrap_or(row.alias.len());
    String::from_utf16_lossy(&row.alias[..length])
}

// ---------------- Datentraeger ----------------

/// Alle Laufwerke `A:`..`Z:`, bei denen Windows Groessen liefert.
#[cfg(windows)]
fn read_disks() -> Vec<Disk> {
    let mut disks = Vec::new();
    for letter in b'A'..=b'Z' {
        let root = format!("{}:\\", letter as char);
        let wide = wide_string(&root);
        let mut free_to_caller = 0u64; // nur fuer die Signatur, nicht ausgewertet
        let mut total = 0u64;
        let mut free = 0u64;
        let ok = unsafe {
            win::GetDiskFreeSpaceExW(
                wide.as_ptr(),
                &mut free_to_caller,
                &mut total,
                &mut free,
            )
        } != 0;
        // Nicht nutzbar (kein Medium, kein Laufwerk) -> nicht auffuehren.
        if !ok || total == 0 {
            continue;
        }
        disks.push(Disk {
            name: format!("{}:", letter as char),
            total_gb: gb_from_bytes(total),
            free_gb: gb_from_bytes(free.min(total)),
            percent: percent_used(total, free).unwrap_or(0.0),
        });
    }
    disks
}

/// Nullterminierter UTF-16-Text fuer die Win32-`...W`-Funktionen.
#[cfg(windows)]
fn wide_string(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

// ---------------- Grafikkarte ----------------

/// Ergebnis aus dem Cache oder eine neue Abfrage (maximal alle 5 s).
///
/// Auch ein Fehlerergebnis wird zwischengespeichert: sonst wuerde jeder Aufruf
/// erneut versuchen, ein nicht vorhandenes `nvidia-smi` zu starten.
#[cfg(windows)]
fn gpu_cached(state: &Mutex<SensorsState>) -> Gpu {
    {
        let guard = state.lock().expect("Sensor-Sperre");
        if let Some((gpu, at)) = guard.gpu.as_ref() {
            if at.elapsed() < GPU_TTL {
                return gpu.clone();
            }
        }
    }
    // Abfrage bewusst ausserhalb der Sperre: sie kann bis zu 1,5 s dauern.
    let gpu = query_nvidia_smi();
    if let Ok(mut guard) = state.lock() {
        guard.gpu = Some((gpu.clone(), Instant::now()));
    }
    gpu
}

/// `nvidia-smi` starten und die erste Datenzeile auswerten.
#[cfg(windows)]
fn query_nvidia_smi() -> Gpu {
    use std::io::Read;
    use std::os::windows::process::CommandExt;
    use std::process::{Command, Stdio};

    /// Windows-Schalter, damit kein Konsolenfenster aufblinkt.
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    let mut command = Command::new("nvidia-smi");
    command.args([
        "--query-gpu=name,utilization.gpu,memory.used,memory.total",
        "--format=csv,noheader,nounits",
    ]);
    command.stdin(Stdio::null());
    command.stdout(Stdio::piped());
    command.stderr(Stdio::null());
    command.creation_flags(CREATE_NO_WINDOW);

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(_) => return Gpu::unavailable("nvidia-smi nicht gefunden"),
    };

    // Eigener Lesethread, damit die Pipe nicht volllaeuft, waehrend wir warten.
    let reader = child.stdout.take().map(|mut stdout| {
        std::thread::spawn(move || {
            let mut text = String::new();
            let _ = stdout.read_to_string(&mut text);
            text
        })
    });

    let deadline = Instant::now() + GPU_TIMEOUT;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    if let Some(handle) = reader {
                        let _ = handle.join();
                    }
                    return Gpu::unavailable("nvidia-smi antwortet nicht (Zeitlimit)");
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            Err(_) => {
                if let Some(handle) = reader {
                    let _ = handle.join();
                }
                return Gpu::unavailable("nvidia-smi nicht lesbar");
            }
        }
    }

    let text = reader
        .and_then(|handle| handle.join().ok())
        .unwrap_or_default();
    parse_nvidia_smi(&text)
        .unwrap_or_else(|| Gpu::unavailable("nvidia-smi lieferte keine verwertbare Ausgabe"))
}

// ---------------- Win32 ----------------

#[cfg(windows)]
mod win {
    use std::ffi::c_void;

    /// Laenge von `WCHAR Alias[IF_MAX_STRING_SIZE + 1]` im C-Header.
    const IF_MAX_STRING_SIZE: usize = 256;
    /// `IF_MAX_PHYS_ADDRESS_LENGTH` im C-Header.
    const IF_MAX_PHYS_ADDRESS_LENGTH: usize = 32;

    /// Teilmenge von `MIB_IF_ROW2` (`ifmib.h`). Reihenfolge und damit
    /// Speicherabbild muessen exakt zum C-Header passen (siehe Test `layout`).
    #[repr(C)]
    pub struct MibIfRow2 {
        pub interface_luid: u64,
        pub interface_index: u32,
        pub interface_guid: [u8; 16],
        pub alias: [u16; IF_MAX_STRING_SIZE + 1],
        pub description: [u16; IF_MAX_STRING_SIZE + 1],
        pub physical_address_length: u32,
        pub physical_address: [u8; IF_MAX_PHYS_ADDRESS_LENGTH],
        pub permanent_physical_address: [u8; IF_MAX_PHYS_ADDRESS_LENGTH],
        pub mtu: u32,
        pub if_type: u32,
        pub tunnel_type: u32,
        pub media_type: u32,
        pub physical_medium_type: u32,
        pub access_type: u32,
        pub direction_type: u32,
        /// Bitfeld `InterfaceAndOperStatusFlags` (1 Byte, danach 3 Byte Padding).
        pub interface_and_oper_status_flags: u8,
        pub oper_status: u32,
        pub admin_status: u32,
        pub media_connect_state: u32,
        pub network_guid: [u8; 16],
        pub connection_type: u32,
        pub transmit_link_speed: u64,
        pub receive_link_speed: u64,
        pub in_octets: u64,
        pub in_ucast_pkts: u64,
        pub in_nucast_pkts: u64,
        pub in_discards: u64,
        pub in_errors: u64,
        pub in_unknown_protos: u64,
        pub in_ucast_octets: u64,
        pub in_multicast_octets: u64,
        pub in_broadcast_octets: u64,
        pub out_octets: u64,
        pub out_ucast_pkts: u64,
        pub out_nucast_pkts: u64,
        pub out_discards: u64,
        pub out_errors: u64,
        pub out_ucast_octets: u64,
        pub out_multicast_octets: u64,
        pub out_broadcast_octets: u64,
        pub out_qlen: u64,
    }

    /// `MIB_IF_TABLE2`: Anzahl plus Feld der Laenge 1 (`ANY_SIZE_ARRAY`).
    #[repr(C)]
    pub struct MibIfTable2 {
        pub num_entries: u32,
        pub table: [MibIfRow2; 1],
    }

    #[link(name = "kernel32")]
    extern "system" {
        pub fn GetDiskFreeSpaceExW(
            directory_name: *const u16,
            free_bytes_available_to_caller: *mut u64,
            total_number_of_bytes: *mut u64,
            total_number_of_free_bytes: *mut u64,
        ) -> i32;
    }

    #[link(name = "iphlpapi")]
    extern "system" {
        pub fn GetIfTable2(table: *mut *mut MibIfTable2) -> u32;
        pub fn FreeMibTable(memory: *const c_void);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(name: &str, rx: u64, tx: u64) -> IfSample {
        IfSample { name: name.to_string(), rx, tx }
    }

    #[test]
    fn belegter_anteil_der_datentraeger() {
        assert_eq!(percent_used(1000, 250), Some(75.0));
        assert_eq!(percent_used(1000, 1000), Some(0.0));
        assert_eq!(percent_used(1000, 0), Some(100.0));
        // Mehr frei als vorhanden (Rundung des Systems) bleibt bei 0 %.
        assert_eq!(percent_used(1000, 2000), Some(0.0));
        // Ohne Gesamtgroesse keine Prozentangabe statt Division durch 0.
        assert_eq!(percent_used(0, 0), None);
        assert_eq!(gb_from_bytes(1024 * 1024 * 1024), 1.0);
        assert_eq!(gb_from_bytes(1536 * 1024 * 1024), 1.5);
    }

    #[test]
    fn raten_aus_zwei_stichproben() {
        // 2000 Bytes in 2 s = 1000 B/s, 1000 Bytes in 2 s = 500 B/s.
        let (rx, tx) = rates_from_samples(Some((1000, 500)), 2.0, (3000, 1500));
        assert_eq!(rx, Some(1000.0));
        assert_eq!(tx, Some(500.0));
    }

    #[test]
    fn erste_messung_hat_keine_rate() {
        assert_eq!(rates_from_samples(None, 1.0, (3000, 1500)), (None, None));
    }

    #[test]
    fn keine_rate_ohne_zeitdifferenz() {
        // Division durch 0 darf nie einen Wert liefern.
        assert_eq!(rates_from_samples(Some((1000, 500)), 0.0, (3000, 1500)), (None, None));
        assert_eq!(rates_from_samples(Some((1000, 500)), -2.0, (3000, 1500)), (None, None));
        // Zurueckgesprungene Zaehler (Adapter neu gestartet) ergeben 0, nicht negativ.
        assert_eq!(
            rates_from_samples(Some((3000, 1500)), 2.0, (1000, 500)),
            (Some(0.0), Some(0.0))
        );
    }

    #[test]
    fn leere_schnittstellentabelle_ist_nicht_verfuegbar() {
        assert!(sum_interfaces(&[]).is_none());
        let network = Network::unavailable("keine aktiven Schnittstellen");
        assert!(!network.available);
        assert!(network.reason.is_some());
        assert!(network.rx_bps.is_none() && network.name.is_none());
    }

    #[test]
    fn nur_aktive_echte_schnittstellen_zaehlen() {
        // IF_TYPE_ETHERNET_CSMACD = 6, aktiv, keine Flags.
        assert!(counts_traffic(IF_OPER_STATUS_UP, 6, 0));
        // Abgeschaltet.
        assert!(!counts_traffic(2, 6, 0));
        // Loopback (24) ist kein echter Traffic.
        assert!(!counts_traffic(IF_OPER_STATUS_UP, IF_TYPE_SOFTWARE_LOOPBACK, 0));
        // NDIS-Filter wuerde dieselben Bytes doppelt zaehlen.
        assert!(!counts_traffic(IF_OPER_STATUS_UP, 6, IF_FLAG_FILTER_INTERFACE));
        // Andere Flags (z. B. ConnectorPresent = 0x04) sind unschaedlich.
        assert!(counts_traffic(IF_OPER_STATUS_UP, 6, 0x04 | 0x01));
    }

    #[test]
    fn summe_und_groesstes_interface() {
        let rows = [sample("Ethernet", 1000, 200), sample("WLAN", 50, 60)];
        let (rx, tx, name) = sum_interfaces(&rows).expect("Summe");
        assert_eq!((rx, tx), (1050, 260));
        assert_eq!(name, "Ethernet");
        // Nur ein Interface: dieses wird genannt.
        let (_, _, single) = sum_interfaces(&[sample("WLAN", 1, 1)]).expect("Summe");
        assert_eq!(single, "WLAN");
    }

    #[test]
    fn nvidia_smi_ausgabe_auswerten() {
        let text = "NVIDIA GeForce RTX 3080, 34, 2048, 8192\n";
        let gpu = parse_nvidia_smi(text).expect("Ausgabe lesbar");
        assert!(gpu.available);
        assert_eq!(gpu.name.as_deref(), Some("NVIDIA GeForce RTX 3080"));
        assert_eq!(gpu.percent, Some(34.0));
        assert_eq!(gpu.memory_used_mb, Some(2048));
        assert_eq!(gpu.memory_total_mb, Some(8192));

        // Nicht unterstuetzte Felder werden null statt geraten.
        let limited = parse_nvidia_smi("Quadro K420, [N/A], 12, [N/A]").expect("Name da");
        assert_eq!(limited.percent, None);
        assert_eq!(limited.memory_used_mb, Some(12));
        assert_eq!(limited.memory_total_mb, None);

        // Unbrauchbare Ausgabe -> None (der Aufrufer meldet "nicht verfuegbar").
        assert!(parse_nvidia_smi("").is_none());
        assert!(parse_nvidia_smi("Nur ein Feld").is_none());
    }

    #[test]
    fn nicht_verfuegbare_teile_haben_eine_begruendung() {
        let fans = Unavailable::new("ohne Zusatzbibliothek nicht lesbar");
        assert!(!fans.available);
        assert!(fans.reason.contains("Zusatzbibliothek"));
        let gpu = Gpu::unavailable("nvidia-smi nicht gefunden");
        assert!(!gpu.available);
        assert_eq!(gpu.reason.as_deref(), Some("nvidia-smi nicht gefunden"));
        assert!(gpu.name.is_none() && gpu.percent.is_none());
    }

    #[test]
    fn live_read_ist_plausibel_und_haelt_den_vertrag() {
        let app = App::new(
            String::new(),
            true,
            &std::env::temp_dir().join("pc-agent-sensors-test"),
            0,
        );
        let state = Mutex::new(SensorsState::default());
        let first = read(&state, &app);
        // Die allererste Anfrage hat noch keine Netzwerkrate.
        assert!(first.network.rx_bps.is_none() || first.network.rx_bps.unwrap() >= 0.0);

        std::thread::sleep(Duration::from_millis(300));
        let second = read(&state, &app);

        if let Some(percent) = second.cpu.percent {
            assert!((0.0..=100.0).contains(&percent), "CPU unplausibel: {percent}");
        }
        if let Some(percent) = second.ram.percent {
            assert!((0.0..=100.0).contains(&percent), "RAM unplausibel: {percent}");
        }
        for disk in &second.disks {
            assert!(
                (0.0..=100.0).contains(&disk.percent),
                "Datentraeger unplausibel: {}",
                disk.name
            );
            assert!(disk.free_gb <= disk.total_gb);
        }
        if second.network.available {
            if let Some(rx) = second.network.rx_bps {
                assert!(rx >= 0.0, "Empfangsrate negativ");
            }
            if let Some(tx) = second.network.tx_bps {
                assert!(tx >= 0.0, "Senderate negativ");
            }
        }
        // Luefter/Temperaturen bleiben ehrlich nicht verfuegbar.
        assert!(!second.fans.available && !second.fans.reason.is_empty());
        assert!(!second.temperatures.available && !second.temperatures.reason.is_empty());

        // JSON-Vertrag: alle sieben Bloecke liegen vor.
        let value = serde_json::to_value(&second).expect("JSON");
        for key in ["cpu", "ram", "gpu", "network", "disks", "fans", "temperatures"] {
            assert!(value.get(key).is_some(), "Feld fehlt: {key}");
        }
        assert!(value["disks"].is_array());
        assert_eq!(value["fans"]["available"], serde_json::json!(false));
        assert!(value["fans"]["reason"].is_string());
    }

    /// Speicherabbild von `MIB_IF_ROW2` – sonst waeren alle Zaehler Unsinn.
    #[test]
    #[cfg(windows)]
    fn layout_passt_zum_c_header() {
        assert_eq!(std::mem::size_of::<win::MibIfRow2>(), 1352);
        assert_eq!(std::mem::offset_of!(win::MibIfRow2, alias), 28);
        assert_eq!(std::mem::offset_of!(win::MibIfRow2, description), 542);
        assert_eq!(std::mem::offset_of!(win::MibIfRow2, oper_status), 1156);
        assert_eq!(std::mem::offset_of!(win::MibIfRow2, in_octets), 1208);
        assert_eq!(std::mem::offset_of!(win::MibIfRow2, out_octets), 1280);
        assert_eq!(std::mem::offset_of!(win::MibIfRow2, out_qlen), 1344);
        assert_eq!(std::mem::offset_of!(win::MibIfTable2, table), 8);
    }
}

