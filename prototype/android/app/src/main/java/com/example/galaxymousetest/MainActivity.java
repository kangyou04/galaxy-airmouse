package com.example.galaxymousetest;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.text.InputType;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final UUID SERVICE = UUID.fromString("8d240000-0cd3-4497-a5bc-d79d3075a39a");
    private static final UUID INPUT = UUID.fromString("8d240001-0cd3-4497-a5bc-d79d3075a39a");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout devices;
    private BluetoothAdapter adapter;
    private volatile BluetoothGatt gatt;
    private boolean scanning;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor gravity;
    private float gravityX, gravityY, gravityZ;
    private boolean hasGravity;
    private TextView sensorStatus;
    private long lastSensorDisplay;
    private long lastMotionSend;
    private volatile boolean controlling;
    private volatile BluetoothGattCharacteristic inputCharacteristic;
    private final AtomicBoolean writePending = new AtomicBoolean(false);
    private Button motionButton;
    private TextView touchSurface;
    private float touchDownX, touchDownY, lastScrollY;
    private boolean touchDown, touchMoved, touchScrolling, touchTwo;
    private final Runnable holdToDrag = () -> { if (touchDown && !touchMoved && controlling) { dragging = true; queueControl('D'); touchSurface.setText("드래그 중 · 손을 떼면 놓기"); } };
    private final ConcurrentLinkedQueue<byte[]> controls = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean controlPending = new AtomicBoolean(false);
    private boolean dragging;
    private EditText textInput;
    private Button sendText;
    private final AtomicBoolean textPending = new AtomicBoolean(false);
    private volatile int textPacketsLeft;
    private volatile byte[] activeControl;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 32);
        layout.setGravity(Gravity.TOP);
        layout.setOnTouchListener((view, event) -> handleTouch(event));
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(32 + bars.left, 48 + bars.top, 32 + bars.right, 32 + bars.bottom);
            } else {
                view.setPadding(32 + insets.getSystemWindowInsetLeft(), 48 + insets.getSystemWindowInsetTop(), 32 + insets.getSystemWindowInsetRight(), 32 + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        status = new TextView(this);
        status.setTextSize(18);
        status.setText("Windows에서 연결 시험 프로그램을 실행한 뒤 검색하세요.");
        layout.addView(status);
        Button scan = new Button(this);
        scan.setText("Windows 기기 검색");
        scan.setOnClickListener(v -> checkPermissionsAndScan());
        layout.addView(scan);
        sensorStatus = new TextView(this);
        sensorStatus.setText("자이로 센서 확인 중...");
        layout.addView(sensorStatus);
        motionButton = new Button(this);
        motionButton.setText("마우스 시작");
        motionButton.setEnabled(false);
        motionButton.setOnClickListener(v -> { if (controlling) cancelTouch(); controlling = !controlling; motionButton.setText(controlling ? "마우스 일시정지" : "마우스 시작"); });
        layout.addView(motionButton);
        touchSurface = new TextView(this) {
            @Override public boolean performClick() { super.performClick(); queueControl('L'); return true; }
        };
        touchSurface.setGravity(Gravity.CENTER);
        touchSurface.setTextSize(17);
        touchSurface.setText("터치: 클릭 · 두 손가락: 우클릭\n위아래로 밀기: 스크롤\n길게 누르고 움직이기: 드래그");
        touchSurface.setBackgroundColor(0xffe7e7e7);
        touchSurface.setContentDescription("터치 영역: 클릭, 스크롤, 길게 눌러 드래그");
        touchSurface.setOnTouchListener((view, event) -> handleTouch(event));
        layout.addView(touchSurface, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        textInput = new EditText(this); textInput.setSingleLine(true); textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); textInput.setHint("PC에 입력할 글자"); layout.addView(textInput);
        LinearLayout keyboard = new LinearLayout(this);
        sendText = new Button(this); sendText.setText("글자 보내기"); sendText.setOnClickListener(v -> queueText()); keyboard.addView(sendText);
        Button backspace = new Button(this); backspace.setText("지우기"); backspace.setOnClickListener(v -> queueControl('K')); keyboard.addView(backspace);
        Button enter = new Button(this); enter.setText("엔터"); enter.setOnClickListener(v -> queueControl('E')); keyboard.addView(enter);
        layout.addView(keyboard);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        layout.addView(devices);
        setContentView(layout);
        layout.requestApplyInsets();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        sensorManager = getSystemService(SensorManager.class);
        gyroscope = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravity = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gyroscope == null || gravity == null) sensorStatus.setText("이 기기에서 필요한 자이로 또는 중력 센서를 찾지 못했습니다.");
    }

    @Override protected void onResume() {
        super.onResume();
        hasGravity = false;
        if (gravity != null) sensorManager.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
        if (gyroscope != null && !sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)) sensorStatus.setText("자이로 센서를 시작하지 못했습니다.");
    }
    @Override protected void onPause() { cancelTouch(); controlling = false; motionButton.setText("마우스 시작"); if (sensorManager != null) sensorManager.unregisterListener(this); super.onPause(); }
    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!controlling) { show("먼저 마우스 시작을 눌러 주세요."); return true; }
                touchDown = true; touchMoved = touchScrolling = touchTwo = false; touchDownX = event.getX(); touchDownY = lastScrollY = event.getY();
                handler.postDelayed(holdToDrag, ViewConfiguration.getLongPressTimeout()); return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (touchDown && !dragging) { touchTwo = true; handler.removeCallbacks(holdToDrag); }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touchDown || dragging) return true;
                float dx = event.getX() - touchDownX, dy = event.getY() - touchDownY;
                if (!touchMoved && Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) { touchMoved = true; touchScrolling = Math.abs(dy) > Math.abs(dx); handler.removeCallbacks(holdToDrag); }
                if (touchScrolling && !touchTwo) {
                    float step = 48 * getResources().getDisplayMetrics().density;
                    int count = 0;
                    while (Math.abs(event.getY() - lastScrollY) >= step && count++ < 4) { boolean down = event.getY() > lastScrollY; queueControl(down ? 'F' : 'B'); lastScrollY += down ? step : -step; }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!touchDown) return true;
                boolean click = !touchMoved && !dragging && !touchTwo;
                boolean rightClick = !touchMoved && !dragging && touchTwo;
                cancelTouch();
                if (click) touchSurface.performClick();
                else if (rightClick) queueControl('R');
                return true;
            case MotionEvent.ACTION_CANCEL: cancelTouch(); return true;
            default: return true;
        }
    }
    private void cancelTouch() {
        handler.removeCallbacks(holdToDrag); touchDown = false;
        if (dragging) { queueControl('U'); dragging = false; }
        if (touchSurface != null) touchSurface.setText("터치: 클릭 · 두 손가락: 우클릭\n위아래로 밀기: 스크롤\n길게 누르고 움직이기: 드래그");
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GRAVITY) { gravityX = event.values[0]; gravityY = event.values[1]; gravityZ = event.values[2]; hasGravity = true; return; }
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        if (event.timestamp - lastSensorDisplay >= 200_000_000L) { lastSensorDisplay = event.timestamp; sensorStatus.setText(String.format(Locale.ROOT, "자이로 확인 (rad/s): %.2f / %.2f / %.2f", event.values[0], event.values[1], event.values[2])); }
        if (controlling && hasGravity && controls.isEmpty() && event.timestamp - lastMotionSend >= 20_000_000L && writePending.compareAndSet(false, true)) {
            lastMotionSend = event.timestamp;
            double magnitude = Math.sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ);
            if (magnitude < 1) { writePending.set(false); return; }
            double yaw = (event.values[0] * gravityX + event.values[1] * gravityY + event.values[2] * gravityZ) / magnitude;
            byte[] packet = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).put((byte)'M').putLong(event.timestamp / 1_000_000L).putFloat((float)-Math.toDegrees(yaw)).putFloat((float)-Math.toDegrees(event.values[0])).array();
            if (!writePacket(packet, false)) { writePending.set(false); pumpControls(); }
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private boolean allowed() {
        if (Build.VERSION.SDK_INT >= 31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionsAndScan() {
        if (!allowed()) {
            requestPermissions(Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }
        startScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && allowed()) startScan();
        else status.setText("기기 검색에 필요한 권한이 허용되지 않았습니다.");
    }

    @SuppressWarnings("MissingPermission") private void startScan() {
        if (!allowed() || adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) { status.setText("Bluetooth가 켜져 있는지 확인하세요."); return; }
        stopScan();
        disconnectCurrent();
        devices.removeAllViews();
        status.setText("10초 동안 Windows 기기를 검색합니다.");
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        adapter.getBluetoothLeScanner().startScan(Collections.singletonList(filter), new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        scanning = true;
        handler.postDelayed(() -> { if (scanning) { stopScan(); status.setText("검색이 끝났습니다. 기기가 없으면 PC 프로그램과 Bluetooth를 확인하세요."); } }, 10000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            runOnUiThread(() -> { stopScan(); showDevice(result.getDevice()); });
        }
        @Override public void onScanFailed(int errorCode) { scanning = false; runOnUiThread(() -> status.setText("검색 실패: " + errorCode)); }
    };

    @SuppressWarnings("MissingPermission") private void showDevice(BluetoothDevice device) {
        if (!allowed()) return;
        String name = device.getName();
        status.setText("기기를 선택해 연결 시험을 시작하세요. 다른 PC와 구분하려면 이름을 확인하세요.");
        Button button = new Button(this);
        button.setText((name == null ? "이름 없는 기기" : name) + " / " + device.getAddress());
        button.setOnClickListener(v -> { disconnectCurrent(); status.setText("연결 중... 페어링 요청이 표시되면 확인하세요."); gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE); });
        devices.addView(button);
    }

    @SuppressWarnings("MissingPermission") private void disconnectCurrent() {
        cancelTouch();
        controlling = false;
        motionButton.setEnabled(false); motionButton.setText("마우스 시작");
        BluetoothGatt previous = gatt; gatt = null; inputCharacteristic = null;
        controls.clear(); writePending.set(false); controlPending.set(false); textPending.set(false); activeControl = null;
        textInput.setEnabled(true); sendText.setEnabled(true);
        if (previous != null && allowed()) { try { previous.close(); } catch (SecurityException ignored) { } }
    }

    @SuppressWarnings("MissingPermission") private void stopScan() {
        if (scanning && allowed() && adapter != null && adapter.getBluetoothLeScanner() != null) adapter.getBluetoothLeScanner().stopScan(scanCallback);
        scanning = false;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt client, int statusCode, int state) {
            if (!allowed()) return;
            if (client != gatt) { try { client.close(); } catch (SecurityException ignored) { } return; }
            try {
                if (statusCode == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) { client.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); show("연결됨. 서비스를 확인합니다."); client.discoverServices(); }
            else { controlling = false; inputCharacteristic = null; controls.clear(); writePending.set(false); controlPending.set(false); textPending.set(false); runOnUiThread(() -> { cancelTouch(); textInput.setEnabled(true); sendText.setEnabled(true); motionButton.setEnabled(false); motionButton.setText("마우스 시작"); }); show("연결 종료 또는 실패: " + statusCode); client.close(); if (gatt == client) gatt = null; }
            } catch (SecurityException e) { show("Bluetooth 권한이 취소되었습니다."); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt client, int result) {
            if (!allowed() || client != gatt) return;
            BluetoothGattService service = client.getService(SERVICE);
            BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(INPUT);
            if (result != BluetoothGatt.GATT_SUCCESS || c == null) { show("서비스를 찾지 못했습니다: " + result); return; }
            inputCharacteristic = c;
            byte[] data = "PING".getBytes(StandardCharsets.UTF_8);
            boolean started;
            try {
                if (Build.VERSION.SDK_INT >= 33) started = client.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
                else { c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); c.setValue(data); started = client.writeCharacteristic(c); }
            } catch (SecurityException e) { show("Bluetooth 권한이 취소되었습니다."); return; }
            show(started ? "시험 신호 전송 중..." : "시험 신호 전송을 시작하지 못했습니다.");
        }
        @Override public void onCharacteristicWrite(BluetoothGatt client, BluetoothGattCharacteristic characteristic, int result) {
            if (client != gatt) return;
            boolean wasControl = controlPending.getAndSet(false);
            byte[] completed = activeControl; activeControl = null;
            boolean wasMotion = writePending.getAndSet(false);
            if (result != BluetoothGatt.GATT_SUCCESS) { controlling = false; textPending.set(false); runOnUiThread(() -> { cancelTouch(); textInput.setEnabled(true); sendText.setEnabled(true); motionButton.setText("마우스 시작"); }); show("전송 실패: " + result + ". 일부 글자가 입력됐을 수 있습니다. PC를 확인하세요."); }
            else if (wasControl && completed != null && completed[0] == 'T' && --textPacketsLeft == 0) { textPending.set(false); runOnUiThread(() -> { textInput.setText(""); textInput.setEnabled(true); sendText.setEnabled(true); }); }
            else if (!wasMotion && !wasControl) { runOnUiThread(() -> motionButton.setEnabled(gyroscope != null && gravity != null)); show("연결됨. Windows에서 승인한 다음 마우스 시작을 누르세요."); }
            if (result == BluetoothGatt.GATT_SUCCESS) pumpControls(); else controls.clear();
        }
    };
    private void queueControl(char command) {
        if (!controlling || inputCharacteristic == null) { show("먼저 마우스 시작을 눌러 주세요."); return; }
        controls.add(new byte[]{(byte)command});
        pumpControls();
    }
    private void queueText() {
        if (!controlling || inputCharacteristic == null) { show("먼저 마우스 시작을 눌러 주세요."); return; }
        String text = textInput.getText().toString();
        if (text.isEmpty() || !textPending.compareAndSet(false, true)) return;
        ArrayList<byte[]> packets = new ArrayList<>();
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write('T');
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            byte[] encoded = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8);
            if (chunk.size() + encoded.length > 20) { packets.add(chunk.toByteArray()); chunk = new ByteArrayOutputStream(); chunk.write('T'); }
            chunk.write(encoded, 0, encoded.length); i += Character.charCount(point);
        }
        if (chunk.size() > 1) packets.add(chunk.toByteArray());
        textPacketsLeft = packets.size(); textInput.setEnabled(false); sendText.setEnabled(false);
        controls.addAll(packets); pumpControls();
    }
    private void pumpControls() {
        if (controls.isEmpty() || !writePending.compareAndSet(false, true)) return;
        byte[] command = controls.poll();
        if (command == null) { writePending.set(false); return; }
        controlPending.set(true);
        activeControl = command;
        if (!writePacket(command, true)) { activeControl = null; controlPending.set(false); writePending.set(false); controls.clear(); textPending.set(false); runOnUiThread(() -> { textInput.setEnabled(true); sendText.setEnabled(true); }); show("입력 전송을 시작하지 못했습니다."); }
    }
    @SuppressWarnings("MissingPermission") private boolean writePacket(byte[] data, boolean response) {
        BluetoothGatt client = gatt;
        BluetoothGattCharacteristic characteristic = inputCharacteristic;
        if (!allowed() || client == null || characteristic == null) return false;
        try {
            int type = response ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            if (Build.VERSION.SDK_INT >= 33) return client.writeCharacteristic(characteristic, data, type) == BluetoothStatusCodes.SUCCESS;
            characteristic.setWriteType(type); characteristic.setValue(data); return client.writeCharacteristic(characteristic);
        } catch (SecurityException e) { return false; }
    }
    private void show(String text) { runOnUiThread(() -> status.setText(text)); }
    @Override protected void onDestroy() { stopScan(); try { if (gatt != null && allowed()) gatt.close(); } catch (SecurityException ignored) { } super.onDestroy(); }
}
