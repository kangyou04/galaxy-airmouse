using System.Text;
using System.Runtime.InteropServices;
using System.Diagnostics;
using GalaxyMouse;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

ApplicationConfiguration.Initialize();
Application.Run(new ConnectionWindow());

sealed class ConnectionWindow : Form
{
    static readonly Guid ServiceId = Guid.Parse("8d240000-0cd3-4497-a5bc-d79d3075a39a");
    static readonly Guid InputId = Guid.Parse("8d240001-0cd3-4497-a5bc-d79d3075a39a");
    readonly Label status = new() { Dock = DockStyle.Fill, AutoSize = false, Padding = new Padding(20), Text = "Bluetooth 준비 중..." };
    readonly Button approve = new() { Dock = DockStyle.Bottom, Height = 42, Text = "요청한 갤럭시 승인", Enabled = false };
    readonly Button stop = new() { Dock = DockStyle.Bottom, Height = 42, Text = "마우스 제어 중지", Enabled = false };
    readonly TrackBar sensitivity = new() { Dock = DockStyle.Bottom, Minimum = 2, Maximum = 30, Value = 18, TickFrequency = 4, Height = 40 };
    readonly Label sensitivityLabel = new() { Dock = DockStyle.Bottom, Height = 24, Text = "감도: 18" };
    readonly object gate = new();
    readonly MotionFilter motion = new();
    GattSession? pendingSession;
    GattSession? allowedSession;
    string? allowedId;
    double remainderX, remainderY;
    readonly System.Threading.Timer cursorTimer;
    int cursorTickBusy;
    volatile int sensitivityValue = 18;
    bool closing;
    double lastDragUpdate;
    double latestX, latestY, latestPhoneSeconds, receivedAt;
    int receivedCount;
    double lastReport;
    bool dragging;
    int sessionGeneration;
    GattServiceProvider? provider;

    public ConnectionWindow()
    {
        Text = "갤럭시 에어마우스 연결 시험";
        ClientSize = new Size(520, 250);
        Controls.Add(status);
        Controls.Add(approve);
        Controls.Add(stop);
        Controls.Add(sensitivity);
        Controls.Add(sensitivityLabel);
        sensitivity.ValueChanged += (_, _) => { sensitivityValue = sensitivity.Value; sensitivityLabel.Text = $"감도: {sensitivity.Value}"; };
        approve.Click += (_, _) => { lock (gate) { if (pendingSession?.SessionStatus != GattSessionStatus.Active) { status.Text = "연결이 끊어졌습니다. 휴대폰에서 다시 검색해 주세요."; return; } allowedSession = pendingSession; allowedId = pendingSession.DeviceId.Id; motion.Reset(); remainderX = remainderY = 0; receivedAt = latestPhoneSeconds = 0; receivedCount = 0; lastReport = 0; } approve.Enabled = false; stop.Enabled = true; status.Text = "승인됨. 휴대폰에서 마우스 시작을 누르세요."; };
        stop.Click += (_, _) => Revoke("제어가 중지되었습니다.");
        cursorTimer = new System.Threading.Timer(_ => { if (Interlocked.Exchange(ref cursorTickBusy, 1) != 0) return; try { UpdateCursor(); } catch (Exception ex) { lock (gate) { ReleaseDrag(); allowedId = null; allowedSession = null; motion.Reset(); } SetStatus($"마우스 제어 오류: {ex.Message}"); } finally { Volatile.Write(ref cursorTickBusy, 0); } }, null, 8, 8);
        Shown += async (_, _) => await StartAsync();
        FormClosing += (_, _) => { lock (gate) { closing = true; ReleaseDrag(); } cursorTimer.Dispose(); provider?.StopAdvertising(); };
    }

    async Task StartAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null || !adapter.IsPeripheralRoleSupported)
            {
                status.Text = "이 PC에서 Bluetooth LE 주변 장치 역할을 사용할 수 없습니다.";
                return;
            }
            var result = await GattServiceProvider.CreateAsync(ServiceId);
            if (result.Error != BluetoothError.Success) { status.Text = $"서비스 생성 실패: {result.Error}"; return; }
            provider = result.ServiceProvider;
            var characteristic = await provider.Service.CreateCharacteristicAsync(InputId, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.EncryptionAndAuthenticationRequired
            });
            if (characteristic.Error != BluetoothError.Success) { status.Text = $"특성 생성 실패: {characteristic.Error}"; return; }
            characteristic.Characteristic.WriteRequested += OnWrite;
            provider.AdvertisementStatusChanged += (_, args) => SetStatus($"Bluetooth 상태: {args.Status} / 오류: {args.Error}\n갤럭시 앱에서 기기를 검색하세요.");
            provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = true });
            status.Text = "Bluetooth 광고를 시작하는 중입니다.";
        }
        catch (Exception ex) { status.Text = $"Bluetooth 시작 실패: {ex.Message}"; }
    }

    async void OnWrite(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        using var deferral = args.GetDeferral();
        try
        {
            var request = await args.GetRequestAsync();
            if (request is null) return;
            if (request.Value.Length > 64) { if (request.Option == GattWriteOption.WriteWithResponse) request.RespondWithProtocolError(0x0d); return; }
            using var reader = DataReader.FromBuffer(request.Value);
            var bytes = new byte[request.Value.Length];
            reader.ReadBytes(bytes);
            if (Encoding.UTF8.GetString(bytes) == "PING")
            {
                if (request.Option != GattWriteOption.WriteWithResponse) return;
                var session = args.Session;
                int generation;
                lock (gate) { if (closing || (pendingSession != null && pendingSession.DeviceId.Id != session.DeviceId.Id) || (allowedId != null && allowedId != session.DeviceId.Id)) { request.RespondWithProtocolError(0x80); return; } pendingSession = session; generation = ++sessionGeneration; }
                session.SessionStatusChanged += (s, e) => { if (e.Status == GattSessionStatus.Closed) { lock (gate) { if (generation != sessionGeneration) return; if (allowedId == s.DeviceId.Id) { ReleaseDrag(); allowedId = null; allowedSession = null; motion.Reset(); } if (pendingSession?.DeviceId.Id == s.DeviceId.Id) pendingSession = null; } SetStatus("연결이 끊어졌습니다. 다시 검색해 주세요."); } };
                request.Respond();
                using var device = await BluetoothLEDevice.FromIdAsync(session.DeviceId.Id);
                string deviceName = string.IsNullOrWhiteSpace(device?.Name) ? "이름을 확인할 수 없는 기기" : device.Name;
                if (IsHandleCreated && !IsDisposed) BeginInvoke(() => { lock (gate) { if (pendingSession?.DeviceId.Id != session.DeviceId.Id || allowedId != null) return; } approve.Enabled = true; status.Text = $"기기 제어 요청: {deviceName}\n사용 중인 갤럭시가 맞는지 확인하고 승인해 주세요."; });
                return;
            }
            if ((bytes.Length == 1 || (bytes.Length > 1 && bytes[0] == (byte)'T')) && request.Option == GattWriteOption.WriteWithResponse)
            {
                lock (gate)
                {
                    if (closing || allowedId == null || allowedId != args.Session.DeviceId.Id || allowedSession?.SessionStatus != GattSessionStatus.Active || receivedAt == 0 || Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency - receivedAt > 1.0) { request.RespondWithProtocolError(0x80); return; }
                    switch (bytes[0])
                    {
                        case (byte)'L': if (!dragging) SendMouse(0x0002, 0x0004); break;
                        case (byte)'R': if (!dragging) SendMouse(0x0008, 0x0010); break;
                        case (byte)'D': if (!dragging) { SendMouse(0x0002); dragging = true; } break;
                        case (byte)'U': ReleaseDrag(); break;
                        case (byte)'F': Wheel(120); break;
                        case (byte)'B': Wheel(-120); break;
                        case (byte)'K': Key(0x08); break;
                        case (byte)'E': Key(0x0D); break;
                        case (byte)'T': try { SendText(new UTF8Encoding(false, true).GetString(bytes, 1, bytes.Length - 1)); } catch (DecoderFallbackException) { request.RespondWithProtocolError(0x80); return; } break;
                        default: request.RespondWithProtocolError(0x80); return;
                    }
                }
                request.Respond();
                return;
            }
            if (bytes.Length != 17 || bytes[0] != (byte)'M') { if (request.Option == GattWriteOption.WriteWithResponse) request.RespondWithProtocolError(0x80); return; }
            double seconds = BitConverter.ToInt64(bytes, 1) / 1000.0;
            float x = BitConverter.ToSingle(bytes, 9), y = BitConverter.ToSingle(bytes, 13);
            lock (gate)
            {
                if (closing || allowedId == null || allowedId != args.Session.DeviceId.Id || allowedSession?.SessionStatus != GattSessionStatus.Active || !float.IsFinite(x) || !float.IsFinite(y) || seconds <= latestPhoneSeconds) { if (request.Option == GattWriteOption.WriteWithResponse) request.RespondWithProtocolError(0x80); return; }
                latestPhoneSeconds = seconds; latestX = x; latestY = y; receivedAt = Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency; receivedCount++;
            }
            if (request.Option == GattWriteOption.WriteWithResponse) request.Respond();
        }
        catch (Exception ex) { SetStatus($"수신 실패: {ex.Message}"); }
    }

    void Revoke(string message)
    {
        lock (gate) { sessionGeneration++; ReleaseDrag(); allowedId = null; allowedSession = null; pendingSession = null; motion.Reset(); remainderX = remainderY = 0; receivedAt = latestPhoneSeconds = 0; receivedCount = 0; }
        approve.Enabled = false; stop.Enabled = false; status.Text = message;
    }

    void UpdateCursor()
    {
        var now = Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency;
        string? report = null;
        lock (gate)
        {
            if (closing || allowedId == null || allowedSession?.SessionStatus != GattSessionStatus.Active || receivedAt == 0 || now - receivedAt > 0.2) { ReleaseDrag(); motion.Reset(); remainderX = remainderY = 0; return; }
            if (dragging && now - lastDragUpdate < 1.0 / 60.0) return;
            if (dragging) lastDragUpdate = now;
            var delta = motion.Sample(now, latestX, latestY);
            int gain = sensitivityValue;
            remainderX += Math.Clamp(delta.X * gain, -80, 80); remainderY += Math.Clamp(delta.Y * gain, -80, 80);
            int dx = (int)remainderX, dy = (int)remainderY; remainderX -= dx; remainderY -= dy;
            if (dx != 0 || dy != 0) MoveMouse(dx, dy);
            if (now - lastReport >= 1) { report = $"마우스 작동 중 · 최근 수신 {receivedCount}회/초"; receivedCount = 0; lastReport = now; }
        }
        if (report != null) SetStatus(report);
    }

    [StructLayout(LayoutKind.Sequential)] struct MouseInput { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] struct KeyboardInput { public ushort virtualKey, scanCode; public uint dwFlags, time; public IntPtr extraInfo; }
    [StructLayout(LayoutKind.Explicit)] struct InputUnion { [FieldOffset(0)] public MouseInput mouse; [FieldOffset(0)] public KeyboardInput keyboard; }
    [StructLayout(LayoutKind.Sequential)] struct Input { public uint type; public InputUnion data; }
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint count, Input[] inputs, int size);
    static void MoveMouse(int x, int y) { var input = new Input { type = 0, data = new InputUnion { mouse = new MouseInput { dx = x, dy = y, dwFlags = 1 } } }; SendInput(1, new[] { input }, Marshal.SizeOf<Input>()); }
    static void SendMouse(params uint[] flags) { var inputs = flags.Select(flag => new Input { type = 0, data = new InputUnion { mouse = new MouseInput { dwFlags = flag } } }).ToArray(); SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>()); }
    static void Wheel(int amount) { var input = new Input { type = 0, data = new InputUnion { mouse = new MouseInput { mouseData = unchecked((uint)amount), dwFlags = 0x0800 } } }; SendInput(1, new[] { input }, Marshal.SizeOf<Input>()); }
    static void Key(ushort code) { var inputs = new[] { Keyboard(code, 0, 0), Keyboard(code, 0, 2) }; SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>()); }
    static void SendText(string text) { var inputs = text.SelectMany(ch => new[] { Keyboard(0, ch, 4), Keyboard(0, ch, 6) }).ToArray(); if (inputs.Length > 0) SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>()); }
    static Input Keyboard(ushort vk, ushort scan, uint flags) => new() { type = 1, data = new InputUnion { keyboard = new KeyboardInput { virtualKey = vk, scanCode = scan, dwFlags = flags } } };
    void ReleaseDrag() { if (dragging) { SendMouse(0x0004); dragging = false; } }

    void SetStatus(string text)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(() => { if (!IsDisposed) status.Text = text; }); } catch (InvalidOperationException) { }
    }
}
