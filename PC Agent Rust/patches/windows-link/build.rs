//! Alias-Importbibliotheken fuer die Windows-GNU-Toolchain.
//!
//! `windows_link::link!("kernel32.dll" ...)` erzeugt
//! `#[link(name = "kernel32.dll")]`. Der GNU-Linker sucht dafuer
//! `libkernel32.dll.a`, die self-contained-Toolchain bringt aber nur
//! `libkernel32.a` mit.
//!
//! Dieses Skript kopiert jede vorhandene Importbibliothek als
//! `lib<name>.dll.a` in den Build-Ordner und meldet ihn als Suchpfad.
//! Weil das ueber `cargo:rustc-link-search` an alle abhaengigen Kisten
//! weitergegeben wird, funktioniert auch der `cdylib`-Build von
//! `boa_engine` (JavaScript-Engine der Plugins).

use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

/// Bibliotheken, die `windows-sys` mit `.dll`-Endung referenziert, ohne dass
/// die Toolchain eine Importbibliothek mitliefert. Sie werden als leeres
/// Archiv angelegt; die Symbole steuert in dem Fall die Standard-Verknuepfung
/// von `std` bei.
const FALLBACK_LIBS: &[&str] = &[
    "api-ms-win-core-handle-l1-1-0.dll",
    "api-ms-win-core-file-l1-1-0.dll",
    "api-ms-win-core-synch-l1-1-0.dll",
    "bcryptprimitives.dll",
    "fwpuclnt.dll",
    "kernel32.dll",
    "ksecdd.sys",
    "mswsock.dll",
    "ntdll.dll",
    "ntoskrnl.exe",
    "secur32.dll",
    "user32.dll",
    "windows.networking.dll",
];

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows")
        || env::var("CARGO_CFG_TARGET_ENV").as_deref() != Ok("gnu")
    {
        return;
    }

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR fehlt"));
    let Some(self_contained) = self_contained_dir() else {
        // MSYS2/MinGW: der Linker bringt die Importbibliotheken selbst mit.
        return;
    };

    let mut aliases = 0usize;
    if let Ok(entries) = fs::read_dir(&self_contained) {
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            // libkernel32.a -> libkernel32.dll.a
            let Some(stem) = name.strip_prefix("lib").and_then(|rest| rest.strip_suffix(".a"))
            else {
                continue;
            };
            let alias = out_dir.join(format!("lib{stem}.dll.a"));
            if !alias.is_file() && fs::copy(&path, &alias).is_ok() {
                aliases += 1;
            }
        }
    }

    // Leere Archive, damit die Suche nicht mit "cannot find -l..." abbricht.
    for name in FALLBACK_LIBS {
        let alias = out_dir.join(format!("lib{name}.a"));
        if !alias.is_file() {
            let _ = fs::write(&alias, b"!<arch>\n");
        }
    }

    println!("cargo:rustc-link-search=native={}", out_dir.display());
    println!("cargo:warning=windows-link: {aliases} Alias-Bibliotheken angelegt");
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