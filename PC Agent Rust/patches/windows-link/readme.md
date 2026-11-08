# windows-link (lokaler Patch)

Unterschied zum Original `windows-link 0.2.1`:
Statt `kind = "raw-dylib"` (benoetigt `dlltool.exe` bei der GNU-Toolchain)
werden klassische `#[link(name = "...")]`-Deklarationen erzeugt. Die
Windows-GNU-Toolchain bringt passende Import-Bibliotheken in
`lib/self-contained` mit (libkernel32.a, libuser32.a, ...), sodass kein
dlltool noetig ist.

Aktiviert via `[patch.crates-io]` in ../Cargo.toml — nur im Rust-Agent-Ordner,
kein Einfluss auf andere Projekte.
