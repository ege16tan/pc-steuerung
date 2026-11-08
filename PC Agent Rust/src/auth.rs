//! Anmeldung: Basic Auth mit PBKDF2-Hash (kompatibel zum Python-Agent),
//! localhost-Ausnahme und alter Token-Fallback.

use std::sync::Arc;

use axum::extract::ConnectInfo;
use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Json, Response};
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use pbkdf2::pbkdf2_hmac;
use sha2::Sha256;
use subtle::ConstantTimeEq;

use crate::app::{Credentials, App};

/// Iterationen wie der Python-Agent (200000); SHA-256 mit 32-Byte-Ausgabe.
pub const PBKDF2_ROUNDS: u32 = 200_000;
/// hashlib.pbkdf2_hmac("sha256", ...) gibt standardmaessig 32 Bytes.
pub const HASH_LEN: usize = 32;

/// Hex-String in Bytes (Paritaet zu Python bytes.fromhex, scharf bei Fehlern).
pub fn hex_decode(value: &str) -> Option<Vec<u8>> {
    if value.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(value.len() / 2);
    let bytes = value.as_bytes();
    for pair in bytes.chunks(2) {
        let hi = hex_digit(pair[0])?;
        let lo = hex_digit(pair[1])?;
        out.push(hi * 16 + lo);
    }
    Some(out)
}

fn hex_digit(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// Prueft den Authorization-Header gegen die Zugangsdaten.
/// Ablauf wie im Python-Agent `_check_basic`.
pub fn check_basic(credentials: &Credentials, authorization: &str) -> bool {
    let Some(payload) = authorization.strip_prefix("Basic ") else {
        return false;
    };
    let Ok(decoded) = B64.decode(payload) else {
        return false;
    };
    let Ok(raw) = String::from_utf8(decoded) else {
        return false;
    };
    let Some((user, password)) = raw.split_once(':') else {
        return false;
    };
    if user != credentials.user {
        return false;
    }
    let Some(salt) = hex_decode(&credentials.salt) else {
        return false;
    };
    let Some(expected) = hex_decode(&credentials.hash) else {
        return false;
    };
    if expected.len() != HASH_LEN {
        return false;
    }
    let mut actual = [0u8; HASH_LEN];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, PBKDF2_ROUNDS, &mut actual);
    // Konstanter Zeitvergleich: XOR-Akkumulator, keine Kurzschluss-Optik.
    let mut difference = 0u8;
    for index in 0..HASH_LEN {
        difference |= actual[index] ^ expected[index];
    }
    difference == 0
}

/// Konstanter Zeitvergleich fuer den Token-Fallback (wie compare_digest).
fn tokens_match(actual: &str, expected: &str) -> bool {
    actual.as_bytes().ct_eq(expected.as_bytes()).into()
}

/// Anmeldungs-Fehler als HTTP-Antwort (401/403 wie Python).
pub struct AuthDenied {
    status: StatusCode,
    detail: &'static str,
    www_auth: bool,
}

impl IntoResponse for AuthDenied {
    fn into_response(self) -> Response {
        let mut response = (
            self.status,
            Json(serde_json::json!({ "detail": self.detail })),
        )
            .into_response();
        if self.www_auth {
            response.headers_mut().insert(
                "WWW-Authenticate",
                axum::http::HeaderValue::from_static("Basic realm=\"PC Agent\""),
            );
        }
        response
    }
}

impl AuthDenied {
    fn missing_login() -> AuthDenied {
        AuthDenied {
            status: StatusCode::UNAUTHORIZED,
            detail: "Login fehlt oder falsch",
            www_auth: true,
        }
    }

    fn wrong_token() -> AuthDenied {
        AuthDenied {
            status: StatusCode::FORBIDDEN,
            detail: "Token fehlt oder falsch",
            www_auth: false,
        }
    }
}

/// Marker-Extractor, der vor jedem geschuetzten Endpunkt die Anmeldung prueft.
pub struct Auth;

/// Zustands-Typ: der Router haelt den Zustand als Arc<App>, also muss der
/// Extractor fuer genau diesen Typ existieren.
impl FromRequestParts<Arc<App>> for Auth {
    type Rejection = AuthDenied;

    async fn from_request_parts(
        parts: &mut Parts,
        app: &Arc<App>,
    ) -> Result<Self, Self::Rejection> {
        // 1) Lokale Zugriffe (PC selbst: Tray, Web-UI) brauchen keinen Login.
        if is_local(parts) {
            return Ok(Auth);
        }
        // 2) Anmeldung per Benutzername/Passwort (Basic Auth).
        let credentials = {
            let guard = app.inner.lock().expect("Zustands-Sperre");
            guard.credentials.clone()
        };
        if let Some(cursor) = credentials {
            let authorization = header_str(parts, AUTHORIZATION);
            if !check_basic(&cursor, &authorization) {
                return Err(AuthDenied::missing_login());
            }
            return Ok(Auth);
        }
        // 3) Fallback: alter Token-Schutz.
        if app.token.is_empty() {
            return Ok(Auth);
        }
        let x_token = header_str(parts, "x-token");
        if tokens_match(&x_token, &app.token) {
            Ok(Auth)
        } else {
            Err(AuthDenied::wrong_token())
        }
    }
}

fn header_str<I>(parts: &Parts, name: I) -> String
where
    I: axum::http::header::AsHeaderName,
{
    parts
        .headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .to_string()
}

/// Paritaet zum Python-Agent: 127.0.0.1, ::1 und "localhost" (theoretischer
/// Text-Wert; real kommen Adressen) gelten als lokaler Zugriff.
fn is_local(parts: &Parts) -> bool {
    match parts.extensions.get::<ConnectInfo<std::net::SocketAddr>>() {
        Some(info) => {
            let host = info.ip().to_string();
            host == "127.0.0.1" || host == "::1" || host == "localhost"
        }
        // Ohne Client-Adresse (Tests) nicht als lokal behandeln.
        None => false,
    }
}

/// Erzeugt Zugangsdaten wie `/setup`: 16 Zufallsbytes Salt + PBKDF2-Hash.
pub fn create_credentials(user: &str, password: &str) -> Result<Credentials, String> {
    let mut salt = [0u8; 16];
    getrandom::getrandom(&mut salt).map_err(|e| format!("Zufall nicht verfuegbar: {e}"))?;
    let mut digest = [0u8; HASH_LEN];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, PBKDF2_ROUNDS, &mut digest);
    Ok(Credentials {
        user: user.to_string(),
        salt: hex_encode(&salt),
        hash: hex_encode(&digest),
    })
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::Credentials;

    fn test_credentials() -> Credentials {
        Credentials {
            user: "testuser".to_string(),
            salt: "000102030405060708090a0b0c0d0e0f".to_string(),
            hash: "38d15d243c81af4321de9f9047634d5d8f6843afedfe90150ec6d6071d6a7179".to_string(),
        }
    }

    /// Authorization-Wert fuer user:passwort in Basic-Codierung.
    fn basic_header(user: &str, password: &str) -> String {
        let raw = format!("{user}:{password}");
        format!("Basic {}", B64.encode(raw.as_bytes()))
    }

    #[test]
    fn basic_auth_nimmt_passendes_passwort() {
        let credentials = test_credentials();
        let header = basic_header("testuser", "MeinTestpasswort1");
        assert!(check_basic(&credentials, &header));
    }

    #[test]
    fn basic_auth_unterscheidet_user_prefix() {
        // Python prueft user gegen Gesamtspeicher: "test" != "testuser" -> false
        let credentials = test_credentials();
        let header = basic_header("test", "MeinTestpasswort1");
        assert!(!check_basic(&credentials, &header));
    }

    #[test]
    fn basic_auth_ablehnt_falsches_passwort() {
        let credentials = test_credentials();
        let header = basic_header("testuser", "falsch");
        assert!(!check_basic(&credentials, &header));
    }

    #[test]
    fn basic_auth_ablehnt_kaputte_header() {
        let credentials = test_credentials();
        assert!(!check_basic(&credentials, "Bearer sonstwas"));
        assert!(!check_basic(&credentials, ""));
        assert!(!check_basic(&credentials, "Basic !definitnichtbase64 "));
    }

    #[test]
    fn basic_auth_ablehnt_kaputte_hex_felder() {
        let mut credentials = test_credentials();
        credentials.salt = "kez".to_string();
        let header = basic_header("testuser", "MeinTestpasswort1");
        assert!(!check_basic(&credentials, &header));
    }

    #[test]
    fn hex_decode_robust() {
        assert_eq!(hex_decode("00ff"), Some(vec![0, 255]));
        assert_eq!(hex_decode("0A"), Some(vec![10]));
        assert_eq!(hex_decode("abc"), None);
        assert_eq!(hex_decode("zz"), None);
        assert_eq!(hex_decode(""), Some(vec![]));
    }

    #[test]
    fn create_credentials_rundet_kommt_wieder() {
        let credentials = create_credentials("tester", "EinLongPasswort99").unwrap();
        assert_eq!(credentials.user, "tester");
        assert_eq!(credentials.salt.len(), 32);
        assert_eq!(credentials.hash.len(), 64);
        let header = basic_header("tester", "EinLongPasswort99");
        assert!(check_basic(&credentials, &header));
    }
}
