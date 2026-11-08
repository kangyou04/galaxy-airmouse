using System.Diagnostics;
using System.Net;

ApplicationConfiguration.Initialize();
Application.Run(new PairForm(args.Length == 1 ? args[0] : ""));

sealed class PairForm : Form
{
    readonly string adb;
    readonly TextBox pairAddress = new() { Width = 410 };
    readonly TextBox pairCode = new() { Width = 150, MaxLength = 6, UseSystemPasswordChar = true };
    readonly TextBox connectAddress = new() { Width = 410 };
    readonly Button pair = new() { Text = "1. 페어링", AutoSize = true };
    readonly Button connect = new() { Text = "2. 연결", AutoSize = true };
    readonly Label status = new() { AutoSize = true, MaximumSize = new Size(470, 0), Text = "휴대폰 페어링 화면의 IP 주소:포트와 6자리 코드를 입력하세요." };

    public PairForm(string adbPath)
    {
        adb = adbPath;
        Text = "갤럭시 무선 디버깅 연결";
        ClientSize = new Size(510, 370);
        var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true };
        layout.Controls.Add(new Label { Text = "휴대폰 페어링 화면의 IP 주소:포트", AutoSize = true });
        layout.Controls.Add(pairAddress);
        layout.Controls.Add(new Label { Text = "휴대폰에 표시된 Wi-Fi 페어링 코드", AutoSize = true });
        layout.Controls.Add(pairCode);
        layout.Controls.Add(pair);
        layout.Controls.Add(new Label { Text = "페어링 후 무선 디버깅 기본 화면의 IP 주소:포트", AutoSize = true, Margin = new Padding(3, 12, 3, 0) });
        layout.Controls.Add(connectAddress);
        layout.Controls.Add(connect);
        layout.Controls.Add(status);
        Controls.Add(layout);
        pair.Click += async (_, _) => await PairAsync();
        connect.Click += async (_, _) => await ConnectAsync();
    }

    static bool ValidEndpoint(string value)
    {
        var parts = value.Trim().Split(':');
        return parts.Length == 2 && IPAddress.TryParse(parts[0], out var address) && address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && int.TryParse(parts[1], out int port) && port is >= 1 and <= 65535;
    }

    async Task<(int Code, string Output)> RunAdbAsync(string command, string endpoint, string? input = null)
    {
        var info = new ProcessStartInfo(adb) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
        info.ArgumentList.Add(command);
        info.ArgumentList.Add(endpoint);
        using var process = Process.Start(info) ?? throw new InvalidOperationException("ADB를 시작하지 못했습니다.");
        if (input != null) await process.StandardInput.WriteLineAsync(input);
        process.StandardInput.Close();
        var output = process.StandardOutput.ReadToEndAsync();
        var error = process.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException) { process.Kill(); throw new TimeoutException("연결 시간이 초과됐습니다."); }
        return (process.ExitCode, await output + await error);
    }

    async Task PairAsync()
    {
        string address = pairAddress.Text.Trim();
        string code = pairCode.Text.Trim();
        if (!ValidEndpoint(address) || code.Length != 6 || !code.All(char.IsAsciiDigit)) { status.Text = "페어링 화면의 주소와 숫자 6자리를 확인하세요."; return; }
        pair.Enabled = false;
        try
        {
            status.Text = "페어링 중입니다...";
            var result = await RunAdbAsync("pair", address, code);
            pairCode.Clear();
            status.Text = result.Code == 0 && result.Output.Contains("Successfully paired", StringComparison.OrdinalIgnoreCase)
                ? "페어링 성공. 휴대폰의 이전 화면으로 돌아가 그 화면의 IP 주소:포트를 아래에 입력하세요."
                : "페어링 실패. 휴대폰에서 새 페어링 코드를 열고 주소와 코드를 다시 확인하세요.";
        }
        catch (Exception ex) { pairCode.Clear(); status.Text = "페어링 오류: " + ex.Message; }
        finally { pair.Enabled = true; }
    }

    async Task ConnectAsync()
    {
        var address = connectAddress.Text.Trim();
        if (!ValidEndpoint(address)) { status.Text = "무선 디버깅 기본 화면의 IP 주소:포트를 확인하세요."; return; }
        connect.Enabled = false;
        try
        {
            status.Text = "연결 중입니다...";
            var result = await RunAdbAsync("connect", address);
            status.Text = result.Code == 0 && (result.Output.Contains("connected to", StringComparison.OrdinalIgnoreCase) || result.Output.Contains("already connected", StringComparison.OrdinalIgnoreCase))
                ? "연결 요청 성공. 채팅에서 완료했다고 알려주세요." : "연결 실패. 기본 화면의 주소와 포트를 확인하세요.";
        }
        catch (Exception ex) { status.Text = "연결 오류: " + ex.Message; }
        finally { connect.Enabled = true; }
    }
}
