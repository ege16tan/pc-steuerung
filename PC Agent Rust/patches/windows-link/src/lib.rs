#![doc = include_str!("../readme.md")]
#![no_std]

/// Defines an external function to import.
///
/// Lokaler Patch: klassische `#[link]`-Deklarationen statt `raw-dylib`,
/// damit kein `dlltool.exe` benoetigt wird. Die Windows-GNU-Toolchain
/// findet die Import-Bibliotheken (libkernel32.a, libuser32.a, ...) im
/// selbst-contained-Ordner der Toolchain.
#[cfg(windows)]
#[macro_export]
macro_rules! link {
    ($library:literal $abi:literal $($link_name:literal)? fn $($function:tt)*) => (
        #[link(name = $library)]
        extern $abi {
            $(#[link_name=$link_name])?
            pub fn $($function)*;
        }
    )
}

/// Defines an external function to import.
#[cfg(not(windows))]
#[macro_export]
macro_rules! link {
    ($library:literal $abi:literal $($link_name:literal)? fn $($function:tt)*) => (
        extern $abi {
            pub fn $($function)*;
        }
    )
}
