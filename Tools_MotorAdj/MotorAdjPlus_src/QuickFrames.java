import java.awt.Container;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * 「馬達參數」分頁右上角的「快速測試動作」:
 *   強制讓機器人去某一幀(兩個格子 A、B,想去哪個按哪個),但不碰你正在編輯的那一幀
 *   (畫面上的數值、捲軸、「寫入」的內容都不變),可以來回按來測穩定性。
 * 送的是 MotorAdj 目前載入的幀資料(「寫入」過的),不是畫面上還沒寫入的修改。
 * 送出的方式和「動作程式」分頁的模擬一樣(SequenceTab.sendFrame:暫借工作區、送完還原)。
 */
public class QuickFrames {
    static JSpinner spA, spB;
    static JButton sendA, sendB;
    static JLabel status;
    static boolean warned = false;

    interface Sender { long send(int f) throws Exception; }
    /** 真正送出的動作(測試時可以換掉) */
    static Sender sender = new Sender() { public long send(int f) throws Exception { return SequenceTab.sendFrame(f); } };

    static JLabel label(String t, int x, int y, int w, int h, JPanel p) {
        JLabel l = new JLabel(t);
        l.setBounds(x, y, w, h);
        p.add(l);
        return l;
    }

    static void setStatus(final String s) {
        SwingUtilities.invokeLater(new Runnable() { public void run() { status.setText(s); } });
    }

    /** 送前檢查:有連線、幀存在、沒有別的在播。回傳 null 表示可以送,否則是要顯示的原因 */
    static String problem(int f) {
        if (SequenceTab.running) return "「動作程式」分頁正在播放,先按停止。";
        int[][][] d = SequenceTab.frameData();
        if (d == null) return "找不到 MotorAdj 的幀資料。";
        if (f < 0 || f >= d.length) return "幀 " + f + " 不存在(目前有 0 ~ " + (d.length - 1) + ")。";
        if (!SequenceTab.robotConnected()) return "機器人還沒連線:先到「設定」按「串口連接」,再按「開啟馬達」。";
        if (!License.unlocked()) return "需要教師金鑰。";
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

    static int val(JSpinner s) { return ((Number) s.getValue()).intValue(); }

    /** 讓機器人去那一幀。在背景送(送一幀約 40 毫秒,不要卡住畫面) */
    static void go(final String name, final int f) {
        String why = problem(f);
        if (why != null) { status.setText(why); return; }
        if (!confirmOnce()) return;
        status.setText("送出 " + name + "(幀 " + f + ")…");
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

    /** panel:「馬達參數」分頁(固定座標)。放在右上角的空白處 */
    static void install(Container panel) {
        final JPanel p = new JPanel(null);
        p.setBorder(BorderFactory.createTitledBorder("快速測試動作(不會改到正在編輯的)"));
        p.setBounds(700, 4, 330, 104);
        p.setOpaque(false);
        p.setToolTipText("讓機器人直接去某一幀,但不碰你正在編輯的畫面;兩個格子來回按,測穩定性。送的是已「寫入」的資料。");

        spA = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
        spB = new JSpinner(new SpinnerNumberModel(1, 0, 999, 1));
        sendA = new JButton("去這一幀");
        sendB = new JButton("去這一幀");
        status = new JLabel("先到「設定」連線、按「開啟馬達」。");
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));

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

        sendA.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { go("A", val(spA)); } });
        sendB.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { go("B", val(spB)); } });
        panel.add(p);
    }
}
