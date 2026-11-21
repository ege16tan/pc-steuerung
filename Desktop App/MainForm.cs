using System.Diagnostics;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace PcSteuerung.Desktop;

/// <summary>
/// Hauptfenster der App: Status, Service starten/stoppen, ein kombinierter
/// Herunterfahren/Abbrechen-Knopf, Ruhezustand, Web-UI, Beenden.
/// Schliessen (X) versteckt nur ins Tray; beendet wird ueber "Beenden".
/// </summary>
internal sealed class MainForm : Form
{
    private const int ShutdownDelaySeconds = 60;

    /// <summary>So lange darf ein frisch gestarteter Dienst still sein (gelb).</summary>
    private const int StartGraceSeconds = 20;

    private static readonly Color DotRunning = Color.FromArgb(46, 204, 113);
    private static readonly Color DotStarting = Color.FromArgb(245, 158, 11);
    private static readonly Color DotStopped = Color.FromArgb(145, 153, 166);
    private static readonly Color DotError = Color.FromArgb(231, 76, 60);

    private enum ServiceState { Starting, Running, Stopped, Error }

    private readonly AgentController _agent = new();
    private readonly DesktopSettings _settings = DesktopSettings.Load();
    private readonly EventWaitHandle _activation;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 2000 };
    private readonly NotifyIcon _tray;

    private readonly Panel _dot = new() { Size = new Size(16, 16), Margin = new Padding(0, 5, 12, 0) };
    private readonly Label _statusTitle = new()
    {
        AutoSize = true,
        Font = new Font("Segoe UI", 13F, FontStyle.Bold),
    };
    private readonly Label _statusDetail = new()
    {
        AutoSize = true,
        MaximumSize = new Size(360, 0),
        Margin = new Padding(0, 4, 0, 0),
        ForeColor = SystemColors.GrayText,
    };

    private readonly Button _btnStart = NewButton("Service starten");
    private readonly Button _btnStop = NewButton("Service stoppen");
    private readonly Button _btnShutdown = NewButton("PC herunterfahren (60 s)", 38);
    private readonly Button _btnHibernate = NewButton("Ruhezustand", 38);
    private readonly Button _btnWeb = NewButton("Web-UI im Browser oeffnen");
    private readonly Button _btnRefresh = NewButton("Status aktualisieren");
    private readonly Button _btnExit = NewButton("Beenden");
    private readonly CheckBox _chkAutoStart = new()
    {
        Text = "Service automatisch starten (Watchdog)",
        AutoSize = true,
        Margin = new Padding(0, 4, 0, 2),
    };

    private readonly ToolStripMenuItem _trayStatus = new("Status wird geprueft ...") { Enabled = false };
    private readonly ToolStripMenuItem _trayStart = new("Service starten");
    private readonly ToolStripMenuItem _trayStop = new("Service stoppen");

    private ServiceState _state = ServiceState.Stopped;
    private Color _dotColor = DotStopped;
    private bool _processRunning;
    private int _pendingSeconds;
    private DateTime _startingSince;
    private DateTime _lastAutoStart = DateTime.MinValue;
    private bool _stopRequested;
    private bool _polling;
    private bool _exitRequested;
    private bool _trayHintShown;

    public MainForm(EventWaitHandle activation)
    {
        _activation = activation;
        Text = "PC Steuerung";
        Icon = Icons.App;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(440, 620);
        MinimumSize = new Size(460, 640);
        Font = new Font("Segoe UI", 9F);

        _dot.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(_dotColor);
            e.Graphics.FillEllipse(brush, 1, 1, 13, 13);
        };

        _btnShutdown.Font = new Font("Segoe UI", 10F, FontStyle.Bold);

        Controls.Add(BuildLayout());

        _tray = new NotifyIcon
        {
            Icon = Icons.Stopped,
            Text = "PC Steuerung",
            Visible = true,
            ContextMenuStrip = CreateTrayMenu(),
        };
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
            {
                ShowWindow();
            }
        };
        _tray.DoubleClick += (_, _) => ShowWindow();

        _btnStart.Click += OnStartService;
        _btnStop.Click += OnStopService;
        _btnShutdown.Click += OnShutdownClick;
        _btnHibernate.Click += OnHibernate;
        _btnWeb.Click += (_, _) => OpenWebUi();
        _btnRefresh.Click += async (_, _) => await PollAsync();
        _btnExit.Click += (_, _) => ExitApp();
        _trayStart.Click += OnStartService;
        _trayStop.Click += OnStopService;
        _chkAutoStart.Checked = _settings.AutoStart;
        _chkAutoStart.CheckedChanged += (_, _) =>
        {
            _settings.AutoStart = _chkAutoStart.Checked;
            _settings.Save();
            _lastAutoStart = DateTime.MinValue;
        };

        _timer.Tick += async (_, _) => await PollAsync();
        _timer.Start();

        // Zweiter Start der App holt dieses Fenster nach vorn.
        ThreadPool.RegisterWaitForSingleObject(
            _activation,
            (_, _) =>
            {
                if (IsDisposed)
                {
                    return;
                }

                try
                {
                    BeginInvoke(ShowWindow);
                }
                catch (InvalidOperationException)
                {
                    // Fenster wird gerade entsorgt.
                }
            },
            null,
            Timeout.Infinite,
            executeOnlyOnce: false);

        UpdateControls();
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    private Control BuildLayout()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 6,
            Padding = new Padding(16, 14, 16, 10),
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (int row = 0; row < 4; row++)
        {
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        }

        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        root.Controls.Add(BuildStatusCard(), 0, 0);
        root.Controls.Add(BuildServiceGroup(), 0, 1);
        root.Controls.Add(BuildPcGroup(), 0, 2);
        root.Controls.Add(BuildToolsPanel(), 0, 3);
        root.Controls.Add(new Panel { Dock = DockStyle.Fill }, 0, 4);
        root.Controls.Add(new Label
        {
            AutoSize = true,
            Margin = new Padding(0, 6, 0, 0),
            ForeColor = SystemColors.GrayText,
            Text = "Service = lokaler pc-agent.exe  \u00b7  Web-UI: http://127.0.0.1:8000/",
        }, 0, 5);

        return root;
    }

    private Control BuildStatusCard()
    {
        var card = new TableLayoutPanel
        {
            ColumnCount = 2,
            RowCount = 1,
            AutoSize = true,
            Dock = DockStyle.Top,
            Margin = new Padding(0, 0, 0, 16),
        };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        _dot.Anchor = AnchorStyles.Top | AnchorStyles.Left;
        card.Controls.Add(_dot, 0, 0);

        var text = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            AutoSize = true,
            WrapContents = false,
            Margin = new Padding(0),
        };
        text.Controls.Add(_statusTitle);
        text.Controls.Add(_statusDetail);
        card.Controls.Add(text, 1, 0);
        return card;
    }

    private Control BuildServiceGroup()
    {
        var box = NewGroup("Service");
        var layout = NewGroupLayout();
        AddRow(layout, _btnStart);
        AddRow(layout, _btnStop);
        AddRow(layout, _chkAutoStart, 0);
        box.Controls.Add(layout);
        return box;
    }

    private Control BuildPcGroup()
    {
        var box = NewGroup("PC");
        var layout = NewGroupLayout();
        AddRow(layout, _btnShutdown);
        AddRow(layout, _btnHibernate, 0);
        box.Controls.Add(layout);
        return box;
    }

    private Control BuildToolsPanel()
    {
        var layout = new TableLayoutPanel
        {
            ColumnCount = 1,
            Dock = DockStyle.Top,
            AutoSize = true,
            Margin = new Padding(0, 12, 0, 0),
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        AddRow(layout, _btnWeb);
        AddRow(layout, _btnRefresh);
        AddRow(layout, _btnExit, 0);
        return layout;
    }

    private static GroupBox NewGroup(string title) => new()
    {
        Text = title,
        Dock = DockStyle.Top,
        AutoSize = true,
        Margin = new Padding(0, 0, 0, 14),
        Padding = new Padding(12, 6, 12, 12),
    };

    private static TableLayoutPanel NewGroupLayout()
    {
        var layout = new TableLayoutPanel
        {
            ColumnCount = 1,
            Dock = DockStyle.Top,
            AutoSize = true,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return layout;
    }

    private static void AddRow(TableLayoutPanel layout, Control control, int bottomMargin = 8)
    {
        // Zeile explizit anlegen: "RowCount - 1" waere beim ersten Eintrag -1.
        int row = layout.RowCount;
        layout.RowCount = row + 1;
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        control.Dock = DockStyle.Top;
        control.Margin = new Padding(0, 0, 0, bottomMargin);
        layout.Controls.Add(control, 0, row);
    }

    private static Button NewButton(string text, int height = 32) => new()
    {
        Text = text,
        Height = height,
        Dock = DockStyle.Top,
        UseVisualStyleBackColor = true,
    };

    private ContextMenuStrip CreateTrayMenu()
    {
        var menu = new ContextMenuStrip();
        var open = new ToolStripMenuItem("Fenster \u00f6ffnen", null, (_, _) => ShowWindow());
        var web = new ToolStripMenuItem("Web-UI \u00f6ffnen", null, (_, _) => OpenWebUi());
        var exit = new ToolStripMenuItem("Beenden", null, (_, _) => ExitApp());
        menu.Items.AddRange(new ToolStripItem[]
        {
            _trayStatus,
            new ToolStripSeparator(),
            open,
            _trayStart,
            _trayStop,
            new ToolStripSeparator(),
            web,
            exit,
        });
        return menu;
    }

    // -----------------------------------------------------------------------
    // Status-Abfrage und Anzeige
    // -----------------------------------------------------------------------

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        _ = PollAsync();
    }

    private async Task PollAsync()
    {
        if (_polling || _exitRequested)
        {
            return;
        }

        _polling = true;
        try
        {
            ServiceStatus? status = await _agent.GetStatusAsync();
            _processRunning = _agent.IsServiceRunning();
            ApplyStatus(status);
        }
        catch
        {
            // Die Anzeige darf nie abstuerzen.
        }
        finally
        {
            _polling = false;
        }
    }

    /// <summary>
    /// Uebersetzt Antwort/Prozess in die vier Zustaende:
    /// gelb = startet, gruen = laeuft, grau = gestoppt, rot = Fehler.
    /// </summary>
    private void ApplyStatus(ServiceStatus? status)
    {
        _pendingSeconds = status?.PendingShutdown ?? 0;

        if (status != null)
        {
            _startingSince = default;
            SetState(ServiceState.Running, DotRunning, "Service l\u00e4uft",
                $"Energieplan: {AgentController.PlanLabel(status.PowerPlan)}" +
                $" \u00b7 Port {AgentController.Port}" +
                (status.Simulate ? " \u00b7 Simulation" : string.Empty));
        }
        else if (_processRunning)
        {
            if (_startingSince == default)
            {
                _startingSince = DateTime.UtcNow;
            }

            if ((DateTime.UtcNow - _startingSince).TotalSeconds < StartGraceSeconds)
            {
                SetState(ServiceState.Starting, DotStarting, "Service wird gestartet \u2026",
                    "Der lokale Dienst antwortet noch nicht.");
            }
            else
            {
                SetState(ServiceState.Error, DotError, "Service antwortet nicht",
                    $"Prozess l\u00e4uft, aber Port {AgentController.Port} liefert keine Antwort.");
            }
        }
        else
        {
            _startingSince = default;
            SetState(ServiceState.Stopped, DotStopped, "Service gestoppt",
                _agent.ExeFound
                    ? $"Port {AgentController.Port} \u00b7 nicht erreichbar"
                    : "pc-agent.exe wurde nicht gefunden.");
        }

        if (!_agent.ExeFound)
        {
            SetState(ServiceState.Error, DotError, "Service nicht gefunden",
                "pc-agent.exe fehlt neben der App bzw. im Ordner \"PC Agent Rust\".");
        }

        Watchdog();
        UpdateControls();
    }

    private void SetState(ServiceState state, Color color, string title, string detail)
    {
        _state = state;
        _dotColor = color;
        _statusTitle.Text = title;
        _statusDetail.Text = detail;
    }

    /// <summary>
    /// Watchdog: startet einen gestoppten Dienst hoechstens alle 20 s neu.
    /// Nach einem manuellen "Service stoppen" bleibt der Dienst aus.
    /// </summary>
    private void Watchdog()
    {
        if (!_settings.AutoStart || _stopRequested || !_agent.ExeFound ||
            _state != ServiceState.Stopped)
        {
            return;
        }

        if ((DateTime.UtcNow - _lastAutoStart).TotalSeconds < 20)
        {
            return;
        }

        _lastAutoStart = DateTime.UtcNow;
        StartService();
    }

    /// <summary>Startet `pc-agent.exe` und zeigt sofort "wird gestartet ..." (gelb).</summary>
    private void StartService()
    {
        (bool ok, string message) = _agent.Start();
        if (!ok)
        {
            SetState(ServiceState.Error, DotError, "Service konnte nicht starten", message);
            return;
        }

        _startingSince = DateTime.UtcNow;
        _processRunning = true;
        SetState(ServiceState.Starting, DotStarting, "Service wird gestartet \u2026",
            "Der lokale Dienst antwortet noch nicht.");
    }

    private void UpdateControls()
    {
        bool running = _state == ServiceState.Running;
        _btnStart.Enabled = !running && _state != ServiceState.Starting && _agent.ExeFound;
        _btnStop.Enabled = _processRunning;
        _btnShutdown.Enabled = running;
        _btnHibernate.Enabled = running;
        _btnShutdown.Text = _pendingSeconds > 0
            ? $"Herunterfahren abbrechen (noch {_pendingSeconds} s)"
            : $"PC herunterfahren ({ShutdownDelaySeconds} s)";

        _tray.Icon = _state switch
        {
            ServiceState.Running => Icons.Running,
            ServiceState.Starting => Icons.Starting,
            ServiceState.Error => Icons.Error,
            _ => Icons.Stopped,
        };
        _tray.Text = Shorten("PC Steuerung \u2013 " + _statusTitle.Text, 60);
        _trayStatus.Text = _statusTitle.Text;
        _trayStart.Enabled = _btnStart.Enabled;
        _trayStop.Enabled = _btnStop.Enabled;
        _dot.Invalidate();
    }

    private static string Shorten(string text, int max) =>
        text.Length <= max ? text : text[..max];

    // -----------------------------------------------------------------------
    // Aktionen
    // -----------------------------------------------------------------------

    private async void OnStartService(object? sender, EventArgs e)
    {
        _stopRequested = false;
        StartService();
        UpdateControls();
        await PollAsync();
    }

    private async void OnStopService(object? sender, EventArgs e)
    {
        // Manuell gestoppt: der Watchdog laesst den Dienst aus, bis "Service
        // starten" gedrueckt wird.
        _stopRequested = true;
        (bool ok, string message) = _agent.Stop();
        if (!ok)
        {
            Warn(message);
        }

        _processRunning = false;
        _startingSince = default;
        await PollAsync();
    }

    /// <summary>
    /// Ein Knopf, zwei Formen: ohne geplanten Shutdown "PC herunterfahren",
    /// mit geplantem Shutdown "Herunterfahren abbrechen (noch N s)".
    /// </summary>
    private async void OnShutdownClick(object? sender, EventArgs e)
    {
        if (_pendingSeconds > 0)
        {
            string? error = await _agent.PostAsync("/abort");
            if (error is null)
            {
                _pendingSeconds = 0;
            }
            else
            {
                Warn($"Abbrechen fehlgeschlagen: {error}");
            }
        }
        else
        {
            DialogResult answer = MessageBox.Show(
                this,
                $"Den PC in {ShutdownDelaySeconds} Sekunden herunterfahren?\n\n" +
                "Abbrechen ist danach \u00fcber denselben Knopf m\u00f6glich.",
                "PC herunterfahren",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2);
            if (answer != DialogResult.Yes)
            {
                return;
            }

            string? error = await _agent.PostAsync($"/shutdown?delay={ShutdownDelaySeconds}");
            if (error is null)
            {
                // Sofort umschalten, nicht erst mit der naechsten Abfrage.
                _pendingSeconds = ShutdownDelaySeconds;
            }
            else
            {
                Warn($"Herunterfahren fehlgeschlagen: {error}");
            }
        }

        UpdateControls();
        await PollAsync();
    }

    private async void OnHibernate(object? sender, EventArgs e)
    {
        string? error = await _agent.PostAsync("/hibernate");
        if (error is not null)
        {
            Warn($"Ruhezustand fehlgeschlagen: {error}");
            return;
        }

        _tray.ShowBalloonTip(3000, "PC Steuerung", "Ruhezustand ausgel\u00f6st.", ToolTipIcon.Info);
        await PollAsync();
    }

    private void OpenWebUi()
    {
        try
        {
            Process.Start(new ProcessStartInfo($"http://127.0.0.1:{AgentController.Port}/")
            {
                UseShellExecute = true,
            });
        }
        catch (Exception error)
        {
            Warn($"Browser konnte nicht ge\u00f6ffnet werden: {error.Message}");
        }
    }

    // -----------------------------------------------------------------------
    // Fenster / Tray
    // -----------------------------------------------------------------------

    private void ShowWindow()
    {
        Show();
        if (WindowState == FormWindowState.Minimized)
        {
            WindowState = FormWindowState.Normal;
        }

        Activate();
        BringToFront();
    }

    private void ExitApp()
    {
        _exitRequested = true;
        Close();
    }

    private void HideToTray()
    {
        Hide();
        if (_trayHintShown)
        {
            return;
        }

        _trayHintShown = true;
        _tray.ShowBalloonTip(
            4000,
            "PC Steuerung",
            "L\u00e4uft im Infobereich weiter. Beenden \u00fcber das Tray-Men\u00fc.",
            ToolTipIcon.Info);
    }

    private void Warn(string message) =>
        MessageBox.Show(this, message, "PC Steuerung", MessageBoxButtons.OK, MessageBoxIcon.Warning);

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // X versteckt nur - der Dienst laeuft weiter. Beendet wird ueber
        // "Beenden" (oder wenn Windows herunterfaehrt).
        if (!_exitRequested && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            HideToTray();
            return;
        }

        base.OnFormClosing(e);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _timer.Stop();
        _timer.Dispose();
        _tray.Visible = false;
        _tray.Dispose();
        base.OnFormClosed(e);
    }
}
