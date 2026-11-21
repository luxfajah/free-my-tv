import Foundation
import SwiftUI

// MARK: - Protocol Key Definitions

enum RemoteKey: String, CaseIterable, Sendable {
    case up
    case down
    case left
    case right
    case ok
    case back
    case home
    case menu
    case apps
    case volUp = "vol_up"
    case volDown = "vol_down"
    case mute
    case playPause = "play_pause"
    case rewind
    case forward
    case power
    case wake
    case standby
    case backspace
    case enter
    case settings
    case search
}

enum KeyAction: String, Sendable {
    case down
    case up
    case tap
}

enum PowerAction: String, Sendable {
    case wake
    case standby
    case toggle
}

// MARK: - Projector Apps

enum ProjectorApp: String, CaseIterable, Identifiable, Sendable {
    case stremio
    case unitv
    case smarttube
    case hdmi
    case bluetooth
    case settings
    case keystone

    var id: String { rawValue }

    var title: String {
        switch self {
        case .stremio: return "Stremio"
        case .unitv: return "UniTV"
        case .smarttube: return "SmartTube"
        case .hdmi: return "HDMI"
        case .bluetooth: return "Bluetooth"
        case .settings: return "Ajustes"
        case .keystone: return "Keystone"
        }
    }

    var subtitle: String {
        switch self {
        case .stremio: return "Filmes e Séries"
        case .unitv: return "TV ao Vivo"
        case .smarttube: return "YouTube sem Ads"
        case .hdmi: return "Entrada Externa"
        case .bluetooth: return "Áudio & Controles"
        case .settings: return "Sistema Android"
        case .keystone: return "Correção 4 Pontos"
        }
    }

    var systemImage: String {
        switch self {
        case .stremio: return "film.stack"
        case .unitv: return "tv"
        case .smarttube: return "play.rectangle.fill"
        case .hdmi: return "cable.connector.horizontal"
        case .bluetooth: return "wave.3.right"
        case .settings: return "gearshape.fill"
        case .keystone: return "viewfinder"
        }
    }

    var accentColor: Color {
        switch self {
        case .stremio: return Color(red: 0.48, green: 0.42, blue: 0.96)
        case .unitv: return Color(red: 0.16, green: 0.78, blue: 0.94)
        case .smarttube: return Color(red: 0.95, green: 0.26, blue: 0.28)
        case .hdmi: return Color(red: 0.22, green: 0.82, blue: 0.48)
        case .bluetooth: return Color(red: 0.25, green: 0.56, blue: 0.98)
        case .settings: return Color(red: 0.62, green: 0.65, blue: 0.72)
        case .keystone: return Color(red: 0.96, green: 0.62, blue: 0.18)
        }
    }
}

// MARK: - Dynamic Projector Dock App Item

struct DockAppItem: Identifiable, Codable, Hashable, Sendable {
    let pkg: String
    let label: String
    let color: String?
    let icon: String?

    var id: String { pkg }

    var parsedColor: Color {
        guard let raw = color?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else {
            return Color(red: 0.36, green: 0.48, blue: 0.92)
        }
        let hex = raw.hasPrefix("#") ? String(raw.dropFirst()) : raw
        guard hex.count == 6, let value = UInt64(hex, radix: 16) else {
            return Color(red: 0.36, green: 0.48, blue: 0.92)
        }
        let r = Double((value >> 16) & 0xFF) / 255.0
        let g = Double((value >> 8) & 0xFF) / 255.0
        let b = Double(value & 0xFF) / 255.0
        return Color(red: r, green: g, blue: b)
    }
}

// MARK: - Resolution Presets

enum ResolutionPreset: String, CaseIterable, Identifiable, Sendable {
    case p1080 = "1080p"
    case p720TV = "720p_tv"
    case p720 = "720p"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .p1080: return "1080p Full HD"
        case .p720TV: return "720p Android TV"
        case .p720: return "720p Nativo"
        }
    }

    var dimensions: String {
        switch self {
        case .p1080: return "1920×1080"
        case .p720TV: return "1280×720 • 213dpi"
        case .p720: return "1280×720"
        }
    }

    var matchesResolutionString: String {
        switch self {
        case .p1080: return "1920x1080"
        case .p720TV, .p720: return "1280x720"
        }
    }
}

// MARK: - Pad Mode & Connection State

enum PadMode: String, CaseIterable, Sendable {
    case touchpad
    case clickpad

    var title: String {
        switch self {
        case .touchpad: return "Touchpad"
        case .clickpad: return "Clickpad"
        }
    }

    var toggleIcon: String {
        switch self {
        case .touchpad: return "dpad.fill"
        case .clickpad: return "hand.draw.fill"
        }
    }

    var toggleLabel: String {
        switch self {
        case .touchpad: return "D-Pad"
        case .clickpad: return "Touch"
        }
    }
}

enum ConnectionState: Equatable, Sendable {
    case disconnected
    case connecting
    case connected
    case reconnecting

    var isConnected: Bool { self == .connected }

    var label: String {
        switch self {
        case .disconnected: return "Offline"
        case .connecting: return "Conectando…"
        case .connected: return "Conectado"
        case .reconnecting: return "Reconectando…"
        }
    }
}

// MARK: - Transport & Discovery Models

enum ConnectionTransport: String, Sendable {
    case wifi
    case bluetooth

    var label: String {
        switch self {
        case .wifi: return "Wi-Fi"
        case .bluetooth: return "Bluetooth"
        }
    }

    var systemImage: String {
        switch self {
        case .wifi: return "wifi"
        case .bluetooth: return "dot.radiowaves.left.and.right"
        }
    }
}

struct DiscoveredBluetoothProjector: Identifiable, Hashable, Sendable {
    let id: UUID
    let name: String
    let rssi: Int
}

enum DiscoverySource: String, Sendable {
    case bonjour = "Bonjour"
    case udp = "UDP"
    case manual = "Manual"
}

struct DiscoveredProjector: Identifiable, Hashable, Sendable {
    var id: String { host }
    let name: String
    let host: String
    let tcpPort: UInt16
    let httpPort: UInt16
    let source: DiscoverySource
    let version: Int

    init(
        name: String,
        host: String,
        tcpPort: UInt16 = 8091,
        httpPort: UInt16 = 8090,
        source: DiscoverySource,
        version: Int = 2
    ) {
        self.name = name
        self.host = host
        self.tcpPort = tcpPort
        self.httpPort = httpPort
        self.source = source
        self.version = version
    }
}

// MARK: - Transient HUD

struct HUDMessage: Equatable, Identifiable, Sendable {
    let id: UUID
    let icon: String
    let title: String
    let subtitle: String?
    let volumeProgress: Double?

    init(
        id: UUID = UUID(),
        icon: String,
        title: String,
        subtitle: String? = nil,
        volumeProgress: Double? = nil
    ) {
        self.id = id
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.volumeProgress = volumeProgress
    }
}

// MARK: - Persistent Storage

enum RemoteStorage {
    static let lastHostKey = "hy300_last_host"
    static let padModeKey = "hy300_pad_mode"
    static let lastBluetoothIDKey = "hy300_last_ble_id"
    static let preferredTransportKey = "hy300_preferred_transport"
    static let dockAppsKey = "hy300_dock_apps"
    static let defaultHost = "192.168.1.7"

    static func savedHost() -> String {
        let value = UserDefaults.standard.string(forKey: lastHostKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (value?.isEmpty == false) ? value! : defaultHost
    }

    static func saveHost(_ host: String) {
        let cleaned = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return }
        UserDefaults.standard.set(cleaned, forKey: lastHostKey)
    }

    static func savedPadMode() -> PadMode {
        guard let raw = UserDefaults.standard.string(forKey: padModeKey),
              let mode = PadMode(rawValue: raw) else {
            return .touchpad
        }
        return mode
    }

    static func savePadMode(_ mode: PadMode) {
        UserDefaults.standard.set(mode.rawValue, forKey: padModeKey)
    }

    static func savedBluetoothPeripheralID() -> UUID? {
        guard let raw = UserDefaults.standard.string(forKey: lastBluetoothIDKey) else {
            return nil
        }
        return UUID(uuidString: raw)
    }

    static func saveBluetoothPeripheralID(_ id: UUID) {
        UserDefaults.standard.set(id.uuidString, forKey: lastBluetoothIDKey)
    }

    static func savedPreferredTransport() -> ConnectionTransport {
        guard let raw = UserDefaults.standard.string(forKey: preferredTransportKey),
              let transport = ConnectionTransport(rawValue: raw) else {
            return .wifi
        }
        return transport
    }

    static func savePreferredTransport(_ transport: ConnectionTransport) {
        UserDefaults.standard.set(transport.rawValue, forKey: preferredTransportKey)
    }

    static func savedDockApps() -> [DockAppItem] {
        guard let data = UserDefaults.standard.data(forKey: dockAppsKey),
              let items = try? JSONDecoder().decode([DockAppItem].self, from: data) else {
            return []
        }
        return items
    }

    static func saveDockApps(_ apps: [DockAppItem]) {
        guard let data = try? JSONEncoder().encode(apps) else { return }
        UserDefaults.standard.set(data, forKey: dockAppsKey)
    }
}
