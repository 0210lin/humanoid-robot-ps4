// ServoID 工具(電腦版):和手機網頁一樣的功能,透過 USB 線或 WiFi 呼叫模組的網址。
// 用法(USB 線):USB 線接模組,開這個程式,「連線方式」選 USB 線的 COM 埠。不用連 WiFi。
// 用法(WiFi):電腦先連上模組的 WiFi(HurtMakerID),「連線方式」選 WiFi。
// 也可以從命令列指定:ServoID_Tool.exe COM5   或   ServoID_Tool.exe 192.168.4.1
// 編譯:build.bat(用 Windows 內建的 csc.exe,不用安裝任何東西)
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO.Ports;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

class Api
{
    // 模組就在旁邊,不走 proxy(Windows 預設會先找 proxy,第一次連線會慢好幾秒)
    static readonly HttpClient http = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(8) };
    public static string Host = "192.168.4.1";
    public static string Port = null;   // null = 走 WiFi;"COM5" = 走 USB 線

    // 回傳 JSON 字串;連不上回傳 null
    public static async Task<string> Get(string path)
    {
        if (Port != null)
        {
            // 模組重開機後 COM 號碼可能變了:原本的埠不見了、剛好只有一個 ESP32 埠,就自動改用它
            if (Array.IndexOf(SerialPort.GetPortNames(), Port) < 0)
            {
                string only = null; int n = 0;
                foreach (var pp in Usb.Ports()) if (pp.Value) { only = pp.Key; n++; }
                if (n == 1) Port = only;
            }
            string port = Port; return await Task.Run(() => Usb.Get(port, path));
        }
        try { return await http.GetStringAsync("http://" + Host + path); }
        catch { return null; }
    }
    public static string Str(string j, string key)
    {
        var m = Regex.Match(j ?? "", "\"" + key + @"""\s*:\s*""((?:[^""\\]|\\.)*)""");
        return m.Success ? m.Groups[1].Value.Replace("\\\"", "\"").Replace("\\\\", "\\") : "";
    }
    public static double Num(string j, string key, double def)
    {
        var m = Regex.Match(j ?? "", "\"" + key + @"""\s*:\s*(-?[0-9.]+)");
        double v; return m.Success && double.TryParse(m.Groups[1].Value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out v) ? v : def;
    }
    public static bool Bool(string j, string key)
    {
        return Regex.IsMatch(j ?? "", "\"" + key + @"""\s*:\s*true");
    }
}

// USB 線:把和網頁一樣的網址當成一行文字送給模組,模組回一行「@」開頭的 JSON。
static class Usb
{
    static SerialPort sp;
    static readonly object gate = new object();
    static int seq = 0;           // 每個指令的編號(參數 _t),新韌體回應會帶回來:「@#編號 {...}」

    public static string Get(string port, string path)
    {
        lock (gate)
        {
            try
            {
                if (sp == null || !sp.IsOpen || sp.PortName != port)
                {
                    CloseNoLock();
                    // DTR / RTS 一律不拉、也不切換:ESP32-C3 內建 USB 看到這兩條線變化會重開機,甚至進「下載模式」(程式不跑)。
                    // 2026-10-14 實測:切換過這兩條線後模組卡在 waiting for download,要拔插 USB 才恢復。
                    sp = new SerialPort(port, 115200) { NewLine = "\n", WriteTimeout = 1000, Encoding = Encoding.UTF8, DtrEnable = false, RtsEnable = false };
                    sp.Open();
                }
                sp.DiscardInBuffer();
                string tag = (seq = seq % 99999 + 1).ToString();
                sp.Write(path + (path.Contains("?") ? "&" : "?") + "_t=" + tag + "\n");
                DateTime kick = DateTime.UtcNow;
                // 掃描、改 ID 比較久(要掃 1~127 號)
                int ms = (path.StartsWith("/api/scan") || path.StartsWith("/api/setid") || path.StartsWith("/api/setall") || path.StartsWith("/api/wiggle") || path.StartsWith("/api/netscan")) ? 8000 : 2000;
                DateTime end = DateTime.UtcNow.AddMilliseconds(ms);
                while (true)
                {
                    int left = (int)(end - DateTime.UtcNow).TotalMilliseconds;
                    if (left <= 0) throw new TimeoutException();
                    sp.ReadTimeout = Math.Min(left, 150);
                    string line;
                    try { line = sp.ReadLine().Trim(); }
                    catch (TimeoutException)
                    {
                        // 等太久就送一個空行推一下(模組會忽略空行):舊韌體的回應有時卡在模組的 USB 緩衝區,要電腦送資料才出來
                        if ((DateTime.UtcNow - kick).TotalMilliseconds >= 150) { sp.Write("\n"); kick = DateTime.UtcNow; }
                        continue;
                    }
                    if (line.StartsWith("@#"))
                    {
                        int sp2 = line.IndexOf(' ');
                        if (sp2 > 2 && line.Substring(2, sp2 - 2) == tag) return line.Substring(sp2 + 1);
                        continue;                                   // 別的指令遲到的回應:丟掉
                    }
                    if (line.StartsWith("@")) return line.Substring(1);   // 舊韌體不帶編號;其他行是開機訊息,略過
                }
            }
            // 沒回應也把埠關掉:模組重開機(按 RST、插拔電池)後舊的連線會失效,下一次才會重新打開。
            // (實測開關埠不會讓模組重開機)
            catch (TimeoutException) { CloseNoLock(); return null; }
            catch { CloseNoLock(); return null; }
        }
    }

    static void CloseNoLock() { try { if (sp != null) sp.Dispose(); } catch { } sp = null; }
    public static void Close() { lock (gate) CloseNoLock(); }

    // 目前插著的 COM 埠。Value = true:是 ESP32 內建 USB(廠商代碼 303A),排在前面
    public static List<KeyValuePair<string, bool>> Ports()
    {
        var esp = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            using (var usb = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Enum\USB"))
                foreach (string dev in usb.GetSubKeyNames())
                {
                    if (!dev.StartsWith("VID_303A", StringComparison.OrdinalIgnoreCase)) continue;
                    using (var d = usb.OpenSubKey(dev))
                        foreach (string inst in d.GetSubKeyNames())
                            using (var k = d.OpenSubKey(inst + @"\Device Parameters"))
                            {
                                string n = k == null ? null : k.GetValue("PortName") as string;
                                if (n != null) esp.Add(n);
                            }
                }
        }
        catch { }
        var list = new List<KeyValuePair<string, bool>>();
        string[] names = SerialPort.GetPortNames();
        Array.Sort(names, StringComparer.OrdinalIgnoreCase);
        foreach (string n in names) if (esp.Contains(n)) list.Add(new KeyValuePair<string, bool>(n, true));
        foreach (string n in names) if (!esp.Contains(n)) list.Add(new KeyValuePair<string, bool>(n, false));
        return list;
    }
}

// 「連線方式」下拉選單的一個選項
class Link
{
    public string Port;   // null = WiFi
    public string Text;
    public override string ToString() { return Text; }
}

// ---------- 外觀:顏色和手機網頁用同一組 ----------
static class P
{
    public static readonly Color Bg = ColorTranslator.FromHtml("#eef2f7");
    public static readonly Color Fg = ColorTranslator.FromHtml("#101828");
    public static readonly Color Sub = ColorTranslator.FromHtml("#5b667a");
    public static readonly Color Card = Color.White;
    public static readonly Color Line = ColorTranslator.FromHtml("#d9e0ea");
    public static readonly Color Acc = ColorTranslator.FromHtml("#2563eb");
    public static readonly Color Ok = ColorTranslator.FromHtml("#15803d");
    public static readonly Color Bad = ColorTranslator.FromHtml("#dc2626");
    public static readonly Color Warn = ColorTranslator.FromHtml("#b45309");
    public const TextFormatFlags Tight = TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix;

    public static Font F(float size, bool bold) { return new Font("Microsoft JhengHei UI", size, bold ? FontStyle.Bold : FontStyle.Regular); }
    public static Color Mix(Color a, Color b, double t)
    {
        return Color.FromArgb((int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
    }
    public static GraphicsPath Round(RectangleF r, float rad)
    {
        float d = rad * 2; var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90); p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90); p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure(); return p;
    }
    // 畫一個圓角方塊(填色 + 框線)
    public static void Box(Graphics g, int w, int h, float rad, Color fill, Color border, float penW)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var path = Round(new RectangleF(penW / 2, penW / 2, w - penW, h - penW), rad))
        using (var b = new SolidBrush(fill)) using (var pen = new Pen(border, penW)) { g.FillPath(b, path); g.DrawPath(pen, path); }
    }
}

// 最上面的藍色標題列,右邊是分頁按鈕
class Header : Control
{
    static readonly Font fTitle = P.F(17f, true), fSub = P.F(9.5f, false), fTab = P.F(10.5f, true);
    public string Sub = "";
    public string[] Tabs = new string[0];
    public event Action<int> TabClick;
    int sel = 0, hot = -1;
    public int Selected { get { return sel; } set { sel = value; Invalidate(); } }
    public Header() { SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true); }

    Rectangle TabRect(int i)
    {
        const int w = 132, h = 36, gap = 8;
        int x0 = Width - 20 - Tabs.Length * w - (Tabs.Length - 1) * gap;
        return new Rectangle(x0 + i * (w + gap), (Height - h) / 2, w, h);
    }
    int HitTab(Point pt) { for (int i = 0; i < Tabs.Length; i++) if (TabRect(i).Contains(pt)) return i; return -1; }
    protected override void OnMouseMove(MouseEventArgs e)
    {
        int h = HitTab(e.Location);
        if (h != hot) { hot = h; Cursor = h >= 0 ? Cursors.Hand : Cursors.Default; Invalidate(); }
        base.OnMouseMove(e);
    }
    protected override void OnMouseLeave(EventArgs e) { hot = -1; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseClick(MouseEventArgs e)
    {
        int h = HitTab(e.Location);
        if (h >= 0 && TabClick != null) TabClick(h);
        base.OnMouseClick(e);
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        var r = new Rectangle(0, 0, Width, Height);
        using (var b = new LinearGradientBrush(r, Color.Black, Color.Black, 12f))
        {
            b.InterpolationColors = new ColorBlend
            {
                Colors = new[] { ColorTranslator.FromHtml("#1e3a8a"), ColorTranslator.FromHtml("#2563eb"), ColorTranslator.FromHtml("#38bdf8") },
                Positions = new[] { 0f, 0.6f, 1f }
            };
            g.FillRectangle(b, r);
        }
        TextRenderer.DrawText(g, Text, fTitle, new Point(22, 11), Color.White, P.Tight);
        TextRenderer.DrawText(g, Sub, fSub, new Point(24, 46), ColorTranslator.FromHtml("#dbe7ff"), P.Tight);
        for (int i = 0; i < Tabs.Length; i++)
        {
            var t = TabRect(i);
            bool on = i == sel;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var path = P.Round(t, 18)) using (var b = new SolidBrush(on ? Color.White : Color.FromArgb(hot == i ? 75 : 40, 255, 255, 255))) g.FillPath(b, path);
            g.SmoothingMode = SmoothingMode.Default;
            TextRenderer.DrawText(g, Tabs[i], fTab, t, on ? P.Acc : Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
        }
    }
}

// 白色圓角卡片:左上角有步驟編號和標題
class Card : Panel
{
    static readonly Font fTitle = P.F(11.5f, true), fSub = P.F(9f, false), fStep = new Font("Segoe UI", 9.5f, FontStyle.Bold);
    public string Title = "", Sub = "";
    public int Step = 0;
    public Card() { DoubleBuffered = true; BackColor = P.Card; SetStyle(ControlStyles.ResizeRedraw, true); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(P.Bg);
        P.Box(g, Width, Height, 14, P.Card, P.Line, 1f);
        int x = 16;
        if (Step > 0)
        {
            using (var b = new SolidBrush(P.Acc)) g.FillEllipse(b, 16, 13, 24, 24);
            TextRenderer.DrawText(g, Step.ToString(), fStep, new Rectangle(16, 13, 25, 24), Color.White, P.Tight | TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            x = 48;
        }
        if (Title == "") return;
        TextRenderer.DrawText(g, Title, fTitle, new Point(x, 14), P.Fg, P.Tight);
        if (Sub != "")
        {
            int w = TextRenderer.MeasureText(g, Title, fTitle, Size.Empty, P.Tight).Width;
            TextRenderer.DrawText(g, Sub, fSub, new Point(x + w + 6, 18), P.Sub, P.Tight);
        }
    }
}

// 淺色凹槽(放記錄文字)
class Well : Panel
{
    public Well() { DoubleBuffered = true; BackColor = P.Bg; SetStyle(ControlStyles.ResizeRedraw, true); }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Parent != null ? Parent.BackColor : P.Card);
        P.Box(e.Graphics, Width, Height, 8, P.Bg, P.Line, 1f);
    }
}

// 圓角按鈕。Kind:0 = 主要(藍)、1 = 次要(淺色)、2 = 危險(紅)
class RButton : Button
{
    public int Kind = 0;
    bool hover = false, down = false;
    public RButton()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        Cursor = Cursors.Hand; Font = P.F(10.5f, true);
        AccessibleRole = AccessibleRole.PushButton;   // 自己畫的按鈕,告訴螢幕報讀等輔助工具這是按鈕
    }
    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        Color back = Parent != null ? Parent.BackColor : P.Card;
        g.Clear(back);
        Color fill = Kind == 2 ? P.Bad : Kind == 1 ? P.Bg : P.Acc;
        Color text = Kind == 1 ? P.Fg : Color.White;
        Color border = Kind == 1 ? P.Line : fill;
        if (!Enabled) { fill = P.Mix(fill, back, 0.6); border = Kind == 1 ? P.Line : fill; if (Kind == 1) text = P.Mix(text, back, 0.6); }
        else if (down) { fill = P.Mix(fill, Color.Black, 0.16); border = Kind == 1 ? P.Line : fill; }
        else if (hover) { fill = P.Mix(fill, Kind == 1 ? Color.Black : Color.White, Kind == 1 ? 0.05 : 0.12); border = Kind == 1 ? P.Line : fill; }
        P.Box(g, Width, Height, 10, fill, border, 1f);
        TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, Width, Height), text, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
    }
}

// 單選的選項框(整個框都可以點)。文字 = Pre + 粗體 Bold + Post
class Opt : Control
{
    static readonly Font fN = P.F(10.5f, false), fB = P.F(10.5f, true);
    public string Pre = "", Bold = "", Post = "";
    public event EventHandler CheckedChanged;
    bool chk = false, hover = false;
    public bool Checked
    {
        get { return chk; }
        set { if (chk == value) return; chk = value; Invalidate(); if (CheckedChanged != null) CheckedChanged(this, EventArgs.Empty); }
    }
    public Opt()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        Cursor = Cursors.Hand; AccessibleRole = AccessibleRole.RadioButton;
    }
    protected override void OnCreateControl() { base.OnCreateControl(); AccessibleName = Pre + Bold + Post; }
    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnClick(EventArgs e)
    {
        base.OnClick(e);
        if (chk || Parent == null) return;
        foreach (Control c in Parent.Controls) { var o = c as Opt; if (o != null && o != this) o.Checked = false; }
        Checked = true;
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(Parent != null ? Parent.BackColor : P.Card);
        P.Box(g, Width, Height, 10, chk ? P.Mix(P.Bg, P.Acc, 0.08) : hover ? P.Mix(P.Bg, Color.Black, 0.03) : P.Bg, chk ? P.Acc : P.Line, chk ? 1.6f : 1f);
        int cy = Height / 2;
        using (var b = new SolidBrush(Color.White)) g.FillEllipse(b, 14, cy - 9, 18, 18);
        using (var pen = new Pen(chk ? P.Acc : ColorTranslator.FromHtml("#9aa7b8"), 1.6f)) g.DrawEllipse(pen, 14, cy - 9, 18, 18);
        if (chk) using (var b = new SolidBrush(P.Acc)) g.FillEllipse(b, 18.5f, cy - 4.5f, 9, 9);
        int x = 44;
        string[] parts = { Pre, Bold, Post };
        for (int i = 0; i < 3; i++)
        {
            Font f = i == 1 ? fB : fN;
            int w = TextRenderer.MeasureText(g, parts[i], f, Size.Empty, P.Tight).Width;
            TextRenderer.DrawText(g, parts[i], f, new Rectangle(x, 0, w + 2, Height), P.Fg, P.Tight | TextFormatFlags.VerticalCenter);
            x += w;
        }
    }
}

class MainForm : Form
{
    ComboBox link;
    bool fillingLinks = false;
    TextBox host, log;
    Label conn, bat, batMsg, clsMsg, scanRes, testRes, sweepRes;
    Opt r74, r12;
    RButton bScan, bSet, bM, bP, bC, bSweep, bSweepStop;
    Header head;
    Panel pageId;          // 分頁 1:編 ID 與測試
    Panel pageMulti;       // 分頁 2:多顆馬達
    Panel pageNet;         // 分頁 3:網路設定(和手機網頁一樣)
    Label apName, apRes, staInfo, netRes;
    TextBox apPw1, apPw2, staSsid, staPw;
    FlowLayoutPanel netList;
    RButton bApPw, bScanNet, bStaSet, bStaClr;
    bool netAsked = false;
    System.Windows.Forms.Timer netTimer;
    NumericUpDown mlo, mhi, mst, mmin;
    RButton bMulti, bMultiStop, bScanAll, bSetAll, bCenterAll, bReleaseAll;
    Label multiRes, ackLbl, scanAllRes, setAllRes, allRes;
    FlowLayoutPanel chips;
    ComboBox allNewId;
    ComboBox newId;
    NumericUpDown tid, slo, shi, st, smin;
    System.Windows.Forms.Timer poll, repeat, sweepTimer;
    bool longBusy = false, moveBusy = false, sweepBusy = false, canMove = false, updatingRadio = false;
    int repeatDelta = 0; bool repeatCenter = false;

    Font norm = P.F(10.5f, false), small = P.F(9f, false), mid = P.F(11f, true), big = P.F(13f, true), pad = P.F(15f, true);

    const int W = 920, H = 720, COL = 440, IN = 408;   // 視窗、欄寬、卡片內容寬

    public MainForm()
    {
        Text = "伺服馬達 ID 工具";
        Font = norm; BackColor = P.Bg; ForeColor = P.Fg;
        ClientSize = new Size(W, H);
        FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;

        head = new Header { Left = 0, Top = 0, Width = W, Height = 72, Text = "伺服馬達 ID 工具", Sub = "USB 線或 WiFi 都可以用", Tabs = new[] { "編 ID 與測試", "多顆馬達", "網路設定" } };
        Controls.Add(head);
        head.TabClick += i => SwitchTab(i);

        // ---- 連線 ----
        var cc = NewCard(this, 14, 84, W - 28, 52, 0, "", "");
        L(cc, 16, 16, 72, 22, "連線方式");
        link = Combo(cc, 92, 12, 230);
        var bRe = new RButton { Left = 330, Top = 11, Width = 64, Height = 30, Text = "重找", Kind = 1, Font = norm }; cc.Controls.Add(bRe);
        host = new TextBox { Left = 404, Top = 14, Width = 130, BorderStyle = BorderStyle.FixedSingle, Text = Api.Host }; cc.Controls.Add(host);
        host.Leave += (s, e) => Api.Host = host.Text.Trim();
        conn = L(cc, 548, 16, 330, 22, "連線中…");
        FillLinks();
        link.SelectedIndexChanged += async (s, e) => { if (fillingLinks) return; ApplyLink(); await RefreshState(); };
        bRe.Click += async (s, e) => { FillLinks(); await RefreshState(); };
        FormClosed += (s, e) => Usb.Close();

        // 連線列下面是分頁內容
        pageId = new Panel { Left = 0, Top = 148, Width = W, Height = H - 148, BackColor = P.Bg }; Controls.Add(pageId);
        BuildMulti();
        BuildNet();

        // ---- 左欄:1 電池、2 找馬達、3 改 ID ----
        var c1 = NewCard(pageId, 14, 148, COL, 236, 1, "電池與馬達電壓", "(先做這步,再插馬達)");
        bat = L(c1, 16, 50, IN, 30, "讀取中…", big);
        batMsg = L(c1, 16, 82, IN, 22, "");
        r74 = new Opt { Left = 16, Top = 108, Width = IN, Height = 40, Pre = "我要編的是 ", Bold = "7.4 V", Post = " 馬達(接 2S 電池)" }; c1.Controls.Add(r74);
        r12 = new Opt { Left = 16, Top = 154, Width = IN, Height = 40, Pre = "我要編的是 ", Bold = "12 V", Post = " 馬達(接 3S 電池)" }; c1.Controls.Add(r12);
        clsMsg = L(c1, 16, 202, IN, 22, "");
        r74.CheckedChanged += async (s, e) => { if (r74.Checked && !updatingRadio) { await Api.Get("/api/class?c=74"); Log("選擇 7.4 V 馬達"); await RefreshState(); } };
        r12.CheckedChanged += async (s, e) => { if (r12.Checked && !updatingRadio) { await Api.Get("/api/class?c=12"); Log("選擇 12 V 馬達"); await RefreshState(); } };

        var c2 = NewCard(pageId, 14, 396, COL, 150, 2, "找馬達", "");
        bScan = new RButton { Left = 16, Top = 50, Width = IN, Height = 42, Text = "掃描(找出馬達現在是幾號)" }; c2.Controls.Add(bScan);
        scanRes = L(c2, 16, 100, IN, 42, "", mid);
        bScan.Click += async (s, e) => await DoScan();

        var c3 = NewCard(pageId, 14, 558, COL, 148, 3, "改成新的 ID", "");
        newId = Combo(c3, 16, 55, 150);
        for (int i = 1; i <= 32; i++) newId.Items.Add(i + " 號"); newId.SelectedIndex = 0;
        bSet = new RButton { Left = 176, Top = 49, Width = 248, Height = 42, Text = "改成這個號碼", Kind = 2 }; c3.Controls.Add(bSet);
        L(c3, 16, 102, IN, 38, "匯流排上只能接一顆馬達。改完會自動讀回確認。", small, P.Sub);
        bSet.Click += async (s, e) => await DoSetId();

        // ---- 右欄:4 測試(含來回掃)、記錄 ----
        var c4 = NewCard(pageId, 466, 148, COL, 412, 4, "測試", "(馬達要空載,±100 可連按或按住不放)");
        L(c4, 16, 54, 62, 22, "馬達 ID");
        tid = Num(c4, 80, 51, 70, 1, 127, 1, 0);
        bM = new RButton { Left = 16, Top = 88, Width = 198, Height = 52, Text = "−100", Font = pad }; c4.Controls.Add(bM);
        bP = new RButton { Left = 226, Top = 88, Width = 198, Height = 52, Text = "+100", Font = pad }; c4.Controls.Add(bP);
        bC = new RButton { Left = 16, Top = 148, Width = IN, Height = 40, Text = "1500 回中", Kind = 1 }; c4.Controls.Add(bC);
        testRes = L(c4, 16, 194, IN, 28, "", big);
        Hook(bM, -100, false); Hook(bP, 100, false); Hook(bC, 0, true);

        c4.Controls.Add(new Panel { Left = 16, Top = 230, Width = IN, Height = 1, BackColor = P.Line });
        L(c4, 16, 241, 62, 22, "來回掃", mid);
        L(c4, 80, 244, 344, 20, "讓潤滑油平均(選用)。發燙或有怪聲就按停止", small, P.Sub);
        L(c4, 16, 275, 40, 22, "左端"); slo = Num(c4, 58, 272, 72, 800, 2200, 900, 0);
        L(c4, 146, 275, 40, 22, "右端"); shi = Num(c4, 188, 272, 72, 800, 2200, 2100, 0);
        L(c4, 276, 275, 40, 22, "單程"); st = Num(c4, 318, 272, 60, 0.8M, 5, 1.5M, 1); L(c4, 384, 275, 30, 22, "秒");
        L(c4, 16, 309, 40, 22, "最長"); smin = Num(c4, 58, 306, 72, 0, 600, 0, 0);
        L(c4, 136, 309, 240, 22, "分鐘(0 = 不限時,按停止才停)");
        bSweep = new RButton { Left = 16, Top = 342, Width = 198, Height = 40, Text = "開始來回掃" }; c4.Controls.Add(bSweep);
        bSweepStop = new RButton { Left = 226, Top = 342, Width = 198, Height = 40, Text = "停止", Kind = 2 }; c4.Controls.Add(bSweepStop);
        sweepRes = L(c4, 16, 386, IN, 22, "");
        bSweep.Click += async (s, e) => await DoSweepStart();
        bSweepStop.Click += async (s, e) => await DoSweepStop(sweepRes);

        var c5 = NewCard(pageId, 466, 572, COL, 134, 0, "記錄", "");
        var well = new Well { Left = 16, Top = 42, Width = IN, Height = 80 }; c5.Controls.Add(well);
        log = new TextBox { Left = 8, Top = 6, Width = IN - 12, Height = 68, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, BorderStyle = BorderStyle.None, BackColor = P.Bg, ForeColor = P.Fg, Font = new Font("Consolas", 9f) };
        well.Controls.Add(log);

        repeat = new System.Windows.Forms.Timer { Interval = 160 };
        repeat.Tick += async (s, e) => { if (repeatCenter) return; await MoveMotor(repeatDelta, false); };
        poll = new System.Windows.Forms.Timer { Interval = 2500 };
        poll.Tick += async (s, e) => await RefreshState();
        sweepTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        sweepTimer.Tick += async (s, e) => await SweepPoll();
        Shown += async (s, e) => { await RefreshState(); poll.Start(); await SweepPoll(); };
        SetEnabled(false);
    }

    // y 用整個視窗的座標寫,放進分頁時自動扣掉分頁的位置
    Card NewCard(Control parent, int x, int y, int w, int h, int step, string title, string sub)
    {
        var c = new Card { Left = x, Top = y - (parent == this ? 0 : parent.Top), Width = w, Height = h, Step = step, Title = title, Sub = sub };
        parent.Controls.Add(c); return c;
    }

    // 換分頁
    void SwitchTab(int i)
    {
        if (i == head.Selected) return;
        head.Selected = i;
        pageId.Visible = i == 0; pageMulti.Visible = i == 1; pageNet.Visible = i == 2;
        if (i == 2) { var _ = NetLoad(); }
    }
    Label L(Control parent, int x, int y, int w, int h, string t, Font f = null, Color? c = null)
    {
        var l = new Label { Left = x, Top = y, Width = w, Height = h, Text = t, BackColor = Color.Transparent, ForeColor = c ?? P.Fg, AutoEllipsis = true, UseMnemonic = false };
        if (f != null) l.Font = f;
        parent.Controls.Add(l); return l;
    }
    NumericUpDown Num(Control parent, int x, int y, int w, decimal min, decimal max, decimal val, int places)
    {
        var n = new NumericUpDown { Left = x, Top = y, Width = w, Minimum = min, Maximum = max, Value = val, DecimalPlaces = places, Increment = places > 0 ? 0.1M : 1, BorderStyle = BorderStyle.FixedSingle };
        parent.Controls.Add(n); return n;
    }
    // 下拉選單:外面包一圈 1 px 的框線(平面樣式本身沒有框)
    ComboBox Combo(Control parent, int x, int y, int w)
    {
        var c = new ComboBox { Left = 1, Top = 1, Width = w - 2, DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat, Font = norm };
        var frame = new Panel { Left = x, Top = y, Width = w, Height = c.Height + 2, BackColor = ColorTranslator.FromHtml("#9aa7b8") };
        frame.Controls.Add(c); parent.Controls.Add(frame); return c;
    }
    void Say(Label l, Color c, string t) { l.ForeColor = c; l.Text = t; }

    // 重新列出「連線方式」:WiFi + 目前插著的 COM 埠。沿用原本選的;第一次開時有 ESP32 的 USB 就先選它
    void FillLinks()
    {
        fillingLinks = true;
        bool first = link.Items.Count == 0;
        string want = Api.Port;
        link.Items.Clear();
        link.Items.Add(new Link { Port = null, Text = "WiFi(連模組的熱點)" });
        int pick = 0;
        foreach (var p in Usb.Ports())
        {
            link.Items.Add(new Link { Port = p.Key, Text = "USB 線:" + p.Key + (p.Value ? "(ESP32)" : "") });
            if (want != null && string.Equals(want, p.Key, StringComparison.OrdinalIgnoreCase)) pick = link.Items.Count - 1;
            else if (want == null && first && !Program.WifiAsked && p.Value && pick == 0) pick = link.Items.Count - 1;
        }
        link.SelectedIndex = pick;
        fillingLinks = false;
        ApplyLink();
    }
    void ApplyLink()
    {
        var l = (Link)link.SelectedItem;
        if (Api.Port != l.Port) Usb.Close();
        Api.Port = l.Port;
        host.Enabled = l.Port == null;   // 位址只有 WiFi 用得到
    }

    void Hook(Button b, int delta, bool center)
    {
        // 按一下動一次;按住不放會每 0.16 秒再送一次
        b.MouseDown += async (s, e) =>
        {
            if (e.Button != MouseButtons.Left) return;
            repeatDelta = delta; repeatCenter = center;
            await MoveMotor(delta, center);
            if (!center) repeat.Start();
        };
        b.MouseUp += (s, e) => repeat.Stop();
        b.MouseLeave += (s, e) => repeat.Stop();
    }
    void Log(string t) { log.Text = DateTime.Now.ToString("HH:mm:ss") + "  " + t + "\r\n" + log.Text; }
    void SetEnabled(bool on) { canMove = on; bM.Enabled = bP.Enabled = bC.Enabled = bSweep.Enabled = bMulti.Enabled = on; }

    // ---------- 分頁 3:網路設定(和手機網頁一樣)----------
    // 這塊板子的熱點:名稱出廠時定好(不能改),密碼可以改;按住 BOOT 3 秒密碼回到出廠密碼。
    // 模組要去找的網路:只能一個,設定後模組會連上它(熱點照樣開著),在那個網路上開 http://servoid.local。
    TextBox Tb(Control parent, int x, int y, int w, bool pw)
    {
        var t = new TextBox { Left = x, Top = y, Width = w, BorderStyle = BorderStyle.FixedSingle, Font = norm, UseSystemPasswordChar = pw };
        parent.Controls.Add(t); return t;
    }
    void BuildNet()
    {
        pageNet = new Panel { Left = 0, Top = 148, Width = W, Height = H - 148, BackColor = P.Bg, Visible = false }; Controls.Add(pageNet);

        var a = NewCard(pageNet, 14, 148, COL, 558, 0, "這塊板子的熱點", "");
        L(a, 16, 48, 120, 20, "熱點名稱", small, P.Sub);
        apName = L(a, 16, 68, IN, 30, "—", big);
        L(a, 16, 100, IN, 20, "出廠時設定,不能改", small, P.Sub);
        a.Controls.Add(new Panel { Left = 16, Top = 130, Width = IN, Height = 1, BackColor = P.Line });
        L(a, 16, 142, IN, 22, "改熱點密碼", mid);
        L(a, 16, 172, IN, 20, "新密碼(8 個字以上)", small, P.Sub);
        apPw1 = Tb(a, 16, 194, IN, true);
        L(a, 16, 228, IN, 20, "再輸入一次", small, P.Sub);
        apPw2 = Tb(a, 16, 250, IN, true);
        bApPw = new RButton { Left = 16, Top = 288, Width = IN, Height = 40, Text = "改密碼" }; a.Controls.Add(bApPw);
        apRes = L(a, 16, 336, IN, 44, "", mid);
        L(a, 16, 386, IN, 60, "改完模組會重新開機,手機 / 電腦要用新密碼重新連線。" + "\n" + "忘記密碼時:按住板子上的 BOOT 鍵 3 秒,密碼會回到出廠密碼。", small, P.Sub);
        bApPw.Click += async (s, e) => await ApPass();

        var b = NewCard(pageNet, 466, 148, COL, 558, 0, "模組要去找的網路", "(只能一個)");
        staInfo = L(b, 16, 46, IN, 44, "—");
        b.Controls.Add(new Panel { Left = 16, Top = 98, Width = IN, Height = 1, BackColor = P.Line });
        L(b, 16, 108, IN, 20, "網路名稱(例如:小明的 iPhone)", small, P.Sub);
        staSsid = Tb(b, 16, 130, 300, false);
        bScanNet = new RButton { Left = 326, Top = 127, Width = 98, Height = 32, Text = "掃描", Kind = 1, Font = norm }; b.Controls.Add(bScanNet);
        netList = new FlowLayoutPanel { Left = 16, Top = 166, Width = IN, Height = 70, BackColor = P.Card, AutoScroll = true }; b.Controls.Add(netList);
        L(b, 16, 240, IN, 20, "密碼(沒有密碼就留空)", small, P.Sub);
        staPw = Tb(b, 16, 262, IN, true);
        bStaSet = new RButton { Left = 16, Top = 298, Width = 198, Height = 40, Text = "設定這個網路" }; b.Controls.Add(bStaSet);
        bStaClr = new RButton { Left = 226, Top = 298, Width = 198, Height = 40, Text = "清除", Kind = 1 }; b.Controls.Add(bStaClr);
        netRes = L(b, 16, 346, IN, 44, "", mid);
        L(b, 16, 396, IN, 110,
          "・只能連 2.4 GHz 的網路。" + "\n" +
          "・iPhone 熱點要到「設定 → 個人熱點」打開「最大化相容性」。" + "\n" +
          "・設定後模組會重新開機。連上後,在那個網路上用瀏覽器開 http://servoid.local;電腦版「連線方式」選 WiFi、位址填 servoid.local。", small, P.Sub);
        bScanNet.Click += async (s, e) => await ScanNet();
        bStaSet.Click += async (s, e) => await StaSet();
        bStaClr.Click += async (s, e) => await NetCmd("/api/netclear", "清除模組要找的網路?(模組會重新開機)");

        netTimer = new System.Windows.Forms.Timer { Interval = 10000 };
        netTimer.Tick += async (s, e) => { if (pageNet.Visible) await NetLoad(); };
        netTimer.Start();
    }

    // 讀模組的網路狀態,顯示在網路設定頁
    async Task<string> NetLoad()
    {
        string j = await Api.Get("/api/net");
        if (j == null) { Say(staInfo, P.Bad, "連不上模組"); return null; }
        if (!j.Contains("\"ap\"")) { Say(staInfo, P.Warn, "模組的韌體太舊,沒有網路設定功能"); apName.Text = "—"; return null; }
        apName.Text = Api.Str(j, "ap");
        string ss = Api.Str(j, "ssid");
        if (ss == "") Say(staInfo, P.Warn, "還沒設定");
        else if (Api.Bool(j, "connected")) Say(staInfo, P.Ok, "「" + ss + "」已連上。在那個網路上開 http://servoid.local(或 http://" + Api.Str(j, "ip") + ")");
        else Say(staInfo, P.Warn, "「" + ss + "」還沒連上(那個網路有開嗎?密碼對嗎?)");
        bStaClr.Enabled = ss != "";
        return j;
    }

    // 第一次連上模組時問一次:要不要設定模組去找的網路(和手機網頁一樣)
    async Task FirstRunAsk()
    {
        string j = await NetLoad();
        if (j == null || Api.Str(j, "ssid") != "" || Api.Bool(j, "skip")) return;
        var r = MessageBox.Show("第一次使用:要設定模組去找的網路嗎?" + "\n\n" +
                                "設定後,模組會自己連上那個網路(例如你的手機熱點或家裡的 WiFi),就不用切換 WiFi," +
                                "在那個網路上開 http://servoid.local 就能用。模組自己的熱點也照樣開著。只能設定一個網路。" + "\n\n" +
                                "按「是」到網路設定頁;按「否」就不再問。", "網路設定", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (r == DialogResult.Yes) { SwitchTab(2); staSsid.Focus(); }
        else { string k = await Api.Get("/api/netskip"); if (k != null) Log(Api.Str(k, "msg")); }
    }

    async Task ScanNet()
    {
        if (longBusy) return;
        longBusy = true; bScanNet.Enabled = false;
        try
        {
            netList.Controls.Clear();
            Say(netRes, P.Sub, "掃描中…(約 3 秒)");
            string j = await Api.Get("/api/netscan");
            if (j == null || !j.Contains("\"list\"")) { Say(netRes, P.Bad, j == null ? "連不上模組" : Api.Str(j, "msg")); return; }
            var names = Regex.Matches(j, @"""s""\s*:\s*""((?:[^""\\]|\\.)*)""");
            if (names.Count == 0) { Say(netRes, P.Warn, "附近沒找到網路(熱點有開嗎?是 2.4 GHz 嗎?)"); return; }
            netRes.Text = "點一下名稱就會填進去";
            foreach (Match m in names)
            {
                string name = m.Groups[1].Value.Replace("\\\"", "\"").Replace("\\\\", "\\");
                var chip = new RButton { Height = 30, Text = name, Kind = 1, Font = norm, Margin = new Padding(0, 0, 8, 8) };
                chip.Width = Math.Min(IN - 20, TextRenderer.MeasureText(name, norm).Width + 28);
                chip.Click += (s, e) => { staSsid.Text = name; staPw.Focus(); };
                netList.Controls.Add(chip);
            }
        }
        finally { longBusy = false; bScanNet.Enabled = true; }
    }

    async Task StaSet()
    {
        string ss = staSsid.Text.Trim(), pw = staPw.Text;
        if (ss == "") { Say(netRes, P.Bad, "請輸入網路名稱"); return; }
        if (MessageBox.Show("設定模組去找「" + ss + "」?" + "\n" + "(只能一個,會取代原本的設定。模組會重新開機)", "網路設定", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        await NetCmd("/api/netset?ssid=" + Uri.EscapeDataString(ss) + "&pass=" + Uri.EscapeDataString(pw), null);
        staPw.Text = "";
    }

    async Task ApPass()
    {
        string a = apPw1.Text, b = apPw2.Text;
        if (a.Length < 8) { Say(apRes, P.Bad, "密碼要 8 個字以上"); return; }
        if (a != b) { Say(apRes, P.Bad, "兩次輸入的密碼不一樣"); return; }
        if (MessageBox.Show("改熱點密碼?" + "\n" + "模組會重新開機,手機 / 電腦要用新密碼重新連線。忘記時按住 BOOT 3 秒可以回到出廠密碼。", "改熱點密碼", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        string j = await Api.Get("/api/appass?pass=" + Uri.EscapeDataString(a));
        if (j == null) { Say(apRes, P.Bad, "連不上模組"); return; }
        Say(apRes, Api.Bool(j, "ok") ? P.Ok : P.Bad, Api.Str(j, "msg"));
        Log(Api.Str(j, "msg"));
        if (Api.Bool(j, "ok")) { apPw1.Text = ""; apPw2.Text = ""; }
    }

    // 送一個網路設定指令(confirm 不是 null 就先問),結果顯示在右邊
    async Task NetCmd(string path, string confirm)
    {
        if (confirm != null && MessageBox.Show(confirm, "網路設定", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        string j = await Api.Get(path);
        if (j == null) { Say(netRes, P.Bad, "連不上模組"); return; }
        string msg = Api.Str(j, "msg");
        Say(netRes, Api.Bool(j, "ok") ? P.Ok : P.Bad, msg == "" ? "模組不認得這個指令,韌體要更新" : msg);
        Log(msg);
    }

    // ---------- 分頁 2:多顆馬達 ----------
    // 一次處理很多顆:找出全部(點號碼讓那顆動一下)、全部改成同一個 ID、全部一起來回轉、全部回中 / 放鬆。
    // 「全部一起」的指令是對 1~127 每個號碼各送一次,所以不管馬達是幾號(同號也行)都會動。
    void BuildMulti()
    {
        pageMulti = new Panel { Left = 0, Top = 148, Width = W, Height = H - 148, BackColor = P.Bg, Visible = false }; Controls.Add(pageMulti);

        // 左上:找出所有馬達
        var a = NewCard(pageMulti, 14, 148, COL, 280, 0, "找出所有馬達", "");
        bScanAll = new RButton { Left = 16, Top = 48, Width = IN, Height = 40, Text = "掃描,列出全部馬達" }; a.Controls.Add(bScanAll);
        scanAllRes = L(a, 16, 96, IN, 24, "", mid);
        chips = new FlowLayoutPanel { Left = 16, Top = 124, Width = IN, Height = 98, BackColor = P.Card, AutoScroll = true }; a.Controls.Add(chips);
        L(a, 16, 226, IN, 44, "點號碼,那顆馬達會左右動一下,方便認出是哪一顆(要先在第 1 頁選好電壓)。多顆同號時只會看到一個號碼。", small, P.Sub);
        bScanAll.Click += async (s, e) => await ScanAll();

        // 左下:全部改成同一個 ID
        var b = NewCard(pageMulti, 14, 440, COL, 266, 0, "全部改成同一個 ID", "");
        L(b, 16, 56, 50, 22, "新 ID");
        allNewId = Combo(b, 66, 52, 120);
        for (int i = 1; i <= 32; i++) allNewId.Items.Add(i + " 號"); allNewId.SelectedIndex = 0;
        bSetAll = new RButton { Left = 200, Top = 46, Width = 224, Height = 42, Text = "全部改成這個號碼", Kind = 2 }; b.Controls.Add(bSetAll);
        setAllRes = L(b, 16, 98, IN, 46, "", mid);
        L(b, 16, 150, IN, 104,
          "● 接上的每一顆都會被改成同一個號碼。\n" +
          "● 改完它們就分不出誰是誰,之後要一顆一顆重新編。\n" +
          "● 不要接在機器人上:機器人的馬達號碼會全部亂掉。\n" +
          "● 會先掃描給你確認,改完再掃一次檢查。", small, P.Bad);
        bSetAll.Click += async (s, e) => await SetAll();

        // 右上:全部一起來回轉
        var c = NewCard(pageMulti, 466, 148, COL, 340, 0, "全部一起來回轉", "(不用管 ID)");
        L(c, 16, 53, 40, 22, "左端"); mlo = Num(c, 58, 50, 72, 800, 2200, 1100, 0);
        L(c, 146, 53, 40, 22, "右端"); mhi = Num(c, 188, 50, 72, 800, 2200, 1900, 0);
        L(c, 276, 53, 40, 22, "單程"); mst = Num(c, 318, 50, 60, 0.8M, 5, 1.5M, 1); L(c, 384, 53, 30, 22, "秒");
        L(c, 16, 87, 40, 22, "最長"); mmin = Num(c, 58, 84, 72, 0, 600, 2, 0); L(c, 136, 87, 270, 22, "分鐘(0 = 不限時,按停止才停)");
        bMulti = new RButton { Left = 16, Top = 118, Width = 198, Height = 42, Text = "全部一起開始轉" }; c.Controls.Add(bMulti);
        bMultiStop = new RButton { Left = 226, Top = 118, Width = 198, Height = 42, Text = "停止", Kind = 2 }; c.Controls.Add(bMultiStop);
        multiRes = L(c, 16, 166, IN, 44, "", mid);
        L(c, 16, 214, IN, 20, "有回應的號碼(最近一趟)", small, P.Sub);
        ackLbl = L(c, 16, 234, IN, 40, "—", mid);
        L(c, 16, 278, IN, 56, "用眼睛看:不轉、只抖、卡卡、有怪聲、特別慢的就是有問題。多顆同號時回應會互相干擾,號碼清單可能不完整。", small, P.Sub);
        bMulti.Click += async (s, e) => await DoMultiStart();
        bMultiStop.Click += async (s, e) => await DoSweepStop(multiRes);

        // 右下:全部一起回中 / 放鬆 + 注意事項
        var d = NewCard(pageMulti, 466, 500, COL, 206, 0, "全部一起", "");
        bCenterAll = new RButton { Left = 16, Top = 46, Width = 198, Height = 40, Text = "全部回中 1500", Kind = 1 }; d.Controls.Add(bCenterAll);
        bReleaseAll = new RButton { Left = 226, Top = 46, Width = 198, Height = 40, Text = "全部放鬆(不出力)", Kind = 1 }; d.Controls.Add(bReleaseAll);
        allRes = L(d, 16, 94, IN, 24, "");
        L(d, 16, 122, IN, 78,
          "● 只接同一種電壓的馬達(和第 1 頁選的一樣)\n" +
          "● 馬達要空載,不要裝在機器人上\n" +
          "● 電流會加總:先接 4 ~ 5 顆試,板子保險絲 5 A", small, P.Sub);
        bCenterAll.Click += async (s, e) => await AllCmd("/api/allpos?pos=1500&t=600");
        bReleaseAll.Click += async (s, e) => await AllCmd("/api/allrelease");
    }

    // 掃描 1~127 號,把找到的號碼做成按鈕;點按鈕 = 那顆動一下
    async Task ScanAll()
    {
        if (longBusy) return;
        longBusy = true; bScanAll.Enabled = false;
        try
        {
            Say(scanAllRes, P.Fg, "掃描中…(約 1 秒)");
            chips.Controls.Clear();
            string j = await Api.Get("/api/scan");
            if (j == null) { Say(scanAllRes, P.Bad, "連不上模組"); return; }
            if (!j.Contains("\"ids\"")) { Say(scanAllRes, P.Bad, Api.Str(j, "msg")); return; }
            string ids = Regex.Match(j, @"""ids""\s*:\s*\[([0-9,\s]*)\]").Groups[1].Value.Trim();
            if (ids == "") { Say(scanAllRes, P.Warn, "沒找到馬達。馬達有接好、有通電嗎?"); Log("掃描全部:沒有馬達"); return; }
            string[] list = ids.Split(',');
            Say(scanAllRes, P.Ok, "找到 " + list.Length + (list.Length >= 16 ? " 顆以上" : " 顆") + ":" + ids.Replace(",", "、") + " 號");
            Log("掃描全部:" + ids);
            foreach (string t in list)
            {
                int id = int.Parse(t.Trim());
                var chip = new RButton { Width = 72, Height = 32, Text = id + " 號", Kind = 1, Font = norm, Margin = new Padding(0, 0, 8, 8) };
                chip.Click += async (s, e) => await Wiggle(id);
                chips.Controls.Add(chip);
            }
        }
        finally { longBusy = false; bScanAll.Enabled = true; }
    }

    async Task Wiggle(int id)
    {
        if (longBusy) return;
        longBusy = true;
        try
        {
            string j = await Api.Get("/api/wiggle?id=" + id);
            if (j == null) { Say(scanAllRes, P.Bad, "連不上模組"); return; }
            Say(scanAllRes, Api.Bool(j, "ok") ? P.Ok : P.Bad, Api.Str(j, "msg"));
        }
        finally { longBusy = false; }
    }

    // 全部改成同一個 ID:先掃描給使用者確認(預設按鈕是「取消」),再改
    async Task SetAll()
    {
        if (longBusy) return;
        int n = allNewId.SelectedIndex + 1;
        longBusy = true; bSetAll.Enabled = false;
        try
        {
            Say(setAllRes, P.Fg, "先掃描…");
            string j = await Api.Get("/api/scan");
            if (j == null) { Say(setAllRes, P.Bad, "連不上模組"); return; }
            if (!j.Contains("\"ids\"")) { Say(setAllRes, P.Bad, Api.Str(j, "msg")); return; }
            string ids = Regex.Match(j, @"""ids""\s*:\s*\[([0-9,\s]*)\]").Groups[1].Value.Trim();
            if (ids == "") { Say(setAllRes, P.Warn, "沒找到馬達。馬達有接好、有通電嗎?"); return; }
            int count = ids.Split(',').Length;
            setAllRes.Text = "";
            if (MessageBox.Show("找到 " + count + " 個號碼:" + ids.Replace(",", "、") + " 號(多顆同號時只看得到一個)。\n\n" +
                                "要把接上的全部馬達都改成 " + n + " 號嗎?\n改完它們就分不出誰是誰,要一顆一顆重新編。\n不要接在機器人上!",
                                "全部改成同一個 ID", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2) != DialogResult.OK)
            { Say(setAllRes, P.Sub, "已取消,沒有改。"); return; }
            Say(setAllRes, P.Fg, "正在全部改成 " + n + " 號…(約 2 秒)");
            Log("全部改 ID → " + n + "(原本 " + ids + ")");
            j = await Api.Get("/api/setall?new=" + n);
            if (j == null) { Say(setAllRes, P.Bad, "連不上模組(結果不確定,請重新掃描看看)"); return; }
            string msg = Api.Str(j, "msg"); bool ok = Api.Bool(j, "ok");
            if (msg == "") msg = "模組不認得這個指令,韌體要更新";
            Log(msg);
            Say(setAllRes, ok ? P.Ok : P.Bad, msg);
            chips.Controls.Clear(); scanAllRes.Text = "";   // 號碼都變了,舊的清單作廢
        }
        finally { longBusy = false; bSetAll.Enabled = true; }
    }

    // 全部回中 / 全部放鬆
    async Task AllCmd(string path)
    {
        if (longBusy) return;
        longBusy = true;
        try
        {
            string j = await Api.Get(path);
            if (j == null) { Say(allRes, P.Bad, "連不上模組"); return; }
            string msg = Api.Str(j, "msg");
            Say(allRes, Api.Bool(j, "ok") ? P.Ok : P.Bad, msg == "" ? "模組不認得這個指令,韌體要更新" : msg);
            Log(msg);
        }
        finally { longBusy = false; }
    }

    async Task DoMultiStart()
    {
        if (MessageBox.Show("接上的馬達會全部一起來回轉。\n馬達都空載、沒有裝在機器人上了嗎?", "多顆一起轉", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        string q = "/api/sweep?all=1&id=0&lo=" + mlo.Value + "&hi=" + mhi.Value +
                   "&t=" + mst.Value.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) + "&min=" + mmin.Value;
        string j = await Api.Get(q);
        if (j == null) { Say(multiRes, P.Bad, "連不上模組"); return; }
        bool ok = Api.Bool(j, "ok"); string msg = Api.Str(j, "msg");
        Log(msg);
        Say(multiRes, ok ? P.Ok : P.Bad, msg);
        if (ok) { Say(ackLbl, P.Sub, "等第一趟…"); sweepTimer.Start(); }
    }

    async Task MoveMotor(int delta, bool center)
    {
        if (moveBusy || longBusy) return;
        moveBusy = true;
        try
        {
            string path = center ? "/api/center?id=" + tid.Value : "/api/move?id=" + tid.Value + "&delta=" + delta;
            string j = await Api.Get(path);
            if (j == null) { Say(testRes, P.Bad, "連不上模組"); return; }
            bool ok = Api.Bool(j, "ok");
            string msg = Api.Str(j, "msg");
            Say(testRes, ok ? P.Ok : P.Bad, ok ? "目標 " + Api.Num(j, "target", 0) + (msg.Contains("極限") ? "(已到極限)" : "") : msg);
            if (!ok) { repeat.Stop(); Log(msg); }
        }
        finally { moveBusy = false; }
    }

    async Task RefreshState()
    {
        if (longBusy) return;
        string j = await Api.Get("/api/state");
        if (j == null)
        {
            Say(conn, P.Bad, Api.Port != null ? "● USB 沒回應:線有插好?模組卡住就按 RST 再等一下" : "● 連不上:電腦連上模組的 WiFi 了嗎?");
            Say(bat, P.Fg, "連不上模組"); batMsg.Text = ""; clsMsg.Text = ""; SetEnabled(false); return;
        }
        Say(conn, P.Ok, Api.Port != null ? "● 已連線(USB 線 " + Api.Port + ")" : "● 已連線(WiFi)");
        double v = Api.Num(j, "vbat", 0); string cls = Api.Str(j, "cls"); int sel = (int)Api.Num(j, "sel", 0);
        bool match = Api.Bool(j, "match"), low = Api.Bool(j, "low");
        string name = cls == "none" ? "沒接電池(只有 USB)" : cls == "2S" ? "2S 電池(約 7.4 V)" : cls == "3S" ? "3S 電池(約 11.1 V)" : "電壓不在 2S / 3S 範圍,請檢查";
        Say(bat, P.Fg, "電池 " + v.ToString("0.00") + " V → " + name);
        if (cls == "none") Say(batMsg, P.Warn, "請先接上電池。");
        else if (cls == "bad") Say(batMsg, P.Bad, "電壓異常,先不要插馬達。");
        else if (low) Say(batMsg, P.Warn, "電池電量偏低,請換電池或充電。");
        else Say(batMsg, P.Ok, "電池電壓正常。");
        if (sel == 0) Say(clsMsg, P.Warn, "請選擇你要編的馬達電壓。");
        else if (match) Say(clsMsg, P.Ok, "電池和你選的馬達電壓相符,可以插馬達。");
        else Say(clsMsg, P.Bad, "電池和選的馬達不符!不要插馬達,已插的請馬上拔掉。");
        updatingRadio = true; r74.Checked = sel == 74; r12.Checked = sel == 12; updatingRadio = false;
        SetEnabled(match && !low);
        // 第一次連上模組:畫面先更新完,再問要不要設定網路(問的視窗不會擋住電池等資訊)
        if (!netAsked) { netAsked = true; BeginInvoke(new Action(() => { var _ = FirstRunAsk(); })); }
    }

    async Task DoScan()
    {
        longBusy = true; bScan.Enabled = bSet.Enabled = false;
        try
        {
            Say(scanRes, P.Fg, "掃描中…(約 1 秒)");
            string j = await Api.Get("/api/scan");
            if (j == null) { Say(scanRes, P.Bad, "連不上模組"); return; }
            if (!j.Contains("\"ids\"")) { Say(scanRes, P.Bad, Api.Str(j, "msg")); return; }   // 例如正在來回掃
            if (!Api.Bool(j, "echo")) Log("警告:沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓。");
            var ids = Regex.Match(j, @"""ids""\s*:\s*\[([0-9,\s]*)\]").Groups[1].Value;
            if (ids == "") { Say(scanRes, P.Warn, "沒找到馬達。馬達有接好、有通電嗎?"); Log("掃描:沒有馬達"); }
            else if (!ids.Contains(",")) { Say(scanRes, P.Ok, "找到 1 顆:" + ids + " 號"); tid.Value = int.Parse(ids); Log("掃描:" + ids + " 號"); }
            else { Say(scanRes, P.Bad, "找到多顆:" + ids + "。要改 ID 請只接一顆。"); Log("掃描:" + ids); }
        }
        finally { longBusy = false; bScan.Enabled = bSet.Enabled = true; }
    }

    async Task DoSetId()
    {
        int n = newId.SelectedIndex + 1;
        if (MessageBox.Show("把馬達的 ID 改成 " + n + " 號?\n(匯流排上只能有這一顆馬達)", "改 ID", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        longBusy = true; bScan.Enabled = bSet.Enabled = false;
        try
        {
            Log("改 ID → " + n + " …(約 3 秒)");
            Say(scanRes, P.Fg, "正在改 ID…");
            string j = await Api.Get("/api/setid?new=" + n);
            if (j == null) { Say(scanRes, P.Bad, "連不上模組"); return; }
            string msg = Api.Str(j, "msg"); bool ok = Api.Bool(j, "ok");
            Log(msg);
            Say(scanRes, ok ? P.Ok : P.Bad, msg);
            if (ok) tid.Value = n;
        }
        finally { longBusy = false; bScan.Enabled = bSet.Enabled = true; }
    }

    // ---------- 來回掃 ----------
    async Task DoSweepStart()
    {
        string q = "/api/sweep?id=" + tid.Value + "&lo=" + slo.Value + "&hi=" + shi.Value +
                   "&t=" + st.Value.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) + "&min=" + smin.Value;
        string j = await Api.Get(q);
        if (j == null) { Say(sweepRes, P.Bad, "連不上模組"); return; }
        bool ok = Api.Bool(j, "ok"); string msg = Api.Str(j, "msg");
        Log(msg);
        Say(sweepRes, ok ? P.Ok : P.Bad, msg);
        if (ok) sweepTimer.Start();
    }

    async Task DoSweepStop(Label res)
    {
        string j = await Api.Get("/api/sweepstop");
        if (j == null) { Say(res, P.Bad, "連不上模組"); return; }
        string msg = Api.Str(j, "msg");
        Log(msg);
        Say(res, Api.Bool(j, "ok") ? P.Ok : P.Bad, msg);
        await Task.Delay(800);
        await SweepPoll();
    }

    // 每秒問一次模組:還在掃嗎、掃了幾趟(開程式時也問一次,模組可能本來就在掃)
    async Task SweepPoll()
    {
        if (sweepBusy) return;
        sweepBusy = true;
        try
        {
            string j = await Api.Get("/api/sweepstate");
            if (j == null || !j.Contains("\"on\"")) return;
            int cycles = (int)Api.Num(j, "cycles", 0);
            bool all = Api.Bool(j, "all");
            Label mine = all ? multiRes : sweepRes, other = all ? sweepRes : multiRes;
            if (Api.Bool(j, "on"))
            {
                Say(mine, P.Ok, (all ? "全部一起轉中:" : "來回掃中:") + "已經 " + cycles + " 趟," + (int)Api.Num(j, "sec", 0) + " 秒(" + (int)Api.Num(j, "lo", 0) + " ↔ " + (int)Api.Num(j, "hi", 0) + ")");
                Say(other, P.Warn, all ? "現在是「全部一起轉」(多顆馬達頁),要先在那一頁按停止" : "現在有一顆在來回掃(第 1 頁),要先按停止");
                if (all)
                {
                    int nack = (int)Api.Num(j, "nack", 0);
                    if (nack > 0) Say(ackLbl, P.Fg, Api.Str(j, "acks").Replace(",", "、") + (nack > 20 ? " …" : "") + "(共 " + nack + " 個號碼)");
                    else Say(ackLbl, P.Warn, "這一趟沒有收到回應(多顆同號時常見;以眼睛看為準)");
                }
                if (!sweepTimer.Enabled) sweepTimer.Start();
            }
            else
            {
                string reason = Api.Str(j, "reason");
                if (reason != "" || cycles > 0) Say(mine, P.Warn, (reason != "" ? "已停止:" + reason : "沒有在轉") + "(共 " + cycles + " 趟)");
                other.Text = "";
                sweepTimer.Stop();
            }
        }
        finally { sweepBusy = false; }
    }
}

static class Program
{
    public static bool WifiAsked = false;   // 命令列指定了 WiFi 位址:就不要自動改選 USB

    [STAThread]
    static void Main(string[] args)
    {
        if (args.Length > 0)
        {
            if (args[0].StartsWith("COM", StringComparison.OrdinalIgnoreCase)) Api.Port = args[0].ToUpperInvariant();
            else { Api.Host = args[0]; WifiAsked = true; }
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new MainForm());
    }
}
