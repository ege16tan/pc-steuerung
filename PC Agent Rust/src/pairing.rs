//! QR-Inhalt fuer das Android-Ersteinrichtungsformular.
//! Der Payload enthaelt ausschliesslich PC-Name, IP und Port.

use std::net::{Ipv4Addr, UdpSocket};
use std::process::Command;
use serde::Deserialize;

#[derive(Clone, Debug, Deserialize, serde::Serialize)]
pub struct NetworkAdapter {
    pub name: String,
    pub ip: String,
    pub mac: String,
    pub is_default: bool,
}

/// Ermittelt die IPv4-Adresse der Standardroute, ohne ein Paket zu senden.
pub fn lan_ipv4() -> Option<String> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    socket.connect((Ipv4Addr::new(8, 8, 8, 8), 80)).ok()?;
    match socket.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(address) if !address.is_loopback() => Some(address.to_string()),
        _ => None,
    }
}

pub fn payload_json(host: &str, port: u16, name: &str, mac: Option<&str>) -> String {
    let mut payload = serde_json::json!({
        "app": "pc-steuerung",
        "version": 1,
        "host": host,
        "port": port,
        "name": name,
    });
    if let Some(mac) = mac { payload["mac"] = serde_json::Value::String(mac.to_string()); }
    payload.to_string()
}

/// Ermittelt LAN-Adapter über die integrierte Windows-Netzwerkverwaltung.
pub fn network_adapters() -> Vec<NetworkAdapter> {
    let script = r#"$items = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue | ForEach-Object { $c = $_; $a = $c.NetAdapter; $ips = @($c.IPv4Address | ForEach-Object { $_.IPAddress } | Where-Object { $_ -and $_ -notlike '127.*' }); if ($a -and $a.Status -eq 'Up' -and $ips.Count -gt 0 -and $a.MacAddress) { [pscustomobject]@{ name = $c.InterfaceAlias; ip = $ips[0]; mac = $a.MacAddress; is_default = [bool]$c.IPv4DefaultGateway } } }); ConvertTo-Json -InputObject $items -Compress"#;
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .output();
    let Ok(output) = output else { return Vec::new(); };
    if !output.status.success() { return Vec::new(); }
    serde_json::from_slice(&output.stdout).unwrap_or_default()
}
