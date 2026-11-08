# windows-link (lokaler Patch)

Unterschied zum Original `windows-link 0.2.1`:
Bei der Windows-GNU-Toolchain werden statt `kind = "raw-dylib"`
(bei GNU benoetigt `dlltool.exe`) klassische `#[link(name = "...")]`-
Deklarationen erzeugt. Die Windows-GNU-Toolchain bringt passende
Import-Bibliotheken in `lib/self-contained` mit (libkernel32.a,
libuser32.a, ...), sodass kein dlltool noetig ist. Bei MSVC bleibt es
beim Original (`raw-dylib`), weil der MSVC-Linker kein dlltool braucht.

Aktiviert via `[patch.crates-io]` in ../Cargo.toml — nur im Rust-Agent-Ordner,
kein Einfluss auf andere Projekte.
