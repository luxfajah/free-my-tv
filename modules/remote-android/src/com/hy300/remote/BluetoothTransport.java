package com.hy300.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Direct Bluetooth LE GATT client (Service 0xA300, Cmd 0xA301 WRITE_TYPE_NO_RESPONSE,
 * State 0xA302 Notify + Read) with Classic Bluetooth RFCOMM fallback for bonded HY300Pro devices.
 */
@SuppressLint("MissingPermission")
public class BluetoothTransport {
    public static final UUID SERVICE_UUID = UUID.fromString("0000a300-0000-1000-8000-00805f9b34fb");
    public static final UUID CMD_CHAR_UUID = UUID.fromString("0000a301-0000-1000-8000-00805f9b34fb");
    public static final UUID STATE_CHAR_UUID = UUID.fromString("0000a302-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onBleConnectionState(String state, String deviceName, String address, String mode, String error);
        void onBleJsonMessage(JSONObject msg);
        void onBleDeviceFound(JSONObject device);
        void onBleStatusChanged(String status, boolean scanning, boolean hasPermission);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService rfcommPool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdChar;
    private BluetoothGattCharacteristic stateChar;

    // Classic RFCOMM fallback state
    private volatile BluetoothSocket rfcommSocket;
    private final LinkedBlockingQueue<String> rfcommQueue = new LinkedBlockingQueue<>(128);
    private volatile boolean isRfcommMode = false;

    private volatile boolean isScanning = false;
    private volatile boolean isReady = false;
    private volatile boolean servicesRequested = false;
    private volatile String connectedAddress = null;
    private volatile String connectedName = "HY300Pro";

    private volatile ScheduledFuture<?> heartbeatFuture;
    private Runnable scanStopRunnable;
    private Runnable mtuFallbackRunnable;

    public BluetoothTransport(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            this.adapter = bm.getAdapter();
        }
    }

    public boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    public String getStatusText() {
        if (adapter == null) return "Bluetooth não suportado";
        if (!hasPermissions()) return "Permissão Bluetooth necessária";
        if (!adapter.isEnabled()) return "Bluetooth desligado no celular";
        return isScanning ? "Procurando projetores via Bluetooth…" : "Bluetooth pronto";
    }

    public boolean isReady() {
        return isReady;
    }

    public boolean isScanning() {
        return isScanning;
    }

    public String getConnectedAddress() {
        return connectedAddress;
    }

    public String getConnectedName() {
        return connectedName;
    }

    // --- Scanning (BLE GATT 0xA300 + Bonded Classic HY300Pro Devices) ---

    public void startScan() {
        if (adapter == null) {
            listener.onBleStatusChanged("Bluetooth não suportado", false, hasPermissions());
            return;
        }
        if (!hasPermissions()) {
            listener.onBleStatusChanged("Permissão Bluetooth necessária", false, false);
            return;
        }
        if (!adapter.isEnabled()) {
            listener.onBleStatusChanged("Bluetooth desligado no celular", false, true);
            return;
        }

        stopScan();

        // Surface any already-bonded HY300 devices immediately (supports RFCOMM fallback)
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String name = d.getName();
                    if (name != null && (name.toUpperCase().contains("HY300") || name.toUpperCase().contains("PROJECTOR"))) {
                        emitFoundDevice(name, d.getAddress(), -58, "Pareado");
                    }
                }
            }
        } catch (Exception ignored) {}

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onBleStatusChanged("Scanner BLE indisponível", false, true);
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            isScanning = true;
            listener.onBleStatusChanged("Procurando projetores via Bluetooth…", true, true);
            scanner.startScan(filters, settings, bleScanCallback);

            scanStopRunnable = this::stopScan;
            mainHandler.postDelayed(scanStopRunnable, 12000);
        } catch (Exception e) {
            isScanning = false;
            listener.onBleStatusChanged("Erro ao escanear BLE: " + e.getMessage(), false, true);
        }
    }

    public void stopScan() {
        if (scanStopRunnable != null) {
            mainHandler.removeCallbacks(scanStopRunnable);
            scanStopRunnable = null;
        }
        if (isScanning && scanner != null && hasPermissions()) {
            try {
                scanner.stopScan(bleScanCallback);
            } catch (Exception ignored) {}
        }
        isScanning = false;
        listener.onBleStatusChanged(getStatusText(), false, hasPermissions());
    }

    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice dev = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null || name.trim().isEmpty()) {
                try { name = dev.getName(); } catch (Exception ignored) {}
            }
            if (name == null || name.trim().isEmpty()) {
                name = "HY300Pro";
            }
            int rssi = result.getRssi();
            if (rssi == 127) rssi = -65;
            emitFoundDevice(name.trim(), dev.getAddress(), rssi, "BLE GATT");
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            listener.onBleStatusChanged("Falha no scan BLE (código " + errorCode + ")", false, hasPermissions());
        }
    };

    private void emitFoundDevice(String name, String address, int rssi, String kind) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("address", address);
            obj.put("rssi", rssi);
            obj.put("kind", kind);
            listener.onBleDeviceFound(obj);
        } catch (Exception ignored) {}
    }

    // --- Connect (BLE GATT Primary -> Classic RFCOMM Fallback) ---

    public synchronized void connect(String address) {
        if (address == null || address.trim().isEmpty()) return;
        if (adapter == null || !hasPermissions() || !adapter.isEnabled()) {
            listener.onBleConnectionState("disconnected", connectedName, address, "ble", getStatusText());
            return;
        }

        disconnect();
        stopScan();

        final String cleanAddr = address.trim();
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(cleanAddr);
        } catch (Exception e) {
            listener.onBleConnectionState("disconnected", "HY300Pro", cleanAddr, "ble", "Endereço BT inválido");
            return;
        }

        String devName = null;
        try { devName = device.getName(); } catch (Exception ignored) {}
        connectedName = (devName != null && !devName.trim().isEmpty()) ? devName.trim() : "HY300Pro";
        connectedAddress = cleanAddr;
        isRfcommMode = false;
        servicesRequested = false;

        listener.onBleConnectionState("connecting", connectedName, cleanAddr, "ble", null);

        try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                tryRfcommFallback(device);
            }
        } catch (Exception e) {
            tryRfcommFallback(device);
        }
    }

    public synchronized void disconnect() {
        stopHeartbeat();
        if (mtuFallbackRunnable != null) {
            mainHandler.removeCallbacks(mtuFallbackRunnable);
            mtuFallbackRunnable = null;
        }
        isReady = false;
        servicesRequested = false;
        cmdChar = null;
        stateChar = null;

        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }

        isRfcommMode = false;
        rfcommQueue.clear();
        if (rfcommSocket != null) {
            try { rfcommSocket.close(); } catch (Exception ignored) {}
            rfcommSocket = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) return;
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                servicesRequested = false;
                boolean mtuOk = false;
                try {
                    mtuOk = g.requestMtu(512);
                } catch (Exception ignored) {}

                if (mtuOk) {
                    mtuFallbackRunnable = () -> {
                        if (gatt == g && !servicesRequested) {
                            servicesRequested = true;
                            try { g.discoverServices(); } catch (Exception ignored) {}
                        }
                    };
                    mainHandler.postDelayed(mtuFallbackRunnable, 1500);
                } else {
                    servicesRequested = true;
                    g.discoverServices();
                }
            } else {
                boolean wasReady = isReady;
                BluetoothDevice dev = g.getDevice();
                disconnect();
                if (!wasReady && dev != null) {
                    // BLE GATT failed before becoming ready -> try Classic RFCOMM fallback
                    tryRfcommFallback(dev);
                } else {
                    listener.onBleConnectionState("disconnected", connectedName, connectedAddress, "ble",
                            status == BluetoothGatt.GATT_SUCCESS ? null : "Desconectado (status " + status + ")");
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (g != gatt) return;
            if (mtuFallbackRunnable != null) {
                mainHandler.removeCallbacks(mtuFallbackRunnable);
                mtuFallbackRunnable = null;
            }
            if (!servicesRequested) {
                servicesRequested = true;
                try { g.discoverServices(); } catch (Exception ignored) {}
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BluetoothDevice dev = g.getDevice();
                disconnect();
                tryRfcommFallback(dev);
                return;
            }

            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) {
                BluetoothDevice dev = g.getDevice();
                disconnect();
                tryRfcommFallback(dev);
                return;
            }

            cmdChar = svc.getCharacteristic(CMD_CHAR_UUID);
            stateChar = svc.getCharacteristic(STATE_CHAR_UUID);

            if (cmdChar == null) {
                BluetoothDevice dev = g.getDevice();
                disconnect();
                tryRfcommFallback(dev);
                return;
            }

            if (stateChar != null) {
                g.setCharacteristicNotification(stateChar, true);
                BluetoothGattDescriptor cccd = stateChar.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    writeCccd(g, cccd);
                } else {
                    finishBleReady(g);
                }
            } else {
                finishBleReady(g);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (g != gatt) return;
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                if (stateChar != null) {
                    try { g.readCharacteristic(stateChar); } catch (Exception ignored) {}
                }
                finishBleReady(g);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (g != gatt || !STATE_CHAR_UUID.equals(characteristic.getUuid())) return;
            byte[] val = characteristic.getValue();
            handleIncomingBytes(val);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (g != gatt || !STATE_CHAR_UUID.equals(characteristic.getUuid())) return;
            handleIncomingBytes(value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (g != gatt || status != BluetoothGatt.GATT_SUCCESS) return;
            if (STATE_CHAR_UUID.equals(characteristic.getUuid())) {
                handleIncomingBytes(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (g != gatt || status != BluetoothGatt.GATT_SUCCESS) return;
            if (STATE_CHAR_UUID.equals(characteristic.getUuid())) {
                handleIncomingBytes(value);
            }
        }
    };

    @SuppressWarnings("deprecation")
    private void writeCccd(BluetoothGatt g, BluetoothGattDescriptor cccd) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(cccd);
        }
    }

    private void finishBleReady(BluetoothGatt g) {
        if (isReady) return;
        isReady = true;
        isRfcommMode = false;
        listener.onBleConnectionState("connected", connectedName, connectedAddress, "ble", null);
        sendRawJson("{\"t\":\"state\"}");
        sendPing();
        startHeartbeat();
    }

    private void handleIncomingBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                listener.onBleJsonMessage(new JSONObject(trimmed));
            } catch (Exception ignored) {}
        }
    }

    // --- Classic Bluetooth RFCOMM Fallback ---

    private void tryRfcommFallback(final BluetoothDevice device) {
        if (device == null || !hasPermissions()) {
            listener.onBleConnectionState("disconnected", connectedName, connectedAddress, "ble",
                    "Serviço GATT A300 indisponível");
            return;
        }
        rfcommPool.execute(() -> {
            BluetoothSocket sock = null;
            try {
                sock = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                sock.connect();
                rfcommSocket = sock;
                isRfcommMode = true;
                isReady = true;
                listener.onBleConnectionState("connected", connectedName, connectedAddress, "rfcomm", null);

                final BluetoothSocket activeSock = sock;
                rfcommPool.execute(() -> rfcommWriterLoop(activeSock));

                sendRawJson("{\"t\":\"state\"}");
                sendPing();
                startHeartbeat();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(activeSock.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (isRfcommMode && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    try {
                        listener.onBleJsonMessage(new JSONObject(trimmed));
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                if (sock != null) {
                    try { sock.close(); } catch (Exception ignored) {}
                }
            } finally {
                if (isRfcommMode || !isReady) {
                    isReady = false;
                    isRfcommMode = false;
                    stopHeartbeat();
                    listener.onBleConnectionState("disconnected", connectedName, connectedAddress, "ble",
                            "Falha ao conectar via Bluetooth");
                }
            }
        });
    }

    private void rfcommWriterLoop(BluetoothSocket activeSock) {
        try {
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(activeSock.getOutputStream(), StandardCharsets.UTF_8));
            while (isRfcommMode && isReady) {
                String payload = rfcommQueue.poll(1, TimeUnit.SECONDS);
                if (payload == null) continue;
                writer.write(payload);
                writer.write('\n');
                writer.flush();
            }
        } catch (Exception ignored) {}
    }

    // --- Low-Latency Send ---

    @SuppressWarnings("deprecation")
    public boolean sendRawJson(String json) {
        if (!isReady || json == null || json.isEmpty()) return false;

        if (isRfcommMode) {
            if (!rfcommQueue.offer(json)) {
                rfcommQueue.poll();
                rfcommQueue.offer(json);
            }
            return true;
        }

        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = cmdChar;
        if (g == null || c == null) return false;

        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        int writeType = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, writeType);
                return true;
            } else {
                c.setWriteType(writeType);
                c.setValue(data);
                return g.writeCharacteristic(c);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void sendPing() {
        try {
            JSONObject ping = new JSONObject();
            ping.put("t", "ping");
            ping.put("ts", System.currentTimeMillis());
            sendRawJson(ping.toString());
        } catch (Exception ignored) {}
    }

    private synchronized void startHeartbeat() {
        stopHeartbeat();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isReady) {
                sendPing();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    public void shutdown() {
        stopScan();
        disconnect();
        scheduler.shutdownNow();
        rfcommPool.shutdownNow();
    }
}
