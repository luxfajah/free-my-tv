package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-mode Bluetooth Remote Server for HY300Pro (works even without Wi-Fi):
 * 1. BLE GATT Peripheral (iOS CoreBluetooth & Android BLE):
 *    - Service: 0000a300-0000-1000-8000-00805f9b34fb (16-bit 0xA300)
 *    - Command Char (Write / Write No Response): 0000a301-...
 *    - State Char (Read / Notify + CCCD 0x2902): 0000a302-...
 * 2. Classic Bluetooth RFCOMM Fallback (Android phones):
 *    - Insecure RFCOMM SDP record "HY300Remote" on UUID 0000a300-... (NDJSON lines)
 */
final class RemoteBleServer {
    static final UUID SERVICE_UUID = UUID.fromString("0000a300-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_CMD_UUID = UUID.fromString("0000a301-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_STATE_UUID = UUID.fromString("0000a302-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String RFCOMM_NAME = "HY300Remote";

    private static RemoteBleServer instance;

    private final Context appContext;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CopyOnWriteArraySet<BluetoothDevice> subscribedDevices = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<String, StringBuilder> rxBuffers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RfcommSession> rfcommClients = new CopyOnWriteArrayList<>();

    private final ExecutorService bleNotifyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RemoteBLE-Notify");
        t.setDaemon(true);
        return t;
    });

    private volatile BluetoothGattServer gattServer;
    private volatile BluetoothGattCharacteristic stateChar;
    private volatile BluetoothLeAdvertiser advertiser;
    private volatile AdvertiseCallback advertiseCallback;
    private volatile BluetoothServerSocket rfcommServerSocket;
    private BroadcastReceiver btStateReceiver;

    private static final class RfcommSession {
        final BluetoothSocket socket;
        final OutputStream out;
        final Object writeLock = new Object();

        RfcommSession(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        boolean sendLine(String jsonLine) {
            byte[] bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                try {
                    out.write(bytes);
                    out.flush();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private RemoteBleServer(Context context) {
        this.appContext = context.getApplicationContext();
        registerBtStateReceiver();
        startBluetoothTransports();
    }

    static synchronized RemoteBleServer start(Context context) {
        if (instance != null && instance.running.get()) {
            return instance;
        }
        try {
            instance = new RemoteBleServer(context);
        } catch (Throwable t) {
            AppLog.w("RemoteBleServer start error: " + t.getMessage());
        }
        return instance;
    }

    static RemoteBleServer get() {
        return instance;
    }

    boolean hasConnectedClients() {
        return !subscribedDevices.isEmpty() || !rfcommClients.isEmpty();
    }

    void broadcastState(String stateJson) {
        if (stateJson == null || stateJson.isEmpty()) return;

        if (!subscribedDevices.isEmpty()) {
            final byte[] payload = stateJson.getBytes(StandardCharsets.UTF_8);
            bleNotifyExecutor.execute(() -> {
                BluetoothGattServer srv = gattServer;
                BluetoothGattCharacteristic ch = stateChar;
                if (srv == null || ch == null) return;
                for (BluetoothDevice dev : subscribedDevices) {
                    try {
                        ch.setValue(payload);
                        srv.notifyCharacteristicChanged(dev, ch, false);
                    } catch (Throwable ignored) {}
                }
            });
        }

        if (!rfcommClients.isEmpty()) {
            for (RfcommSession s : rfcommClients) {
                if (!s.sendLine(stateJson)) {
                    rfcommClients.remove(s);
                    s.close();
                }
            }
        }
    }

    private void notifyDevice(BluetoothDevice device, String jsonLine) {
        if (device == null || jsonLine == null) return;
        final byte[] payload = jsonLine.getBytes(StandardCharsets.UTF_8);
        bleNotifyExecutor.execute(() -> {
            BluetoothGattServer srv = gattServer;
            BluetoothGattCharacteristic ch = stateChar;
            if (srv == null || ch == null) return;
            try {
                ch.setValue(payload);
                srv.notifyCharacteristicChanged(device, ch, false);
            } catch (Throwable ignored) {}
        });
    }

    private synchronized void startBluetoothTransports() {
        try {
            BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) {
                AppLog.w("RemoteBleServer: BluetoothManager unavailable");
                return;
            }
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                AppLog.w("RemoteBleServer: Bluetooth adapter off or unavailable");
                return;
            }

            startGattAndAdvertising(bm, adapter);
            startRfcommServer(adapter);
        } catch (Throwable t) {
            AppLog.w("RemoteBleServer init error: " + t.getMessage());
        }
    }

    private void startGattAndAdvertising(BluetoothManager bm, BluetoothAdapter adapter) {
        try {
            if (gattServer != null) return;

            BluetoothGattServer server = bm.openGattServer(appContext, gattCallback);
            if (server == null) {
                AppLog.w("RemoteBleServer: openGattServer returned null");
                return;
            }

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic cmdChar = new BluetoothGattCharacteristic(
                    CHAR_CMD_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
            );

            BluetoothGattCharacteristic stChar = new BluetoothGattCharacteristic(
                    CHAR_STATE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ
                            | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ
            );

            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ
                            | BluetoothGattDescriptor.PERMISSION_WRITE
            );
            cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            stChar.addDescriptor(cccd);

            service.addCharacteristic(cmdChar);
            service.addCharacteristic(stChar);

            boolean added = server.addService(service);
            this.stateChar = stChar;
            this.gattServer = server;
            AppLog.w("RemoteBleServer GATT service " + SERVICE_UUID + " added=" + added);

            BluetoothLeAdvertiser adv = adapter.getBluetoothLeAdvertiser();
            if (adv == null) {
                AppLog.w("RemoteBleServer: BluetoothLeAdvertiser unavailable on this adapter");
                return;
            }
            this.advertiser = adv;

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();

            AdvertiseData advData = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();

            AdvertiseData scanResp = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    AppLog.w("RemoteBleServer BLE advertising onStartSuccess (UUID " + SERVICE_UUID + ")");
                }

                @Override public void onStartFailure(int errorCode) {
                    AppLog.w("RemoteBleServer BLE advertising onStartFailure errorCode=" + errorCode);
                }
            };

            adv.startAdvertising(settings, advData, scanResp, advertiseCallback);
        } catch (Throwable t) {
            AppLog.w("RemoteBleServer GATT/Advertise error: " + t.getMessage());
        }
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (device == null) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                AppLog.w("RemoteBleServer BLE connected: " + device.getAddress());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                AppLog.w("RemoteBleServer BLE disconnected: " + device.getAddress());
                subscribedDevices.remove(device);
                rxBuffers.remove(device.getAddress());
                RemoteDaemonBridge.releaseAll();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            BluetoothGattServer srv = gattServer;
            if (srv == null) return;
            if (CHAR_STATE_UUID.equals(characteristic.getUuid())) {
                byte[] full = RemoteTcpServer.buildStateJson(appContext).toString().getBytes(StandardCharsets.UTF_8);
                byte[] slice = (offset >= full.length) ? new byte[0] : Arrays.copyOfRange(full, offset, full.length);
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
                } catch (Throwable ignored) {}
            } else {
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            BluetoothGattServer srv = gattServer;
            if (responseNeeded && srv != null) {
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                } catch (Throwable ignored) {}
            }
            if (!CHAR_CMD_UUID.equals(characteristic.getUuid()) || value == null || value.length == 0) {
                return;
            }
            String chunk = new String(value, StandardCharsets.UTF_8);
            handleIncomingBleText(device, chunk);
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            BluetoothGattServer srv = gattServer;
            if (srv == null) return;
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                byte[] val = subscribedDevices.contains(device)
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, val);
                } catch (Throwable ignored) {}
            } else {
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            BluetoothGattServer srv = gattServer;
            if (CCCD_UUID.equals(descriptor.getUuid()) && value != null) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        || Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    subscribedDevices.add(device);
                    if (responseNeeded && srv != null) {
                        try {
                            srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                        } catch (Throwable ignored) {}
                    }
                    // Immediately push current state upon subscribe
                    notifyDevice(device, RemoteTcpServer.buildStateJson(appContext).toString());
                    return;
                } else {
                    subscribedDevices.remove(device);
                }
            }
            if (responseNeeded && srv != null) {
                try {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                } catch (Throwable ignored) {}
            }
        }
    };

    private void handleIncomingBleText(BluetoothDevice device, String chunk) {
        String key = device != null ? device.getAddress() : "ble";
        StringBuilder sb = rxBuffers.computeIfAbsent(key, k -> new StringBuilder());
        synchronized (sb) {
            sb.append(chunk);
            if (sb.length() > 4096) {
                sb.setLength(0);
                return;
            }
            int nl;
            while ((nl = sb.indexOf("\n")) != -1) {
                String line = sb.substring(0, nl).trim();
                sb.delete(0, nl + 1);
                if (!line.isEmpty()) {
                    dispatchBleJson(device, line);
                }
            }
            // Fast path: single-packet complete JSON without trailing newline
            String remaining = sb.toString().trim();
            if (remaining.startsWith("{") && remaining.endsWith("}")) {
                sb.setLength(0);
                dispatchBleJson(device, remaining);
            }
        }
    }

    private void dispatchBleJson(BluetoothDevice device, String jsonLine) {
        RemoteTcpServer tcp = RemoteTcpServer.get();
        if (tcp == null) return;
        tcp.handleCommand(jsonLine, reply -> notifyDevice(device, reply));
    }

    private void startRfcommServer(BluetoothAdapter adapter) {
        if (rfcommServerSocket != null) return;
        Thread acceptThread = new Thread(() -> {
            try {
                BluetoothServerSocket server = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        RFCOMM_NAME, SERVICE_UUID);
                rfcommServerSocket = server;
                AppLog.w("RemoteBleServer RFCOMM listening (" + RFCOMM_NAME + " / " + SERVICE_UUID + ")");
                while (running.get()) {
                    BluetoothSocket client = server.accept();
                    Thread t = new Thread(() -> handleRfcommClient(client), "RemoteBT-RFCOMM-Client");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Throwable t) {
                AppLog.w("RemoteBleServer RFCOMM loop ended: " + t.getMessage());
            } finally {
                closeRfcommServer();
            }
        }, "RemoteBT-RFCOMM-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void handleRfcommClient(BluetoothSocket socket) {
        RfcommSession session = null;
        try {
            session = new RfcommSession(socket);
            rfcommClients.add(session);
            session.sendLine(RemoteTcpServer.buildStateJson(appContext).toString());

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            final RfcommSession finalSession = session;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                RemoteTcpServer tcp = RemoteTcpServer.get();
                if (tcp != null) {
                    tcp.handleCommand(line, finalSession::sendLine);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (session != null) {
                rfcommClients.remove(session);
                session.close();
            } else {
                try { socket.close(); } catch (Throwable ignored) {}
            }
            RemoteDaemonBridge.releaseAll();
        }
    }

    private synchronized void stopBluetoothTransports() {
        try {
            if (advertiser != null && advertiseCallback != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
        } catch (Throwable ignored) {}
        advertiser = null;
        advertiseCallback = null;

        try {
            if (gattServer != null) gattServer.close();
        } catch (Throwable ignored) {}
        gattServer = null;
        stateChar = null;
        subscribedDevices.clear();
        rxBuffers.clear();

        closeRfcommServer();
        for (RfcommSession s : rfcommClients) s.close();
        rfcommClients.clear();
    }

    private void closeRfcommServer() {
        BluetoothServerSocket srv = rfcommServerSocket;
        rfcommServerSocket = null;
        if (srv != null) {
            try { srv.close(); } catch (Throwable ignored) {}
        }
    }

    private void registerBtStateReceiver() {
        try {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            btStateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        bleNotifyExecutor.execute(RemoteBleServer.this::startBluetoothTransports);
                    } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        bleNotifyExecutor.execute(RemoteBleServer.this::stopBluetoothTransports);
                    }
                }
            };
            appContext.registerReceiver(btStateReceiver, filter);
        } catch (Throwable t) {
            AppLog.w("RemoteBleServer BT state receiver error: " + t.getMessage());
        }
    }
}
