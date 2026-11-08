//! Wake-on-LAN: Magic Packet per UDP-Broadcast.
//! Paritaet zum Python-Agent `_wake`.

use std::net::UdpSocket;

/// Standard-Broadcast des Agenten (Env PC_WOL_BROADCAST im Python-Agent).
pub const DEFAULT_BROADCAST: &str = "192.168.1.255";

/// Parst eine MAC in 6 Bytes: Trenner "-" wird zu ":", Leerzeichen an den
/// Enden werden entfernt (Paritaet zum Python-Agent).
pub fn parse_mac(mac: &str) -> Option<[u8; 6]> {
    let cleaned = mac.trim().to_lowercase().replace('-', ":");
    let parts: Vec<&str> = cleaned.split(':').collect();
    if parts.len() != 6 {
        return None;
    }
    let mut bytes = [0u8; 6];
    for (index, part) in parts.iter().enumerate() {
        bytes[index] = u8::from_str_radix(part.trim(), 16).ok()?;
    }
    Some(bytes)
}

/// Standard-Magic-Packet: 6x 0xFF, danach 16x die MAC (insgesamt 102 Bytes).
pub fn build_packet(mac_bytes: &[u8; 6]) -> Vec<u8> {
    let mut packet = Vec::with_capacity(6 + 16 * 6);
    packet.extend_from_slice(&[0xFFu8; 6]);
    for _ in 0..16 {
        packet.extend_from_slice(mac_bytes);
    }
    packet
}

/// Sendet das WoL-Paket; im Simulationsmodus wird nur geprueft (true/Ablehnung).
pub fn send_wol(mac_bytes: &[u8; 6], port: u16, broadcast: &str, simulate: bool) -> bool {
    if simulate {
        return true;
    }
    let packet = build_packet(mac_bytes);
    let result = UdpSocket::bind("0.0.0.0:0").and_then(|socket| {
        socket.set_broadcast(true)?;
        socket.send_to(&packet, (broadcast, port))
    });
    matches!(result, Ok(sent) if sent == packet.len())
}

/// Broadcast-Adresse aus der Umgebung (wie der Python-Agent).
pub fn configured_broadcast() -> String {
    std::env::var("PC_WOL_BROADCAST").unwrap_or_else(|_| DEFAULT_BROADCAST.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_mac_liest_alle_formate() {
        // Doppelpunkt in klein und gross, Bindestrich, Leerzeichen drumherum.
        let mac = parse_mac("AA:BB:CC:DD:EE:FF").unwrap();
        assert_eq!(mac, [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);
        let mac = parse_mac("aa-bb-cc-dd-ee-ff").unwrap();
        assert_eq!(mac, [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);
        let mac = parse_mac("  1a:2B:3c:4D:5e:6f  ").unwrap();
        assert_eq!(mac, [0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f]);
        assert!(parse_mac("").is_none());
        assert!(parse_mac("AA:BB:CC:DD:EE").is_none());
        assert!(parse_mac("AA:BB:CC:DD:EE:FF:00").is_none());
        assert!(parse_mac("AA:BB:CC:DD:EE:GG").is_none());
    }

    #[test]
    fn build_packet_hat_standardform() {
        let mac = [0x11u8, 0x22, 0x33, 0x44, 0x55, 0x66];
        let packet = build_packet(&mac);
        assert_eq!(packet.len(), 102);
        assert!(packet[..6].iter().all(|byte| *byte == 0xff));
        for index in 0..16 {
            assert_eq!(&packet[6 + index * 6..12 + index * 6], &mac);
        }
    }
}
