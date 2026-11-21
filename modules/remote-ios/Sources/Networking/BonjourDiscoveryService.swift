import Darwin
import Foundation
import Network
import Observation

/// Dual-layer projector discovery:
/// 1. Primary: Bonjour / mDNS (`_hy300remote._tcp`) via `Network.framework` `NWBrowser`
/// 2. Secondary: UDP broadcast `"HY300_DISCOVER"` to `255.255.255.255:8092`
@MainActor
@Observable
final class BonjourDiscoveryService {
    var discovered: [DiscoveredProjector] = []
    var isScanning: Bool = false

    @ObservationIgnored private let browserQueue = DispatchQueue(label: "com.hy300.discovery.bonjour", qos: .utility)
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var activeResolvers: [String: NWConnection] = [:]
    @ObservationIgnored private var udpScanTask: Task<Void, Never>?

    func startScan() {
        stopScan()
        isScanning = true
        startBonjourBrowser()
        sendUDPBroadcastProbe()
    }

    func refresh() {
        discovered.removeAll()
        startScan()
    }

    func stopScan() {
        isScanning = false
        browser?.cancel()
        browser = nil
        udpScanTask?.cancel()
        udpScanTask = nil

        for (_, conn) in activeResolvers {
            conn.cancel()
        }
        activeResolvers.removeAll()
    }

    // MARK: - Primary: Bonjour NWBrowser (`_hy300remote._tcp`)

    private func startBonjourBrowser() {
        let descriptor = NWBrowser.Descriptor.bonjour(type: "_hy300remote._tcp", domain: nil)
        let params = NWParameters.tcp
        params.includePeerToPeer = true

        let nwBrowser = NWBrowser(for: descriptor, using: params)
        self.browser = nwBrowser

        nwBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor [weak self] in
                self?.handleBrowseResults(results)
            }
        }

        nwBrowser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                if case .failed = state {
                    self?.isScanning = false
                }
            }
        }

        nwBrowser.start(queue: browserQueue)
    }

    private func handleBrowseResults(_ results: Set<NWBrowser.Result>) {
        for result in results {
            guard case .service(let name, _, _, _) = result.endpoint else { continue }
            resolveBonjourEndpoint(result.endpoint, serviceName: name)
        }
    }

    private func resolveBonjourEndpoint(_ endpoint: NWEndpoint, serviceName: String) {
        if activeResolvers[serviceName] != nil { return }

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.connectionTimeout = 3
        let params = NWParameters(tls: nil, tcp: tcpOptions)
        let resolver = NWConnection(to: endpoint, using: params)
        activeResolvers[serviceName] = resolver

        resolver.stateUpdateHandler = { [weak self, weak resolver] state in
            switch state {
            case .ready:
                if let remote = resolver?.currentPath?.remoteEndpoint,
                   case .hostPort(let host, let port) = remote {
                    let hostStr = Self.extractIPv4String(from: host)
                    let tcpPort = port.rawValue == 0 ? UInt16(8091) : port.rawValue
                    if let hostStr {
                        let projector = DiscoveredProjector(
                            name: serviceName,
                            host: hostStr,
                            tcpPort: tcpPort,
                            httpPort: 8090,
                            source: .bonjour,
                            version: 2
                        )
                        Task { @MainActor [weak self] in
                            self?.upsert(projector)
                            self?.activeResolvers[serviceName]?.cancel()
                            self?.activeResolvers.removeValue(forKey: serviceName)
                        }
                        return
                    }
                }
                Task { @MainActor [weak self] in
                    self?.activeResolvers[serviceName]?.cancel()
                    self?.activeResolvers.removeValue(forKey: serviceName)
                }

            case .failed, .cancelled:
                Task { @MainActor [weak self] in
                    self?.activeResolvers.removeValue(forKey: serviceName)
                }

            default:
                break
            }
        }

        resolver.start(queue: browserQueue)
    }

    private nonisolated static func extractIPv4String(from host: NWEndpoint.Host) -> String? {
        switch host {
        case .ipv4(let addr):
            let raw = addr.rawValue
            guard raw.count == 4 else { return nil }
            return raw.map { String($0) }.joined(separator: ".")
        case .name(let hostname, _):
            return hostname
        case .ipv6:
            return nil
        @unknown default:
            return nil
        }
    }

    // MARK: - Secondary: UDP Broadcast ("HY300_DISCOVER" -> 255.255.255.255:8092)

    private func sendUDPBroadcastProbe() {
        udpScanTask?.cancel()
        udpScanTask = Task { [weak self] in
            guard let self else { return }
            let results = await self.performUDPDiscoveryBurst()
            guard !Task.isCancelled else { return }
            for projector in results {
                self.upsert(projector)
            }
            // Keep Bonjour active briefly, mark scanning settled after UDP window
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled {
                self.isScanning = false
            }
        }
    }

    private nonisolated func performUDPDiscoveryBurst() async -> [DiscoveredProjector] {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let found = Self.runBlockingUDPProbe()
                continuation.resume(returning: found)
            }
        }
    }

    private nonisolated static func runBlockingUDPProbe() -> [DiscoveredProjector] {
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        defer { Darwin.close(fd) }

        var broadcast: Int32 = 1
        _ = Darwin.setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &broadcast, socklen_t(MemoryLayout<Int32>.size))

        var reuse: Int32 = 1
        _ = Darwin.setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var timeout = timeval(tv_sec: 1, tv_usec: 200_000)
        _ = Darwin.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var targetAddr = sockaddr_in()
        targetAddr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        targetAddr.sin_family = sa_family_t(AF_INET)
        targetAddr.sin_port = UInt16(8092).bigEndian
        targetAddr.sin_addr = in_addr(s_addr: INADDR_BROADCAST)

        let payload = Array("HY300_DISCOVER".utf8)
        // Send 2 quick probes for Wi-Fi broadcast reliability
        for _ in 0..<2 {
            _ = payload.withUnsafeBytes { rawBuf in
                withUnsafePointer(to: &targetAddr) { addrPtr in
                    addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                        Darwin.sendto(
                            fd,
                            rawBuf.baseAddress,
                            rawBuf.count,
                            0,
                            sockPtr,
                            socklen_t(MemoryLayout<sockaddr_in>.size)
                        )
                    }
                }
            }
            usleep(120_000)
        }

        var results: [DiscoveredProjector] = []
        var recvBuf = [UInt8](repeating: 0, count: 1024)
        let deadline = Date().addingTimeInterval(1.5)

        while Date() < deadline {
            var senderAddr = sockaddr_in()
            var senderLen = socklen_t(MemoryLayout<sockaddr_in>.size)

            let received = withUnsafeMutablePointer(to: &senderAddr) { addrPtr in
                addrPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                    Darwin.recvfrom(fd, &recvBuf, recvBuf.count, 0, sockPtr, &senderLen)
                }
            }

            if received <= 0 {
                break
            }

            let data = Data(recvBuf[0..<received])
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                continue
            }

            var ip = (json["ip"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if ip.isEmpty {
                var cStr = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                var addrCopy = senderAddr.sin_addr
                if inet_ntop(AF_INET, &addrCopy, &cStr, socklen_t(INET_ADDRSTRLEN)) != nil {
                    ip = String(cString: cStr)
                }
            }
            guard !ip.isEmpty else { continue }

            let device = (json["device"] as? String) ?? "HY300Pro"
            let tcpPort = UInt16((json["tcp"] as? Int) ?? 8091)
            let httpPort = UInt16((json["http"] as? Int) ?? 8090)
            let ver = (json["ver"] as? Int) ?? 2

            let item = DiscoveredProjector(
                name: device,
                host: ip,
                tcpPort: tcpPort,
                httpPort: httpPort,
                source: .udp,
                version: ver
            )
            if !results.contains(where: { $0.host == item.host }) {
                results.append(item)
            }
        }

        return results
    }

    // MARK: - Merge Results

    private func upsert(_ projector: DiscoveredProjector) {
        if let idx = discovered.firstIndex(where: { $0.host == projector.host }) {
            // Prefer UDP metadata if Bonjour had a generic name, or keep latest
            discovered[idx] = projector
        } else {
            discovered.append(projector)
        }
    }
}
