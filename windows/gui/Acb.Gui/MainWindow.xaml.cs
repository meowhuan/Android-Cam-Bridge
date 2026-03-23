using Acb.Gui.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using Windows.Graphics;
using WinRT.Interop;

namespace Acb.Gui;

public sealed partial class MainWindow : Window
{
    private readonly ReceiverApiClient _client = new();
    private readonly DispatcherTimer _statsTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private readonly DispatcherTimer _usbNativeTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _usbAoaTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private bool _autoRefreshEnabled;
    private bool _usbNativeRefreshInProgress;
    private int _usbNativeTickCount;
    private bool _usbAoaRefreshInProgress;
    private Process? _receiverProcess;
    private bool _receiverStartedByGui;
    private bool _uiInitialized;

    public MainWindow()
    {
        InitializeComponent();
        TryResizeWindow(1320, 840);
        _statsTimer.Tick += OnStatsTimerTick;
        _usbNativeTimer.Tick += OnUsbNativeTimerTick;
        _usbNativeTimer.Start();
        _usbAoaTimer.Tick += OnUsbAoaTimerTick;
        _usbAoaTimer.Start();
        Closed += OnWindowClosed;
        _uiInitialized = true;
        ApplyLanguage();
        AppLogger.Info("main window initialized");
    }

    private async void OnSetupAdb(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var transport = GetComboValue(TransportBox, "usb-adb");
            if (transport == "usb-native")
            {
                AppendOutput(
                    IsZh()
                        ? "USB Native 模式不需要 ADB 映射，请直接确保手机可通过 USB 网络访问 Receiver。"
                        : "USB Native mode does not use ADB mapping. Ensure phone can reach Receiver over USB networking.",
                    "usb-native");
                return;
            }
            if (transport == "usb-aoa")
            {
                AppendOutput(
                    IsZh()
                        ? "USB AOA 模式不需要 ADB 映射，请直接使用 AOA 连接。"
                        : "USB AOA mode does not use ADB mapping. Use AOA Connect instead.",
                    "usb-aoa");
                return;
            }
            AppendOutput(await _client.SetupAdbAsync(), "adb setup");
            AppLogger.Info("adb setup requested");
        }
        catch (Exception ex)
        {
            AppLogger.Error("adb setup failed", ex);
            AppendError(ex);
        }
    }

    private async void OnRefreshDevices(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            AppendOutput(await _client.GetDevicesAsync(), "devices");
            AppLogger.Info("devices refreshed");
        }
        catch (Exception ex)
        {
            AppLogger.Error("refresh devices failed", ex);
            AppendError(ex);
        }
    }

    private async void OnStartSession(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var connectionMode = GetComboValue(ConnectionModeBox, "managed");
            var transport = GetComboValue(TransportBox, "usb-adb");
            var qualityPreset = GetComboValue(QualityPresetBox, "balanced");
            var fitMode = GetComboValue(FitModeBox, "letterbox");
            var (width, height) = ParseResolution(((ResolutionBox.SelectedItem as ComboBoxItem)?.Content?.ToString()) ?? "1280x720");
            var fps = ParseInt(((FpsBox.SelectedItem as ComboBoxItem)?.Content?.ToString()) ?? "30", 30);
            var bitrate = ParseInt(BitrateBox.Text, BitrateForPreset(qualityPreset, width, height));
            var audioEnabled = AudioEnabledBox.IsChecked ?? true;

            if (connectionMode == "attach")
            {
                AppendOutput($"attach mode: receiver={ReceiverAddressBox.Text}, fitMode={fitMode}, quality={qualityPreset}. no session start requested.", "attach");
                AppLogger.Info("attach mode selected, no start session");
                return;
            }

            if (transport == "usb-adb")
            {
                var adbResp = await _client.SetupAdbAsync();
                AppendOutput(adbResp, "adb setup");
            }
            else if (transport == "usb-native")
            {
                AppendOutput(
                    IsZh()
                        ? "USB Native 已选：跳过 ADB 配置，按原始 transport=usb-native 发起会话。"
                        : "USB Native selected: skipping ADB setup and starting session with transport=usb-native.",
                    "usb-native");
            }
            else if (transport == "usb-aoa")
            {
                AppendOutput(
                    IsZh()
                        ? "USB AOA 已选：跳过 ADB 配置，先建立 AOA 连接再发起会话。"
                        : "USB AOA selected: skipping ADB setup, establishing AOA connection before starting session.",
                    "usb-aoa");
                var aoaResp = await _client.UsbAoaConnectAsync();
                AppendOutput(aoaResp, "usb-aoa connect");
            }

            var options = new StartSessionOptions
            {
                Transport = transport,
                Width = width,
                Height = height,
                Fps = fps,
                Bitrate = bitrate,
                AudioEnabled = audioEnabled
            };

            var sessionResp = await _client.StartV2SessionAsync(options);
            AppendOutput(sessionResp, "session start");
            AppLogger.Info($"session start requested transport={transport} {width}x{height}@{fps} bitrate={bitrate}");

            var sessionId = ExtractSessionId(sessionResp);
            if (!string.IsNullOrWhiteSpace(sessionId))
            {
                SessionIdBox.Text = sessionId;
            }
            if (transport == "usb-native")
            {
                await RefreshUsbNativeStatusAndLinkAsync();
            }
            if (transport == "usb-aoa")
            {
                await RefreshUsbAoaStatusAsync();
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("start session failed", ex);
            AppendError(ex);
        }
    }

    private async void OnStopSession(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var id = SessionIdBox.Text?.Trim();
            if (string.IsNullOrWhiteSpace(id))
            {
                AppendOutput(IsZh() ? "请先输入 session id" : "please input session id", "validation");
                return;
            }

            AppendOutput(await _client.StopV2SessionAsync(id), "session stop");
            AppLogger.Info($"session stop requested sessionId={id}");
        }
        catch (Exception ex)
        {
            AppLogger.Error("stop session failed", ex);
            AppendError(ex);
        }
    }

    private async void OnFetchStats(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var id = SessionIdBox.Text?.Trim();
            if (string.IsNullOrWhiteSpace(id))
            {
                AppendOutput(IsZh() ? "请先输入 session id" : "please input session id", "validation");
                return;
            }

            AppendOutput(await _client.GetV2StatsAsync(id), "stats");
        }
        catch (Exception ex)
        {
            AppLogger.Error("fetch stats failed", ex);
            AppendError(ex);
        }
    }

    private void OnToggleAutoRefreshStats(object sender, RoutedEventArgs e)
    {
        _autoRefreshEnabled = !_autoRefreshEnabled;
        if (_autoRefreshEnabled)
        {
            _statsTimer.Start();
            AppendOutput(IsZh() ? "已开启自动刷新统计（5秒）" : "auto stats refresh enabled (5s)", "stats");
        }
        else
        {
            _statsTimer.Stop();
            AppendOutput(IsZh() ? "已关闭自动刷新统计" : "auto stats refresh disabled", "stats");
        }
        ApplyLanguage();
    }

    private async void OnStatsTimerTick(object? sender, object e)
    {
        try
        {
            if (!_autoRefreshEnabled) return;
            await EnsureReceiverReadyAsync();
            var id = SessionIdBox.Text?.Trim();
            if (string.IsNullOrWhiteSpace(id)) return;
            AppendOutput(await _client.GetV2StatsAsync(id), "stats auto");
        }
        catch (Exception ex)
        {
            AppLogger.Error("auto stats tick failed", ex);
            AppendError(ex);
        }
    }

    private async void OnRefreshUsbNativeDevices(object sender, RoutedEventArgs e)
    {
        await RefreshUsbNativeDevicesAsync(true);
    }

    private async void OnRefreshUsbNativeLink(object sender, RoutedEventArgs e)
    {
        await RefreshUsbNativeStatusAndLinkAsync(true);
    }

    private async void OnUsbNativeHandshake(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var sessionId = SessionIdBox.Text?.Trim() ?? string.Empty;
            if (string.IsNullOrWhiteSpace(sessionId))
            {
                AppendOutput(IsZh() ? "请先启动会话并获取 session id" : "start session first to get session id", "usb-native");
                return;
            }

            var devicePath = UsbNativeSelectedDeviceBox.Text?.Trim() ?? string.Empty;
            if (string.IsNullOrWhiteSpace(devicePath))
            {
                AppendOutput(IsZh() ? "请先刷新并选择 USB 设备" : "refresh and select a USB device first", "usb-native");
                return;
            }

            var resp = await _client.UsbNativeHandshakeAsync(sessionId, devicePath);
            AppendOutput(resp, "usb-native handshake");
            await RefreshUsbNativeStatusAndLinkAsync();
        }
        catch (Exception ex)
        {
            AppLogger.Error("usb-native handshake failed", ex);
            AppendError(ex);
        }
    }

    private void OnUsbNativeDeviceSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (UsbNativeDevicesList.SelectedItem is UsbNativeDeviceItem item)
        {
            UsbNativeSelectedDeviceBox.Text = item.Path;
        }
    }

    private async void OnUsbAoaConnect(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var result = await _client.UsbAoaConnectAsync();
            AppendOutput(result, "usb-aoa connect");
            AppLogger.Info("usb-aoa connect requested");
            await RefreshUsbAoaStatusAsync();
        }
        catch (Exception ex)
        {
            AppLogger.Error("usb-aoa connect failed", ex);
            AppendError(ex);
        }
    }

    private async void OnUsbAoaDisconnect(object sender, RoutedEventArgs e)
    {
        try
        {
            await EnsureReceiverReadyAsync();
            var result = await _client.UsbAoaDisconnectAsync();
            AppendOutput(result, "usb-aoa disconnect");
            AppLogger.Info("usb-aoa disconnect requested");
            await RefreshUsbAoaStatusAsync();
        }
        catch (Exception ex)
        {
            AppLogger.Error("usb-aoa disconnect failed", ex);
            AppendError(ex);
        }
    }

    private async void OnTransportChanged(object sender, SelectionChangedEventArgs e)
    {
        UpdateUsbNativePanelVisibility();
        UpdateUsbAoaPanelVisibility();
        var transport = GetComboValue(TransportBox, "usb-adb");
        if (transport == "usb-native")
        {
            await RefreshUsbNativeDevicesAsync(false);
            await RefreshUsbNativeStatusAndLinkAsync(false);
        }
        else if (transport == "usb-aoa")
        {
            await RefreshUsbAoaStatusAsync(false);
        }
    }

    private async void OnUsbNativeTimerTick(object? sender, object e)
    {
        if (_usbNativeRefreshInProgress) return;
        if (GetComboValue(TransportBox, "usb-adb") != "usb-native") return;
        _usbNativeTickCount++;
        await RefreshUsbNativeStatusAndLinkAsync(false);
        if (_usbNativeTickCount % 5 == 0)
        {
            await RefreshUsbNativeDevicesAsync(false);
        }
    }

    private async void OnUsbAoaTimerTick(object? sender, object e)
    {
        if (_usbAoaRefreshInProgress) return;
        if (GetComboValue(TransportBox, "usb-adb") != "usb-aoa") return;
        await RefreshUsbAoaStatusAsync(false);
    }

    private static string GetComboValue(ComboBox box, string fallback)
    {
        if (box.SelectedItem is ComboBoxItem item)
        {
            if (item.Tag is string tag && !string.IsNullOrWhiteSpace(tag)) return tag;
            return item.Content?.ToString() ?? fallback;
        }
        return fallback;
    }

    private async Task EnsureReceiverReadyAsync()
    {
        _client.SetBaseAddress(ReceiverAddressBox.Text);
        if (!IsLocalAddress(ReceiverAddressBox.Text)) return;

        if (!Process.GetProcessesByName("acb-receiver").Any())
        {
            var exe = ResolveReceiverPath();
            if (exe != null)
            {
                _receiverProcess = Process.Start(new ProcessStartInfo
                {
                    FileName = exe,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WorkingDirectory = Path.GetDirectoryName(exe) ?? Environment.CurrentDirectory
                });
                _receiverStartedByGui = _receiverProcess != null;
                await Task.Delay(400);
            }
        }
    }

    private void OnWindowClosed(object sender, WindowEventArgs args)
    {
        try
        {
            _statsTimer.Stop();
            _usbNativeTimer.Stop();
            _usbAoaTimer.Stop();
            if ((AutoStopReceiverBox.IsChecked ?? true) && _receiverStartedByGui && _receiverProcess is { HasExited: false })
            {
                _receiverProcess.Kill(true);
                _receiverProcess.Dispose();
                _receiverProcess = null;
                _receiverStartedByGui = false;
                AppLogger.Info("managed receiver process stopped on exit");
            }
        }
        catch
        {
            // Ignore shutdown errors.
        }

        AppLogger.Info("window closed");
    }

    private static bool IsLocalAddress(string address)
    {
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri)) return false;
        var host = uri.Host.ToLowerInvariant();
        return host == "127.0.0.1" || host == "localhost";
    }

    private static string? ResolveReceiverPath()
    {
        var bundled = ResolveBundledReceiverPath();
        if (bundled != null) return bundled;

        var baseDir = AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(baseDir, "acb-receiver.exe"),
            Path.Combine(baseDir, "receiver", "acb-receiver.exe"),
            Path.GetFullPath(Path.Combine(baseDir, "..", "..", "..", "..", "..", "..", "build", "windows", "receiver", "Release", "acb-receiver.exe"))
        };

        return candidates.FirstOrDefault(File.Exists);
    }

    private void TryResizeWindow(int width, int height)
    {
        try
        {
            var hwnd = WindowNative.GetWindowHandle(this);
            var windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hwnd);
            var appWindow = AppWindow.GetFromWindowId(windowId);
            appWindow?.Resize(new SizeInt32(width, height));
        }
        catch
        {
            // Ignore if window sizing API is unavailable in current runtime.
        }
    }

    private static string? ResolveBundledReceiverPath()
    {
        var data = BundledReceiverData.TryGetBytes();
        if (data == null || data.Length == 0) return null;

        try
        {
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AcbGui", "bundled");
            Directory.CreateDirectory(root);
            var exePath = Path.Combine(root, "acb-receiver.exe");
            var hashPath = Path.Combine(root, "acb-receiver.sha256");
            var currentHash = Convert.ToHexString(SHA256.HashData(data));
            var oldHash = File.Exists(hashPath) ? File.ReadAllText(hashPath).Trim() : string.Empty;

            if (!File.Exists(exePath) || !string.Equals(currentHash, oldHash, StringComparison.OrdinalIgnoreCase))
            {
                File.WriteAllBytes(exePath, data);
                File.WriteAllText(hashPath, currentHash);
            }

            return exePath;
        }
        catch
        {
            return null;
        }
    }

    private bool IsZh()
    {
        var current = GetComboValue(LanguageBox, "auto");
        if (current == "zh-CN") return true;
        if (current == "en-US") return false;
        return CultureInfo.CurrentUICulture.Name.StartsWith("zh", StringComparison.OrdinalIgnoreCase);
    }

    private void ApplyLanguage()
    {
        if (!_uiInitialized) return;
        if (AppTitleText == null || ConnectionTitleText == null || OutputTitleText == null) return;

        var zh = IsZh();
        Title = "ACB Receiver";
        AppTitleText.Text = "ACB Receiver";
        ConnectionTitleText.Text = zh ? "连接" : "Connection";
        OutputTitleText.Text = zh ? "输出" : "Output";
        LanguageLabelText.Text = zh ? "语言" : "Language";
        ReceiverUrlLabelText.Text = zh ? "Receiver 地址" : "Receiver URL";
        ConnectionModeLabelText.Text = zh ? "连接模式" : "Connection Mode";
        TransportLabelText.Text = zh ? "传输方式" : "Transport";
        QualityLabelText.Text = zh ? "画质预设" : "Quality";
        FitModeLabelText.Text = zh ? "画面适配" : "Fit Mode";
        ResolutionLabelText.Text = zh ? "分辨率" : "Resolution";
        FpsLabelText.Text = "FPS";
        BitrateLabelText.Text = zh ? "视频码率" : "Bitrate";
        UsbNativePanelTitleText.Text = zh ? "USB Native 面板" : "USB Native Panel";
        UsbAoaPanelTitleText.Text = zh ? "USB AOA 面板" : "USB AOA Panel";
        ReceiverAddressBox.PlaceholderText = zh ? "Receiver 地址" : "receiver address";
        BitrateBox.PlaceholderText = zh ? "视频码率 bps" : "video bitrate bps";
        SessionIdBox.PlaceholderText = "session id";
        UsbNativeSelectedDeviceBox.PlaceholderText = zh ? "已选设备路径" : "selected device path";
        UsbNativeStatusBox.PlaceholderText = zh ? "usb-native 状态" : "usb-native status";
        UsbNativeLinkBox.PlaceholderText = zh ? "usb-native 链路" : "usb-native link";
        UsbAoaStatusBox.PlaceholderText = zh ? "usb-aoa 状态" : "usb-aoa status";

        ConnectionModeManagedItem.Content = zh ? "托管（自动）" : "Managed";
        ConnectionModeAttachItem.Content = zh ? "附加（仅连接）" : "Attach";
        TransportUsbItem.Content = zh ? "USB（ADB）" : "USB (ADB)";
        TransportUsbNativeItem.Content = zh ? "USB（Native）" : "USB (Native)";
        TransportUsbAoaItem.Content = zh ? "USB（AOA 直连）" : "USB (AOA Direct)";
        TransportLanItem.Content = zh ? "无线局域网" : "LAN (Wi-Fi)";
        QualityBalancedItem.Content = zh ? "均衡" : "Balanced";
        QualityHighItem.Content = zh ? "高质量" : "High";
        QualityUltraItem.Content = zh ? "超高质量" : "Ultra";
        FitLetterboxItem.Content = zh ? "黑边保比例" : "Letterbox";
        FitCropItem.Content = zh ? "裁剪填充" : "Crop";
        FitStretchItem.Content = zh ? "拉伸填满" : "Stretch";

        SetupAdbButton.Content = zh ? "配置 ADB" : "Setup ADB";
        RefreshDevicesButton.Content = zh ? "刷新设备" : "Refresh Devices";
        AudioEnabledBox.Content = zh ? "启用音频" : "Audio Enabled";
        AutoStopReceiverBox.Content = zh ? "退出时停止托管 Receiver" : "Stop managed receiver on exit";
        StartSessionButton.Content = zh ? "启动 v2 会话" : "Start v2 Session";
        StopSessionButton.Content = zh ? "停止 v2 会话" : "Stop v2 Session";
        FetchStatsButton.Content = zh ? "获取 v2 统计" : "Fetch v2 Stats";
        RefreshUsbNativeDevicesButton.Content = zh ? "刷新 USB 设备" : "Refresh USB Devices";
        UsbNativeHandshakeButton.Content = zh ? "执行握手" : "Handshake";
        RefreshUsbNativeLinkButton.Content = zh ? "刷新链路" : "Refresh Link";
        UsbAoaConnectButton.Content = zh ? "AOA 连接" : "AOA Connect";
        UsbAoaDisconnectButton.Content = zh ? "AOA 断开" : "AOA Disconnect";
        AutoRefreshButton.Content = _autoRefreshEnabled
            ? (zh ? "关闭自动刷新（5秒）" : "Disable Auto Refresh (5s)")
            : (zh ? "开启自动刷新（5秒）" : "Auto Refresh Stats (5s)");
        UpdateUsbNativePanelVisibility();
        UpdateUsbAoaPanelVisibility();
    }

    private void OnLanguageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_uiInitialized) return;
        ApplyLanguage();
    }

    private static int ParseInt(string text, int fallback)
    {
        return int.TryParse(text, out var value) && value > 0 ? value : fallback;
    }

    private static (int width, int height) ParseResolution(string value)
    {
        var parts = value.Split('x');
        if (parts.Length != 2) return (1280, 720);
        var w = ParseInt(parts[0], 1280);
        var h = ParseInt(parts[1], 720);
        return (w, h);
    }

    private static int BitrateForPreset(string preset, int width, int height)
    {
        var pixels = width * height;
        return preset switch
        {
            "high" => pixels >= 1920 * 1080 ? 8_000_000 : 6_000_000,
            "ultra" => pixels >= 1920 * 1080 ? 12_000_000 : 8_000_000,
            _ => pixels >= 1920 * 1080 ? 6_000_000 : 4_000_000
        };
    }

    private static string? ExtractSessionId(string json)
    {
        const string key = "\"sessionId\":\"";
        var start = json.IndexOf(key, StringComparison.Ordinal);
        if (start < 0) return null;
        start += key.Length;
        var end = json.IndexOf('"', start);
        if (end <= start) return null;
        return json.Substring(start, end - start);
    }

    private void AppendOutput(string message, string? tag = null)
    {
        if (string.IsNullOrWhiteSpace(message)) return;
        var prefix = $"[{DateTime.Now:HH:mm:ss}]";
        if (!string.IsNullOrWhiteSpace(tag))
        {
            prefix += $" [{tag}]";
        }

        if (!string.IsNullOrWhiteSpace(OutputBox.Text))
        {
            OutputBox.Text += Environment.NewLine + Environment.NewLine;
        }
        OutputBox.Text += prefix + Environment.NewLine + message.Trim();
        OutputBox.SelectionStart = OutputBox.Text.Length;
    }

    private void AppendError(Exception ex)
    {
        AppendOutput($"{ex.GetType().Name}: {ex.Message}", "error");
    }

    private void UpdateUsbNativePanelVisibility()
    {
        UsbNativePanel.Visibility = GetComboValue(TransportBox, "usb-adb") == "usb-native"
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void UpdateUsbAoaPanelVisibility()
    {
        UsbAoaPanel.Visibility = GetComboValue(TransportBox, "usb-adb") == "usb-aoa"
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private async Task RefreshUsbNativeDevicesAsync(bool appendOutput)
    {
        try
        {
            _usbNativeRefreshInProgress = true;
            await EnsureReceiverReadyAsync();
            var json = await _client.GetUsbNativeDevicesAsync();
            PopulateUsbNativeDevices(json);
            if (appendOutput) AppendOutput(json, "usb-native devices");
        }
        catch (Exception ex)
        {
            AppLogger.Error("refresh usb-native devices failed", ex);
            if (appendOutput) AppendError(ex);
        }
        finally
        {
            _usbNativeRefreshInProgress = false;
        }
    }

    private async Task RefreshUsbNativeStatusAndLinkAsync(bool appendOutput = false)
    {
        try
        {
            _usbNativeRefreshInProgress = true;
            await EnsureReceiverReadyAsync();
            var statusJson = await _client.GetUsbNativeStatusAsync();
            var linkJson = await _client.GetUsbNativeLinkAsync();
            UsbNativeStatusBox.Text = statusJson;
            UsbNativeLinkBox.Text = linkJson;
            if (appendOutput)
            {
                AppendOutput(statusJson, "usb-native status");
                AppendOutput(linkJson, "usb-native link");
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("refresh usb-native status/link failed", ex);
            if (appendOutput) AppendError(ex);
        }
        finally
        {
            _usbNativeRefreshInProgress = false;
        }
    }

    private async Task RefreshUsbAoaStatusAsync(bool appendOutput = false)
    {
        try
        {
            _usbAoaRefreshInProgress = true;
            await EnsureReceiverReadyAsync();
            var statusJson = await _client.GetUsbAoaStatusAsync();
            UsbAoaStatusBox.Text = statusJson;
            if (appendOutput)
            {
                AppendOutput(statusJson, "usb-aoa status");
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("refresh usb-aoa status failed", ex);
            if (appendOutput) AppendError(ex);
        }
        finally
        {
            _usbAoaRefreshInProgress = false;
        }
    }

    private void PopulateUsbNativeDevices(string json)
    {
        var devices = new List<UsbNativeDeviceItem>();
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty("devices", out var arr) && arr.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in arr.EnumerateArray())
                {
                    var path = item.TryGetProperty("path", out var p) ? p.GetString() ?? string.Empty : string.Empty;
                    var desc = item.TryGetProperty("description", out var d) ? d.GetString() ?? string.Empty : string.Empty;
                    var androidCandidate = item.TryGetProperty("androidCandidate", out var a) && a.ValueKind == JsonValueKind.True;
                    devices.Add(new UsbNativeDeviceItem(path, desc, androidCandidate));
                }
            }
        }
        catch
        {
            // Ignore parse errors and keep old list.
            return;
        }

        UsbNativeDevicesList.Items.Clear();
        foreach (var d in devices)
        {
            UsbNativeDevicesList.Items.Add(d);
        }

        var preferred = devices.FirstOrDefault(d => d.AndroidCandidate) ?? devices.FirstOrDefault();
        if (preferred != null)
        {
            UsbNativeDevicesList.SelectedItem = preferred;
            UsbNativeSelectedDeviceBox.Text = preferred.Path;
        }
        else
        {
            UsbNativeSelectedDeviceBox.Text = string.Empty;
        }
    }

    private sealed class UsbNativeDeviceItem
    {
        public string Path { get; }
        public string Description { get; }
        public bool AndroidCandidate { get; }

        public UsbNativeDeviceItem(string path, string description, bool androidCandidate)
        {
            Path = path;
            Description = description;
            AndroidCandidate = androidCandidate;
        }

        public override string ToString()
        {
            var mark = AndroidCandidate ? "[android] " : string.Empty;
            var label = string.IsNullOrWhiteSpace(Description) ? Path : Description;
            return mark + label;
        }
    }
}
