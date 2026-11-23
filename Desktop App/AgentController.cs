using System.Diagnostics;
using System.Net.Http;
using System.Text.Json;
using System.Threading;

namespace PcSteuerung.Desktop;

/// <summary>Antwort von `GET /status`, soweit die App sie braucht.</summary>
internal sealed record ServiceStatus(string PowerPlan, int PendingShutdown, bool Simulate);

/// <summary>
/// Steuert den lokalen Rust-Dienst `pc-agent.exe`: Prozess starten/stoppen und
/// HTTP-Aufrufe gegen `http://127.0.0.1:8000`. In der Oberflaeche heisst das
/// immer "Service", der Dateiname bleibt technisch `pc-agent.exe`.
/// </summary>
internal sealed class AgentController
{
    public const int Port = 8000;

    private const string ExeName = "pc-agent.exe";
    private static readonly TimeSpan StatusTimeout = TimeSpan.FromMilliseconds(1500);
    private static readonly TimeSpan ActionTimeout = TimeSpan.FromSeconds(6);

    private readonly HttpClient _http = new()
    {
        BaseAddress = new Uri($"http://127.0.0.1:{Port}/"),
        // Zeitlimits kommen pro Aufruf (Status kurz, Aktionen laenger).
        Timeout = Timeout.InfiniteTimeSpan,
    };

    /// <summary>Pfad zu `pc-agent.exe` (null = nicht gefunden).</summary>
    public string? ExePath { get; } = ResolveExe();

    public bool ExeFound => ExePath != null;

    /// <summary>`GET /status`; null = keine (brauchbare) Antwort.</summary>
    public async Task<ServiceStatus?> GetStatusAsync()
    {
        using var cts = new CancellationTokenSource(StatusTimeout);
        try
        {
            using HttpResponseMessage response = await _http.GetAsync("status", cts.Token)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            string body = await response.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
            using JsonDocument document = JsonDocument.Parse(body);
            JsonElement root = document.RootElement;
            return new ServiceStatus(
                PowerPlan: root.TryGetProperty("power_plan", out JsonElement plan) &&
                    plan.ValueKind == JsonValueKind.String
                        ? plan.GetString() ?? "?"
                        : "?",
                PendingShutdown: root.TryGetProperty("pending_shutdown", out JsonElement pending) &&
                    pending.TryGetInt32(out int seconds)
                        ? Math.Max(seconds, 0)
                        : 0,
                Simulate: root.TryGetProperty("simulate", out JsonElement simulate) &&
                    simulate.ValueKind == JsonValueKind.True);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>POST auf den Dienst; null = Erfolg, sonst ein kurzer Fehlertext.</summary>
    public async Task<string?> PostAsync(string path)
    {
        using var cts = new CancellationTokenSource(ActionTimeout);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, path.TrimStart('/'));
            // Der Dienst verlangt fuer schreibende Aufrufe von localhost diese
            // Kopfzeile (Schutz gegen fremde Webseiten).
            request.Headers.TryAddWithoutValidation("X-PC-Steuerung", "1");
            using HttpResponseMessage response = await _http.SendAsync(request, cts.Token)
                .ConfigureAwait(false);
            return response.IsSuccessStatusCode ? null : $"HTTP {(int)response.StatusCode}";
        }
        catch (OperationCanceledException)
        {
            return "Zeitueberschreitung";
        }
        catch (Exception error)
        {
            return error.Message;
        }
    }

    /// <summary>Laeuft `pc-agent.exe` (unabhaengig davon, ob er antwortet)?</summary>
    public bool IsServiceRunning()
    {
        foreach (Process process in FindProcesses())
        {
            process.Dispose();
            return true;
        }

        return false;
    }

    /// <summary>Dienst als Hintergrundprozess starten (kein Konsolenfenster).</summary>
    public (bool Ok, string Message) Start()
    {
        if (IsServiceRunning())
        {
            return (true, "Der Service laeuft bereits.");
        }

        if (ExePath is null)
        {
            return (false, $"{ExeName} wurde nicht gefunden.");
        }

        try
        {
            var info = new ProcessStartInfo(ExePath)
            {
                WorkingDirectory = Path.GetDirectoryName(ExePath)!,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            info.Environment["PC_AGENT_PORT"] = Port.ToString();
            // Echtbetrieb ist Standard; Simulation nur bewusst einschalten.
            // Immer setzen (nicht nur wenn leer), damit ein global gesetztes
            // PC_AGENT_SIMULATE=1 nicht den Echtbetrieb überschreibt.
            info.Environment["PC_AGENT_SIMULATE"] = "0";

            using Process? started = Process.Start(info);
            return started != null
                ? (true, "Service gestartet.")
                : (false, "Der Service konnte nicht gestartet werden.");
        }
        catch (Exception error)
        {
            return (false, $"Start fehlgeschlagen: {error.Message}");
        }
    }

    /// <summary>Alle laufenden `pc-agent.exe` beenden.</summary>
    public (bool Ok, string Message) Stop()
    {
        List<Process> processes = FindProcesses();
        if (processes.Count == 0)
        {
            return (true, "Der Service war bereits gestoppt.");
        }

        try
        {
            foreach (Process process in processes)
            {
                using (process)
                {
                    process.Kill(entireProcessTree: true);
                    process.WaitForExit(3000);
                }
            }

            return (true, "Service gestoppt.");
        }
        catch (Exception error)
        {
            return (false, $"Stopp fehlgeschlagen: {error.Message}");
        }
    }

    /// <summary>Deutsche Bezeichnung des Energieplans (wie in der App).</summary>
    public static string PlanLabel(string plan) => plan switch
    {
        "high" => "Hoechstleistung",
        "balanced" => "Ausbalanciert",
        "saver" => "Energiesparen",
        "anderer" => "Anderer Plan",
        "unbekannt" or "?" or "" => "Unbekannt",
        _ => plan,
    };

    private List<Process> FindProcesses()
    {
        var found = new List<Process>();
        Process[] candidates;
        try
        {
            candidates = Process.GetProcessesByName(Path.GetFileNameWithoutExtension(ExeName));
        }
        catch
        {
            return found;
        }

        foreach (Process process in candidates)
        {
            if (MatchesPath(process))
            {
                found.Add(process);
            }
            else
            {
                process.Dispose();
            }
        }

        return found;
    }

    /// <summary>Nur den Dienst dieses Projekts beenden, nicht fremde Prozesse.</summary>
    private bool MatchesPath(Process process)
    {
        if (ExePath is null)
        {
            return true;
        }

        try
        {
            string? path = process.MainModule?.FileName;
            return path != null && string.Equals(path, ExePath, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            // Kein Zugriff auf das Modul (z. B. anderer Benutzer): nicht anfassen.
            return false;
        }
    }

    /// <summary>
    /// Sucht `pc-agent.exe`: Umgebungsvariable, dann neben der EXE und bis zu
    /// acht Ebenen darueber (Entwicklung: Repo-Wurzel, Installation: {app}).
    /// </summary>
    private static string? ResolveExe()
    {
        string? fromEnvironment = Environment.GetEnvironmentVariable("PC_AGENT_EXE");
        if (!string.IsNullOrWhiteSpace(fromEnvironment) && File.Exists(fromEnvironment))
        {
            return fromEnvironment;
        }

        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (int level = 0; level < 8 && directory != null; level++, directory = directory.Parent)
        {
            string direct = Path.Combine(directory.FullName, ExeName);
            if (File.Exists(direct))
            {
                return direct;
            }

            // Entwicklung: pc-agent.exe liegt als Geschwister-Ordner "PC Agent Rust".
            if (directory.Parent != null)
            {
                string sibling = Path.Combine(directory.Parent.FullName, "PC Agent Rust", ExeName);
                if (File.Exists(sibling))
                {
                    return sibling;
                }
            }

            string inFolder = Path.Combine(directory.FullName, "PC Agent Rust", ExeName);
            if (File.Exists(inFolder))
            {
                return inFolder;
            }

            // dist-Ordner neben der Repo-Wurzel.
            if (directory.Parent != null)
            {
                string dist = Path.Combine(directory.Parent.FullName, "dist", ExeName);
                if (File.Exists(dist))
                {
                    return dist;
                }
            }
        }

        return null;
    }
}
