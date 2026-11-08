//! Web-Oberflaeche: 1:1 identisch zur Python-Antwort von `GET /`.
//! (include_str! bettet die Datei beim Build ein; sie liegt danach im Binary
//! und benoetigt keine externen Dateien.)

pub const PAGE: &str = include_str!("page.html");
