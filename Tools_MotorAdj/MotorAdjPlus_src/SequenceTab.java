import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

/**
 * 「動作序列」分頁:依序打 SetFrameRun(幀, 毫秒); 就能把動作播出來,不用手把。
 *   ・可以只在畫面上模擬(26 顆馬達的長條圖依時間移動),也可以同時真的送給機器人
 *   ・支援:SetFrameRun(幀, 毫秒);  delay(毫秒);  repeat 3 { … }  // 註解
 *   ・幀的資料用 MotorAdj 目前載入的(「馬達參數」分頁「寫入」過的幀)
 * 送給機器人的方式和 MotorAdj 自己一樣:把那一幀放進 MotorPosDataTmp,逐顆用 uart_sendMotorCmd 送出
 * (會加上「馬達偏移量」)。沒有勾選「啟用」的馬達不送,跟 Micro 的 SetFrameRun 一樣。
 */
public class SequenceTab {

    static final int N = 26;

    // ---------------- 步驟 ----------------
    static class Step {
        int line;          // 原始行號(從 1 開始)
        boolean isFrame;   // true = SetFrameRun,false = delay
        int frame, ms;
    }

    static class Parsed {
        List<Step> steps = new ArrayList<Step>();
        List<String> errors = new ArrayList<String>();
        int firstErrorLine = -1;
    }

    static final Pattern P_FRAME = Pattern.compile("^SetFrameRun\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*;?$");
    static final Pattern P_DELAY = Pattern.compile("^delay\\s*\\(\\s*(\\d+)\\s*\\)\\s*;?$");
    static final Pattern P_REPEAT = Pattern.compile("^(?:repeat|for)\\s*\\(?\\s*(\\d+)\\s*(?:times|次)?\\s*\\)?\\s*\\{$");
    static final int MAX_STEPS = 20000;

    static String stripComment(String l) {
        int i = l.indexOf("//");
        if (i >= 0) l = l.substring(0, i);
        return l.trim();
    }

    /** 讀一段文字(frames = 目前載入的幀數,<= 0 代表不檢查) */
    public static Parsed parse(String text, int frames) {
        Parsed r = new Parsed();
        String[] lines = text.replace("\r", "").split("\n", -1);
        // 迴圈堆疊:每層記錄 {起點步驟索引, 次數, 行號}
        List<int[]> stack = new ArrayList<int[]>();
        for (int i = 0; i < lines.length; i++) {
            String l = stripComment(lines[i]);
            if (l.isEmpty()) continue;
            int ln = i + 1;
            Matcher m = P_FRAME.matcher(l);
            if (m.matches()) {
                int f = Integer.parseInt(m.group(1));
                int ms = Integer.parseInt(m.group(2));
                if (frames > 0 && f >= frames) { err(r, ln, "幀 " + f + " 不存在(目前載入了 " + frames + " 幀,編號從 0 開始)"); continue; }
                Step s = new Step();
                s.line = ln; s.isFrame = true; s.frame = f; s.ms = ms;
                r.steps.add(s);
                continue;
            }
            m = P_DELAY.matcher(l);
            if (m.matches()) {
                Step s = new Step();
                s.line = ln; s.isFrame = false; s.ms = Integer.parseInt(m.group(1));
                r.steps.add(s);
                continue;
            }
            m = P_REPEAT.matcher(l);
            if (m.matches()) {
                stack.add(new int[] {r.steps.size(), Integer.parseInt(m.group(1)), ln});
                continue;
            }
            if (l.equals("}")) {
                if (stack.isEmpty()) { err(r, ln, "多了一個 }(前面沒有 repeat)"); continue; }
                int[] top = stack.remove(stack.size() - 1);
                int from = top[0], times = top[1];
                List<Step> body = new ArrayList<Step>(r.steps.subList(from, r.steps.size()));
                for (int k = 1; k < times; k++) {
                    for (Step b : body) {
                        if (r.steps.size() >= MAX_STEPS) { err(r, ln, "步驟太多了(超過 " + MAX_STEPS + " 步)"); break; }
                        r.steps.add(b);
                    }
                }
                continue;
            }
            err(r, ln, "看不懂這一行:" + l + "  (可以用 SetFrameRun(幀, 毫秒); / delay(毫秒); / repeat 3 { … } )");
        }
        for (int[] open : stack) err(r, open[2], "這個 repeat 的 { 沒有對應的 }");
        return r;
    }

    static void err(Parsed r, int line, String msg) {
        r.errors.add("第 " + line + " 行:" + msg);
        if (r.firstErrorLine < 0 || line < r.firstErrorLine) r.firstErrorLine = line;
    }

    // ---------------- 狀態 ----------------
    static final Object lock = new Object();
    static double[] from = new double[N], to = new double[N];
    static double[] tm = new double[N];
    static long startNs = 0;
    static volatile boolean running = false;
    static volatile boolean stopFlag = false;
    static Thread worker;
    static int stepCursor = 0;               // 單步用
    static Parsed stepParsed = null;
    static boolean sentWarned = false;

    static JTextArea area;
    static javax.swing.text.JTextComponent statusLbl;   // 可以自動換行的狀態文字
    static JLabel nowLbl;
    static JCheckBox sendBox, loopBox;
    static JButton playBtn, stopBtn, stepBtn;
    static DefaultListModel<String> logModel = new DefaultListModel<String>();
    static JComponent bars;
    static JPanel tabPanel;
    static Object highlightTag = null;

    static int[][][] frameData() {
        try {
            Object outer = Plus.outer();
            if (outer == null) return null;
            return (int[][][]) Plus.fld(Plus.fld(outer, "MotorSet"), "MotorPosData");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 總幀數 = MotorAdj 幀資料陣列的長度(MotorFramCnt 是「畫面現在停在第幾幀」,不是總數) */
    static int frameCount() {
        int[][][] d = frameData();
        return d == null ? 0 : d.length;
    }

    static boolean robotConnected() {
        try {
            Object outer = Plus.outer();
            if (outer == null) return false;
            return Boolean.TRUE.equals(Plus.fld(Plus.fld(outer, "MotorSet"), "SerialPort_isOpen"));
        } catch (Throwable t) {
            return false;
        }
    }

    static double[] poseNow() {
        double[] p = new double[N];
        long now = System.nanoTime();
        synchronized (lock) {
            for (int m = 0; m < N; m++) {
                double t = Math.max(1, tm[m]);
                double e = (now - startNs) / 1e6;
                double k = Math.min(1.0, Math.max(0.0, e / t));
                p[m] = from[m] + (to[m] - from[m]) * k;
            }
        }
        return p;
    }

    /** 開始播一幀:目前的位置當起點,有啟用的馬達往目標走(時間 = 那一幀的「速度」欄) */
    static double slowFactor() {
        try { return new double[] {1, 2, 5, 10}[Math.max(0, speedBox.getSelectedIndex())]; } catch (Throwable t) { return 1; }
    }

    static void beginFrame(int f) { beginFrame(f, 1); }

    static void beginFrame(int f, double slow) {
        int[][][] d = frameData();
        if (d == null || f < 0 || f >= d.length) return;
        double[] cur = poseNow();
        synchronized (lock) {
            for (int m = 0; m < N && m < d[f].length; m++) {
                from[m] = cur[m];
                if (d[f][m][0] != 0) {
                    to[m] = d[f][m][1];
                    tm[m] = Math.max(1, d[f][m][2] * slow);
                } else {
                    to[m] = cur[m];           // 沒啟用的馬達:不動
                    tm[m] = 1;
                }
            }
            startNs = System.nanoTime();
        }
    }

    /** 真的把這一幀送給機器人。回傳花了幾毫秒;失敗丟例外 */
    /** 送指令時會暫借工作區(MotorPosDataTmp),所以同一時間只能有一個在送(模擬播放、快速測試動作共用) */
    static final Object SEND_LOCK = new Object();

    static long sendFrame(int f) throws Exception {
        synchronized (SEND_LOCK) { return sendFrameLocked(f); }
    }

    static long sendFrameLocked(int f) throws Exception {
        if (!License.unlocked()) throw new Exception("「送到機器人」是教師功能,需要教師金鑰");
        Object outer = Plus.outer();
        Object ms = Plus.fld(outer, "MotorSet");
        int[][][] d = (int[][][]) Plus.fld(ms, "MotorPosData");
        int[][] tmp = (int[][]) Plus.fld(ms, "MotorPosDataTmp");
        Method send = outer.getClass().getDeclaredMethod("uart_sendMotorCmd", int.class, int.class);
        send.setAccessible(true);
        long t0 = System.nanoTime();
        // MotorAdj 送指令時讀的是 MotorPosDataTmp(「馬達參數」分頁的工作區)。送完一定要還原,
        // 不然畫面上顯示的和內部資料不一樣,之後按「寫入」會把錯的資料寫進去。
        int[][] saved = new int[N][3];
        for (int m = 0; m < N && m < tmp.length; m++) for (int k = 0; k < 3; k++) saved[m][k] = tmp[m][k];
        try {
            for (int m = 0; m < N && m < d[f].length; m++) {
                for (int k = 0; k < 3; k++) tmp[m][k] = d[f][m][k];
            }
            for (int m = 0; m < N && m < d[f].length; m++) {
                if (d[f][m][0] == 0) continue;       // 沒啟用的馬達不送(跟 Micro 的 SetFrameRun 一樣)
                Object r = send.invoke(outer, f, m);
                if (r instanceof Integer && ((Integer) r).intValue() != 0) throw new Exception("送出失敗(馬達 " + (m + 1) + ")。序列埠斷了嗎?");
            }
        } finally {
            for (int m = 0; m < N && m < tmp.length; m++) for (int k = 0; k < 3; k++) tmp[m][k] = saved[m][k];
        }
        return (System.nanoTime() - t0) / 1000000;
    }

    // ---------------- 執行 ----------------
    static void setStatus(final String s) {
        SwingUtilities.invokeLater(new Runnable() { public void run() { statusLbl.setText(s); } });
    }

    static void markLine(final int line) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                Highlighter h = area.getHighlighter();
                if (highlightTag != null) { h.removeHighlight(highlightTag); highlightTag = null; }
                if (line <= 0) return;
                try {
                    int s = area.getLineStartOffset(line - 1);
                    int e = area.getLineEndOffset(line - 1);
                    highlightTag = h.addHighlight(s, e, new DefaultHighlighter.DefaultHighlightPainter(new Color(0x00, 0xB8, 0xD4, 110)));
                } catch (Exception ex) { }
            }
        });
    }

    static void addLog(final String t) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                logModel.addElement(t);
                while (logModel.size() > 300) logModel.remove(0);
            }
        });
    }

    static boolean sleepStop(long ms) {
        long end = System.nanoTime() + ms * 1000000L;
        while (System.nanoTime() < end) {
            if (stopFlag) return false;
            try { Thread.sleep(Math.min(10, Math.max(1, (end - System.nanoTime()) / 1000000L))); } catch (InterruptedException e) { return false; }
        }
        return !stopFlag;
    }

    /** 執行一步。回傳 false = 該停了(被停止或失敗) */
    static boolean runStep(Step s, boolean send) {
        markLine(s.line);
        if (s.isFrame) {
            double slow = send ? 1 : slowFactor();
            beginFrame(s.frame, slow);
            long spent = 0;
            if (send) {
                try { spent = sendFrame(s.frame); }
                catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    setStatus("送給機器人失敗:" + c.getMessage());
                    addLog("✘ 第 " + s.line + " 行 送出失敗:" + c.getMessage());
                    return false;
                }
            }
            addLog("第 " + s.line + " 行  幀 " + s.frame + "  " + s.ms + " ms" + (send && spent > s.ms ? "  (傳送花了 " + spent + " ms,比設定的時間長)" : ""));
            return sleepStop((long) (Math.max(0, s.ms - spent) * slow));
        } else {
            addLog("第 " + s.line + " 行  等待 " + s.ms + " ms");
            return sleepStop((long) (s.ms * (send ? 1 : slowFactor())));
        }
    }

    static Parsed parseNow() {
        boolean ok = refreshDerived();
        int frames = frameCount();
        Parsed p = parse(area.getText(), frames);
        if (!ok) p.errors.add(0, "這一段的格式看不懂,沒辦法模擬(左邊要選一個 case)");
        if (frames <= 0) p.errors.add(0, "MotorAdj 裡沒有載入任何幀(先在「馬達參數」分頁載入或寫入幀)");
        return p;
    }

    static boolean checkReady(Parsed p, boolean send) {
        if (!p.errors.isEmpty()) {
            statusLbl.setText(p.errors.get(0) + (p.errors.size() > 1 ? "(還有 " + (p.errors.size() - 1) + " 個錯誤)" : ""));
            if (p.firstErrorLine > 0) markLine(p.firstErrorLine);
            return false;
        }
        if (p.steps.isEmpty()) { statusLbl.setText("沒有可以播的動作。一行一個,例如 SetFrameRun(1, 100);"); return false; }
        if (send) {
            if (!License.require("送到機器人")) { sendBox.setSelected(false); return false; }
            if (!robotConnected()) {
                statusLbl.setText("機器人還沒連線:請先到「設定」分頁按「串口連接」,再到「馬達參數」分頁按「開啟馬達」。(或把「送到機器人」取消勾選,只看模擬)");
                return false;
            }
            if (!sentWarned) {
                int ans = JOptionPane.showConfirmDialog(null,
                        "要把動作真的送給機器人。機器人會依序動作,請確認:\n・機器人已經架空或周圍安全\n・手隨時可以按「停止」或「放鬆馬達」\n\n開始嗎?",
                        "送到機器人", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ans != JOptionPane.OK_OPTION) return false;
                sentWarned = true;
            }
        }
        return true;
    }

    static void play() {
        try {
            playInner();
        } catch (Throwable t) {
            t.printStackTrace();
            running = false;
            playBtn.setEnabled(true);
            stepBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            statusLbl.setText("播放失敗:" + t);
            addLog("✘ 播放失敗:" + t);
        }
    }

    static void playInner() {
        if (running) {
            if (worker != null && !worker.isAlive()) { running = false; }   // 上一次的執行緒已經不在了,別卡住
            else { statusLbl.setText("還在播放中,先按停止。"); return; }
        }
        final boolean send = sendBox.isSelected();
        final Parsed p = parseNow();
        if (!checkReady(p, send)) return;
        stepParsed = null;
        running = true;
        stopFlag = false;
        playBtn.setEnabled(false);
        stepBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusLbl.setText(send ? "播放中(送給機器人)…" : "播放中(模擬)…");
        addLog("▶ 開始播放(" + (send ? "送給機器人" : "模擬") + ",共 " + p.steps.size() + " 步)");
        final boolean loop = loopBox.isSelected();
        worker = new Thread(new Runnable() {
            public void run() {
                boolean ok = true;
                int rounds = 0;
                outer:
                do {
                    rounds++;
                    for (Step s : p.steps) {
                        if (!runStep(s, send)) { ok = false; break outer; }
                    }
                } while (loopBox.isSelected() && !stopFlag);
                final boolean fin = ok && !stopFlag;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        running = false;
                        playBtn.setEnabled(true);
                        stepBtn.setEnabled(true);
                        stopBtn.setEnabled(false);
                        markLine(0);
                        if (fin) statusLbl.setText("播完了。");
                        else if (statusLbl.getText().startsWith("播放中")) statusLbl.setText("已停止。");
                    }
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    static void stop() {
        stopFlag = true;
    }

    static void relaxAll() {
        stopFlag = true;
        try {
            Object outer = Plus.outer();
            if (outer == null) { statusLbl.setText("找不到 MotorAdj 的內部資料。"); return; }
            if (!robotConnected()) { statusLbl.setText("機器人還沒連線。"); return; }
            Plus.disableAllKeepData(outer);
            statusLbl.setText("已送出「放鬆全部馬達」。要再動作,請在「馬達參數」分頁按「開啟馬達」。");
        } catch (Throwable t) {
            statusLbl.setText("放鬆失敗:" + t);
        }
    }

    static void stepOnce() {
        if (running) return;
        boolean send = sendBox.isSelected();
        if (stepParsed == null) {
            Parsed p = parseNow();
            if (!checkReady(p, send)) return;
            stepParsed = p;
            stepCursor = 0;
        }
        if (stepCursor >= stepParsed.steps.size()) {
            stepParsed = null;
            statusLbl.setText("單步:已經是最後一步。再按一次從頭開始。");
            markLine(0);
            return;
        }
        final Step s = stepParsed.steps.get(stepCursor++);
        final boolean sd = send;
        running = true;
        stopFlag = false;
        playBtn.setEnabled(false);
        stepBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        new Thread(new Runnable() {
            public void run() {
                runStep(s, sd);
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        running = false;
                        playBtn.setEnabled(true);
                        stepBtn.setEnabled(true);
                        stopBtn.setEnabled(false);
                        statusLbl.setText("單步:第 " + s.line + " 行完成(" + stepCursor + " / " + stepParsed.steps.size() + ")。");
                    }
                });
            }
        }).start();
    }

    // ---------------- 畫面 ----------------
    static class Bars extends JComponent {
        Bars() { setPreferredSize(new Dimension(520, 300)); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = javax.swing.UIManager.getColor("Label.foreground");
            if (fg == null) fg = Color.BLACK;
            double[] p = poseNow();
            int w = getWidth(), h = getHeight();
            int left = 12, top = 14, bottom = 34;
            int ch = h - top - bottom;
            int slot = Math.max(12, (w - 2 * left) / N);
            int bw = Math.max(8, slot - 5);
            for (int m = 0; m < N; m++) {
                int x = left + m * slot;
                g.setColor(new Color(128, 128, 128, 60));
                g.fillRect(x, top, bw, ch);
                double v = Math.max(600, Math.min(2400, p[m]));
                int bh = (int) ((v - 600) / 1800.0 * ch);
                g.setColor(new Color(0x00, 0xB8, 0xD4));
                g.fillRect(x, top + ch - bh, bw, bh);
                g.setColor(fg);
                g.setFont(getFont().deriveFont(10f));
                String t = String.valueOf(m + 1);
                g.drawString(t, x + (bw - g.getFontMetrics().stringWidth(t)) / 2, top + ch + 13);
                String o = String.valueOf((int) Math.round(p[m] - 1500));
                g.setFont(getFont().deriveFont(9f));
                g.drawString(o, x + (bw - g.getFontMetrics().stringWidth(o)) / 2, top + ch + 26);
            }
            g.setColor(new Color(128, 128, 128, 170));
            g.drawLine(left, top + ch / 2, left + N * slot, top + ch / 2);
            g.dispose();
        }
    }

    static final String EXAMPLE =
          "// 一行一個動作:SetFrameRun(幀編號, 花幾毫秒);\n"
        + "// 幀的資料用 MotorAdj 目前載入的(「馬達參數」分頁「寫入」過的幀)\n"
        + "SetFrameRun(2, 100);\n"
        + "SetFrameRun(3, 100);\n"
        + "repeat 3 {\n"
        + "  SetFrameRun(4, 90);\n"
        + "  SetFrameRun(5, 110);\n"
        + "}\n"
        + "SetFrameRun(1, 150);\n";

    // ================= 從 custom.ino 載入 case =================

    static final String[] HELD = {"(沒有)", "UP", "DOWN", "LEFT", "RIGHT", "TRIANGLE", "CROSS", "CIRCLE", "SQUARE", "L1", "L2", "R1", "R2"};

    static boolean clauseVal(Blocks.Clause c, String held) {
        boolean none = held.startsWith("(");
        boolean v;
        if (c.kind.equals("KEY_NONE")) v = none;
        else if (c.kind.equals("KEY_ANY")) v = !none;
        else if (c.kind.equals("KEY_IS") || c.kind.equals("HOLD")) v = c.param.equals(held);
        else if (c.kind.equals("KEY_NONE_OR")) {
            v = none;
            for (String b : c.param.split(",")) if (b.trim().equals(held)) v = true;
        } else v = true;     // 搖桿方向、L3/R3、自訂條件:當作成立
        return c.not ? !v : v;
    }

    static boolean condVal(Blocks.Cond c, String held) {
        if (c.clauses.isEmpty()) return true;
        boolean r = !c.or;
        for (Blocks.Clause k : c.clauses) {
            boolean v = clauseVal(k, held);
            if (c.or) r = r || v; else r = r && v;
        }
        return r;
    }

    static void emit(StringBuilder sb, List<Blocks.Node> list, int d, int times, String held) {
        for (Blocks.Node n : list) {
            String pad = "";
            for (int i = 0; i < d; i++) pad += "  ";
            if (!n.enabled) continue;
            if (n.type.equals("ACTION")) sb.append(pad).append("SetFrameRun(").append(n.a.trim()).append(", ").append(n.b.trim()).append(");\n");
            else if (n.type.equals("WAIT")) sb.append(pad).append("delay(").append(n.a.trim()).append(");\n");
            else if (n.type.equals("REPEAT")) {
                sb.append(pad).append("repeat ").append(times).append(" {\n");
                emit(sb, n.body, d + 1, times, held);
                sb.append(pad).append("}\n");
            } else if (n.type.equals("IF")) {
                boolean yes = condVal(n.cond, held);
                sb.append(pad).append("// 如果 ").append(Blocks.condCode(n.cond)).append(" → ").append(yes ? "成立" : "不成立").append("\n");
                emit(sb, yes ? n.body : n.elseBody, d, times, held);
            } else if (n.type.equals("RELAX")) sb.append(pad).append("// (放鬆全部馬達)\n");
            else if (n.type.equals("CODE")) sb.append(pad).append("// (略過一段程式碼)\n");
            else if (n.type.equals("COMMENT")) { for (String l : n.a.split("\n", -1)) sb.append(pad).append("// ").append(l).append("\n"); }
        }
    }

    /** 把 custom.ino 的一個 case 轉成動作序列文字;回傳 null 表示讀不懂 */
    static String fromCase(String caseText, String label, int times, String held) {
        Blocks.Script s = Blocks.parse(caseText);
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(label).append("(按著的組合鍵:").append(held).append(";迴圈重複 ").append(times).append(" 次)\n");
        emit(sb, s.body, 0, times, held);
        return sb.toString();
    }

    static JComboBox<String> heldBox, speedBox;
    static JSpinner timesSp;
    static boolean derivedOk = true;
    static final String IDLE_TEXT = "播放的是左邊「選擇要修改的動作」目前的內容(改了馬上算進去,不用先寫入)。先只看模擬,確定了再勾選「送到機器人」。";

    static void setDerivedText(String t) {
        if (!t.equals(area.getText())) area.setText(t);
    }

    /**
     * 把「動作程式」分頁編輯框裡目前的內容(還沒寫回 custom.ino 的修改也算)展開成要播的步驟,放進 area(唯讀)。
     * 迴圈 → repeat N;「如果有按著…」依「按著的組合鍵」決定走哪個分支。成功回傳 true。
     */
    static boolean refreshDerived() {
        if (area == null || timesSp == null) return false;
        Object sel = CodeTab.combo == null ? null : CodeTab.combo.getSelectedItem();
        String label = sel == null ? "目前這一段" : String.valueOf(sel);
        String src = CodeTab.area == null ? "" : CodeTab.area.getText().replace("\r", "");
        String t = fromCase(src, label, ((Number) timesSp.getValue()).intValue(), (String) heldBox.getSelectedItem());
        if (t == null) {
            derivedOk = false;
            setDerivedText("// 這一段的格式看不懂,沒辦法模擬。\n// (左邊要選一個 case,內容要是 case ...: 開頭)");
            if (!running && statusLbl != null) statusLbl.setText("這一段的格式看不懂,沒辦法模擬(左邊要選一個 case)。");
            return false;
        }
        derivedOk = true;
        setDerivedText(t);
        if (!running && statusLbl != null && statusLbl.getText().startsWith("這一段的格式看不懂")) statusLbl.setText(IDLE_TEXT);
        return true;
    }

    static javax.swing.Timer refreshTimer;

    static void scheduleRefresh() {
        if (refreshTimer != null) refreshTimer.restart();
    }

    static boolean install() {
        JTabbedPane tabs = null;
        for (Frame f : Frame.getFrames()) {
            tabs = CodeTab.findTabs(f);
            if (tabs != null) break;
        }
        if (tabs == null) return false;

        // 展開後的步驟(唯讀):行號就是播放時反白、錯誤訊息裡說的那一行
        area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(2);
        area.getDocument().addDocumentListener(new DocumentListener() {
            void ch() { stepParsed = null; }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });

        playBtn = new JButton("▶ 播放");
        stopBtn = new JButton("■ 停止");
        stepBtn = new JButton("單步");
        JButton relax = new JButton("放鬆全部馬達");
        sendBox = new JCheckBox("送到機器人(馬達真的會動)", false);
        loopBox = new JCheckBox("循環播放", false);
        heldBox = new JComboBox<String>(HELD);
        timesSp = new JSpinner(new SpinnerNumberModel(2, 1, 50, 1));
        speedBox = new JComboBox<String>(new String[] {"原速", "慢 2 倍", "慢 5 倍", "慢 10 倍"});
        javax.swing.JTextArea st = new javax.swing.JTextArea(IDLE_TEXT, 3, 24);
        st.setLineWrap(true);
        st.setWrapStyleWord(true);
        st.setEditable(false);
        st.setOpaque(false);
        st.setFocusable(false);
        st.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusLbl = st;
        stopBtn.setEnabled(false);
        relax.setToolTipText("緊急用:馬上停止播放,並把所有馬達放鬆");
        sendBox.setToolTipText("勾選後,每一幀都會真的送給機器人。要先到「設定」按「串口連接」、「馬達參數」按「開啟馬達」。");
        heldBox.setToolTipText("如果這一段裡有「如果有按著 L2…」之類的判斷,選你要模擬按著哪一個鍵");
        timesSp.setToolTipText("「按住就重複」的迴圈,模擬時跑幾輪");
        speedBox.setToolTipText("只影響畫面模擬(慢動作,方便看很短的動作);「送到機器人」時一律原速");
        sendBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            if (sendBox.isSelected() && !License.require("送到機器人")) sendBox.setSelected(false);
        } });
        playBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { play(); } });
        stopBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { stop(); } });
        stepBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { stepOnce(); } });
        relax.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { relaxAll(); } });

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row1.add(playBtn);
        row1.add(stopBtn);
        row1.add(stepBtn);
        row1.add(relax);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row2.add(sendBox);
        row2.add(loopBox);
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row3.add(new JLabel("按著的組合鍵:"));
        row3.add(heldBox);
        row3.add(new JLabel("迴圈輪數:"));
        row3.add(timesSp);
        row3.add(new JLabel("模擬速度:"));
        row3.add(speedBox);
        JPanel controls = new JPanel();
        controls.setLayout(new javax.swing.BoxLayout(controls, javax.swing.BoxLayout.Y_AXIS));
        for (JPanel r : new JPanel[] {row1, row2, row3}) { r.setAlignmentX(0f); controls.add(r); }
        st.setAlignmentX(0f);
        controls.add(st);

        bars = new Bars();
        JPanel barBox = new JPanel(new BorderLayout());
        barBox.setBorder(BorderFactory.createTitledBorder("26 顆馬達的位置(長條 = 位置,下面的數字 = 相對 1500)"));
        barBox.add(bars, BorderLayout.CENTER);

        JScrollPane stepsSp = new JScrollPane(area);
        stepsSp.setBorder(BorderFactory.createTitledBorder("實際會播的步驟"));
        final JList<String> logList = new JList<String>(logModel);
        logList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logModel.addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded(javax.swing.event.ListDataEvent e) { logList.ensureIndexIsVisible(logModel.size() - 1); }
            public void intervalRemoved(javax.swing.event.ListDataEvent e) { }
            public void contentsChanged(javax.swing.event.ListDataEvent e) { }
        });
        JScrollPane logSp = new JScrollPane(logList);
        JButton clear = new JButton("清除記錄");
        clear.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { logModel.clear(); } });
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.setBorder(BorderFactory.createTitledBorder("播放記錄"));
        logBox.add(logSp, BorderLayout.CENTER);
        logBox.add(clear, BorderLayout.SOUTH);
        JSplitPane bottomSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stepsSp, logBox);
        bottomSplit.setResizeWeight(0.5);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, barBox, bottomSplit);
        right.setResizeWeight(0.6);

        JPanel sim = new JPanel(new BorderLayout());
        sim.add(controls, BorderLayout.NORTH);
        sim.add(right, BorderLayout.CENTER);

        // 放進「動作程式」分頁:左邊是程式編輯,右邊是模擬;找不到那個分頁才另外開一個「動作序列」分頁
        tabPanel = null;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (!"動作程式".equals(tabs.getTitleAt(i))) continue;
            java.awt.Component host = tabs.getComponentAt(i);
            if (host instanceof JPanel && ((JPanel) host).getLayout() instanceof BorderLayout) {
                BorderLayout bl = (BorderLayout) ((JPanel) host).getLayout();
                java.awt.Component center = bl.getLayoutComponent(BorderLayout.CENTER);
                if (center != null) {
                    ((JPanel) host).remove(center);
                    final JSplitPane both = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, sim);
                    both.setResizeWeight(0.5);
                    both.setContinuousLayout(true);
                    ((JComponent) center).setMinimumSize(new Dimension(280, 100));
                    sim.setMinimumSize(new Dimension(380, 200));
                    // 第一次顯示出來、有寬度時,把分隔線放在正中間
                    both.addComponentListener(new java.awt.event.ComponentAdapter() {
                        boolean done = false;
                        public void componentResized(java.awt.event.ComponentEvent e) {
                            if (done || both.getWidth() < 400) return;
                            done = true;
                            SwingUtilities.invokeLater(new Runnable() { public void run() { both.setDividerLocation(0.5); } });
                        }
                    });
                    ((JPanel) host).add(both, BorderLayout.CENTER);
                    tabPanel = (JPanel) host;
                    ((JPanel) host).revalidate();
                }
            }
            break;
        }
        if (tabPanel == null) {
            tabPanel = sim;
            tabs.addTab("動作序列", sim);
        }

        // 左邊的程式、選的動作、組合鍵、輪數有變,就重新展開(稍微等一下,打字時不要一直算)
        refreshTimer = new javax.swing.Timer(250, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { if (!running) refreshDerived(); }
        });
        refreshTimer.setRepeats(false);
        if (CodeTab.area != null) {
            CodeTab.area.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { scheduleRefresh(); }
                public void removeUpdate(DocumentEvent e) { scheduleRefresh(); }
                public void changedUpdate(DocumentEvent e) { scheduleRefresh(); }
            });
        }
        if (CodeTab.combo != null) {
            CodeTab.combo.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { scheduleRefresh(); } });
        }
        heldBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { if (!running) refreshDerived(); } });
        timesSp.addChangeListener(new javax.swing.event.ChangeListener() { public void stateChanged(javax.swing.event.ChangeEvent e) { if (!running) refreshDerived(); } });
        refreshDerived();

        // 起始位置:全部放在中心(1500)
        for (int m = 0; m < N; m++) { from[m] = 1500; to[m] = 1500; tm[m] = 1; }
        new javax.swing.Timer(33, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { if (tabPanel.isShowing()) bars.repaint(); }
        }).start();
        return true;
    }
}
