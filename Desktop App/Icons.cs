using System.Drawing;
using System.Reflection;

namespace PcSteuerung.Desktop;

/// <summary>
/// Icon-Zugriff: eingebettete .ico-Dateien aus `assets\`. Fehlt eine Datei,
/// wird ein farbiger Punkt gezeichnet - die App startet also immer.
/// </summary>
internal static class Icons
{
    public static Icon App { get; } = Load("app.ico") ?? SystemIcons.Application;

    public static Icon Running { get; } = Load("tray-running.ico") ?? DrawDot(Color.FromArgb(46, 204, 113));

    public static Icon Starting { get; } = Load("tray-starting.ico") ?? DrawDot(Color.FromArgb(245, 158, 11));

    public static Icon Stopped { get; } = Load("tray-stopped.ico") ?? DrawDot(Color.FromArgb(145, 153, 166));

    public static Icon Error { get; } = Load("tray-error.ico") ?? DrawDot(Color.FromArgb(231, 76, 60));

    private static Icon? Load(string fileName)
    {
        try
        {
            Assembly assembly = typeof(Icons).Assembly;
            string? resource = assembly.GetManifestResourceNames()
                .FirstOrDefault(name => name.EndsWith(fileName, StringComparison.OrdinalIgnoreCase));
            if (resource is null)
            {
                return null;
            }

            using Stream? stream = assembly.GetManifestResourceStream(resource);
            return stream is null ? null : new Icon(stream);
        }
        catch
        {
            return null;
        }
    }

    private static Icon DrawDot(Color color)
    {
        using var bitmap = new Bitmap(16, 16);
        using (Graphics graphics = Graphics.FromImage(bitmap))
        {
            graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(color);
            graphics.FillEllipse(brush, 1, 1, 14, 14);
        }

        return Icon.FromHandle(bitmap.GetHicon());
    }
}
