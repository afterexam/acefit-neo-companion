package com.acefit.neo;

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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Serialized BLE GATT client for ACEFAST ACEFIT NEO (WQ7033).
 */
@SuppressLint("MissingPermission")
public final class NeoBleClient {
    private static final String TAG = "NeoBleClient";
    private static final long CONNECT_TIMEOUT_MS = 10000L;
    private static final long SCAN_TIMEOUT_MS = 3500L;
    private static final long WRITE_WATCHDOG_MS = 600L;
    private static final long INTER_CMD_DELAY_MS = 120L;

    public enum ConnStatus {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    public interface Listener {
        void onConnectionChanged(ConnStatus status, String deviceName, String mac, String message);
        void onStateUpdated(NeoProtocol.NeoState state, NeoProtocol.PacketType packetType);
        void onLog(String line);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final NeoProtocol.NeoState state = new NeoProtocol.NeoState();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothLeScanner activeScanner;
    private ScanCallback activeScanCallback;
    private ConnStatus status = ConnStatus.DISCONNECTED;
    private String currentName = "";
    private String currentMac = "";

    private final List<BluetoothGattDescriptor> cccdQueue = new ArrayList<>();
    private final Queue<byte[]> writeQueue = new ArrayDeque<>();
    private boolean servicesReady = false;
    private boolean writeInFlight = false;
    private boolean mtuRequested = false;

    private final Runnable connectTimeoutTask = () -> {
        if (status == ConnStatus.CONNECTING) {
            emitLog("GATT connect timeout after " + CONNECT_TIMEOUT_MS + "ms");
            closeGatt("连接超时，请确认耳机已取出并已开启蓝牙");
        }
    };

    private final Runnable scanTimeoutTask = () -> {
        if (status == ConnStatus.SCANNING) {
            stopScan();
            emitLog("BLE scan timed out without match");
            setDisconnected("未扫描到 ACEFAST ACEFIT NEO，请开盖或先在系统蓝牙配对");
        }
    };

    private final Runnable mtuFallbackTask = () -> {
        if (gatt != null && status == ConnStatus.CONNECTING && !servicesReady) {
            emitLog("MTU callback skipped; discovering services");
            try {
                gatt.discoverServices();
            } catch (Throwable t) {
                closeGatt("服务发现失败: " + t.getMessage());
            }
        }
    };

    private final Runnable writeWatchdogTask = () -> {
        if (writeInFlight) {
            writeInFlight = false;
            pumpWriteQueue();
        }
    };

    public NeoBleClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public ConnStatus getStatus() {
        return status;
    }

    public NeoProtocol.NeoState getState() {
        return state;
    }

    /** Connects to a matching bonded ACEFIT NEO first, otherwise scans over BLE. */
    public void connect(String preferredMac) {
        disconnect();

        BluetoothManager bm = appContext.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            setDisconnected("请先打开手机蓝牙");
            return;
        }

        try {
            BluetoothDevice bondedMatch = findBondedTarget(adapter, preferredMac);
            if (bondedMatch != null) {
                emitLog("Found bonded NEO: " + bondedMatch.getAddress());
                connectDevice(bondedMatch);
                return;
            }
        } catch (SecurityException se) {
            emitLog("Missing permission for getBondedDevices: " + se.getMessage());
            setDisconnected("缺少蓝牙连接权限，请授予权限");
            return;
        }

        startScan(adapter);
    }

    /** Disconnects any active BLE connection or pending scan. */
    public void disconnect() {
        stopScan();
        closeGatt("已断开连接");
    }

    /** Queries detail -> battery -> gesture -> sound sequentially. */
    public void refreshAll() {
        if (status != ConnStatus.CONNECTED) return;
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_DETAIL_STATE));
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_BATTERY_STATE));
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_GESTURE_STATE));
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_SOUND_STATE));
    }

    /** Queries current battery and connection topology. */
    public void refreshBattery() {
        if (status != ConnStatus.CONNECTED) return;
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_DETAIL_STATE));
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_BATTERY_STATE));
    }

    /** Switches EQ preset (0=Origin, 1=Vocal, 2=Bass) and reads back state. */
    public void setSoundMode(int mode) {
        if (status != ConnStatus.CONNECTED) return;
        enqueueCommand(NeoProtocol.buildSoundCommand(mode));
        mainHandler.postDelayed(() -> enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_SOUND_STATE)), 250);
    }

    /** Updates one of the 8 gesture slots and persists to earbuds. */
    public void setGestureSlot(int slotIndex, String newActionCode) {
        if (status != ConnStatus.CONNECTED || slotIndex < 0 || slotIndex >= 8) return;
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(i == slotIndex ? newActionCode : state.gestures[i]);
        }
        byte[] cmd = NeoProtocol.buildGestureSetCommand(sb.toString());
        emitLog("TX GESTURE_SET (" + sb + "): " + NeoProtocol.toHex(cmd));
        enqueueCommand(cmd);
        mainHandler.postDelayed(() -> enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_GESTURE_STATE)), 250);
    }

    /** Resets all 8 touch gestures to factory defaults and reads back state. */
    public void resetGestures() {
        if (status != ConnStatus.CONNECTED) return;
        enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_GESTURE_RESET));
        mainHandler.postDelayed(() -> enqueueCommand(NeoProtocol.fromHex(NeoProtocol.CMD_GESTURE_STATE)), 250);
    }

    private BluetoothDevice findBondedTarget(BluetoothAdapter adapter, String preferredMac) {
        BluetoothDevice fallback = null;
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String name = d.getName();
            if (preferredMac != null && !preferredMac.isEmpty() && preferredMac.equalsIgnoreCase(d.getAddress())) {
                return d;
            }
            if (NeoProtocol.TARGET_DEVICE_NAME.equalsIgnoreCase(name)) {
                return d;
            }
            if (name != null && name.toUpperCase().contains("ACEFIT NEO")) {
                fallback = d;
            }
        }
        return fallback;
    }

    private void startScan(BluetoothAdapter adapter) {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setDisconnected("无法启动 BLE 扫描");
            return;
        }
        status = ConnStatus.SCANNING;
        notifyConn("正在搜索 ACEFAST ACEFIT NEO...");
        emitLog("BLE scan started");

        activeScanner = scanner;
        activeScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                String name = d.getName();
                if (name == null && result.getScanRecord() != null) {
                    name = result.getScanRecord().getDeviceName();
                }
                if (name != null && (NeoProtocol.TARGET_DEVICE_NAME.equalsIgnoreCase(name) || name.toUpperCase().contains("ACEFIT NEO"))) {
                    emitLog("Scan hit: " + name + " (" + d.getAddress() + ")");
                    stopScan();
                    connectDevice(d);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                emitLog("Scan failed code=" + errorCode);
                stopScan();
                setDisconnected("BLE 扫描失败 (code=" + errorCode + ")");
            }
        };

        try {
            scanner.startScan(activeScanCallback);
            mainHandler.postDelayed(scanTimeoutTask, SCAN_TIMEOUT_MS);
        } catch (SecurityException se) {
            emitLog("Scan permission missing: " + se.getMessage());
            setDisconnected("缺少蓝牙扫描权限，请授予权限");
        }
    }

    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeoutTask);
        if (activeScanner != null && activeScanCallback != null) {
            try {
                activeScanner.stopScan(activeScanCallback);
            } catch (Throwable t) {
                Log.w(TAG, "stopScan ignored", t);
            }
        }
        activeScanner = null;
        activeScanCallback = null;
    }

    private void connectDevice(BluetoothDevice device) {
        status = ConnStatus.CONNECTING;
        currentMac = device.getAddress();
        try {
            currentName = device.getName() != null ? device.getName() : NeoProtocol.TARGET_DEVICE_NAME;
        } catch (SecurityException ignored) {
            currentName = NeoProtocol.TARGET_DEVICE_NAME;
        }
        notifyConn("正在连接 BLE GATT...");
        emitLog("connectGatt TRANSPORT_LE -> " + currentMac);
        servicesReady = false;
        mtuRequested = false;
        writeInFlight = false;
        cccdQueue.clear();
        writeQueue.clear();

        mainHandler.removeCallbacks(connectTimeoutTask);
        mainHandler.postDelayed(connectTimeoutTask, CONNECT_TIMEOUT_MS);

        try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException se) {
            mainHandler.removeCallbacks(connectTimeoutTask);
            emitLog("connectGatt permission error: " + se.getMessage());
            setDisconnected("缺少蓝牙连接权限");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            mainHandler.post(() -> {
                if (g != gatt) return;
                emitLog("GATT state=" + newState + " status=" + st);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!mtuRequested) {
                        mtuRequested = true;
                        boolean req = g.requestMtu(247);
                        emitLog("requestMtu(247)=" + req);
                        if (!req) {
                            g.discoverServices();
                        } else {
                            mainHandler.postDelayed(mtuFallbackTask, 600L);
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGatt(st == 0 ? "已断开连接" : "蓝牙连接中断 (status=" + st + ")");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            mainHandler.post(() -> {
                if (g != gatt) return;
                mainHandler.removeCallbacks(mtuFallbackTask);
                emitLog("MTU=" + mtu + " status=" + st);
                if (!servicesReady) {
                    g.discoverServices();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            mainHandler.post(() -> {
                if (g != gatt) return;
                if (st != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt("服务发现失败 (status=" + st + ")");
                    return;
                }
                BluetoothGattService mainSvc = g.getService(NeoProtocol.SVC_MAIN);
                if (mainSvc == null) {
                    closeGatt("未找到 WQ7033 服务 (00007033)");
                    return;
                }
                writeChar = mainSvc.getCharacteristic(NeoProtocol.CHAR_WRITE);
                if (writeChar == null) {
                    closeGatt("未找到写特征 (00001001)");
                    return;
                }
                cccdQueue.clear();
                enqueueNotify(g, mainSvc.getCharacteristic(NeoProtocol.CHAR_NOTIFY));
                BluetoothGattService secSvc = g.getService(NeoProtocol.SVC_SEC);
                if (secSvc != null) {
                    enqueueNotify(g, secSvc.getCharacteristic(NeoProtocol.CHAR_NOTIFY2));
                }
                if (cccdQueue.isEmpty()) {
                    onGattReady();
                } else {
                    writeNextCccd(g);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int st) {
            mainHandler.post(() -> {
                if (g != gatt) return;
                emitLog("CCCD write " + descriptor.getCharacteristic().getUuid() + " status=" + st);
                if (!cccdQueue.isEmpty()) {
                    writeNextCccd(g);
                } else {
                    onGattReady();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int st) {
            mainHandler.post(() -> {
                if (g != gatt) return;
                mainHandler.removeCallbacks(writeWatchdogTask);
                writeInFlight = false;
                if (st != BluetoothGatt.GATT_SUCCESS) {
                    emitLog("CHAR write status=" + st);
                }
                mainHandler.postDelayed(() -> pumpWriteQueue(), INTER_CMD_DELAY_MS);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            handleNotifyBytes(ch, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) {
                handleNotifyBytes(ch, ch.getValue());
            }
        }
    };

    private void handleNotifyBytes(BluetoothGattCharacteristic ch, byte[] raw) {
        if (raw == null || raw.length == 0) return;
        byte[] copy = raw.clone();
        mainHandler.post(() -> {
            emitLog("RX [" + ch.getUuid().toString().substring(4, 8) + "] " + NeoProtocol.toHex(copy));
            NeoProtocol.PacketType type = NeoProtocol.applyNotification(copy, state);
            if (type == NeoProtocol.PacketType.INVALID_CRC) {
                emitLog("WARN: RX CRC mismatch");
                return;
            }
            if (type != NeoProtocol.PacketType.UNKNOWN) {
                listener.onStateUpdated(state, type);
            }
        });
    }

    private void enqueueNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        if (ch == null) return;
        g.setCharacteristicNotification(ch, true);
        BluetoothGattDescriptor d = ch.getDescriptor(NeoProtocol.CCCD);
        if (d != null) cccdQueue.add(d);
    }

    @SuppressWarnings("deprecation")
    private void writeNextCccd(BluetoothGatt g) {
        if (cccdQueue.isEmpty()) return;
        BluetoothGattDescriptor d = cccdQueue.remove(0);
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }
    }

    private void onGattReady() {
        mainHandler.removeCallbacks(connectTimeoutTask);
        servicesReady = true;
        status = ConnStatus.CONNECTED;
        notifyConn("已连接");
        emitLog("GATT ready; refreshing state");
        mainHandler.postDelayed(this::refreshAll, 150L);
    }

    private void enqueueCommand(byte[] payload) {
        if (status != ConnStatus.CONNECTED || gatt == null || writeChar == null || !servicesReady) {
            return;
        }
        writeQueue.add(payload);
        pumpWriteQueue();
    }

    @SuppressWarnings("deprecation")
    private void pumpWriteQueue() {
        if (writeInFlight || gatt == null || writeChar == null || !servicesReady) return;
        byte[] next = writeQueue.poll();
        if (next == null) return;

        writeInFlight = true;
        int props = writeChar.getProperties();
        int wType = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;

        emitLog("TX " + NeoProtocol.toHex(next));
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = gatt.writeCharacteristic(writeChar, next, wType) == BluetoothGatt.GATT_SUCCESS;
        } else {
            writeChar.setWriteType(wType);
            writeChar.setValue(next);
            ok = gatt.writeCharacteristic(writeChar);
        }
        if (!ok) {
            emitLog("TX writeCharacteristic failed immediately");
            writeInFlight = false;
            mainHandler.postDelayed(this::pumpWriteQueue, INTER_CMD_DELAY_MS);
            return;
        }
        mainHandler.postDelayed(writeWatchdogTask, WRITE_WATCHDOG_MS);
    }

    private void closeGatt(String reason) {
        mainHandler.removeCallbacks(connectTimeoutTask);
        mainHandler.removeCallbacks(mtuFallbackTask);
        mainHandler.removeCallbacks(writeWatchdogTask);
        writeQueue.clear();
        cccdQueue.clear();
        servicesReady = false;
        writeInFlight = false;
        writeChar = null;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Throwable ignored) {}
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        setDisconnected(reason);
    }

    private void setDisconnected(String msg) {
        status = ConnStatus.DISCONNECTED;
        notifyConn(msg);
    }

    private void notifyConn(String msg) {
        listener.onConnectionChanged(status, currentName, currentMac, msg);
    }

    private void emitLog(String s) {
        Log.i(TAG, s);
        listener.onLog(s);
    }
}
