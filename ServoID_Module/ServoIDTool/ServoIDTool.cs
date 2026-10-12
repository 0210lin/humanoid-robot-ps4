// ServoID 工具(電腦版):和手機網頁一樣的功能,透過 WiFi 呼叫模組的網址。
// 用法:電腦先連上模組的 WiFi(HurtMakerID),再開這個程式。要連別的位址:ServoID_Tool.exe 192.168.4.1
// 編譯:build.bat(用 Windows 內建的 csc.exe,不用安裝任何東西)
using System;
using System.Drawing;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

class Api
{
    static readonly HttpClient http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
    public static string Host = "192.168.4.1";

    // 回傳 JSON 字串;連不上回傳 null
    public static async Task<string> Get(string path)
    {
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

class MainForm : Form
{
    TextBox host, log;
    Label conn, bat, batMsg, clsMsg, scanRes, testRes;
    RadioButton r74, r12;
    Button bScan, bSet, bM, bP, bC;
    ComboBox newId;
    NumericUpDown tid;
    System.Windows.Forms.Timer poll, repeat;
    bool longBusy = false, moveBusy = false, canMove = false, updatingRadio = false;
    int repeatDelta = 0; bool repeatCenter = false;

    Font big = new Font("Microsoft JhengHei UI", 13f, FontStyle.Bold);
    Font norm = new Font("Microsoft JhengHei UI", 10.5f);

    public MainForm()
    {
        Text = "伺服馬達 ID 工具";
        Font = norm;
        ClientSize = new Size(500, 760);
        FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;
        int y = 10;

        Label(10, y, "模組位址:"); host = new TextBox { Left = 90, Top = y - 3, Width = 140, Text = Api.Host }; Controls.Add(host);
        host.Leave += (s, e) => Api.Host = host.Text.Trim();
        conn = Label(240, y, "連線中…"); conn.Width = 250; y += 36;

        var g1 = Group("1. 電池與馬達電壓(先做這步,再插馬達)", ref y, 150);
        bat = new Label { Left = 10, Top = 24, Width = 460, Height = 28, Font = big, Text = "讀取中…" }; g1.Controls.Add(bat);
        batMsg = new Label { Left = 10, Top = 54, Width = 460, Height = 22 }; g1.Controls.Add(batMsg);
        r74 = new RadioButton { Left = 10, Top = 78, Width = 460, Text = "我要編的是 7.4 V 馬達(要接 2S 電池)" }; g1.Controls.Add(r74);
        r12 = new RadioButton { Left = 10, Top = 100, Width = 460, Text = "我要編的是 12 V 馬達(要接 3S 電池)" }; g1.Controls.Add(r12);
        clsMsg = new Label { Left = 10, Top = 124, Width = 460, Height = 22 }; g1.Controls.Add(clsMsg);
        r74.CheckedChanged += async (s, e) => { if (r74.Checked && !updatingRadio) { await Api.Get("/api/class?c=74"); Log("選擇 7.4 V 馬達"); await RefreshState(); } };
        r12.CheckedChanged += async (s, e) => { if (r12.Checked && !updatingRadio) { await Api.Get("/api/class?c=12"); Log("選擇 12 V 馬達"); await RefreshState(); } };

        var g2 = Group("2. 找馬達", ref y, 80);
        bScan = new Button { Left = 10, Top = 24, Width = 260, Height = 32, Text = "掃描(找出馬達現在是幾號)" }; g2.Controls.Add(bScan);
        scanRes = new Label { Left = 10, Top = 58, Width = 460, Height = 20 }; g2.Controls.Add(scanRes);
        bScan.Click += async (s, e) => await DoScan();

        var g3 = Group("3. 改成新的 ID(匯流排上只能接一顆馬達)", ref y, 66);
        g3.Controls.Add(new Label { Left = 10, Top = 30, Width = 60, Text = "新 ID:" });
        newId = new ComboBox { Left = 70, Top = 26, Width = 80, DropDownStyle = ComboBoxStyle.DropDownList };
        for (int i = 1; i <= 32; i++) newId.Items.Add(i + " 號"); newId.SelectedIndex = 0; g3.Controls.Add(newId);
        bSet = new Button { Left = 170, Top = 24, Width = 200, Height = 32, Text = "改成這個號碼" }; g3.Controls.Add(bSet);
        bSet.Click += async (s, e) => await DoSetId();

        var g4 = Group("4. 測試(−100 / +100 可以一直按,或按住不放,到極限 800~2200)", ref y, 130);
        g4.Controls.Add(new Label { Left = 10, Top = 30, Width = 80, Text = "馬達 ID:" });
        tid = new NumericUpDown { Left = 90, Top = 26, Width = 70, Minimum = 1, Maximum = 127, Value = 1 }; g4.Controls.Add(tid);
        bM = new Button { Left = 10, Top = 62, Width = 100, Height = 40, Text = "−100", Font = big }; g4.Controls.Add(bM);
        bP = new Button { Left = 120, Top = 62, Width = 100, Height = 40, Text = "+100", Font = big }; g4.Controls.Add(bP);
        bC = new Button { Left = 230, Top = 62, Width = 100, Height = 40, Text = "1500", Font = big }; g4.Controls.Add(bC);
        testRes = new Label { Left = 10, Top = 104, Width = 460, Height = 24, Font = big }; g4.Controls.Add(testRes);
        Hook(bM, -100, false); Hook(bP, 100, false); Hook(bC, 0, true);

        log = new TextBox { Left = 10, Top = y, Width = 480, Height = 760 - y - 10, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Font = new Font("Consolas", 9f) };
        Controls.Add(log);

        repeat = new System.Windows.Forms.Timer { Interval = 160 };
        repeat.Tick += async (s, e) => { if (repeatCenter) return; await MoveMotor(repeatDelta, false); };
        poll = new System.Windows.Forms.Timer { Interval = 2500 };
        poll.Tick += async (s, e) => await RefreshState();
        Shown += async (s, e) => { await RefreshState(); poll.Start(); };
        SetEnabled(false);
    }

    Label Label(int x, int y, string t) { var l = new Label { Left = x, Top = y, Width = 80, Height = 22, Text = t }; Controls.Add(l); return l; }
    GroupBox Group(string t, ref int y, int h)
    {
        var g = new GroupBox { Left = 10, Top = y, Width = 480, Height = h, Text = t }; Controls.Add(g); y += h + 8; return g;
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
    void SetEnabled(bool on) { canMove = on; bM.Enabled = bP.Enabled = bC.Enabled = on; }

    async Task MoveMotor(int delta, bool center)
    {
        if (moveBusy || longBusy) return;
        moveBusy = true;
        try
        {
            string path = center ? "/api/center?id=" + tid.Value : "/api/move?id=" + tid.Value + "&delta=" + delta;
            string j = await Api.Get(path);
            if (j == null) { testRes.ForeColor = Color.Firebrick; testRes.Text = "連不上模組"; return; }
            bool ok = Api.Bool(j, "ok");
            testRes.ForeColor = ok ? Color.Green : Color.Firebrick;
            string msg = Api.Str(j, "msg");
            testRes.Text = ok ? "目標 " + Api.Num(j, "target", 0) + (msg.Contains("極限") ? "(已到極限)" : "") : msg;
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
            conn.ForeColor = Color.Firebrick; conn.Text = "連不上:電腦連上模組的 WiFi 了嗎?";
            bat.Text = "連不上模組"; SetEnabled(false); return;
        }
        conn.ForeColor = Color.Green; conn.Text = "已連線";
        double v = Api.Num(j, "vbat", 0); string cls = Api.Str(j, "cls"); int sel = (int)Api.Num(j, "sel", 0);
        bool match = Api.Bool(j, "match"), low = Api.Bool(j, "low");
        string name = cls == "none" ? "沒接電池(只有 USB)" : cls == "2S" ? "2S 電池(約 7.4 V)" : cls == "3S" ? "3S 電池(約 11.1 V)" : "電壓不在 2S / 3S 範圍,請檢查";
        bat.Text = "電池 " + v.ToString("0.00") + " V → " + name;
        if (cls == "none") { batMsg.ForeColor = Color.DarkGoldenrod; batMsg.Text = "請先接上電池。"; }
        else if (cls == "bad") { batMsg.ForeColor = Color.Firebrick; batMsg.Text = "電壓異常,先不要插馬達。"; }
        else if (low) { batMsg.ForeColor = Color.DarkGoldenrod; batMsg.Text = "電池電量偏低,請換電池或充電。"; }
        else { batMsg.ForeColor = Color.Green; batMsg.Text = "電池電壓正常。"; }
        if (sel == 0) { clsMsg.ForeColor = Color.DarkGoldenrod; clsMsg.Text = "請選擇你要編的馬達電壓。"; }
        else if (match) { clsMsg.ForeColor = Color.Green; clsMsg.Text = "電池和你選的馬達電壓相符,可以插馬達。"; }
        else { clsMsg.ForeColor = Color.Firebrick; clsMsg.Text = "電池和選的馬達不符!不要插馬達,已插的請馬上拔掉。"; }
        updatingRadio = true; r74.Checked = sel == 74; r12.Checked = sel == 12; updatingRadio = false;
        SetEnabled(match && !low);
    }

    async Task DoScan()
    {
        longBusy = true; bScan.Enabled = bSet.Enabled = false;
        try
        {
            scanRes.ForeColor = Color.Black; scanRes.Text = "掃描中…(約 1 秒)";
            string j = await Api.Get("/api/scan");
            if (j == null) { scanRes.ForeColor = Color.Firebrick; scanRes.Text = "連不上模組"; return; }
            if (!Api.Bool(j, "echo")) Log("警告:沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓。");
            var ids = Regex.Match(j, @"""ids""\s*:\s*\[([0-9,\s]*)\]").Groups[1].Value;
            if (ids == "") { scanRes.ForeColor = Color.DarkGoldenrod; scanRes.Text = "沒找到馬達。馬達有接好、有通電嗎?"; Log("掃描:沒有馬達"); }
            else if (!ids.Contains(",")) { scanRes.ForeColor = Color.Green; scanRes.Text = "找到 1 顆:" + ids + " 號"; tid.Value = int.Parse(ids); Log("掃描:" + ids + " 號"); }
            else { scanRes.ForeColor = Color.Firebrick; scanRes.Text = "找到多顆:" + ids + "。要改 ID 請只接一顆。"; Log("掃描:" + ids); }
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
            Log("改 ID → " + n + " …");
            string j = await Api.Get("/api/setid?new=" + n);
            if (j == null) { scanRes.ForeColor = Color.Firebrick; scanRes.Text = "連不上模組"; return; }
            string msg = Api.Str(j, "msg"); bool ok = Api.Bool(j, "ok");
            Log(msg);
            scanRes.ForeColor = ok ? Color.Green : Color.Firebrick; scanRes.Text = msg;
            if (ok) tid.Value = n;
        }
        finally { longBusy = false; bScan.Enabled = bSet.Enabled = true; }
    }
}

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        if (args.Length > 0) Api.Host = args[0];
        Application.EnableVisualStyles();
        Application.Run(new MainForm());
    }
}
