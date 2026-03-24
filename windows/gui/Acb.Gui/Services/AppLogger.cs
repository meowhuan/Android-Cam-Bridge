using System.Text;

namespace Acb.Gui.Services;

internal static class AppLogger
{
    private static readonly object Sync = new();
    private static string? _logFilePath;
    public static event Action<string>? LineWritten;

    public static string? LogFilePath => _logFilePath;

    public static string Initialize()
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AcbGui",
            "logs");
        Directory.CreateDirectory(root);
        _logFilePath = Path.Combine(root, $"acb-gui-{DateTime.Now:yyyyMMdd}.log");
        Info("logger initialized");
        return _logFilePath;
    }

    public static void Info(string message)
    {
        Write("INFO", message, null);
    }

    public static void Error(string message, Exception? ex = null)
    {
        Write("ERROR", message, ex);
    }

    private static void Write(string level, string message, Exception? ex)
    {
        if (string.IsNullOrWhiteSpace(_logFilePath)) return;
        var line = new StringBuilder();
        line.Append('[').Append(DateTime.Now.ToString("s")).Append("] ");
        line.Append(level).Append(' ').Append(message);
        if (ex != null)
        {
            line.Append(" | ").Append(ex.GetType().Name).Append(": ").Append(ex.Message);
            line.AppendLine();
            line.Append(ex.StackTrace);
        }

        lock (Sync)
        {
            File.AppendAllText(_logFilePath, line + Environment.NewLine, Encoding.UTF8);
        }

        LineWritten?.Invoke(line.ToString());
    }
}
