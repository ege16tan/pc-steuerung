using System.Threading;

namespace PcSteuerung.Desktop;

/// <summary>
/// Einstiegspunkt der Desktop-App "PC Steuerung".
///
/// Eine einzige EXE zeigt Fenster und Tray-Symbol. Es gibt bewusst keinen
/// Autostart: gestartet wird ueber das Desktop-Symbol. Ein benannter Mutex
/// verhindert zwei Fenster/Tray-Symbole; ein zweiter Start holt nur das
/// vorhandene Fenster nach vorn.
/// </summary>
internal static class Program
{
    private const string MutexName = @"Local\PCSteuerungDesktop";
    private const string ShowEventName = @"Local\PCSteuerungDesktopShow";

    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool createdNew);
        if (!createdNew)
        {
            // Laeuft schon: vorhandenes Fenster oeffnen, kein zweites Tray-Symbol.
            if (EventWaitHandle.TryOpenExisting(ShowEventName, out EventWaitHandle? show))
            {
                using (show)
                {
                    show.Set();
                }
            }

            return;
        }

        using var activation = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(activation));
        GC.KeepAlive(mutex);
    }
}
