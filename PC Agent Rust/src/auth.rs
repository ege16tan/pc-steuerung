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

use crate::app::{Credentials, TokenCredentials, App, ROLE_ADMIN};

/// Iterationen wie der Python-Agent (200000); SHA-256 mit 32-Byte-Ausgabe.
pub const PBKDF2_ROUNDS: u32 = 200_000;
/// hashlib.pbkdf2_hmac("sha256", ...) gibt standardmaessig 32 Bytes.
pub const HASH_LEN: usize = 32;

/// Hex-String in Bytes (Paritaet zu Python bytes.fromhex, scharf bei Fehlern).
pub fn hex_decode(value: &str) -> Option<Vec<u8>> {
    if !value.len().is_multiple_of(2) {
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

/// Prueft den Authorization-Header gegen die Zugangsdaten und liefert die Rolle
/// (`admin`/`viewer`). Ablauf wie im Python-Agent `_check_basic`, erweitert um
/// die Benutzerliste aus Phase 3.
pub fn check_basic_role(credentials: &Credentials, authorization: &str) -> Option<String> {
    let payload = authorization.strip_prefix("Basic ")?;
    let decoded = B64.decode(payload).ok()?;
    let raw = String::from_utf8(decoded).ok()?;
    let (user, password) = raw.split_once(':')?;

    // Hauptbenutzer (alte Dateien ohne `role` sind admin).
    if verify_password(user, &credentials.user, &credentials.salt, &credentials.hash, password) {
        return Some(credentials.role().to_string());
    }
    // Weitere Benutzer mit eigener Rolle.
    for entry in &credentials.users {
        if !entry.is_usable() {
            continue;
        }
        if verify_password(user, &entry.user, &entry.salt, &entry.hash, password) {
            return Some(entry.role().to_string());
        }
    }
    None
}

/// Wie `check_basic_role`, nur als Ja/Nein (Tests/Kompatibilitaet).
#[allow(dead_code)]
pub fn check_basic(credentials: &Credentials, authorization: &str) -> bool {
    check_basic_role(credentials, authorization).is_some()
}

/// Benutzername + PBKDF2-Hash vergleichen (konstante Zeit fuer den Hash).
/// Rechnet IMMER PBKDF2, auch bei unbekanntem Benutzernamen (Dummy-Hash),
/// damit die Laufzeit nicht verraet, ob ein User existiert.
fn verify_password(user: &str, expected_user: &str, salt: &str, hash: &str, password: &str) -> bool {
    let user_matches = expected_user.is_empty() || user == expected_user;
    // Dummy-Salt/Hash fuer unbekannte Benutzer, damit PBKDF2 immer gleich teuer ist.
    let (salt_bytes, expected) = if user_matches {
        match (hex_decode(salt), hex_decode(hash)) {
            (Some(salt), Some(expected)) if expected.len() == HASH_LEN => (salt, expected),
            _ => return false,
        }
    } else {
        // Dummy-Werte: PBKDF2 wird dennoch mit gleichen Runden berechnet.
        (vec![0u8; 16], vec![0u8; HASH_LEN])
    };
    let mut actual = [0u8; HASH_LEN];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt_bytes, PBKDF2_ROUNDS, &mut actual);
    // Konstanter Zeitvergleich: XOR-Akkumulator, keine Kurzschluss-Optik.
    let mut difference = 0u8;
    for index in 0..HASH_LEN {
        difference |= actual[index] ^ expected[index];
    }
    // User-Name muss ebenfalls stimmen (konstantzeitig ueber das Hash-Ergebnis
    // kombiniert, aber explizit geprüft).
    difference == 0 && user_matches
}

fn check_token(credentials: &TokenCredentials, token: &str) -> bool {
    let Some(salt) = hex_decode(&credentials.salt) else { return false; };
    let Some(expected) = hex_decode(&credentials.hash) else { return false; };
    if expected.len() != HASH_LEN { return false; }
    let mut actual = [0u8; HASH_LEN];
    pbkdf2_hmac::<Sha256>(token.as_bytes(), &salt, PBKDF2_ROUNDS, &mut actual);
    let mut difference = 0u8;
    for index in 0..HASH_LEN { difference |= actual[index] ^ expected[index]; }
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

    fn read_only() -> AuthDenied {
        AuthDenied {
            status: StatusCode::FORBIDDEN,
            detail: "Nur Lesezugriff (Rolle viewer)",
            www_auth: false,
        }
    }

    fn rate_limited() -> AuthDenied {
        AuthDenied {
            status: StatusCode::TOO_MANY_REQUESTS,
            detail: "Zu viele fehlgeschlagene Anmeldeversuche. Später erneut versuchen.",
            www_auth: false,
        }
    }
}

/// Ergebnis der Anmeldung: Rolle des Benutzers.
#[derive(Clone, Copy, Debug)]
pub struct Auth {
    pub role: &'static str,
}

impl Auth {
    pub fn admin() -> Auth {
        Auth { role: ROLE_ADMIN }
    }

    pub fn is_admin(&self) -> bool {
        self.role == ROLE_ADMIN
    }
}

/// Marker-Extractor, der vor jedem geschuetzten Endpunkt die Anmeldung prueft.
/// Lesezugriff (Status, Messwerte, Ereignisse, Log) genuegt hier.
impl FromRequestParts<Arc<App>> for Auth {
    type Rejection = AuthDenied;

    async fn from_request_parts(
        parts: &mut Parts,
        app: &Arc<App>,
    ) -> Result<Self, Self::Rejection> {
        // 1) Lokale Zugriffe brauchen keinen Login und haben alle Rechte.
        if is_local(parts) {
            return Ok(Auth::admin());
        }

        // 2) IP-Adresse fuer Rate-Limit ermitteln.
        let ip = parts
            .extensions
            .get::<ConnectInfo<std::net::SocketAddr>>()
            .map(|info| info.ip())
            .unwrap_or(std::net::IpAddr::from([0, 0, 0, 0]));

        // 3) Rate-Limit prüfen: gesperrte IP wird abgewiesen.
        {
            let limiter = app.login_limiter.lock().expect("Login-Sperre");
            if limiter.is_locked(ip) {
                return Err(AuthDenied::rate_limited());
            }
        }

        // 4) Anmeldung per Benutzername/Passwort (Basic Auth).
        let (credentials, token_credentials) = {
            let guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
            (guard.credentials.clone(), guard.token_credentials.clone())
        };
        if let Some(cursor) = credentials {
            let authorization = header_str(parts, AUTHORIZATION);
            let result = check_basic_role(&cursor, &authorization);
            {
                let mut limiter = app.login_limiter.lock().expect("Login-Sperre");
                if result.is_some() {
                    limiter.record_success(ip);
                } else {
                    limiter.record_failure(ip);
                }
            }
            let Some(role) = result else {
                return Err(AuthDenied::missing_login());
            };
            return Ok(Auth {
                role: crate::app::parse_role(&role),
            });
        }
        if let Some(credentials) = token_credentials {
            let token = header_str(parts, "x-token");
            return if check_token(&credentials, &token) { Ok(Auth::admin()) } else { Err(AuthDenied::wrong_token()) };
        }
        // 3) Fallback: alter Token-Schutz.
        if app.token.is_empty() {
            return Ok(Auth::admin());
        }
        let x_token = header_str(parts, "x-token");
        if tokens_match(&x_token, &app.token) {
            Ok(Auth::admin())
        } else {
            Err(AuthDenied::wrong_token())
        }
    }
}

/// Wie [`Auth`], aber zusaetzlich nur fuer Rollen mit Schreibrecht (`admin`).
/// `viewer` bekommt 403 – die Web-UI blendet die Knoepfe zusaetzlich aus.
pub struct WriteAuth(pub Auth);

impl FromRequestParts<Arc<App>> for WriteAuth {
    type Rejection = AuthDenied;

    async fn from_request_parts(
        parts: &mut Parts,
        app: &Arc<App>,
    ) -> Result<Self, Self::Rejection> {
        let auth = Auth::from_request_parts(parts, app).await?;
        if auth.is_admin() {
            Ok(WriteAuth(auth))
        } else {
            Err(AuthDenied::read_only())
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
        role: ROLE_ADMIN.to_string(),
        users: Vec::new(),
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
            ..Default::default()
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
        // Ohne Rollenfeld ist der Hauptbenutzer Administrator.
        assert_eq!(credentials.role(), ROLE_ADMIN);
        assert_eq!(
            check_basic_role(&credentials, &header).as_deref(),
            Some(ROLE_ADMIN)
        );
    }

    #[test]
    fn weitere_benutzer_bekommen_ihre_rolle() {
        let mut credentials = test_credentials();
        let viewer = create_credentials("gast", "NurLesen123").unwrap();
        credentials.users.push(crate::app::UserEntry {
            user: viewer.user,
            salt: viewer.salt,
            hash: viewer.hash,
            role: "viewer".to_string(),
        });

        let guest_header = basic_header("gast", "NurLesen123");
        assert_eq!(
            check_basic_role(&credentials, &guest_header).as_deref(),
            Some("viewer")
        );
        // Hauptbenutzer bleibt admin.
        let admin_header = basic_header("testuser", "MeinTestpasswort1");
        assert_eq!(
            check_basic_role(&credentials, &admin_header).as_deref(),
            Some(ROLE_ADMIN)
        );
        // Falsches Passwort beim Gast bleibt falsch.
        assert!(check_basic_role(&credentials, &basic_header("gast", "falsch")).is_none());
        // Alte Felder in `credentials.json` (ohne role/users) bleiben lesbar.
        let legacy: Credentials =
            serde_json::from_str(r#"{"user":"u","salt":"00","hash":"11"}"#).unwrap();
        assert_eq!(legacy.role(), ROLE_ADMIN);
        assert!(legacy.users.is_empty());
    }
}

