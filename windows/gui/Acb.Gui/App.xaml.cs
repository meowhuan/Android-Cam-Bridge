using Microsoft.UI.Xaml;
using Acb.Gui.Services;

namespace Acb.Gui;

public partial class App : Application
{
    private Window? _window;

    public App()
    {
        Environment.SetEnvironmentVariable("MICROSOFT_WINDOWSAPPRUNTIME_BASE_DIRECTORY", AppContext.BaseDirectory);
        var logPath = AppLogger.Initialize();
        AppLogger.Info($"app start, baseDir={AppContext.BaseDirectory}, log={logPath}");

        UnhandledException += (_, e) =>
        {
            AppLogger.Error("unhandled UI exception", e.Exception);
        };

        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            AppLogger.Error("unhandled domain exception", e.ExceptionObject as Exception);
        };

        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            AppLogger.Error("unobserved task exception", e.Exception);
            e.SetObserved();
        };
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        AppLogger.Info("window launch");
        _window = new MainWindow();
        _window.Activate();
    }
}
