import CoreBluetooth
import Foundation
import Observation

/// Low-latency Bluetooth LE GATT transport matching the HY300Pro `RemoteBleServer`.
/// - Service UUID: `A300` (`0000a300-0000-1000-8000-00805f9b34fb`)
/// - Command Characteristic: `A301` (Write Without Response + Write)
/// - State Characteristic: `A302` (Read + Notify)
@MainActor
@Observable
final class BluetoothRemoteTransport: NSObject {
    // MARK: - GATT UUIDs
    static let serviceUUID = CBUUID(string: "A300")
    static let commandCharUUID = CBUUID(string: "A301")
    static let stateCharUUID = CBUUID(string: "A302")

    // MARK: - Observable State
    var bluetoothState: CBManagerState = .unknown
    var isScanning: Bool = false
    var discoveredPeripherals: [DiscoveredBluetoothProjector] = []
    var connectedPeripheralID: UUID? = nil
    var connectedPeripheralName: String? = nil
    var isReady: Bool = false

    // MARK: - Callbacks
    @ObservationIgnored var onConnected: ((String, UUID) -> Void)?
    @ObservationIgnored var onDisconnected: ((String?) -> Void)?
    @ObservationIgnored var onMessageData: ((Data) -> Void)?

    // MARK: - Private CoreBluetooth State
    @ObservationIgnored private var centralManager: CBCentralManager?
    @ObservationIgnored private var peripheralsByID: [UUID: CBPeripheral] = [:]
    @ObservationIgnored private var connectedPeripheral: CBPeripheral?
    @ObservationIgnored private var commandCharacteristic: CBCharacteristic?
    @ObservationIgnored private var stateCharacteristic: CBCharacteristic?

    @ObservationIgnored private var pendingScan: Bool = false
    @ObservationIgnored private var pendingConnectID: UUID? = nil
    @ObservationIgnored private var scanStopTask: Task<Void, Never>?

    override init() {
        super.init()
    }

    // MARK: - Central Lifecycle

    private func ensureCentralManager() -> CBCentralManager {
        if let centralManager {
            return centralManager
        }
        // Running on `.main` guarantees all delegate callbacks execute on MainActor.
        let manager = CBCentralManager(delegate: self, queue: .main)
        self.centralManager = manager
        self.bluetoothState = manager.state
        return manager
    }

    func startScan(durationSeconds: TimeInterval = 12) {
        let manager = ensureCentralManager()
        if manager.state != .poweredOn {
            pendingScan = true
            isScanning = false
            return
        }

        pendingScan = false
        discoveredPeripherals.removeAll()
        isScanning = true

        manager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )

        scanStopTask?.cancel()
        scanStopTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(durationSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.stopScan()
        }
    }

    func stopScan() {
        scanStopTask?.cancel()
        scanStopTask = nil
        pendingScan = false
        if isScanning {
            centralManager?.stopScan()
            isScanning = false
        }
    }

    func connect(peripheralID: UUID) {
        let manager = ensureCentralManager()
        pendingConnectID = peripheralID

        if manager.state != .poweredOn {
            return
        }

        stopScan()

        // Try direct cached or system-known peripheral first for instant reconnect
        if let known = peripheralsByID[peripheralID] ?? manager.retrievePeripherals(withIdentifiers: [peripheralID]).first {
            connect(to: known, using: manager)
        } else {
            // Fall back to scanning and auto-connecting as soon as the UUID appears
            startScan(durationSeconds: 15)
        }
    }

    private func connect(to peripheral: CBPeripheral, using manager: CBCentralManager) {
        if let current = connectedPeripheral, current.identifier != peripheral.identifier {
            manager.cancelPeripheralConnection(current)
        }
        isReady = false
        commandCharacteristic = nil
        stateCharacteristic = nil
        connectedPeripheral = peripheral
        peripheralsByID[peripheral.identifier] = peripheral
        peripheral.delegate = self
        manager.connect(peripheral, options: nil)
    }

    func disconnect() {
        pendingConnectID = nil
        stopScan()
        isReady = false
        commandCharacteristic = nil
        stateCharacteristic = nil

        if let peripheral = connectedPeripheral {
            peripheral.delegate = nil
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        connectedPeripheral = nil
        connectedPeripheralID = nil
        connectedPeripheralName = nil
    }

    // MARK: - Low-Latency GATT Write

    func send(_ data: Data) {
        guard isReady,
              let peripheral = connectedPeripheral,
              let cmdChar = commandCharacteristic,
              !data.isEmpty else {
            return
        }

        let maxNoResp = peripheral.maximumWriteValueLength(for: .withoutResponse)
        if cmdChar.properties.contains(.writeWithoutResponse) && data.count <= maxNoResp {
            peripheral.writeValue(data, for: cmdChar, type: .withoutResponse)
        } else {
            peripheral.writeValue(data, for: cmdChar, type: .withResponse)
        }
    }

    // MARK: - State Helpers

    var statusDescription: String {
        switch bluetoothState {
        case .poweredOn:
            return isScanning ? "Procurando projetores via Bluetooth…" : "Bluetooth pronto"
        case .poweredOff:
            return "Bluetooth desligado no iPhone"
        case .unauthorized:
            return "Permissão Bluetooth negada nos Ajustes"
        case .unsupported:
            return "Bluetooth LE não suportado neste dispositivo"
        case .resetting:
            return "Reiniciando Bluetooth…"
        case .unknown:
            return "Inicializando Bluetooth…"
        @unknown default:
            return "Bluetooth indisponível"
        }
    }

    // MARK: - MainActor Delegate Handlers

    private func handleStateUpdate(_ state: CBManagerState, manager: CBCentralManager) {
        bluetoothState = state

        switch state {
        case .poweredOn:
            if let targetID = pendingConnectID {
                connect(peripheralID: targetID)
            } else if pendingScan {
                startScan()
            }
        case .poweredOff, .unauthorized, .unsupported, .resetting:
            isScanning = false
            isReady = false
            commandCharacteristic = nil
            stateCharacteristic = nil
            if connectedPeripheral != nil {
                connectedPeripheral = nil
                connectedPeripheralID = nil
                connectedPeripheralName = nil
                onDisconnected?(statusDescription)
            }
        case .unknown:
            break
        @unknown default:
            break
        }
    }

    private func handleDiscovered(
        _ peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi: NSNumber,
        manager: CBCentralManager
    ) {
        peripheralsByID[peripheral.identifier] = peripheral
        let localName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = (localName?.isEmpty == false ? localName : nil)
            ?? peripheral.name
            ?? "HY300Pro"

        let rssiValue = rssi.intValue == 127 ? -65 : rssi.intValue
        let item = DiscoveredBluetoothProjector(
            id: peripheral.identifier,
            name: resolvedName,
            rssi: rssiValue
        )

        if let idx = discoveredPeripherals.firstIndex(where: { $0.id == item.id }) {
            discoveredPeripherals[idx] = item
        } else {
            discoveredPeripherals.append(item)
        }
        discoveredPeripherals.sort { $0.rssi > $1.rssi }

        if let pendingID = pendingConnectID, pendingID == peripheral.identifier {
            stopScan()
            connect(to: peripheral, using: manager)
        }
    }

    private func handleDidConnect(_ peripheral: CBPeripheral) {
        pendingConnectID = nil
        connectedPeripheral = peripheral
        connectedPeripheralID = peripheral.identifier
        connectedPeripheralName = peripheral.name ?? "HY300Pro"
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
    }

    private func handleDidFailToConnect(_ peripheral: CBPeripheral, error: Error?) {
        if connectedPeripheral?.identifier == peripheral.identifier {
            connectedPeripheral = nil
            connectedPeripheralID = nil
            connectedPeripheralName = nil
            isReady = false
            commandCharacteristic = nil
            stateCharacteristic = nil
        }
        onDisconnected?(error?.localizedDescription ?? "Falha ao conectar via Bluetooth")
    }

    private func handleDidDisconnect(_ peripheral: CBPeripheral, error: Error?) {
        if connectedPeripheral?.identifier == peripheral.identifier {
            connectedPeripheral = nil
            connectedPeripheralID = nil
            connectedPeripheralName = nil
            isReady = false
            commandCharacteristic = nil
            stateCharacteristic = nil
            onDisconnected?(error?.localizedDescription)
        }
    }

    private func handleDidDiscoverServices(_ peripheral: CBPeripheral, error: Error?) {
        guard error == nil, let services = peripheral.services else {
            onDisconnected?(error?.localizedDescription ?? "Serviço GATT A300 não encontrado")
            return
        }
        for service in services where service.uuid == Self.serviceUUID {
            peripheral.discoverCharacteristics([Self.commandCharUUID, Self.stateCharUUID], for: service)
        }
    }

    private func handleDidDiscoverCharacteristics(_ peripheral: CBPeripheral, service: CBService, error: Error?) {
        guard error == nil, let characteristics = service.characteristics else {
            onDisconnected?(error?.localizedDescription ?? "Características GATT não encontradas")
            return
        }

        for char in characteristics {
            if char.uuid == Self.commandCharUUID {
                commandCharacteristic = char
            } else if char.uuid == Self.stateCharUUID {
                stateCharacteristic = char
                peripheral.setNotifyValue(true, for: char)
                peripheral.readValue(for: char)
            }
        }

        if commandCharacteristic != nil && !isReady {
            isReady = true
            let name = peripheral.name ?? connectedPeripheralName ?? "HY300Pro"
            connectedPeripheralName = name
            connectedPeripheralID = peripheral.identifier
            onConnected?(name, peripheral.identifier)
        }
    }

    private func handleDidUpdateValue(_ characteristic: CBCharacteristic, error: Error?) {
        guard error == nil,
              characteristic.uuid == Self.stateCharUUID,
              let data = characteristic.value,
              !data.isEmpty else {
            return
        }
        onMessageData?(data)
    }
}

// MARK: - CBCentralManagerDelegate

extension BluetoothRemoteTransport: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            handleStateUpdate(central.state, manager: central)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        MainActor.assumeIsolated {
            handleDiscovered(peripheral, advertisementData: advertisementData, rssi: RSSI, manager: central)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            handleDidConnect(peripheral)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            handleDidFailToConnect(peripheral, error: error)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            handleDidDisconnect(peripheral, error: error)
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BluetoothRemoteTransport: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            handleDidDiscoverServices(peripheral, error: error)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            handleDidDiscoverCharacteristics(peripheral, service: service, error: error)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        MainActor.assumeIsolated {
            handleDidUpdateValue(characteristic, error: error)
        }
    }
}
