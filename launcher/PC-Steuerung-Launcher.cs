using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Text;
using System.Windows.Forms;

namespace PcSteuerungLauncher
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LauncherForm());
        }
    }

    /// <summary>
    /// Kleiner manueller Launcher: startet Agent und Tray ohne Konsole.
    /// Liegt neben agent-manager.ps1 und tray.ps1.
    /// </summary>
    internal sealed class LauncherForm : Form
    {
        private readonly string root;
        private readonly Label status;
        private readonly Timer timer;

        public LauncherForm()
        {
            root = AppDomain.CurrentDomain.BaseDirectory;

            Text = "PC Steuerung";
            ClientSize = new Size(420, 340);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(15, 17, 21);
            ForeColor = Color.FromArgb(230, 232, 238);
            Font = new Font("Segoe UI", 9F);

            var title = new Label
            {
                Text = "PC Steuerung",
                Font = new Font("Segoe UI", 18F, FontStyle.Bold),
                ForeColor = Color.White,
                AutoSize = true,
                Location = new Point(24, 20)
            };
            Controls.Add(title);

            status = new Label
            {
                Text = "Status wird geprüft …",
                ForeColor = Color.FromArgb(154, 163, 178),
                AutoSize = false,
                Size = new Size(372, 24),
                Location = new Point(26, 62)
            };
            Controls.Add(status);

            int y = 100;
            AddButton("Agent + Tray starten", 26, y, StartAll);
            y += 46;
            AddButton("Agent starten", 26, y, () => RunManager("start"));
            y += 46;
            AddButton("Agent stoppen", 26, y, () => RunManager("stop"));
            y += 46;
            AddButton("Tray-Symbol starten", 26, y, StartTray);
            y += 46;
            AddButton("Web-UI öffnen", 26, y, OpenWeb);
            y += 46;
            AddButton("Beenden", 26, y, Close);

            timer = new Timer { Interval = 2000 };
            timer.Tick += (s, e) => UpdateStatus();
            timer.Start();
            UpdateStatus();
        }

        private void AddButton(string text, int x, int y, Action action)
        {
            var button = new Button
            {
                Text = text,
                Location = new Point(x, y),
                Size = new Size(368, 36),
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(30, 34, 43),
                ForeColor = Color.FromArgb(230, 232, 238),
            };
            button.FlatAppearance.BorderColor = Color.FromArgb(35, 42, 56);
            button.Click += (s, e) => action();
            Controls.Add(button);
        }

        private void StartAll()
        {
            RunManager("start");
            StartTray();
        }

        private void RunManager(string action)
        {
            status.Text = action == "start" ? "Agent startet …" : "Agent stoppt …";
            RunPowerShell(
                "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" +
                Path.Combine(root, "agent-manager.ps1") + "\" -Action " + action);
        }

        private void StartTray()
        {
            RunPowerShell(
                "-NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File \"" +
                Path.Combine(root, "tray.ps1") + "\"");
        }

        private void RunPowerShell(string arguments)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = arguments,
                    WorkingDirectory = root,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                };
                Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "Start fehlgeschlagen: " + ex.Message,
                    "PC Steuerung",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }

        private void OpenWeb()
        {
            try
            {
                Process.Start(new ProcessStartInfo("http://127.0.0.1:8000/")
                {
                    UseShellExecute = true
                });
            }
            catch
            {
                // Browser konnte nicht geoeffnet werden; Status bleibt sichtbar.
            }
        }

        private void UpdateStatus()
        {
            try
            {
                var request = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:8000/status");
                request.Timeout = 1200;
                request.ReadWriteTimeout = 1200;
                using (var response = (HttpWebResponse)request.GetResponse())
                using (var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
                {
                    var body = reader.ReadToEnd();
                    if (body.Contains("\"online\":true"))
                    {
                        status.Text = body.Contains("\"simulate\":true")
                            ? "Agent läuft · Simulationsmodus"
                            : "Agent läuft · ECHT-Modus";
                        status.ForeColor = Color.FromArgb(46, 204, 113);
                    }
                    else
                    {
                        status.Text = "Agent läuft · PC offline";
                        status.ForeColor = Color.FromArgb(241, 196, 15);
                    }
                }
            }
            catch
            {
                status.Text = "Agent gestoppt";
                status.ForeColor = Color.FromArgb(231, 76, 60);
            }
        }
    }
}
