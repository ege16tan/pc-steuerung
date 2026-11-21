using System.Text.Json;

namespace PcSteuerung.Desktop;

/// <summary>
/// Kleine Einstellungen der Desktop-App (bisher nur der Watchdog).
/// Liegt im Datenordner des Agenten: %LOCALAPPDATA%\PC Steuerung.
/// </summary>
internal sealed class DesktopSettings
{
    public bool AutoStart { get; set; } = true;

    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "PC Steuerung",
        "desktop-settings.json");

    public static DesktopSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var json = File.ReadAllText(FilePath);
                return JsonSerializer.Deserialize<DesktopSettings>(json) ?? new DesktopSettings();
            }
        }
        catch
        {
            // Kaputte Datei darf den Start nicht verhindern -> Standardwerte.
        }

        return new DesktopSettings();
    }

    public void Save()
    {
        try
        {
            string directory = Path.GetDirectoryName(FilePath)!;
            Directory.CreateDirectory(directory);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
        }
        catch
        {
            // Einstellung ist nicht kritisch.
        }
    }
}
