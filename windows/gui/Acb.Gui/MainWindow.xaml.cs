using Acb.Gui.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using System;
using System.Collections.ObjectModel;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Reflection;
using Windows.Graphics;
using Windows.System;
using WinRT.Interop;

namespace Acb.Gui;

public sealed partial class MainWindow : Window
{
    private enum LeftSection
    {
        Session,
        Devices,
        VirtualCam,
        Logs,
        About
    }

    private readonly ReceiverApiClient _client = new();
    private readonly ReleaseUpdateService _releaseUpdateService = new();
    private readonly DispatcherTimer _statsTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private readonly DispatcherTimer _usbNativeTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _usbAoaTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _virtualCamTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private bool _autoRefreshEnabled;
    private bool _usbNativeRefreshInProgress;
    private int _usbNativeTickCount;
    private bool _usbAoaRefreshInProgress;
    private bool _virtualCamRefreshInProgress;
    private bool _sessionRunning;
    private bool _virtualCamRunning;
    private Process? _receiverProcess;
    private bool _receiverStartedByGui;
    private Process? _virtualCamBridgeProcess;
    private bool _virtualCamStartedByGui;
    private bool _virtualCamReceiverOverrideActive;
    private bool _uiInitialized;
    private bool _windowChromeInitialized;
    private LeftSection _activeSection = LeftSection.Session;
    private AppPreferences _preferences = new();
    private bool _updateCheckInProgress;
    private bool _updateCheckQueued;
    private bool _updateAvailable;
    private DateTimeOffset? _lastUpdateCheckAt;
    private string? _lastUpdateErrorMessage;
    private ReleaseUpdateResult? _lastUpdateResult;
    private const string VirtualCamPipeName = "acb-virtualcam-control";
    private readonly List<LogEntryItem> _allLogEntries = new();
    private readonly ObservableCollection<LogEntryItem> _visibleLogEntries = new();

    public MainWindow()
    {
        InitializeComponent();
        _preferences = AppPreferencesStore.Load(defaultParticipateInPreviewBuilds: IsPreviewBuildVersion(GetApplicationVersion()));
        PreviewBuildsBox.IsChecked = _preferences.ParticipateInPreviewBuilds;
        SuppressUpdateRemindersBox.IsChecked = _preferences.SuppressUpdateReminders;
        RepositoryUrlBox.Text = ReleaseUpdateService.RepositoryUrl;
        TryApplyWindowBackdrop();
        Activated += OnWindowActivated;
        TryResizeWindow(1320, 840);
        _statsTimer.Tick += OnStatsTimerTick;
        _usbNativeTimer.Tick += OnUsbNativeTimerTick;
        _usbNativeTimer.Start();
        _usbAoaTimer.Tick += OnUsbAoaTimerTick;
        _usbAoaTimer.Start();
        _virtualCamTimer.Tick += OnVirtualCamTimerTick;
        _virtualCamTimer.Start();
        Closed += OnWindowClosed;
        AppLogger.LineWritten += OnAppLogLineWritten;
        _uiInitialized = true;
        LogsListView.ItemsSource = _visibleLogEntries;
        VirtualCamReceiverBox.Text = NormalizeReceiverForVirtualCam(ReceiverAddressBox.Text);
        ShowSection(LeftSection.Session);
        ApplyLanguage();
        UpdateBuildIdentity();
        UpdateReleaseUpdateUi();
        if (!string.IsNullOrWhiteSpace(AppLogger.LogFilePath))
        {
            AppendOutput(AppLogger.LogFilePath!, "gui log");
        }
        AppLogger.Info("main window initialized");
        _ = CheckForUpdatesAsync(false);
    }

    private void OnWindowActivated(object sender, WindowActivatedEventArgs args)
    {
        if (_windowChromeInitialized)
        {
            return;
        }

        _windowChromeInitialized = true;
        TryConfigureWindowChrome();
    }

    private void TryApplyWindowBackdrop()
    {
        try
        {
            SystemBackdrop = new MicaBackdrop();
        }
        catch (Exception ex)
        {
            AppLogger.Error("window backdrop apply failed", ex);
        }
    }

    private void TryConfigureWindowChrome()
    {
        try
        {
            ExtendsContentIntoTitleBar = true;
            if (WindowTitleBar != null)
            {
                SetTitleBar(WindowTitleBar);
            }

            var hwnd = WindowNative.GetWindowHandle(this);
            var windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hwnd);
            var appWindow = AppWindow.GetFromWindowId(windowId);
            if (appWindow != null && AppWindowTitleBar.IsCustomizationSupported())
            {
                var titleBar = appWindow.TitleBar;
                titleBar.ButtonBackgroundColor = Microsoft.UI.Colors.Transparent;
                titleBar.ButtonInactiveBackgroundColor = Microsoft.UI.Colors.Transparent;
                titleBar.ButtonHoverBackgroundColor = Microsoft.UI.ColorHelper.FromArgb(36, 0, 103, 192);
                titleBar.ButtonPressedBackgroundColor = Microsoft.UI.ColorHelper.FromArgb(64, 0, 103, 192);
                titleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
                UpdateTitleBarInsets(titleBar);
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("window chrome configure failed", ex);
        }
    }

    private void UpdateTitleBarInsets(AppWindowTitleBar titleBar)
    {
        try
        {
            var scale = Content.XamlRoot?.RasterizationScale ?? 1.0;
            if (TitleBarLeftInsetColumn != null)
            {
                TitleBarLeftInsetColumn.Width = new GridLength(titleBar.LeftInset / scale);
            }
            if (TitleBarRightInsetColumn != null)
            {
                TitleBarRightInsetColumn.Width = new GridLength(titleBar.RightInset / scale);
            }
        }
        catch
        {
            // Ignore inset refresh failures on older runtimes.
        }
    }

    private void OnReceiverAddressChanged(object sender, TextChangedEventArgs e)
    {
        if (!_uiInitialized || _virtualCamReceiverOverrideActive)
        {
            return;
        }

        var normalized = NormalizeReceiverForVirtualCam(ReceiverAddressBox.Text);
        if (!string.Equals(VirtualCamReceiverBox.Text, normalized, StringComparison.OrdinalIgnoreCase))
        {
            VirtualCamReceiverBox.Text = normalized;
        }
        UpdateChromeSummary();
    }

    private void OnVirtualCamReceiverChanged(object sender, TextChangedEventArgs e)
    {
        if (!_uiInitialized)
        {
            return;
        }

        var receiver = NormalizeReceiverForVirtualCam(ReceiverAddressBox.Text);
        var virtualCamReceiver = NormalizeReceiverForVirtualCam(VirtualCamReceiverBox.Text);
        _virtualCamReceiverOverrideActive =
            !string.IsNullOrWhiteSpace(virtualCamReceiver) &&
            !string.Equals(receiver, virtualCamReceiver, StringComparison.OrdinalIgnoreCase);
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
            _sessionRunning = !string.IsNullOrWhiteSpace(sessionId);
            if (transport == "usb-native")
            {
                await RefreshUsbNativeStatusAndLinkAsync();
            }
            if (transport == "usb-aoa")
            {
                await RefreshUsbAoaStatusAsync();
            }
            if (VirtualCamAutoStartBox.IsChecked ?? true)
            {
                try
                {
                    await StartVirtualCamAsync();
                }
                catch (FileNotFoundException ex)
                {
                    VirtualCamStatusBox.Text = ex.Message;
                    AppendOutput(ex.Message, "virtualcam");
                    AppLogger.Info("virtual camera bridge missing for auto start");
                }
                catch (Exception ex)
                {
                    VirtualCamStatusBox.Text = ex.Message;
                    AppendOutput($"{ex.GetType().Name}: {ex.Message}", "virtualcam");
                    AppLogger.Error("virtual camera auto start failed", ex);
                }
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
            _sessionRunning = false;
            UpdateChromeSummary();
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

    private async void OnStartVirtualCam(object sender, RoutedEventArgs e)
    {
        try
        {
            await StartVirtualCamAsync();
        }
        catch (FileNotFoundException ex)
        {
            VirtualCamStatusBox.Text = ex.Message;
            AppendOutput(ex.Message, "virtualcam");
            AppLogger.Info("virtual camera bridge executable not found");
        }
        catch (Exception ex)
        {
            AppLogger.Error("start virtual camera failed", ex);
            AppendError(ex);
        }
    }

    private async void OnStopVirtualCam(object sender, RoutedEventArgs e)
    {
        try
        {
            await StopVirtualCamAsync(false);
        }
        catch (OperationCanceledException)
        {
            var message = IsZh() ? "虚拟摄像头桥接未响应，已视为停止" : "virtual camera bridge not responding; treated as stopped";
            VirtualCamStatusBox.Text = message;
            AppendOutput(message, "virtualcam");
        }
        catch (Exception ex)
        {
            AppLogger.Error("stop virtual camera failed", ex);
            AppendError(ex);
        }
    }

    private async void OnRefreshVirtualCamStatus(object sender, RoutedEventArgs e)
    {
        await RefreshVirtualCamStatusAsync(true);
    }

    private async void OnTransportChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_uiInitialized || TransportBox == null || UsbNativePanel == null || UsbAoaPanel == null)
        {
            return;
        }

        UpdateUsbNativePanelVisibility();
        UpdateUsbAoaPanelVisibility();
        var transport = GetComboValue(TransportBox, "usb-adb");
        if (transport == "usb-native")
        {
            ShowSection(LeftSection.Devices);
            await RefreshUsbNativeDevicesAsync(false);
            await RefreshUsbNativeStatusAndLinkAsync(false);
        }
        else if (transport == "usb-aoa")
        {
            ShowSection(LeftSection.Devices);
            await RefreshUsbAoaStatusAsync(false);
        }
        UpdateChromeSummary();
    }

    private void OnShowSessionSection(object sender, RoutedEventArgs e)
    {
        ShowSection(LeftSection.Session);
    }

    private void OnShowDevicesSection(object sender, RoutedEventArgs e)
    {
        ShowSection(LeftSection.Devices);
    }

    private void OnShowVirtualCamSection(object sender, RoutedEventArgs e)
    {
        ShowSection(LeftSection.VirtualCam);
    }

    private void OnShowLogsSection(object sender, RoutedEventArgs e)
    {
        ShowSection(LeftSection.Logs);
    }

    private async void OnShowAboutSection(object sender, RoutedEventArgs e)
    {
        ShowSection(LeftSection.About);
        if (_lastUpdateResult == null && !_updateCheckInProgress)
        {
            await CheckForUpdatesAsync(false);
        }
    }

    private async void OnCheckForUpdates(object sender, RoutedEventArgs e)
    {
        await CheckForUpdatesAsync(true);
    }

    private async void OnPreviewParticipationChanged(object sender, RoutedEventArgs e)
    {
        if (!_uiInitialized)
        {
            return;
        }

        _preferences = _preferences with { ParticipateInPreviewBuilds = PreviewBuildsBox.IsChecked ?? false };
        SaveUpdatePreferences();
        AppLogger.Info($"preview testing preference changed enabled={_preferences.ParticipateInPreviewBuilds}");
        UpdateReleaseUpdateUi();
        await CheckForUpdatesAsync(false);
    }

    private void OnSuppressUpdateRemindersChanged(object sender, RoutedEventArgs e)
    {
        if (!_uiInitialized)
        {
            return;
        }

        _preferences = _preferences with { SuppressUpdateReminders = SuppressUpdateRemindersBox.IsChecked ?? false };
        SaveUpdatePreferences();
        AppLogger.Info($"update reminders suppressed={_preferences.SuppressUpdateReminders}");
        UpdateReleaseUpdateUi();
    }

    private async void OnOpenRepository(object sender, RoutedEventArgs e)
    {
        await OpenExternalUrlAsync(ReleaseUpdateService.RepositoryUrl);
    }

    private async void OnOpenReleasePage(object sender, RoutedEventArgs e)
    {
        await OpenExternalUrlAsync(GetReleasePageUrl());
    }

    private async void OnVirtualCamTimerTick(object? sender, object e)
    {
        if (_virtualCamRefreshInProgress) return;
        await RefreshVirtualCamStatusAsync(false);
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

    private async Task StartVirtualCamAsync()
    {
        await EnsureReceiverReadyAsync();
        await EnsureVirtualCamBridgeReadyAsync();

        var receiver = NormalizeReceiverForVirtualCam(VirtualCamReceiverBox.Text);
        if (string.IsNullOrWhiteSpace(receiver))
        {
            receiver = NormalizeReceiverForVirtualCam(ReceiverAddressBox.Text);
        }
        if (string.IsNullOrWhiteSpace(receiver))
        {
            receiver = "127.0.0.1:39393";
        }

        var intervalMs = ParseInt(VirtualCamIntervalBox.Text, 16);
        intervalMs = Math.Clamp(intervalMs, 5, 1000);
        VirtualCamReceiverBox.Text = receiver;
        VirtualCamIntervalBox.Text = intervalMs.ToString(CultureInfo.InvariantCulture);

        await SendVirtualCamCommandAsync($"SET_RECEIVER {receiver}");
        await SendVirtualCamCommandAsync($"SET_INTERVAL {intervalMs}");
        var response = await SendVirtualCamCommandAsync("START");
        VirtualCamStatusBox.Text = response;
        AppendOutput($"{response}{Environment.NewLine}receiver={receiver} interval={intervalMs}ms", "virtualcam");
        AppLogger.Info($"virtual camera started receiver={receiver} intervalMs={intervalMs}");
        _virtualCamRunning = true;
        UpdateChromeSummary();
        await RefreshVirtualCamStatusAsync(false);
    }

    private async Task StopVirtualCamAsync(bool exitBridge)
    {
        if (!IsVirtualCamBridgeRunning())
        {
            var notRunning = IsZh() ? "虚拟摄像头桥接未运行" : "virtual camera bridge is not running";
            VirtualCamStatusBox.Text = notRunning;
            AppendOutput(notRunning, "virtualcam");
            _virtualCamRunning = false;
            UpdateChromeSummary();
            return;
        }

        string response;
        try
        {
            response = await SendVirtualCamCommandAsync(exitBridge ? "EXIT" : "STOP");
        }
        catch (Exception ex)
        {
            if (!exitBridge)
            {
                if (ex is OperationCanceledException || ex is TimeoutException || ex is IOException)
                {
                    response = IsZh() ? "虚拟摄像头桥接未响应，已视为停止" : "virtual camera bridge not responding; treated as stopped";
                }
                else
                {
                    throw;
                }
            }
            else
            {
                AppLogger.Error("virtual camera bridge stop on exit failed", ex);
                response = "virtual camera bridge already stopped";
            }
        }

        VirtualCamStatusBox.Text = response;
        AppendOutput(response, "virtualcam");
        AppLogger.Info(exitBridge ? "virtual camera bridge exited" : "virtual camera stopped");
        _virtualCamRunning = false;
        UpdateChromeSummary();

        if (exitBridge && _virtualCamBridgeProcess != null)
        {
            DisposeManagedProcess(ref _virtualCamBridgeProcess, "virtual camera bridge");
            _virtualCamStartedByGui = false;
        }
    }

    private async Task RefreshVirtualCamStatusAsync(bool appendOutput)
    {
        if (!appendOutput &&
            !_virtualCamStartedByGui &&
            !Process.GetProcessesByName("acb-virtualcam-bridge").Any())
        {
            return;
        }

        try
        {
            _virtualCamRefreshInProgress = true;
            var response = await SendVirtualCamCommandAsync("STATUS");
            VirtualCamStatusBox.Text = response;
            _virtualCamRunning = response.Contains("streaming=1", StringComparison.OrdinalIgnoreCase);
            UpdateChromeSummary();
            if (appendOutput)
            {
                AppendOutput(response, "virtualcam");
            }
        }
        catch (Exception ex)
        {
            VirtualCamStatusBox.Text = IsZh() ? "虚拟摄像头桥接未运行" : "virtual camera bridge is not running";
            _virtualCamRunning = false;
            UpdateChromeSummary();
            if (appendOutput)
            {
                AppendOutput($"{ex.GetType().Name}: {ex.Message}", "virtualcam");
            }
        }
        finally
        {
            _virtualCamRefreshInProgress = false;
        }
    }

    private async Task EnsureVirtualCamBridgeReadyAsync()
    {
        if (Process.GetProcessesByName("acb-virtualcam-bridge").Any())
        {
            return;
        }

        var exe = ResolveVirtualCamBridgePath();
        if (exe == null)
        {
            throw new FileNotFoundException(
                IsZh()
                    ? "未找到虚拟摄像头桥接程序。请确认安装包中包含 virtualcam-bridge 组件，或先在仓库中构建 build/windows/virtualcam-bridge/Release/acb-virtualcam-bridge.exe。"
                    : "Virtual camera bridge executable not found. Make sure the install includes the virtualcam-bridge component, or build build/windows/virtualcam-bridge/Release/acb-virtualcam-bridge.exe first.");
        }

        _virtualCamBridgeProcess = Process.Start(new ProcessStartInfo
        {
            FileName = exe,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            WorkingDirectory = Path.GetDirectoryName(exe) ?? Environment.CurrentDirectory
        });
        _virtualCamStartedByGui = _virtualCamBridgeProcess != null;
        await Task.Delay(500);
    }

    private static async Task<string> SendVirtualCamCommandAsync(string command, TimeSpan? timeout = null)
    {
        using var pipe = new NamedPipeClientStream(".", VirtualCamPipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        using var cts = new CancellationTokenSource(timeout ?? TimeSpan.FromSeconds(3));
        await pipe.ConnectAsync(cts.Token).ConfigureAwait(false);

        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
        using var reader = new StreamReader(pipe, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, leaveOpen: true);
        await writer.WriteLineAsync(command).ConfigureAwait(false);
        var response = await reader.ReadLineAsync(cts.Token).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(response))
        {
            throw new IOException("No response from virtual camera bridge.");
        }
        if (response.StartsWith("ERR", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException(response);
        }
        return response;
    }

    private static string NormalizeReceiverForVirtualCam(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return string.Empty;
        }

        if (Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri))
        {
            var port = uri.IsDefaultPort ? 39393 : uri.Port;
            return $"{uri.Host}:{port}";
        }

        var trimmed = value.Trim();
        return trimmed.Contains("://", StringComparison.Ordinal) ? string.Empty : trimmed;
    }

    private static string? ResolveVirtualCamBridgePath()
    {
        var baseDir = AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(baseDir, "acb-virtualcam-bridge.exe"),
            Path.Combine(baseDir, "virtualcam-bridge", "acb-virtualcam-bridge.exe"),
            Path.GetFullPath(Path.Combine(baseDir, "..", "virtualcam-bridge", "acb-virtualcam-bridge.exe")),
            Path.GetFullPath(Path.Combine(baseDir, "..", "..", "virtualcam-bridge", "acb-virtualcam-bridge.exe")),
            Path.GetFullPath(Path.Combine(baseDir, "..", "..", "..", "..", "..", "..", "dist", "acb-win-x64", "virtualcam-bridge", "acb-virtualcam-bridge.exe")),
            Path.GetFullPath(Path.Combine(baseDir, "..", "..", "..", "..", "..", "..", "build", "windows", "virtualcam-bridge", "Release", "acb-virtualcam-bridge.exe"))
        };

        return candidates.FirstOrDefault(File.Exists);
    }

    private bool IsVirtualCamBridgeRunning()
    {
        return (_virtualCamBridgeProcess is { HasExited: false }) || Process.GetProcessesByName("acb-virtualcam-bridge").Any();
    }

    private void ShowSection(LeftSection section)
    {
        _activeSection = section;

        if (SessionSection != null)
        {
            SessionSection.Visibility = section == LeftSection.Session ? Visibility.Visible : Visibility.Collapsed;
        }
        if (DevicesSection != null)
        {
            DevicesSection.Visibility = section == LeftSection.Devices ? Visibility.Visible : Visibility.Collapsed;
        }
        if (VirtualCamSection != null)
        {
            VirtualCamSection.Visibility = section == LeftSection.VirtualCam ? Visibility.Visible : Visibility.Collapsed;
        }
        if (LogsSection != null)
        {
            LogsSection.Visibility = section == LeftSection.Logs ? Visibility.Visible : Visibility.Collapsed;
        }
        if (AboutSection != null)
        {
            AboutSection.Visibility = section == LeftSection.About ? Visibility.Visible : Visibility.Collapsed;
        }

        UpdateSectionButtonStates();
        UpdateChromeSummary();
    }

    private void UpdateSectionButtonStates()
    {
        UpdateSectionButtonVisual(SessionSectionButton, _activeSection == LeftSection.Session);
        UpdateSectionButtonVisual(DevicesSectionButton, _activeSection == LeftSection.Devices);
        UpdateSectionButtonVisual(VirtualCamSectionButton, _activeSection == LeftSection.VirtualCam);
        UpdateSectionButtonVisual(LogsSectionButton, _activeSection == LeftSection.Logs);
        UpdateSectionButtonVisual(AboutSectionButton, _activeSection == LeftSection.About);
    }

    private void UpdateChromeSummary()
    {
        if (!_uiInitialized)
        {
            return;
        }

        var zh = IsZh();
        if (CurrentSectionTitleText != null)
        {
            CurrentSectionTitleText.Text = _activeSection switch
            {
                LeftSection.Devices => zh ? "设备与链路" : "Devices & Link",
                LeftSection.VirtualCam => zh ? "虚拟摄像头" : "Virtual Camera",
                LeftSection.Logs => zh ? "日志与诊断" : "Logs & Diagnostics",
                LeftSection.About => zh ? "关于与更新" : "About & Updates",
                _ => zh ? "会话控制" : "Session Control"
            };
        }

        if (CurrentSectionSubtitleText != null)
        {
            CurrentSectionSubtitleText.Text = _activeSection switch
            {
                LeftSection.Devices => zh
                    ? "管理 ADB、USB Native、USB AOA 连接和设备状态。"
                    : "Manage ADB, USB Native, USB AOA connections, and device state.",
                LeftSection.VirtualCam => zh
                    ? "静默启动桥接程序，并把接收端画面投递到系统虚拟摄像头。"
                    : "Silently launch the bridge and publish receiver frames into the system virtual camera.",
                LeftSection.Logs => zh
                    ? "查看运行时日志、Receiver 返回和自动化输出。"
                    : "Review runtime logs, receiver responses, and automation output.",
                LeftSection.About => zh
                    ? "查看仓库地址、当前构建渠道，以及 GitHub Releases 更新状态。"
                    : "Review repository links, build channel details, and GitHub release update status.",
                _ => zh
                    ? "调整画质与传输参数，启动会话并跟踪实时状态。"
                    : "Adjust quality and transport, start sessions, and track live state."
            };
        }

        if (SummaryReceiverValueText != null)
        {
            var receiver = NormalizeReceiverForVirtualCam(ReceiverAddressBox?.Text);
            SummaryReceiverValueText.Text = string.IsNullOrWhiteSpace(receiver)
                ? (zh ? "未设置" : "Not set")
                : receiver;
        }

        if (SummaryTransportValueText != null)
        {
            SummaryTransportValueText.Text = GetTransportSummaryLabel(zh);
        }

        if (SummarySessionValueText != null)
        {
            SummarySessionValueText.Text = _sessionRunning
                ? (zh ? "已连接" : "Connected")
                : (zh ? "空闲" : "Idle");
        }

        if (SummaryVirtualCamValueText != null)
        {
            SummaryVirtualCamValueText.Text = _virtualCamRunning
                ? (zh ? "运行中" : "Running")
                : (zh ? "未启动" : "Stopped");
        }
    }

    private string GetTransportSummaryLabel(bool zh)
    {
        return GetComboValue(TransportBox, "usb-adb") switch
        {
            "usb-native" => zh ? "USB Native" : "USB Native",
            "usb-aoa" => zh ? "USB AOA" : "USB AOA",
            "lan" => zh ? "无线局域网" : "LAN",
            _ => zh ? "USB ADB" : "USB ADB"
        };
    }

    private void UpdateBuildIdentity()
    {
        if (BuildVersionText == null || BuildChannelText == null)
        {
            return;
        }

        var zh = IsZh();
        var version = GetApplicationVersion();
        var channel = GetBuildChannelLabel(zh);
        BuildVersionText.Text = $"ACB {version}";
        BuildChannelText.Text = zh
            ? $"渠道 · {channel}"
            : $"Channel · {channel}";
        if (TitleBarVersionChipText != null)
        {
            TitleBarVersionChipText.Text = $"v{version}";
        }
        if (TitleBarChannelChipText != null)
        {
            TitleBarChannelChipText.Text = channel;
        }
    }

    private static string GetApplicationVersion()
    {
        var assembly = Assembly.GetExecutingAssembly();
        var informational = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(informational))
        {
            var plusIndex = informational.IndexOf('+');
            return plusIndex > 0 ? informational.Substring(0, plusIndex) : informational;
        }

        return assembly.GetName().Version?.ToString(3) ?? "1.0.0";
    }

    private static string GetBuildChannelLabel(bool zh)
    {
        var version = GetApplicationVersion().ToLowerInvariant();
        if (version.Contains("-local", StringComparison.Ordinal) || version.Contains("-dev", StringComparison.Ordinal))
        {
            return zh ? "开发版" : "Dev";
        }
        if (version.Contains("-preview", StringComparison.Ordinal) ||
            version.Contains("-alpha", StringComparison.Ordinal) ||
            version.Contains("-beta", StringComparison.Ordinal) ||
            version.Contains("-rc", StringComparison.Ordinal))
        {
            return zh ? "预览版" : "Preview";
        }
        return zh ? "正式版" : "Release";
    }

    private static void UpdateSectionButtonVisual(Button? button, bool active)
    {
        if (button == null)
        {
            return;
        }

        button.Opacity = active ? 1.0 : 0.72;
        button.FontWeight = active ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal;
        button.Background = Application.Current.Resources[active ? "AppNavActiveBrush" : "AppNavInactiveBrush"] as Microsoft.UI.Xaml.Media.Brush;
        button.BorderThickness = new Thickness(active ? 1 : 0);
    }

    private void ShutdownManagedProcessesOnExit()
    {
        if (_virtualCamStartedByGui)
        {
            try
            {
                SendVirtualCamCommandAsync("EXIT", TimeSpan.FromMilliseconds(1200)).GetAwaiter().GetResult();
                AppLogger.Info("virtual camera bridge exit command sent during shutdown");
            }
            catch (Exception ex)
            {
                AppLogger.Error("virtual camera bridge exit command failed during shutdown", ex);
            }

            DisposeManagedProcess(ref _virtualCamBridgeProcess, "virtual camera bridge");
            _virtualCamStartedByGui = false;
            _virtualCamRunning = false;
        }

        if ((AutoStopReceiverBox?.IsChecked ?? true) && _receiverStartedByGui)
        {
            DisposeManagedProcess(ref _receiverProcess, "managed receiver");
            _receiverStartedByGui = false;
            AppLogger.Info("managed receiver process stopped on exit");
        }
    }

    private static void DisposeManagedProcess(ref Process? process, string label)
    {
        var target = process;
        process = null;
        if (target == null)
        {
            return;
        }

        try
        {
            if (!target.HasExited && !target.WaitForExit(1200))
            {
                target.Kill(true);
                target.WaitForExit(1200);
                AppLogger.Info($"{label} terminated after graceful shutdown timeout");
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error($"{label} shutdown failed", ex);
        }
        finally
        {
            try
            {
                target.Dispose();
            }
            catch
            {
                // Ignore process disposal race.
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
            _virtualCamTimer.Stop();
            AppLogger.LineWritten -= OnAppLogLineWritten;
            ShutdownManagedProcessesOnExit();
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
        if (OutputTitleText == null) return;

        var zh = IsZh();
        Title = "Android Cam Bridge";
        if (TitleBarAppNameText != null)
        {
            TitleBarAppNameText.Text = zh ? "Android Cam Bridge" : "Android Cam Bridge";
        }
        if (TitleBarSubtitleText != null)
        {
            TitleBarSubtitleText.Text = zh ? "接收端" : "Receiver";
        }
        OutputTitleText.Text = zh ? "日志视图" : "Logs";
        OutputSubtitleText.Text = zh
            ? "按级别、来源或关键字筛选运行日志与返回结果。"
            : "Filter runtime logs and responses by level, source, or keyword.";
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
        VirtualCamPanelTitleText.Text = zh ? "虚拟摄像头" : "Virtual Camera";
        ReceiverAddressBox.PlaceholderText = zh ? "Receiver 地址" : "receiver address";
        BitrateBox.PlaceholderText = zh ? "视频码率 bps" : "video bitrate bps";
        SessionIdBox.PlaceholderText = "session id";
        UsbNativeSelectedDeviceBox.PlaceholderText = zh ? "已选设备路径" : "selected device path";
        UsbNativeStatusBox.PlaceholderText = zh ? "usb-native 状态" : "usb-native status";
        UsbNativeLinkBox.PlaceholderText = zh ? "usb-native 链路" : "usb-native link";
        UsbAoaStatusBox.PlaceholderText = zh ? "usb-aoa 状态" : "usb-aoa status";
        VirtualCamReceiverBox.PlaceholderText = zh ? "虚拟摄像头拉流地址 host:port" : "virtual camera receiver host:port";
        VirtualCamIntervalBox.PlaceholderText = zh ? "轮询间隔 ms" : "interval ms";
        VirtualCamStatusBox.PlaceholderText = zh ? "虚拟摄像头状态" : "virtual camera status";
        SessionSectionButtonText.Text = zh ? "会话" : "Session";
        DevicesSectionButtonText.Text = zh ? "设备" : "Devices";
        VirtualCamSectionButtonText.Text = zh ? "虚拟摄像头" : "Virtual Camera";
        LogsSectionButtonText.Text = zh ? "日志" : "Logs";
        SummaryReceiverLabelText.Text = zh ? "接收端" : "Receiver";
        SummaryTransportLabelText.Text = zh ? "传输" : "Transport";
        SummarySessionLabelText.Text = zh ? "会话" : "Session";
        SummaryVirtualCamLabelText.Text = zh ? "虚拟摄像头" : "Virtual Cam";

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
        VirtualCamAutoStartBox.Content = zh ? "会话启动时同时开启虚拟摄像头" : "Start virtual camera with session";
        VirtualCamStartButton.Content = zh ? "启动虚拟摄像头" : "Start Virtual Camera";
        VirtualCamStopButton.Content = zh ? "停止虚拟摄像头" : "Stop Virtual Camera";
        VirtualCamStatusButton.Content = zh ? "刷新状态" : "Refresh Status";
        ClearLogsButton.Content = zh ? "清空" : "Clear";
        LogAutoScrollBox.Content = zh ? "自动跟随" : "Auto-follow";
        (LogLevelFilterBox.Items[0] as ComboBoxItem)!.Content = zh ? "全部级别" : "All levels";
        (LogLevelFilterBox.Items[1] as ComboBoxItem)!.Content = zh ? "信息" : "Info";
        (LogLevelFilterBox.Items[2] as ComboBoxItem)!.Content = zh ? "错误" : "Error";
        (LogLevelFilterBox.Items[3] as ComboBoxItem)!.Content = zh ? "事件" : "Event";
        (LogSourceFilterBox.Items[0] as ComboBoxItem)!.Content = zh ? "全部来源" : "All sources";
        (LogSourceFilterBox.Items[1] as ComboBoxItem)!.Content = zh ? "应用" : "App";
        (LogSourceFilterBox.Items[2] as ComboBoxItem)!.Content = zh ? "会话" : "Session";
        (LogSourceFilterBox.Items[3] as ComboBoxItem)!.Content = zh ? "设备" : "Devices";
        (LogSourceFilterBox.Items[4] as ComboBoxItem)!.Content = zh ? "虚拟摄像头" : "Virtual Camera";
        LogSearchBox.PlaceholderText = zh ? "筛选日志" : "Filter logs";
        AboutPanelTitleText.Text = zh ? "关于 Android Cam Bridge" : "About Android Cam Bridge";
        AboutPanelSubtitleText.Text = zh
            ? "查看仓库地址、当前构建信息和发布渠道。"
            : "Review repository, build identity, and release channel details.";
        AboutCurrentVersionLabelText.Text = zh ? "当前版本" : "Current Version";
        AboutBuildChannelLabelText.Text = zh ? "发布渠道" : "Channel";
        RepositoryLabelText.Text = zh ? "仓库地址" : "Repository";
        OpenRepositoryButton.Content = zh ? "打开仓库" : "Open Repository";
        OpenReleasesPageButton.Content = zh ? "打开发布页" : "Open Releases";
        ReleaseUpdatesTitleText.Text = zh ? "发行版更新" : "Release Updates";
        ReleaseUpdatesSubtitleText.Text = zh
            ? "从 GitHub Releases 检查新版本。提醒会保持轻量，不会打断当前操作。"
            : "Check GitHub Releases for new builds with lightweight reminders that never interrupt current work.";
        PreviewBuildsBox.Content = zh ? "参与预览版测试" : "Participate in preview testing";
        SuppressUpdateRemindersBox.Content = zh ? "不再提示更新（不建议）" : "Do not remind again (Not recommended)";
        SuppressUpdateRemindersHintText.Text = zh
            ? "自动检查仍可继续运行，但轻量提醒和导航角标会保持隐藏；你仍然可以手动检查更新。"
            : "Automatic checks can still run, but lightweight reminders and navigation badges stay hidden until you check manually.";
        PreviewRiskTitleText.Text = zh ? "风险提示" : "Risk Notice";
        PreviewRiskText.Text = zh
            ? "预览版可能包含未完成功能、协议变化或兼容性回归。只有在你愿意协助测试时才建议开启。"
            : "Preview builds may include unfinished changes, protocol shifts, or compatibility regressions. Enable them only if you are comfortable helping test.";
        UpdateCurrentVersionLabelText.Text = zh ? "当前版本" : "Current";
        UpdateLatestVersionLabelText.Text = zh ? "最新发布" : "Latest Release";
        UpdateLastCheckedLabelText.Text = zh ? "上次检查" : "Last Checked";
        UpdateInfoBarActionButton.Content = zh ? "打开发布页" : "Open Release";
        AutoRefreshButton.Content = _autoRefreshEnabled
            ? (zh ? "关闭自动刷新（5秒）" : "Disable Auto Refresh (5s)")
            : (zh ? "开启自动刷新（5秒）" : "Auto Refresh Stats (5s)");
        UpdateAboutSectionButtonLabel();
        UpdateSectionButtonStates();
        UpdateUsbNativePanelVisibility();
        UpdateUsbAoaPanelVisibility();
        UpdateChromeSummary();
        UpdateBuildIdentity();
        UpdateReleaseUpdateUi();
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

    private async Task CheckForUpdatesAsync(bool userInitiated)
    {
        if (_updateCheckInProgress)
        {
            _updateCheckQueued = true;
            return;
        }

        _updateCheckInProgress = true;
        _lastUpdateErrorMessage = null;
        var checkedAt = DateTimeOffset.Now;
        UpdateReleaseUpdateUi();

        try
        {
            var result = await _releaseUpdateService.CheckForUpdatesAsync(
                GetApplicationVersion(),
                _preferences.ParticipateInPreviewBuilds);

            _lastUpdateResult = result;
            _updateAvailable = result.UpdateAvailable;
            AppLogger.Info($"release update check completed includePreview={result.IncludePreview} available={result.UpdateAvailable} latest={result.LatestRelease.TagName}");
        }
        catch (Exception ex)
        {
            _lastUpdateErrorMessage = ex.Message;
            AppLogger.Error("release update check failed", ex);
            if (userInitiated)
            {
                AppendOutput(
                    IsZh()
                        ? $"更新检查失败：{ex.Message}"
                        : $"update check failed: {ex.Message}",
                    "app");
            }
        }
        finally
        {
            _lastUpdateCheckAt = checkedAt;
            _updateCheckInProgress = false;
            UpdateReleaseUpdateUi();
            if (_updateCheckQueued)
            {
                _updateCheckQueued = false;
                _ = CheckForUpdatesAsync(false);
            }
        }
    }

    private void UpdateReleaseUpdateUi()
    {
        if (UpdateStatusText == null)
        {
            return;
        }

        var zh = IsZh();
        var currentVersion = FormatVersionLabel(GetApplicationVersion());

        AboutCurrentVersionValueText.Text = currentVersion;
        AboutBuildChannelValueText.Text = GetBuildChannelLabel(zh);
        UpdateCurrentVersionValueText.Text = currentVersion;

        CheckUpdatesButton.Content = _updateCheckInProgress
            ? (zh ? "检查中..." : "Checking...")
            : (zh ? "检查更新" : "Check for Updates");
        CheckUpdatesButton.IsEnabled = !_updateCheckInProgress;

        UpdateLatestVersionValueText.Text = _lastUpdateResult?.LatestRelease is { } latestRelease
            ? FormatVersionLabel(latestRelease.Version)
            : (_updateCheckInProgress
                ? (zh ? "检查中..." : "Checking...")
                : (zh ? "尚未检查" : "Not checked"));

        UpdateLastCheckedValueText.Text = _lastUpdateCheckAt.HasValue
            ? _lastUpdateCheckAt.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm")
            : (zh ? "未检查" : "Never");

        UpdateInfoBar.IsOpen = false;
        UpdateInfoBar.Severity = InfoBarSeverity.Informational;
        UpdateInfoBar.Title = string.Empty;
        UpdateInfoBar.Message = string.Empty;

        if (_lastUpdateResult is { UpdateAvailable: true } result && ShouldShowUpdateReminder())
        {
            var latestLabel = FormatVersionLabel(result.LatestRelease.Version);
            UpdateInfoBar.IsOpen = true;
            UpdateInfoBar.Title = zh ? "发现可用更新" : "Update Available";
            UpdateInfoBar.Message = zh
                ? $"{latestLabel} 已发布。提醒不会阻断当前使用，你可以在方便时前往发布页更新。"
                : $"{latestLabel} is available. This reminder stays lightweight, so you can update whenever convenient.";
        }

        UpdateStatusText.Text = BuildUpdateStatusMessage(zh);
        UpdateAboutSectionButtonLabel();
    }

    private string BuildUpdateStatusMessage(bool zh)
    {
        if (_updateCheckInProgress)
        {
            return zh
                ? "正在检查 GitHub Releases，请稍候。"
                : "Checking GitHub Releases now.";
        }

        if (!string.IsNullOrWhiteSpace(_lastUpdateErrorMessage))
        {
            return zh
                ? $"更新检查失败：{_lastUpdateErrorMessage}"
                : $"Update check failed: {_lastUpdateErrorMessage}";
        }

        if (_lastUpdateResult is not { } result)
        {
            return zh
                ? "应用会在后台检查 GitHub Releases，提示保持轻量，不影响当前使用。"
                : "The app can check GitHub Releases in the background while keeping reminders lightweight and non-blocking.";
        }

        if (result.UpdateAvailable)
        {
            if (_preferences.SuppressUpdateReminders)
            {
                return zh
                    ? $"发现新版本 {FormatVersionLabel(result.LatestRelease.Version)}，但你已关闭更新提醒。仍可随时手动检查或打开发布页。"
                    : $"A newer build {FormatVersionLabel(result.LatestRelease.Version)} is available, but update reminders are currently suppressed. You can still check manually or open the release page anytime.";
            }

            return zh
                ? $"发现新版本 {FormatVersionLabel(result.LatestRelease.Version)}。这是轻提醒，不会打断当前操作，可在方便时更新。"
                : $"A newer build {FormatVersionLabel(result.LatestRelease.Version)} is available. This is a lightweight reminder, so you can update whenever convenient.";
        }

        if (result.IncludePreview)
        {
            return zh
                ? "当前已是最新版本，检查范围包含预览版。"
                : "You're up to date, including preview releases.";
        }

        if (result.CurrentBuildIsPreview)
        {
            return zh
                ? "当前未发现更高的正式版。你正在使用预览版，如需继续接收 beta/rc，请开启“参与预览版测试”。"
                : "No newer stable release was found. You're on a preview build, so enable preview testing if you want beta or rc updates.";
        }

        return zh
            ? "当前已是最新正式版。"
            : "You're on the latest stable release.";
    }

    private void UpdateAboutSectionButtonLabel()
    {
        if (AboutSectionButtonText == null)
        {
            return;
        }

        var zh = IsZh();
        AboutSectionButtonText.Text = ShouldShowUpdateReminder()
            ? (zh ? "关于（有更新）" : "About (Update)")
            : (zh ? "关于" : "About");
    }

    private bool ShouldShowUpdateReminder()
    {
        return _updateAvailable && !_preferences.SuppressUpdateReminders;
    }

    private void SaveUpdatePreferences()
    {
        AppPreferencesStore.Save(_preferences);
    }

    private static bool IsPreviewBuildVersion(string version)
    {
        var normalized = version.ToLowerInvariant();
        return normalized.Contains("-preview", StringComparison.Ordinal) ||
               normalized.Contains("-alpha", StringComparison.Ordinal) ||
               normalized.Contains("-beta", StringComparison.Ordinal) ||
               normalized.Contains("-rc", StringComparison.Ordinal);
    }

    private static string FormatVersionLabel(string version)
    {
        if (string.IsNullOrWhiteSpace(version))
        {
            return "n/a";
        }

        return version.StartsWith("v", StringComparison.OrdinalIgnoreCase)
            ? version
            : $"v{version}";
    }

    private string GetReleasePageUrl()
    {
        var latestReleaseUrl = _lastUpdateResult?.LatestRelease.HtmlUrl;
        return string.IsNullOrWhiteSpace(latestReleaseUrl)
            ? ReleaseUpdateService.ReleasesUrl
            : latestReleaseUrl;
    }

    private async Task OpenExternalUrlAsync(string url)
    {
        try
        {
            if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                return;
            }

            var launched = await Launcher.LaunchUriAsync(uri);
            if (!launched)
            {
                AppendOutput(
                    IsZh()
                        ? $"无法打开链接：{url}"
                        : $"unable to open link: {url}",
                    "app");
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error($"open external url failed url={url}", ex);
            AppendError(ex);
        }
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

    private void OnAppLogLineWritten(string line)
    {
        if (!_uiInitialized || string.IsNullOrWhiteSpace(line))
        {
            return;
        }

        DispatcherQueue.TryEnqueue(() =>
        {
            if (!_uiInitialized || LogsListView == null)
            {
                return;
            }

            AddAppLogEntry(line.TrimEnd());
        });
    }

    private void AppendOutput(string message, string? tag = null)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return;
        }

        AddLogEntry(
            level: string.Equals(tag, "error", StringComparison.OrdinalIgnoreCase) ? "ERROR" : "EVENT",
            source: NormalizeLogSource(tag),
            message: message.Trim(),
            timestamp: DateTime.Now);
    }

    private void AppendError(Exception ex)
    {
        AppendOutput($"{ex.GetType().Name}: {ex.Message}", "error");
    }

    private void AddAppLogEntry(string line)
    {
        if (string.IsNullOrWhiteSpace(line))
        {
            return;
        }

        var level = "INFO";
        var message = line;
        var split = line.IndexOf("] ", StringComparison.Ordinal);
        if (split >= 0 && split + 2 < line.Length)
        {
            var rest = line.Substring(split + 2);
            if (rest.StartsWith("ERROR ", StringComparison.OrdinalIgnoreCase))
            {
                level = "ERROR";
                message = rest.Substring(6);
            }
            else if (rest.StartsWith("INFO ", StringComparison.OrdinalIgnoreCase))
            {
                level = "INFO";
                message = rest.Substring(5);
            }
            else
            {
                message = rest;
            }
        }

        AddLogEntry(level, "app", message, DateTime.Now);
    }

    private void AddLogEntry(string level, string source, string message, DateTime timestamp)
    {
        var entry = new LogEntryItem(
            timestamp: timestamp,
            timestampText: timestamp.ToString("HH:mm:ss"),
            levelText: string.IsNullOrWhiteSpace(level) ? "EVENT" : level,
            sourceText: NormalizeLogSource(source),
            message: message);

        _allLogEntries.Add(entry);
        if (MatchesLogFilters(entry))
        {
            _visibleLogEntries.Add(entry);
            TryAutoScrollLogs();
        }
    }

    private bool MatchesLogFilters(LogEntryItem entry)
    {
        var levelFilter = GetComboTag(LogLevelFilterBox, "all");
        if (levelFilter != "all" && !string.Equals(entry.LevelText, levelFilter, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var sourceFilter = GetComboTag(LogSourceFilterBox, "all");
        if (sourceFilter != "all" && !string.Equals(entry.SourceText, sourceFilter, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var query = LogSearchBox?.Text?.Trim();
        if (!string.IsNullOrWhiteSpace(query))
        {
            var haystack = $"{entry.SourceText} {entry.LevelText} {entry.Message}".ToLowerInvariant();
            if (!haystack.Contains(query.ToLowerInvariant(), StringComparison.Ordinal))
            {
                return false;
            }
        }

        return true;
    }

    private string NormalizeLogSource(string? source)
    {
        var value = source?.Trim().ToLowerInvariant().Replace(" ", string.Empty) ?? "app";
        return value switch
        {
            "" => "app",
            var v when v.Contains("virtualcam") => "virtualcam",
            var v when v.Contains("usb") || v.Contains("adb") || v.Contains("device") => "devices",
            var v when v.Contains("session") || v.Contains("stats") => "session",
            var v when v.Contains("error") => "app",
            _ => "app"
        };
    }

    private static string GetComboTag(ComboBox? comboBox, string fallback)
    {
        if (comboBox?.SelectedItem is ComboBoxItem item)
        {
            return item.Tag?.ToString() ?? fallback;
        }
        return fallback;
    }

    private void OnLogFilterChanged(object sender, object e)
    {
        RefreshLogEntries();
    }

    private void RefreshLogEntries()
    {
        _visibleLogEntries.Clear();
        foreach (var entry in _allLogEntries.Where(MatchesLogFilters))
        {
            _visibleLogEntries.Add(entry);
        }
        TryAutoScrollLogs();
    }

    private void OnClearLogs(object sender, RoutedEventArgs e)
    {
        _allLogEntries.Clear();
        _visibleLogEntries.Clear();
    }

    private void TryAutoScrollLogs()
    {
        if (!(LogAutoScrollBox?.IsChecked ?? true) || LogsListView == null || _visibleLogEntries.Count == 0)
        {
            return;
        }

        var last = _visibleLogEntries[^1];
        DispatcherQueue.TryEnqueue(() =>
        {
            LogsListView.UpdateLayout();
            LogsListView.ScrollIntoView(last, ScrollIntoViewAlignment.Leading);
        });
    }

    private void UpdateUsbNativePanelVisibility()
    {
        if (TransportBox == null || UsbNativePanel == null)
        {
            return;
        }

        UsbNativePanel.Visibility = GetComboValue(TransportBox, "usb-adb") == "usb-native"
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void UpdateUsbAoaPanelVisibility()
    {
        if (TransportBox == null || UsbAoaPanel == null)
        {
            return;
        }

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

    private sealed class LogEntryItem
    {
        public DateTime Timestamp { get; }
        public string TimestampText { get; }
        public string LevelText { get; }
        public string SourceText { get; }
        public string Message { get; }

        public LogEntryItem(DateTime timestamp, string timestampText, string levelText, string sourceText, string message)
        {
            Timestamp = timestamp;
            TimestampText = timestampText;
            LevelText = levelText;
            SourceText = sourceText;
            Message = message;
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
