import java.awt.Container;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JScrollBar;

/**
 * 馬達參數分頁的捲軸微調:
 *   ・每條馬達捲軸左右兩端各有一顆明顯的「-」「+」按鈕:按一下 ±N(預設 10),按住不放會連續移動
 *   ・點捲軸中間的空白軌道:一次 ±M(預設 100)
 * 兩個數字都可以在畫面左側的下拉選單修改,會記住。
 */
public class Steps {

    static final Integer[] UNIT_CHOICES = {1, 5, 10, 20, 30};
    static final Integer[] BLOCK_CHOICES = {10, 20, 30, 50, 100, 200};
    static final int BTN = 18;      // 按鈕大小(像素):和捲軸差不多高,比例才協調
    static final int BAR_H = 14;    // 捲軸高度(和按鈕上下置中)
    static final int GAP = 3;       // 按鈕與捲軸的間隔

    static List<JScrollBar> bars = new ArrayList<JScrollBar>();
    static List<JButton> minus = new ArrayList<JButton>();
    static List<JButton> plus = new ArrayList<JButton>();

    static int unit()  { return Background.readInt("posUnit", 10, 1, 500); }
    static int block() { return Background.readInt("posBlock", 100, 1, 1000); }

    static void apply() {
        int u = unit(), b = block();
        for (int i = 0; i < bars.size(); i++) {
            JScrollBar sb = bars.get(i);
            sb.setUnitIncrement(u);
            sb.setBlockIncrement(b);
            sb.setToolTipText("左右的 -/+ 按鈕:一次 ±" + u + ";點空白軌道:一次 ±" + b);
            minus.get(i).setToolTipText("-" + u + "(按住可連續移動)");
            plus.get(i).setToolTipText("+" + u + "(按住可連續移動)");
        }
    }

    /** 做一顆按住會連續觸發的 +/- 按鈕 */
    static JButton stepButton(String text, final JScrollBar sb, final int dir) {
        final JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "roundRect");
        b.setEnabled(sb.isEnabled());
        paint(b);

        final javax.swing.Timer repeat = new javax.swing.Timer(80, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                sb.setValue(sb.getValue() + dir * sb.getUnitIncrement());
            }
        });
        repeat.setInitialDelay(400);
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (!b.isEnabled()) return;
                sb.setValue(sb.getValue() + dir * sb.getUnitIncrement());   // 先動一次
                repeat.start();                                              // 按住不放就連續動
            }
            public void mouseReleased(java.awt.event.MouseEvent e) { repeat.stop(); }
            public void mouseExited(java.awt.event.MouseEvent e) { repeat.stop(); }
        });
        // 捲軸被啟用 / 關閉時(開啟、關閉馬達),按鈕也跟著
        sb.addPropertyChangeListener("enabled", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                b.setEnabled(sb.isEnabled());
                paint(b);
            }
        });
        return b;
    }

    /** 啟用時:跟啟用的捲軸同色(藍底白字);沒啟用時:淡灰色,和灰色的捲軸一致 */
    static void paint(JButton b) {
        if ("classic".equals(Theme.current)) {
            b.setBackground(null);
            b.setForeground(null);
            return;
        }
        if (b.isEnabled()) {
            b.setBackground(Theme.ACCENT);
            b.setForeground(java.awt.Color.WHITE);
        } else {
            b.setBackground(Theme.trackOff());
            b.setForeground(new java.awt.Color(0x8A, 0x92, 0x9E));
        }
    }

    /** 外觀切換後(淺色 / 深色 / 原本),重新上色 */
    static void repaintAll() {
        for (JButton b : minus) paint(b);
        for (JButton b : plus) paint(b);
    }

    /** 一列:左邊說明文字(垂直置中)、右邊下拉選單,兩者同高同 y */
    static void addRow(Container panel, String text, JComboBox<Integer> box, String tip,
                       int x, int labelW, int comboX, int comboW, int y, int h) {
        JLabel l = new JLabel(text);
        l.setBounds(x, y, labelW, h);
        l.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
        panel.add(l);
        box.setBounds(comboX, y, comboW, h);
        box.setToolTipText(tip);
        panel.add(box);
    }

    static JComboBox<Integer> combo(Integer[] choices, int cur, final String key) {
        final JComboBox<Integer> box = new JComboBox<Integer>(choices);
        // 選單裡顯示「±10」這樣的字,不用在說明文字後面掛一個「±」
        box.setRenderer(new javax.swing.DefaultListCellRenderer() {
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean sel, boolean foc) {
                java.awt.Component c = super.getListCellRendererComponent(list, value, index, sel, foc);
                ((JLabel) c).setText("± " + value);
                return c;
            }
        });
        box.setSelectedItem(Integer.valueOf(cur));
        if (!Integer.valueOf(cur).equals(box.getSelectedItem())) {
            box.addItem(Integer.valueOf(cur));
            box.setSelectedItem(Integer.valueOf(cur));
        }
        box.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Object v = box.getSelectedItem();
                if (v instanceof Integer) {
                    Settings.p.setProperty(key, String.valueOf(v));
                    try { Settings.save(); } catch (Exception ex) { ex.printStackTrace(); }
                    apply();
                }
            }
        });
        return box;
    }

    /** panel:「馬達參數」分頁的面板(座標排版) */
    static void install(Container panel) {
        bars.clear();
        minus.clear();
        plus.clear();
        List<JScrollBar> found = new ArrayList<JScrollBar>();
        for (java.awt.Component c : panel.getComponents()) {
            if (c instanceof JScrollBar) {
                found.add((JScrollBar) c);
            }
        }
        for (JScrollBar sb : found) {
            Rectangle r = sb.getBounds();
            // 捲軸縮短,左右各讓出一顆按鈕的位置
            sb.setBounds(r.x + BTN + GAP, r.y + (BTN - BAR_H) / 2, r.width - 2 * (BTN + GAP), BAR_H);
            sb.putClientProperty("JScrollBar.showButtons", Boolean.FALSE);   // 內建的小箭頭不要了
            JButton m = stepButton("-", sb, -1);
            JButton p = stepButton("+", sb, +1);
            m.setBounds(r.x, r.y, BTN, BTN);
            p.setBounds(r.x + r.width - BTN, r.y, BTN, BTN);
            panel.add(m);
            panel.add(p);
            bars.add(sb);
            minus.add(m);
            plus.add(p);
        }
        apply();

        // 版面:標題、兩列(說明文字 + 下拉選單)。文字與選單同高、垂直置中,左右對齊
        final int X = 30, LABEL_W = 112, COMBO_X = 148, COMBO_W = 76, ROW_H = 26;

        JLabel title = new JLabel("捲軸微調");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBounds(X, 366, 200, 22);
        panel.add(title);

        addRow(panel, "「-」「+」按鈕", combo(UNIT_CHOICES, unit(), "posUnit"),
                "按捲軸兩端的 -/+ 按鈕時,一次移動多少", X, LABEL_W, COMBO_X, COMBO_W, 392, ROW_H);
        addRow(panel, "點空白軌道", combo(BLOCK_CHOICES, block(), "posBlock"),
                "點捲軸中間的空白軌道時,一次移動多少", X, LABEL_W, COMBO_X, COMBO_W, 424, ROW_H);

        panel.repaint();
    }
}
