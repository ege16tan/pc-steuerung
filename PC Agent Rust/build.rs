//! Build-Hilfe fuer die Windows-GNU-Toolchain.
//!
//! `windows-sys` deklariert seine Funktionen normalerweise per `raw-dylib`.
//! Dafuer ruft rustc `dlltool` auf, das wiederum einen GNU-Assembler (`as.exe`)
//! erwartet. Die von rustup mitgelieferte GNU-Toolchain enthaelt diesen
//! Assembler nicht. Der lokale Patch `patches/windows-link` stellt deshalb auf
//! klassische `#[link(name = "...")]`-Deklarationen um.
//!
//! Dabei uebergibt rustc die Bibliotheksnamen inklusive Endung an den Linker
//! (`-lkernel32.dll`). Dieses Build-Skript legt deshalb im OUT_DIR passende
//! Alias-Dateien an:
//!   - vorhandene Import-Bibliotheken werden kopiert (libkernel32.a ->
//!     libkernel32.dll.a),
//!   - Bibliotheken ohne Import-Bibliothek, deren Symbole nicht gebraucht
//!     werden, werden als leeres Archiv angelegt.
//!
//! Die Liste der Bibliotheken wird aus dem Quelltext der eingebundenen
//! `windows-sys`-Version gelesen. Nur fuer `x86_64-pc-windows-gnu` aktiv;
//! MSVC-Targets nutzen weiterhin `raw-dylib` und brauchen das nicht.

use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

/// Notfall-Liste, falls der windows-sys-Quelltext nicht gefunden wird.
const FALLBACK_LIBS: &[&str] = &[
    "advapi32.dll",
    "api-ms-win-core-handle-l1-1-0.dll",
    "fwpuclnt.dll",
    "kernel32.dll",
    "ksecdd.sys",
    "mswsock.dll",
    "ntdll.dll",
    "ntoskrnl.exe",
    "oleaut32.dll",
    "secur32.dll",
    "user32.dll",
    "windows.networking.dll",
    "ws2_32.dll",
];

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    // Nur das GNU-Target braucht die Aliasse.
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows")
        || env::var("CARGO_CFG_TARGET_ENV").as_deref() != Ok("gnu")
    {
        return;
    }

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR fehlt"));
    let Some(self_contained) = self_contained_dir() else {
        // Keine self-contained-Toolchain (z. B. MSYS2-MinGW): Dann bringt der
        // Linker die Import-Bibliotheken selbst mit und wir muessen nichts tun.
        return;
    };

    let libs = windows_sys_libraries().unwrap_or_else(|| {
        FALLBACK_LIBS.iter().map(|name| name.to_string()).collect()
    });

    for name in &libs {
        // Import-Bibliotheken heissen lib<name ohne .dll>.a; der Linker sucht
        // bei -l<name> aber lib<name>.a (also z. B. libkernel32.dll.a).
        let stem = name.strip_suffix(".dll").unwrap_or(name);
        let source = self_contained.join(format!("lib{stem}.a"));
        if source.is_file() {
            // Echte Import-Bibliothek: kopieren, damit auch Symbole passen.
            let _ = fs::copy(&source, out_dir.join(format!("lib{name}.a")));
        } else {
            // Keine Import-Bibliothek vorhanden: leeres Archiv. Der Linker
            // findet die Bibliothek, und nicht benutzte Symbole stoeren nicht.
            let _ = fs::write(out_dir.join(format!("lib{name}.a")), b"!<arch>\n");
        }
    }

    println!("cargo:rustc-link-search=native={}", out_dir.display());
}

/// Ermittelt `.../lib/rustlib/<target>/lib/self-contained` ueber rustc.
fn self_contained_dir() -> Option<PathBuf> {
    let rustc = env::var("RUSTC").unwrap_or_else(|_| "rustc".to_string());
    let target = env::var("TARGET").ok()?;
    let output = Command::new(rustc)
        .args(["--print", "target-libdir", "--target", &target])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let lib_dir = String::from_utf8(output.stdout).ok()?;
    let dir = PathBuf::from(lib_dir.trim()).join("self-contained");
    dir.is_dir().then_some(dir)
}

/// Liest alle `windows_link::link!("...")`-Bibliotheksnamen aus den
/// windows-sys-Quellen im Cargo-Registry-Cache.
fn windows_sys_libraries() -> Option<BTreeSet<String>> {
    let mut sources = Vec::new();
    collect_windows_sys_dirs(&cargo_home().join("registry").join("src"), &mut sources);
    if sources.is_empty() {
        return None;
    }

    let mut libs = BTreeSet::new();
    for source in sources {
        collect_rs_files(&source, &mut |path| {
            if let Ok(text) = fs::read_to_string(path) {
                let marker = "windows_link::link!(\"";
                let mut rest = text.as_str();
                while let Some(index) = rest.find(marker) {
                    let after = &rest[index + marker.len()..];
                    if let Some(end) = after.find('"') {
                        libs.insert(after[..end].to_string());
                    }
                    rest = after;
                }
            }
        });
    }

    (!libs.is_empty()).then_some(libs)
}

fn cargo_home() -> PathBuf {
    if let Ok(home) = env::var("CARGO_HOME") {
        return PathBuf::from(home);
    }
    let profile = env::var("USERPROFILE").or_else(|_| env::var("HOME")).unwrap_or_default();
    PathBuf::from(profile).join(".cargo")
}

/// Sucht `windows-sys-<version>/src`-Ordner unter registry/src/<index>/.
fn collect_windows_sys_dirs(root: &Path, out: &mut Vec<PathBuf>) {
    let Ok(indexes) = fs::read_dir(root) else {
        return;
    };
    for index in indexes.flatten() {
        let Ok(entries) = fs::read_dir(index.path()) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if name.starts_with("windows-sys-") {
                let src = path.join("src");
                if src.is_dir() {
                    out.push(src);
                }
            }
        }
    }
}

fn collect_rs_files(dir: &Path, visit: &mut impl FnMut(&Path)) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_rs_files(&path, visit);
        } else if path.extension().map(|ext| ext == "rs").unwrap_or(false) {
            visit(&path);
        }
    }
}
