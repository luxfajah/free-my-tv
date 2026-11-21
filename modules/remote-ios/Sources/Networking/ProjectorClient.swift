import Foundation
import Network
import Observation
import SwiftUI

/// Serial-queue-owned NDJSON line framing buffer (avoids crossing actor boundaries on hot receive path).
private final class NDJSONLineBuffer: @unchecked Sendable {
    private var buffer = Data()

    func appendAndExtractLines(_ chunk: Data) -> [Data] {
        buffer.append(chunk)
        var lines: [Data] = []
        let newline: UInt8 = 0x0A
        while let range = buffer.firstRange(of: Data([newline])) {
            let line = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
            buffer.removeSubrange(buffer.startIndex...range.lowerBound)
            if !line.isEmpty {
                lines.append(line)
            }
        }
        // Guard against unbounded growth if malformed stream
        if buffer.count > 65_536 {
            buffer.removeAll(keepingCapacity: false)
        }
        return lines
    }

    func reset() {
        buffer.removeAll(keepingCapacity: false)
    }
}

/// Dual-transport low-latency remote client for HY300Pro:
/// - Wi-Fi: Persistent TCP NDJSON (`NWConnection` to `:8091`, `TCP_NODELAY`)
/// - Bluetooth LE: Direct CoreBluetooth GATT (`A300` / `A301` / `A302`)
@MainActor
@Observable
final class ProjectorClient {
    // MARK: - Observable State
    var host: String
    var tcpPort: UInt16 = 8091
    var httpPort: UInt16 = 8090
    var activeTransport: ConnectionTransport = RemoteStorage.savedPreferredTransport()
    let bluetooth = BluetoothRemoteTransport()

    var connectionState: ConnectionState = .disconnected
    var deviceName: String = "HY300Pro"
    var isAwake: Bool = true
    var volume: Int = 10
    var maxVolume: Int = 20
    var isMuted: Bool = false
    var resolution: String = "1920x1080"
    var isDaemonActive: Bool = false
    var latencyMs: Int? = nil
    var lastError: String? = nil
    var activeHUD: HUDMessage? = nil
    var dockApps: [DockAppItem] = RemoteStorage.savedDockApps()
    var isKillingBackgroundApps: Bool = false

    // MARK: - Private Transport State
    @ObservationIgnored private let ioQueue = DispatchQueue(label: "com.hy300.remote.tcp", qos: .userInteractive)
    @ObservationIgnored private var connection: NWConnection?
    @ObservationIgnored private var connectionID: UUID = UUID()
    @ObservationIgnored private let lineBuffer = NDJSONLineBuffer()

    @ObservationIgnored private var heartbeatTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    @ObservationIgnored private var hudDismissTask: Task<Void, Never>?
    @ObservationIgnored private var killBgTimeoutTask: Task<Void, Never>?

    @ObservationIgnored private var reconnectAttempt: Int = 0
    @ObservationIgnored private var isUserDisconnected: Bool = false
    @ObservationIgnored private var isForeground: Bool = true
    @ObservationIgnored private var activeBluetoothID: UUID? = RemoteStorage.savedBluetoothPeripheralID()

    init(host: String = RemoteStorage.savedHost()) {
        self.host = host
        setupBluetoothCallbacks()
    }

    private func setupBluetoothCallbacks() {
        bluetooth.onConnected = { [weak self] name, peripheralID in
            guard let self, self.activeTransport == .bluetooth else { return }
            self.activeBluetoothID = peripheralID
            self.deviceName = name
            self.connectionState = .connected
            self.reconnectAttempt = 0
            self.lastError = nil
            self.requestState()
            self.sendPing()
            self.startHeartbeat()
            self.showHUD(icon: "dot.radiowaves.left.and.right", title: "Bluetooth Conectado")
        }

        bluetooth.onDisconnected = { [weak self] errorMessage in
            guard let self, self.activeTransport == .bluetooth else { return }
            self.heartbeatTask?.cancel()
            self.heartbeatTask = nil
            self.latencyMs = nil
            self.lastError = errorMessage
            if !self.isUserDisconnected {
                self.connectionState = .disconnected
                self.scheduleReconnect()
            }
        }

        bluetooth.onMessageData = { [weak self] data in
            guard let self, self.activeTransport == .bluetooth else { return }
            self.handleIncomingLine(data)
        }
    }

    // MARK: - Connection Lifecycle

    func connectDefault() {
        if activeTransport == .bluetooth, let bleID = activeBluetoothID ?? RemoteStorage.savedBluetoothPeripheralID() {
            connectBluetooth(peripheralID: bleID)
        } else {
            connect(to: host)
        }
    }

    func connect(to newHost: String? = nil, tcpPort: UInt16 = 8091, httpPort: UInt16 = 8090) {
        bluetooth.disconnect()
        activeTransport = .wifi
        RemoteStorage.savePreferredTransport(.wifi)

        if let newHost {
            let trimmed = newHost.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                self.host = trimmed
                RemoteStorage.saveHost(trimmed)
            }
        }
        self.tcpPort = tcpPort
        self.httpPort = httpPort
        self.isUserDisconnected = false
        self.reconnectAttempt = 0
        self.lastError = nil

        openTCPConnection()
    }

    func connectBluetooth(peripheralID: UUID) {
        teardownTCPConnection()
        activeTransport = .bluetooth
        activeBluetoothID = peripheralID
        RemoteStorage.savePreferredTransport(.bluetooth)
        RemoteStorage.saveBluetoothPeripheralID(peripheralID)

        isUserDisconnected = false
        reconnectAttempt = 0
        lastError = nil
        latencyMs = nil
        connectionState = .connecting

        bluetooth.connect(peripheralID: peripheralID)
    }

    func reconnectNow() {
        isUserDisconnected = false
        reconnectAttempt = 0
        switch activeTransport {
        case .wifi:
            openTCPConnection()
        case .bluetooth:
            if let bleID = activeBluetoothID ?? RemoteStorage.savedBluetoothPeripheralID() {
                connectionState = .reconnecting
                bluetooth.connect(peripheralID: bleID)
            } else {
                bluetooth.startScan()
            }
        }
    }

    func disconnect() {
        isUserDisconnected = true
        teardownTCPConnection()
        bluetooth.disconnect()
        connectionState = .disconnected
        latencyMs = nil
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            isForeground = true
            if !isUserDisconnected && connectionState != .connected {
                reconnectNow()
            } else if connectionState == .connected {
                sendPing()
                requestState()
                if heartbeatTask == nil {
                    startHeartbeat()
                }
            }
        case .background:
            isForeground = false
            heartbeatTask?.cancel()
            heartbeatTask = nil
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    private func teardownTCPConnection() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil

        connectionID = UUID()
        let old = connection
        connection = nil
        old?.stateUpdateHandler = nil
        old?.cancel()

        let buffer = lineBuffer
        ioQueue.async { buffer.reset() }
    }

    private func openTCPConnection() {
        teardownTCPConnection()

        guard let port = NWEndpoint.Port(rawValue: tcpPort) else {
            lastError = "Porta inválida: \(tcpPort)"
            connectionState = .disconnected
            return
        }

        let currentID = UUID()
        connectionID = currentID
        connectionState = reconnectAttempt > 0 ? .reconnecting : .connecting

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true // TCP_NODELAY for instant <5ms key pipelining
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = 10
        tcpOptions.keepaliveInterval = 5
        tcpOptions.keepaliveCount = 3
        tcpOptions.connectionTimeout = 5

        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        parameters.allowLocalEndpointReuse = true

        let conn = NWConnection(
            host: NWEndpoint.Host(host),
            port: port,
            using: parameters
        )
        self.connection = conn

        conn.stateUpdateHandler = { [weak self] newState in
            Task { @MainActor [weak self] in
                self?.handleTCPStateChange(newState, for: currentID)
            }
        }

        conn.start(queue: ioQueue)
    }

    private func handleTCPStateChange(_ state: NWConnection.State, for id: UUID) {
        guard id == connectionID, activeTransport == .wifi else { return }

        switch state {
        case .ready:
            connectionState = .connected
            reconnectAttempt = 0
            lastError = nil
            startReceiveLoop(for: id)
            requestState()
            requestDockApps()
            sendPing()
            startHeartbeat()

        case .waiting(let error):
            lastError = error.localizedDescription
            connectionState = .reconnecting
            scheduleReconnect()

        case .failed(let error):
            lastError = error.localizedDescription
            connectionState = .disconnected
            latencyMs = nil
            scheduleReconnect()

        case .cancelled:
            if !isUserDisconnected {
                connectionState = .disconnected
            }
            latencyMs = nil

        case .preparing, .setup:
            if connectionState != .reconnecting {
                connectionState = .connecting
            }

        @unknown default:
            break
        }
    }

    private func scheduleReconnect() {
        guard !isUserDisconnected, isForeground else { return }
        reconnectTask?.cancel()

        let attempt = min(reconnectAttempt, 4)
        let delaySeconds = min(0.5 * pow(2.0, Double(attempt)), 8.0)
        reconnectAttempt += 1

        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            switch self.activeTransport {
            case .wifi:
                self.openTCPConnection()
            case .bluetooth:
                if let bleID = self.activeBluetoothID {
                    self.connectionState = .reconnecting
                    self.bluetooth.connect(peripheralID: bleID)
                }
            }
        }
    }

    private func startHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard !Task.isCancelled else { break }
                guard let self, self.connectionState == .connected else { break }
                self.sendPing()
            }
        }
    }

    // MARK: - Receive Loop (TCP) + Unified NDJSON Parser (TCP & BLE)

    private func startReceiveLoop(for id: UUID) {
        guard let conn = connection else { return }
        let buffer = lineBuffer

        conn.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            if let data, !data.isEmpty {
                let lines = buffer.appendAndExtractLines(data)
                if !lines.isEmpty {
                    Task { @MainActor [weak self] in
                        guard let self, self.connectionID == id else { return }
                        for line in lines {
                            self.handleIncomingLine(line)
                        }
                    }
                }
            }

            if isComplete || error != nil {
                Task { @MainActor [weak self] in
                    guard let self, self.connectionID == id else { return }
                    self.connectionState = .disconnected
                    self.latencyMs = nil
                    self.scheduleReconnect()
                }
                return
            }

            Task { @MainActor [weak self] in
                guard let self, self.connectionID == id, self.connectionState == .connected else { return }
                self.startReceiveLoop(for: id)
            }
        }
    }

    private func handleIncomingLine(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["t"] as? String else {
            return
        }

        switch type {
        case "state":
            if let device = json["device"] as? String, !device.isEmpty {
                deviceName = device
            }
            if let awake = json["awake"] as? Bool {
                isAwake = awake
            }
            if let vol = json["vol"] as? Int {
                volume = vol
            }
            if let maxV = json["maxVol"] as? Int, maxV > 0 {
                maxVolume = maxV
            }
            if let muted = json["muted"] as? Bool {
                isMuted = muted
            }
            if let res = json["res"] as? String, !res.isEmpty {
                resolution = res.replacingOccurrences(of: "Override size: ", with: "")
            }
            if let daemon = json["daemon"] as? Bool {
                isDaemonActive = daemon
            }

        case "pong":
            if let ts = json["ts"] as? Int64 {
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                latencyMs = max(1, Int(nowMs - ts))
            } else if let tsNum = json["ts"] as? NSNumber {
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                latencyMs = max(1, Int(nowMs - tsNum.int64Value))
            }

        case "apps":
            if let rawItems = json["items"] as? [[String: Any]] {
                applyParsedDockApps(rawItems)
            }

        case "bg_killed":
            killBgTimeoutTask?.cancel()
            killBgTimeoutTask = nil
            isKillingBackgroundApps = false
            let count = (json["count"] as? Int) ?? 0
            HapticEngine.shared.success()
            let title = count > 0 ? "\(count) apps fechados" : "Apps em 2º plano fechados"
            showHUD(icon: "checkmark.circle.fill", title: title)

        default:
            break
        }
    }

    private func applyParsedDockApps(_ rawItems: [[String: Any]]) {
        let parsed: [DockAppItem] = rawItems.compactMap { item in
            guard let pkg = item["pkg"] as? String, !pkg.isEmpty else { return nil }
            let label = (item["label"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedLabel = (label?.isEmpty == false) ? label! : pkg
            let color = item["color"] as? String
            let icon = item["icon"] as? String
            return DockAppItem(pkg: pkg, label: resolvedLabel, color: color, icon: icon)
        }
        if !parsed.isEmpty {
            dockApps = parsed
            RemoteStorage.saveDockApps(parsed)
        }
    }

    // MARK: - Unified Send Router (TCP NDJSON or BLE GATT)

    func sendRawJSON(_ json: String) {
        guard let data = json.data(using: .utf8), !data.isEmpty else { return }
        sendRawData(data)
    }

    private func sendPayload(_ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            return
        }
        sendRawData(data)
    }

    private func sendRawData(_ data: Data) {
        switch activeTransport {
        case .bluetooth:
            guard bluetooth.isReady, connectionState == .connected else {
                if !isUserDisconnected && connectionState == .disconnected {
                    reconnectNow()
                }
                return
            }
            bluetooth.send(data)

        case .wifi:
            guard let conn = connection, connectionState == .connected else {
                if !isUserDisconnected && connectionState == .disconnected {
                    reconnectNow()
                }
                return
            }
            var framed = data
            framed.append(0x0A) // '\n' NDJSON terminator
            // Fire-and-forget pipelined send: no ack wait for <5ms tactile response
            conn.send(content: framed, completion: .contentProcessed { [weak self] error in
                if let error {
                    Task { @MainActor [weak self] in
                        self?.lastError = error.localizedDescription
                    }
                }
            })
        }
    }

    // MARK: - High-Level Remote Commands

    func sendKey(_ key: RemoteKey, action: KeyAction = .tap) {
        sendPayload([
            "t": "key",
            "k": key.rawValue,
            "a": action.rawValue
        ])

        // Optimistic HUD updates for tactile responsiveness
        switch (key, action) {
        case (.volUp, .down), (.volUp, .tap):
            isMuted = false
            volume = min(maxVolume, volume + 1)
            showVolumeHUD()
        case (.volDown, .down), (.volDown, .tap):
            volume = max(0, volume - 1)
            showVolumeHUD()
        case (.mute, .tap):
            isMuted.toggle()
            showHUD(
                icon: isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                title: isMuted ? "Sem Som" : "Som Ativo"
            )
        default:
            break
        }
    }

    func sendText(_ text: String) {
        guard !text.isEmpty else { return }
        sendPayload([
            "t": "text",
            "v": text
        ])
    }

    func setVolume(_ value: Int) {
        let clamped = max(0, min(maxVolume, value))
        volume = clamped
        isMuted = clamped == 0
        sendPayload([
            "t": "vol",
            "v": clamped
        ])
        showVolumeHUD()
    }

    func sendPower(_ action: PowerAction) {
        sendPayload([
            "t": "power",
            "a": action.rawValue
        ])
        switch action {
        case .wake:
            isAwake = true
            showHUD(icon: "power", title: "Ligando Projetor")
        case .standby:
            isAwake = false
            showHUD(icon: "moon.zzz.fill", title: "Modo Standby")
        case .toggle:
            showHUD(icon: "power", title: "Alternando Energia")
        }
    }

    func launchApp(_ app: ProjectorApp) {
        sendPayload([
            "t": "app",
            "id": app.rawValue
        ])
        showHUD(icon: app.systemImage, title: "Abrindo \(app.title)")
    }

    func launchDockApp(_ pkg: String, label: String? = nil) {
        sendPayload([
            "t": "app",
            "id": pkg
        ])
        showHUD(icon: "square.grid.2x2.fill", title: "Abrindo \(label ?? pkg)")
    }

    func requestDockApps() {
        if connectionState == .connected {
            sendPayload(["t": "apps"])
        }
        if activeTransport == .wifi {
            Task { [weak self] in
                await self?.fetchDockAppsHTTP()
            }
        }
    }

    func fetchDockAppsHTTP() async {
        guard let url = URL(string: "http://\(host):\(httpPort)/api/apps") else { return }
        var req = URLRequest(url: url)
        req.timeoutInterval = 4
        guard let (data, response) = try? await URLSession.shared.data(for: req),
              let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode) else {
            return
        }
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let rawItems = json["items"] as? [[String: Any]] {
            applyParsedDockApps(rawItems)
        } else if let rawItems = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            applyParsedDockApps(rawItems)
        }
    }

    func killBackgroundApps() {
        guard !isKillingBackgroundApps else { return }
        isKillingBackgroundApps = true
        HapticEngine.shared.tapMedium()
        sendPayload(["t": "kill_bg"])

        killBgTimeoutTask?.cancel()
        killBgTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled, let self, self.isKillingBackgroundApps else { return }
            self.isKillingBackgroundApps = false
            HapticEngine.shared.success()
            self.showHUD(icon: "sparkles", title: "Apps em 2º plano fechados")
        }
    }

    func iconURL(for app: DockAppItem) -> URL? {
        let rawPath: String
        if let icon = app.icon, !icon.isEmpty {
            if icon.hasPrefix("http://") || icon.hasPrefix("https://") {
                return URL(string: icon)
            }
            rawPath = icon.hasPrefix("/") ? icon : "/\(icon)"
        } else {
            let encodedPkg = app.pkg.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? app.pkg
            rawPath = "/api/app-icon/\(encodedPkg)"
        }
        return URL(string: "http://\(host):\(httpPort)\(rawPath)")
    }

    func setResolution(_ preset: ResolutionPreset) {
        sendPayload([
            "t": "res",
            "p": preset.rawValue
        ])
        resolution = preset.matchesResolutionString
        showHUD(icon: "display", title: preset.title)
    }

    func resetKeystone() {
        sendPayload([
            "t": "keystone",
            "a": "reset"
        ])
        showHUD(icon: "viewfinder", title: "Keystone Resetado")
    }

    func openURL(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if connectionState == .connected {
            sendPayload([
                "t": "open_url",
                "url": trimmed
            ])
        } else {
            // Fallback HTTP POST /api/open_url
            let targetHost = host
            let port = httpPort
            Task.detached {
                guard let url = URL(string: "http://\(targetHost):\(port)/api/open_url") else { return }
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.httpBody = try? JSONSerialization.data(withJSONObject: ["url": trimmed])
                req.timeoutInterval = 5
                _ = try? await URLSession.shared.data(for: req)
            }
        }
        showHUD(icon: "arrow.up.forward.app.fill", title: "Enviado ao Projetor", subtitle: trimmed)
    }

    func requestState() {
        sendPayload(["t": "state"])
    }

    func sendPing() {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        sendPayload([
            "t": "ping",
            "ts": nowMs
        ])
    }

    // MARK: - Transient HUD

    func showVolumeHUD() {
        let progress = maxVolume > 0 ? Double(volume) / Double(maxVolume) : 0
        let icon: String
        if isMuted || volume == 0 {
            icon = "speaker.slash.fill"
        } else if progress < 0.35 {
            icon = "speaker.wave.1.fill"
        } else if progress < 0.72 {
            icon = "speaker.wave.2.fill"
        } else {
            icon = "speaker.wave.3.fill"
        }
        showHUD(
            icon: icon,
            title: isMuted ? "Mudo" : "Volume",
            subtitle: "\(volume)/\(maxVolume)",
            volumeProgress: isMuted ? 0 : progress
        )
    }

    func showHUD(
        icon: String,
        title: String,
        subtitle: String? = nil,
        volumeProgress: Double? = nil
    ) {
        hudDismissTask?.cancel()
        withAnimation(.snappy(duration: 0.22)) {
            activeHUD = HUDMessage(
                icon: icon,
                title: title,
                subtitle: subtitle,
                volumeProgress: volumeProgress
            )
        }
        hudDismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_650_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) {
                self?.activeHUD = nil
            }
        }
    }
}
