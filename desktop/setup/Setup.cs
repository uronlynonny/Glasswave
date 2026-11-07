// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
//
// Glasswave Setup: the small window that installs and updates Glasswave for Windows. It plays the
// liquid glass opening, then shows one progress bar while it downloads and installs, and opens
// Glasswave when it's done. No wizard, nothing to click.
//
// Usage:
//   GlasswaveSetup.exe                     Downloads the newest Glasswave and installs it (the
//                                          website's download).
//   GlasswaveSetup.exe --msi <file> --update [--wait <pid,pid>] [--launch <Glasswave.exe>]
//                                          Installs an update Glasswave already downloaded, after
//                                          the app has closed, then reopens it.
// Built with the C# compiler that comes with Windows (see desktop/build.gradle.kts). C# 5 only.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

static class Setup
{
    [STAThread]
    static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        var form = new SetupForm(args);
        // Checks only: --shot <folder> saves pictures of the window at a few moments, shows nothing.
        if (args.Length >= 2 && args[0] == "--shot") { form.Shots(args[1]); return 0; }
        if (args.Length >= 2 && args[0] == "--film") { form.Film(args[1]); return 0; }
        Application.Run(form);
        return form.ExitCode;
    }
}

class SetupForm : Form
{
    const string MsiUrl = "https://github.com/uronlynonny/Glasswave/releases/download/windows/Glasswave-Windows.msi";
    static readonly Color Lime = Color.FromArgb(198, 244, 50);
    static readonly Color Faint = Color.FromArgb(140, 140, 140);
    static readonly Color Track = Color.FromArgb(34, 34, 34);

    public int ExitCode = 1;

    // What was asked for.
    string msi;
    bool update;
    bool noOpen; // checks only: install without opening Glasswave after
    bool downloadOnly; // checks only: download (from GW_SETUP_URL if set), don't install
    string launch;
    List<int> waitFor = new List<int>();

    // What's on screen.
    Bitmap[] frames = new Bitmap[0];
    int[] due = new int[0];
    Stopwatch clock = Stopwatch.StartNew();
    string title = "Installing Glasswave";
    string status = "Getting ready…";
    float progress;          // 0..1
    bool busy = true;        // the bar shimmers while there's no number yet
    bool failed;
    bool canClose = true;
    float scale = 1f;
    float fadeIn;
    System.Windows.Forms.Timer timer;
    Rectangle closeBox, retryBox;
    bool overClose, overRetry;
    Point dragFrom;
    bool dragging;

    public SetupForm(string[] args)
    {
        for (int i = 0; i < args.Length; i++)
        {
            string a = args[i];
            if (a == "--msi" && i + 1 < args.Length) msi = args[++i];
            else if (a == "--update") update = true;
            else if (a == "--no-open") noOpen = true;
            else if (a == "--download-only") downloadOnly = true;
            else if (a == "--launch" && i + 1 < args.Length) launch = args[++i];
            else if (a == "--wait" && i + 1 < args.Length)
                foreach (string p in args[++i].Split(','))
                {
                    int pid;
                    if (int.TryParse(p, out pid)) waitFor.Add(pid);
                }
        }
        if (update) title = "Updating Glasswave";

        Text = title;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.Black;
        ShowInTaskbar = true;
        DoubleBuffered = true;
        Opacity = 0;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        using (var g = CreateGraphics()) scale = g.DpiX / 96f;
        ClientSize = new Size(S(420), S(340));
        LoadFrames();

        timer = new System.Windows.Forms.Timer();
        timer.Interval = 15;
        timer.Tick += delegate
        {
            if (fadeIn < 1f) { fadeIn = Math.Min(1f, fadeIn + 0.08f); Opacity = fadeIn; }
            Invalidate();
        };
    }

    int S(float v) { return (int)Math.Round(v * scale); }

    long? fakeTime; // checks only

    // Checks only: a frame every 40 ms of a pretend install, for a preview clip.
    public void Film(string dir)
    {
        Directory.CreateDirectory(dir);
        int k = 0;
        for (long t = 0; t <= 7000; t += 40, k++)
        {
            fakeTime = t;
            if (t < 1500) { status = "Getting ready…"; busy = true; progress = 0; }
            else if (t < 4200) { float d = (t - 1500) / 2700f; status = "Downloading… " + (130 * d).ToString("0.0") + " MB of 130 MB"; busy = false; progress = 0.6f * d; }
            else if (t < 6400) { status = "Installing…"; busy = false; progress = 0.6f + 0.4f * (t - 4200) / 2200f; canClose = false; }
            else { title = "Glasswave is ready"; status = "Opening Glasswave…"; progress = 1f; }
            using (var bmp = new Bitmap(ClientSize.Width, ClientSize.Height))
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.Black);
                OnPaint(new PaintEventArgs(g, ClientRectangle));
                bmp.Save(Path.Combine(dir, "f" + k.ToString("0000") + ".png"));
            }
        }
    }

    public void Shots(string dir)
    {
        Directory.CreateDirectory(dir);
        Action<string, long, string, float, bool, bool, bool> shot = (name, t, st, p, b, f, c) =>
        {
            fakeTime = t; status = st; progress = p; busy = b; failed = f; canClose = c;
            using (var bmp = new Bitmap(ClientSize.Width, ClientSize.Height))
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.Black);
                OnPaint(new PaintEventArgs(g, ClientRectangle));
                bmp.Save(Path.Combine(dir, name + ".png"));
            }
        };
        shot("1-opening", 300, "Getting ready…", 0, true, false, true);
        shot("2-opening", 700, "Getting ready…", 0, true, false, true);
        shot("2b-handover", 2250, "Downloading… 3.1 MB of 130 MB", 0.6f * 3.1f / 130f, false, false, true);
        shot("3-download", 2600, "Downloading… 41.2 MB of 130 MB", 0.6f * 41.2f / 130f, false, false, true);
        shot("4-install", 9000, "Installing…", 0.82f, false, false, false);
        title = "Glasswave is ready";
        shot("5-done", 12000, "Opening Glasswave…", 1f, false, false, false);
        title = "Updating Glasswave";
        shot("6-update", 4000, "Installing…", 0.45f, false, false, false);
        title = "Installing Glasswave";
        shot("7-failed", 6000, "Couldn't download Glasswave. Check your internet and try again.", 0, false, true, true);
    }

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ClassStyle |= 0x20000; // a soft shadow under the window
            return cp;
        }
    }

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Windows 11: round corners and a dark frame.
        int round = 2; try { DwmSetWindowAttribute(Handle, 33, ref round, 4); } catch { }
        int dark = 1; try { DwmSetWindowAttribute(Handle, 20, ref dark, 4); } catch { }
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        timer.Start();
        var t = new Thread(Run);
        t.IsBackground = true;
        t.Start();
    }

    // The opening's frames (a strip of 240 px squares) and when each one is due.
    void LoadFrames()
    {
        try
        {
            var asm = Assembly.GetExecutingAssembly();
            string[] times;
            using (var s = asm.GetManifestResourceStream("intro.txt"))
            using (var r = new StreamReader(s)) times = r.ReadToEnd().Trim().Split(' ');
            using (var s = asm.GetManifestResourceStream("intro-strip.png"))
            using (var strip = new Bitmap(s))
            {
                int n = strip.Width / strip.Height, side = strip.Height;
                frames = new Bitmap[n];
                due = new int[n];
                int at = 0;
                for (int i = 0; i < n; i++)
                {
                    frames[i] = strip.Clone(new Rectangle(i * side, 0, side, side), strip.PixelFormat);
                    due[i] = at;
                    int d; at += int.TryParse(i < times.Length ? times[i] : "17", out d) ? d : 17;
                }
            }
        }
        catch { }
    }

    // GW_SETUP_LOG=<file> (checks only): writes each step and the bar's position.
    static void Log(string m)
    {
        string f = Environment.GetEnvironmentVariable("GW_SETUP_LOG");
        if (string.IsNullOrEmpty(f)) return;
        try { File.AppendAllText(f, DateTime.Now.ToString("HH:mm:ss.fff") + " " + m + Environment.NewLine); } catch { }
    }
    string lastLog;

    void Say(string newStatus, float newProgress, bool shimmer)
    {
        string line = newStatus + " " + (int)(newProgress * 100) + "%" + (shimmer ? " (gliding)" : "");
        if (line != lastLog) { lastLog = line; Log(line); }
        BeginInvoke((MethodInvoker)delegate { status = newStatus; progress = newProgress; busy = shimmer; });
    }

    void Fail(string why)
    {
        Log("FAILED: " + why);
        BeginInvoke((MethodInvoker)delegate { failed = true; canClose = true; busy = false; status = why; });
    }

    // ---- The work ----

    void Run()
    {
        try
        {
            failed = false;
            Thread.Sleep(700); // let the opening play a moment first
            if (update) WaitForApp();
            string file = msi;
            if (file == null)
            {
                file = Download();
                if (file == null) return;
            }
            BeginInvoke((MethodInvoker)delegate { canClose = false; });
            if (downloadOnly) { Say("Downloaded " + new FileInfo(file).Length + " bytes", 1f, false); Log("DONE download-only"); ExitCode = 0; BeginInvoke((MethodInvoker)delegate { FadeAndClose(); }); return; }
            int code = Install(file);
            Log("installer finished with " + code);
            if (code == 1618) // another install is running: wait for it, then try again
            {
                Say("Waiting for another installer to finish…", 0, true);
                for (int i = 0; i < 60 && code == 1618; i++) { Thread.Sleep(2000); code = Install(file); }
            }
            if (code != 0 && code != 3010 && code != 1641)
            {
                Fail(code == 1602 ? "Installing was cancelled." : "Couldn't install Glasswave (error " + code + ").");
                return;
            }
            if (msi == null) { try { File.Delete(file); } catch { } }
            BeginInvoke((MethodInvoker)delegate { title = update ? "Glasswave is up to date" : "Glasswave is ready"; });
            Say(noOpen ? "All done" : "Opening Glasswave…", 1f, false);
            Thread.Sleep(500);
            if (!noOpen) Open();
            ExitCode = 0;
            Thread.Sleep(900);
            BeginInvoke((MethodInvoker)delegate { FadeAndClose(); });
        }
        catch (Exception e)
        {
            Fail("Something went wrong: " + e.Message);
        }
    }

    void WaitForApp()
    {
        if (waitFor.Count == 0) return;
        Say("Closing Glasswave…", 0, true);
        foreach (int pid in waitFor)
        {
            try
            {
                var p = Process.GetProcessById(pid);
                p.WaitForExit(60000);
            }
            catch { } // already closed
        }
        Thread.Sleep(500);
    }

    string Download()
    {
        ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072; // TLS 1.2
        string dir = Path.Combine(Path.GetTempPath(), "GlasswaveSetup");
        Directory.CreateDirectory(dir);
        string file = Path.Combine(dir, "Glasswave-Windows.msi");
        Say("Downloading…", 0, true);
        for (int attempt = 1; attempt <= 3; attempt++)
        {
            try
            {
                string url = Environment.GetEnvironmentVariable("GW_SETUP_URL");
                var req = (HttpWebRequest)WebRequest.Create(string.IsNullOrEmpty(url) ? MsiUrl : url);
                req.UserAgent = "GlasswaveSetup";
                using (var resp = req.GetResponse())
                using (var input = resp.GetResponseStream())
                using (var output = File.Create(file))
                {
                    long total = resp.ContentLength, got = 0;
                    var buf = new byte[81920];
                    int n;
                    var tick = Stopwatch.StartNew();
                    while ((n = input.Read(buf, 0, buf.Length)) > 0)
                    {
                        output.Write(buf, 0, n);
                        got += n;
                        if (tick.ElapsedMilliseconds > 100 || got == total)
                        {
                            tick.Restart();
                            if (total > 0) Say("Downloading… " + Mb(got) + " of " + Mb(total), 0.6f * got / total, false);
                            else Say("Downloading… " + Mb(got), 0, true);
                        }
                    }
                    if (total > 0 && got != total) throw new IOException("the download stopped early");
                }
                return file;
            }
            catch (Exception e)
            {
                if (attempt == 3) { Fail("Couldn't download Glasswave. Check your internet and try again."); return null; }
                Say("Connection dropped, trying again…", 0, true);
                Thread.Sleep(2000 * attempt);
                GC.KeepAlive(e);
            }
        }
        return null;
    }

    static string Mb(long b) { return (b / 1048576.0).ToString(b >= 100 * 1048576L ? "0" : "0.0") + " MB"; }

    // ---- Windows Installer, with its own progress messages instead of its window ----

    delegate int InstallUIHandler(IntPtr context, uint messageType, [MarshalAs(UnmanagedType.LPWStr)] string message);
    [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern int MsiSetInternalUI(int level, IntPtr window);
    [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern InstallUIHandler MsiSetExternalUI(InstallUIHandler handler, uint filter, IntPtr context);
    [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiInstallProduct(string package, string commandLine);

    static InstallUIHandler handler; // kept alive while Windows Installer calls it
    long total, done;
    bool forward = true;
    // Windows Installer works in two stretches: preparing (short, the bar's first quarter here)
    // and copying files (the rest). The bar never moves back.
    float segFrom, segTo, shown;
    bool started;
    float start, span;

    int Install(string file)
    {
        // Downloaded: the bar is 60% full, installing fills the rest. An update: installing is all of it.
        start = msi == null ? 0.6f : 0f;
        span = 1f - start;
        shown = 0f; started = false;
        Say("Installing…", start, true);
        handler = OnInstallerMessage;
        MsiSetInternalUI(2, IntPtr.Zero); // no Windows Installer window
        MsiSetExternalUI(handler, 0x400 /* progress */, IntPtr.Zero);
        return (int)MsiInstallProduct(file, "REBOOT=ReallySuppress");
    }

    int OnInstallerMessage(IntPtr context, uint messageType, string message)
    {
        if ((messageType & 0xFF000000) != 0x0A000000 || string.IsNullOrEmpty(message)) return 0;
        // "1: <kind> 2: <a> 3: <b> 4: <c>"
        var f = new long[5];
        string[] parts = message.Split(' ');
        for (int i = 0; i + 1 < parts.Length; i += 2)
        {
            int k; long v;
            if (int.TryParse(parts[i].TrimEnd(':'), out k) && k >= 1 && k <= 4 && long.TryParse(parts[i + 1], out v)) f[k] = v;
        }
        switch (f[1])
        {
            case 0: // a new stretch of work starts
                Log("installer stage: total=" + f[2] + " direction=" + f[3] + " script=" + f[4]);
                total = f[2]; forward = f[3] == 0; done = forward ? 0 : total;
                if (total > 0)
                {
                    if (f[4] == 1) { segFrom = 0f; segTo = 0.25f; }
                    else { segFrom = Math.Max(shown, started ? 0.25f : 0f); segTo = 1f; }
                    started = true;
                }
                break;
            case 2: // work done
                if (total > 0) done = forward ? done + f[2] : done - f[2];
                break;
            case 3: // more work found
                total += f[2];
                break;
        }
        if (started && total > 0)
        {
            float frac = Math.Max(0f, Math.Min(1f, (float)done / total));
            shown = Math.Max(shown, segFrom + (segTo - segFrom) * frac);
            Say("Installing…", start + span * shown, false);
        }
        return 1;
    }

    void Open()
    {
        string exe = launch;
        if (string.IsNullOrEmpty(exe) || !File.Exists(exe))
            exe = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Glasswave", "Glasswave.exe");
        if (!File.Exists(exe)) return;
        try { Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe) }); } catch { }
    }

    void FadeAndClose()
    {
        canClose = true; // finished: nothing left to protect
        var t = new System.Windows.Forms.Timer();
        t.Interval = 15;
        t.Tick += delegate { Opacity -= 0.1; if (Opacity <= 0.01) { t.Stop(); Close(); } };
        t.Start();
    }

    // ---- Drawing ----

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        int w = ClientSize.Width;

        // The opening, once through; then the wave keeps flowing, like music, until it's done.
        if (frames.Length > 0)
        {
            long t = fakeTime ?? clock.ElapsedMilliseconds;
            int side = S(190);
            var box = new Rectangle((w - side) / 2, S(24), side, side);
            long flowFrom = due[due.Length - 1] + 300;
            if (t < flowFrom)
            {
                int i = 0;
                while (i + 1 < frames.Length && due[i + 1] <= t) i++;
                g.DrawImage(frames[i], box);
            }
            else
            {
                float k = Math.Min(1f, (t - flowFrom) / 400f); // fades over from the recording
                if (k < 1f)
                {
                    g.DrawImage(frames[frames.Length - 1], box);
                    using (var cover = new SolidBrush(Color.FromArgb((int)(255 * k), 0, 0, 0))) g.FillRectangle(cover, box);
                }
                DrawWave(g, box, (t - flowFrom) / 1000f, k);
            }
        }

        // (Point sizes: Windows scales them for the screen already.)
        using (var tf = new Font("Segoe UI Semibold", 13f, GraphicsUnit.Point))
        using (var sf = new Font("Segoe UI", 9.5f, GraphicsUnit.Point))
        using (var center = new StringFormat { Alignment = StringAlignment.Center })
        {
            g.DrawString(title, tf, Brushes.White, new RectangleF(0, S(222), w, S(30)), center);
            using (var b = new SolidBrush(failed ? Color.FromArgb(255, 140, 120) : Faint))
                g.DrawString(status, sf, b, new RectangleF(S(20), S(250), w - S(40), S(22)), center);
        }

        // The bar.
        var bar = new Rectangle(S(70), S(284), w - S(140), S(6));
        using (var track = Rounded(bar, bar.Height / 2f)) using (var b = new SolidBrush(Track)) g.FillPath(b, track);
        if (busy && !failed)
        {
            // No number yet: a short lime piece glides along.
            float p = ((fakeTime ?? clock.ElapsedMilliseconds) % 1400) / 1400f;
            int piece = bar.Width / 4;
            int x = bar.X + (int)((bar.Width + piece) * p) - piece;
            var r = Rectangle.Intersect(bar, new Rectangle(x, bar.Y, piece, bar.Height));
            if (r.Width > 0) using (var path = Rounded(r, r.Height / 2f)) using (var b = new SolidBrush(Lime)) g.FillPath(b, path);
        }
        else if (progress > 0 && !failed)
        {
            var r = new Rectangle(bar.X, bar.Y, Math.Max(bar.Height, (int)(bar.Width * Math.Min(1f, progress))), bar.Height);
            using (var path = Rounded(r, r.Height / 2f)) using (var b = new SolidBrush(Lime)) g.FillPath(b, path);
        }

        // Try again, after something went wrong.
        retryBox = Rectangle.Empty;
        if (failed)
        {
            using (var f = new Font("Segoe UI Semibold", 9.5f, GraphicsUnit.Point))
            {
                var size = g.MeasureString("Try again", f);
                retryBox = new Rectangle((w - (int)size.Width - S(28)) / 2, S(300), (int)size.Width + S(28), S(28));
                using (var path = Rounded(retryBox, retryBox.Height / 2f))
                using (var b = new SolidBrush(overRetry ? Color.FromArgb(220, 255, 90) : Lime)) g.FillPath(b, path);
                using (var center = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
                    g.DrawString("Try again", f, Brushes.Black, retryBox, center);
            }
        }

        // Close, top right (not while installing: stopping halfway would leave Glasswave broken).
        closeBox = new Rectangle(w - S(40), S(10), S(30), S(30));
        if (canClose)
        {
            if (overClose) using (var b = new SolidBrush(Color.FromArgb(40, 255, 255, 255))) g.FillEllipse(b, closeBox);
            using (var pen = new Pen(Color.FromArgb(overClose ? 230 : 150, 255, 255, 255), 1.4f * scale))
            {
                int m = S(10);
                g.DrawLine(pen, closeBox.Left + m, closeBox.Top + m, closeBox.Right - m, closeBox.Bottom - m);
                g.DrawLine(pen, closeBox.Right - m, closeBox.Top + m, closeBox.Left + m, closeBox.Bottom - m);
            }
        }
    }

    // Glasswave's wave, drawn live: the logo's shape (about one and a quarter swings), flowing to
    // the left with a soft glow. At the start it lines up with the recording's last frame.
    static void DrawWave(Graphics g, Rectangle box, float seconds, float alpha)
    {
        float side = box.Width;
        float cx = box.X + side * 0.5f, cy = box.Y + side * 0.5f;
        float halfW = side * 0.26f, amp = side * 0.127f, stroke = side * 0.088f;
        // Starts still (matching the recording) and gets going over the first second.
        float te = seconds < 1f ? seconds * seconds * 0.5f : seconds - 0.5f;
        float phase = te * (float)(Math.PI * 2) * 0.55f;
        amp *= 1f + 0.06f * (float)Math.Sin(te * 2.1);
        var pts = new PointF[64];
        for (int i = 0; i < pts.Length; i++)
        {
            float u = i / (float)(pts.Length - 1);
            double th = Math.PI * (-0.07 + 2.09 * u) + phase;
            pts[i] = new PointF(cx - halfW + 2 * halfW * u, cy - amp * (float)Math.Sin(th));
        }
        // A soft glow: a few wide, faint strokes under the wave.
        float[] widths = { 3.2f, 2.5f, 1.9f, 1.45f };
        int[] alphas = { 6, 9, 13, 18 };
        for (int k = 0; k < widths.Length; k++)
            using (var glow = new Pen(Color.FromArgb((int)(alphas[k] * alpha), Lime), stroke * widths[k]))
            {
                glow.StartCap = glow.EndCap = LineCap.Round; glow.LineJoin = LineJoin.Round;
                g.DrawCurve(glow, pts, 0.5f);
            }
        using (var pen = new Pen(Color.FromArgb((int)(255 * alpha), Lime), stroke))
        {
            pen.StartCap = pen.EndCap = LineCap.Round; pen.LineJoin = LineJoin.Round;
            g.DrawCurve(pen, pts, 0.5f);
        }
    }

    static GraphicsPath Rounded(Rectangle r, float radius)
    {
        var p = new GraphicsPath();
        float d = Math.Max(1f, Math.Min(radius * 2, Math.Min(r.Width, r.Height)));
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    // ---- Mouse: drag the window anywhere, close, try again ----

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (dragging) { Location = new Point(Location.X + e.X - dragFrom.X, Location.Y + e.Y - dragFrom.Y); return; }
        overClose = canClose && closeBox.Contains(e.Location);
        overRetry = failed && retryBox.Contains(e.Location);
        Cursor = overClose || overRetry ? Cursors.Hand : Cursors.Default;
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button != MouseButtons.Left) return;
        if (canClose && closeBox.Contains(e.Location)) { Close(); return; }
        if (failed && retryBox.Contains(e.Location))
        {
            failed = false; busy = true; progress = 0; status = "Trying again…";
            var t = new Thread(Run); t.IsBackground = true; t.Start();
            return;
        }
        dragging = true; dragFrom = e.Location;
    }

    protected override void OnMouseUp(MouseEventArgs e) { base.OnMouseUp(e); dragging = false; }

    protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); overClose = overRetry = false; }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Windows Installer is mid-way: finish first.
        if (!canClose && e.CloseReason == CloseReason.UserClosing) e.Cancel = true;
        base.OnFormClosing(e);
    }
}
