import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * 「快速測試動作」:強制讓機器人去某一幀(兩個格子 A、B,想去哪個按哪個),但不碰你正在編輯的那一幀
 * (畫面上的數值、捲軸、「寫入」的內容都不變),可以來回按來測穩定性。
 * 每個分頁都有一份:A / B 的幀號和下面那行提示在所有分頁共用(在一個分頁改了,別的分頁也跟著變)。
 * 送的是 MotorAdj 目前載入的幀資料(「寫入」過的),不是畫面上還沒寫入的修改。
 * 送出的方式和「動作程式」分頁的模擬一樣(SequenceTab.sendFrame:暫借工作區、送完還原)。
 */
public class QuickFrames {
    // 所有分頁共用
    static final SpinnerNumberModel modelA = new SpinnerNumberModel(0, 0, 999, 1);
    static final SpinnerNumberModel modelB = new SpinnerNumberModel(1, 0, 999, 1);
    static final List<JLabel> statusLabels = new ArrayList<JLabel>();
    static String statusText = "先到「設定」連線、按「開啟馬達」。";
    static boolean warned = false;

    // 舊程式(測試)用的:第一份(馬達參數分頁)的元件
    static JSpinner spA, spB;
    static JButton sendA, sendB;
    static JLabel status;

    interface Sender { long send(int f) throws Exception; }
    /** 真正送出的動作(測試時可以換掉) */
    static Sender sender = new Sender() { public long send(int f) throws Exception { return SequenceTab.sendFrame(f); } };

    static void setText(String s) {
        statusText = s;
        for (JLabel l : statusLabels) l.setText(s);
    }

    static void setStatus(final String s) {
        SwingUtilities.invokeLater(new Runnable() { public void run() { setText(s); } });
    }

    /** 連模擬都做不了的原因(在播放、沒有幀資料、幀不存在)。null = 可以 */
    static String problem(int f) {
        if (SequenceTab.running) return "「動作程式」分頁正在播放,先按停止。";
        int[][][] d = SequenceTab.frameData();
        if (d == null) return "找不到 MotorAdj 的幀資料。";
        if (f < 0 || f >= d.length) return "幀 " + f + " 不存在(目前有 0 ~ " + (d.length - 1) + ")。";
        return null;
    }

    /** 不能送給真機的原因(沒連線、沒金鑰)。null = 可以送。不能送時還是會在 3D 模擬裡顯示 */
    static String sendProblem() {
        if (!SequenceTab.robotConnected()) return "機器人沒連線,只在模擬裡顯示。要讓真機動:先到「設定」按「串口連接」,再按「開啟馬達」。";
        if (!License.unlocked()) return "需要教師金鑰才能送給真機,只在模擬裡顯示。";
        return null;
    }

    static boolean confirmOnce() {
        if (warned) return true;
        int ans = JOptionPane.showConfirmDialog(null,
                "會把你指定的幀真的送給機器人,機器人會動。請確認:\n・機器人已經架空或周圍安全\n・手隨時可以按「放鬆全部馬達」\n\n"
                + "(送的是已經「寫入」的資料,不會動到你正在編輯的畫面)",
                "快速測試動作", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return false;
        warned = true;
        return true;
    }

    static int val(SpinnerNumberModel m) { return ((Number) m.getValue()).intValue(); }

    /** 去那一幀:一定先在 3D 模擬裡顯示;機器人有連線(而且有金鑰)才再送給真機。送一幀約 40 毫秒,在背景送 */
    static void go(final String name, final int f) {
        String why = problem(f);
        if (why != null) { setText(why); return; }
        SequenceTab.beginFrame(f);                      // 3D 模擬馬上開始走到那一幀(時間 = 那一幀的速度欄)
        String noSend = sendProblem();
        if (noSend != null) { setText(name + "(幀 " + f + "):" + noSend); return; }
        if (!confirmOnce()) { setText(name + "(幀 " + f + "):只在模擬裡顯示,沒有送給真機。"); return; }
        setText("送出 " + name + "(幀 " + f + ")…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    long ms = sender.send(f);
                    setStatus("機器人已經去 " + name + "(幀 " + f + ")了(送出花 " + ms + " 毫秒)。你正在編輯的畫面沒有被改動。");
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    setStatus(c.getMessage() != null && c.getMessage().startsWith("送出失敗") ? c.getMessage() : "送出失敗:" + c.getMessage());
                }
            }
        }, "QuickFrames-go").start();
    }

    static JLabel label(String t, int x, int y, int w, int h, JPanel p) {
        JLabel l = new JLabel(t);
        l.setBounds(x, y, w, h);
        p.add(l);
        return l;
    }

    static JLabel newStatus() {
        JLabel l = new JLabel(statusText);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabels.add(l);
        return l;
    }

    static java.awt.event.ActionListener goA() {
        return new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { go("A", val(modelA)); } };
    }

    static java.awt.event.ActionListener goB() {
        return new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { go("B", val(modelB)); } };
    }

    // ---------- 大的(馬達參數分頁右上角,固定座標) ----------

    static void install(Container panel) {
        final JPanel p = new JPanel(null);
        p.setBorder(BorderFactory.createTitledBorder("快速測試動作(不會改到正在編輯的)"));
        p.setBounds(700, 4, 330, 104);
        p.setOpaque(false);
        p.setToolTipText("讓機器人直接去某一幀,但不碰你正在編輯的畫面;兩個格子來回按,測穩定性。送的是已「寫入」的資料。");

        spA = new JSpinner(modelA);
        spB = new JSpinner(modelB);
        sendA = new JButton("去這一幀");
        sendB = new JButton("去這一幀");
        status = newStatus();

        label("A 幀:", 10, 18, 40, 24, p);
        spA.setBounds(52, 18, 70, 24);
        p.add(spA);
        sendA.setBounds(130, 18, 110, 24);
        p.add(sendA);
        label("B 幀:", 10, 46, 40, 24, p);
        spB.setBounds(52, 46, 70, 24);
        p.add(spB);
        sendB.setBounds(130, 46, 110, 24);
        p.add(sendB);
        status.setBounds(10, 74, 312, 24);
        p.add(status);

        sendA.addActionListener(goA());
        sendB.addActionListener(goB());
        panel.add(p);
    }

    // ---------- 其他分頁 ----------

    /** 固定座標分頁用的小方塊(380 x 66) */
    static JPanel buildSmallBlock() {
        JPanel p = new JPanel(null);
        p.setBorder(BorderFactory.createTitledBorder("快速到幀(不改到正在編輯的)"));
        p.setOpaque(false);
        p.setSize(380, 66);
        p.setToolTipText("讓機器人直接去某一幀,不碰你正在編輯的畫面。送的是已「寫入」的資料。");
        JSpinner a = new JSpinner(modelA), b = new JSpinner(modelB);
        JButton ga = new JButton("去"), gb = new JButton("去");
        label("A:", 8, 16, 20, 22, p);
        a.setBounds(28, 16, 60, 22);
        p.add(a);
        ga.setBounds(92, 16, 52, 22);
        p.add(ga);
        label("B:", 160, 16, 20, 22, p);
        b.setBounds(180, 16, 60, 22);
        p.add(b);
        gb.setBounds(244, 16, 52, 22);
        p.add(gb);
        JLabel st = newStatus();
        st.setBounds(8, 40, 364, 20);
        p.add(st);
        ga.addActionListener(goA());
        gb.addActionListener(goB());
        return p;
    }

    /** 上下排列版面的分頁用的一整排 */
    static JPanel buildRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        p.setOpaque(false);
        p.setToolTipText("讓機器人直接去某一幀,不碰你正在編輯的畫面。送的是已「寫入」的資料。");
        JSpinner a = new JSpinner(modelA), b = new JSpinner(modelB);
        JButton ga = new JButton("去"), gb = new JButton("去");
        p.add(new JLabel("快速到幀  A:"));
        p.add(a);
        p.add(ga);
        p.add(new JLabel("B:"));
        p.add(b);
        p.add(gb);
        p.add(newStatus());
        ga.addActionListener(goA());
        gb.addActionListener(goB());
        return p;
    }

    /** 在固定座標的面板裡,找一塊 w x h 的空地(先找右上角,再往左、往下找)。找不到回傳 null */
    static Rectangle freeSpot(Container panel, int w, int h, int designW, int designH) {
        for (int y = 4; y + h <= designH; y += 4) {
            for (int x = designW - w - 8; x >= 4; x -= 4) {
                Rectangle r = new Rectangle(x - 4, y - 4, w + 8, h + 8);
                boolean ok = true;
                for (Component c : panel.getComponents()) {
                    if (!c.isVisible()) continue;
                    if (c.getBounds().width > designW - 40 && c.getBounds().height > designH - 60) continue;     // 整頁大小的背景容器
                    if (r.intersects(c.getBounds())) { ok = false; break; }
                }
                if (ok) return new Rectangle(x, y, w, h);
            }
        }
        return null;
    }

    /**
     * 把「快速到幀」放進每個分頁(「馬達參數」分頁已經有大的,不再放)。要在所有分頁都建好、Center.install 之前呼叫。
     */
    static void installEverywhere() {
        JTabbedPane tabs = null;
        for (java.awt.Frame f : java.awt.Frame.getFrames()) {
            tabs = CodeTab.findTabs(f);
            if (tabs != null) break;
        }
        if (tabs == null) return;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component c = tabs.getComponentAt(i);
            if (!(c instanceof JPanel)) continue;
            JPanel root = (JPanel) c;
            String title = tabs.getTitleAt(i);
            try {
                if (root.getLayout() == null) {
                    // 固定座標分頁:「馬達參數」已經有大的了
                    boolean hasBig = false;
                    for (Component k : root.getComponents()) if (k instanceof JPanel && ((JPanel) k).getBorder() != null && k.getBounds().width == 330 && k.getBounds().height == 104) hasBig = true;
                    if (hasBig) continue;
                    JPanel blk = buildSmallBlock();
                    Rectangle r = freeSpot(root, blk.getWidth(), blk.getHeight(), 1040, 628);
                    if (r == null) { System.err.println("QuickFrames:「" + title + "」分頁找不到空位,沒有加上快速到幀"); continue; }
                    blk.setBounds(r);
                    root.add(blk);
                    root.repaint();
                } else if (root.getLayout() instanceof BorderLayout) {
                    BorderLayout bl = (BorderLayout) root.getLayout();
                    Component north = bl.getLayoutComponent(BorderLayout.NORTH);
                    JPanel row = buildRow();
                    if (north == null) {
                        root.add(row, BorderLayout.NORTH);
                    } else if (north instanceof JPanel && ((JPanel) north).getLayout() instanceof FlowLayout) {
                        ((JPanel) north).add(row);
                    } else {
                        root.remove(north);
                        JPanel wrap = new JPanel(new BorderLayout());
                        wrap.add(north, BorderLayout.CENTER);
                        wrap.add(row, BorderLayout.SOUTH);
                        root.add(wrap, BorderLayout.NORTH);
                    }
                    root.revalidate();
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
