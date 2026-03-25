using System.Text.Json;

namespace Acb.Gui.Services;

public sealed record class AppPreferences
{
    public bool ParticipateInPreviewBuilds { get; init; }
    public bool SuppressUpdateReminders { get; init; }
}

internal static class AppPreferencesStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private static string SettingsPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AcbGui",
            "settings.json");

    public static AppPreferences Load(bool defaultParticipateInPreviewBuilds)
    {
        try
        {
            if (!File.Exists(SettingsPath))
            {
                return new AppPreferences
                {
                    ParticipateInPreviewBuilds = defaultParticipateInPreviewBuilds
                };
            }

            var json = File.ReadAllText(SettingsPath);
            var preferences = JsonSerializer.Deserialize<AppPreferences>(json);
            if (preferences != null)
            {
                return preferences;
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("preferences load failed", ex);
        }

        return new AppPreferences
        {
            ParticipateInPreviewBuilds = defaultParticipateInPreviewBuilds
        };
    }

    public static void Save(AppPreferences preferences)
    {
        try
        {
            var directory = Path.GetDirectoryName(SettingsPath);
            if (!string.IsNullOrWhiteSpace(directory))
            {
                Directory.CreateDirectory(directory);
            }

            var json = JsonSerializer.Serialize(preferences, JsonOptions);
            File.WriteAllText(SettingsPath, json);
        }
        catch (Exception ex)
        {
            AppLogger.Error("preferences save failed", ex);
        }
    }
}
