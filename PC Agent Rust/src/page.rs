//! Web-Oberflaeche: HTML, Stylesheet und Skript werden beim Build eingebettet
//! (`include_str!`), damit der Agent keine externen Dateien braucht.
//!
//! Aufteilung: `page.html` (Gerüst), `app.css` (Darstellung), `app.js` (Logik).
//! Alle drei werden ohne Anmeldung ausgeliefert – wie bisher `GET /`.

pub const PAGE: &str = include_str!("page.html");
pub const APP_CSS: &str = include_str!("app.css");
pub const APP_JS: &str = include_str!("app.js");

