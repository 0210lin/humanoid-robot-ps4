import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;

/**
 * 「鏡像」分頁 + 「馬達參數」分頁的鏡像按鈕。
 * 鏡像只改畫面上的工作資料(MotorPosDataTmp),不寫入任何幀;
 * 要存起來,請自己輸入幀編號再按原本的「寫入」。
 * 反向的中心固定 1500(新值 = 3000 - 對方的值),偏掉的部分請用「馬達偏移量」調整。
 */
public class MirrorTab {

    static final int CENTER2 = 3000;           // 2 × 1500
    static final String[] DIRS = {"同向", "反向"};
    static final String[] MODES = {"互換", "A→B", "B→A"};

    static JTable table;
    static DefaultTableModel model;
    static boolean loading = false;

    // ================= 設定(存在 MotorAdjPlus.properties) =================

    /** 一列:啟用, A, B, 方向, 模式 */
    static void loadRows() {
        loading = true;
        model.setRowCount(0);
        int n = 0;
        try { n = Integer.parseInt(Settings.raw("mirror.count")); } catch (Exception e) { }
        for (int i = 0; i < n; i++) {
            String[] f = Settings.raw("mirror." + i).split(",");
            if (f.length < 5) continue;
            try {
                model.addRow(new Object[] {
                    Boolean.valueOf(f[3].equals("1")),
                    Integer.valueOf(f[0]), Integer.valueOf(f[1]),
                    f[2].equals("R") ? DIRS[1] : DIRS[0],
                    f[4].equals("AB") ? MODES[1] : f[4].equals("BA") ? MODES[2] : MODES[0]
                });
            } catch (Exception e) { }
        }
        loading = false;
    }

    static void saveRows() {
        if (loading) return;
        int n = model.getRowCount();
        Settings.p.setProperty("mirror.count", String.valueOf(n));
        for (int i = 0; i < n; i++) {
            String mode = model.getValueAt(i, 4).equals(MODES[1]) ? "AB" : model.getValueAt(i, 4).equals(MODES[2]) ? "BA" : "S";
            Settings.p.setProperty("mirror." + i, model.getValueAt(i, 1) + "," + model.getValueAt(i, 2) + ","
                    + (model.getValueAt(i, 3).equals(DIRS[1]) ? "R" : "S") + ","
                    + (Boolean.TRUE.equals(model.getValueAt(i, 0)) ? "1" : "0") + "," + mode);
        }
        try { Settings.save(); } catch (Exception e) { e.printStackTrace(); }
    }

    // ================= 鏡像運算 =================

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 對畫面上的工作資料(Tmp)套用所有啟用的配對,並重新整理畫面。回傳訊息(給提示用) */
    static String apply(Object outer) throws Exception {
        Object ms = Plus.fld(outer, "MotorSet");
        int[][] tmp = (int[][]) Plus.fld(ms, "MotorPosDataTmp");
        int min = ((Integer) Plus.fld(ms, "MotorPosMin")).intValue();
        int max = ((Integer) Plus.fld(ms, "MotorPosMax")).intValue();
        int n = tmp.length;

        // 取出所有啟用的配對,檢查有沒有同一顆馬達出現在兩組
        List<int[]> pairs = new ArrayList<int[]>();   // {a, b, reverse(0/1), mode(0互換/1 A→B/2 B→A)}
        boolean[] used = new boolean[n + 1];
        StringBuilder dup = new StringBuilder();
        for (int i = 0; i < model.getRowCount(); i++) {
            if (!Boolean.TRUE.equals(model.getValueAt(i, 0))) continue;
            int a = ((Integer) model.getValueAt(i, 1)).intValue();
            int b = ((Integer) model.getValueAt(i, 2)).intValue();
            if (a < 1 || b < 1 || a > n || b > n) continue;
            int rev = model.getValueAt(i, 3).equals(DIRS[1]) ? 1 : 0;
            int mode = model.getValueAt(i, 4).equals(MODES[1]) ? 1 : model.getValueAt(i, 4).equals(MODES[2]) ? 2 : 0;
            if (used[a] && a != b) dup.append(a).append(' ');
            if (used[b] && a != b) dup.append(b).append(' ');
            used[a] = true;
            used[b] = true;
            pairs.add(new int[] {a - 1, b - 1, rev, mode});
        }
        if (dup.length() > 0) {
            return "馬達 " + dup.toString().trim() + " 出現在不只一組啟用的配對裡,結果會不確定。請先調整配對,這次沒有做任何修改。";
        }
        if (pairs.isEmpty()) {
            return "沒有啟用的配對。請先到「鏡像」分頁新增並勾選「啟用」。";
        }

        // 用「按下前」的快照計算,避免先改的影響後改的
        int[][] snap = new int[n][];
        for (int i = 0; i < n; i++) snap[i] = tmp[i].clone();

        for (int[] p : pairs) {
            int i = p[0], j = p[1], rev = p[2], mode = p[3];
            if (i == j) {
                // 同一顆馬達:反向 = 自己左右反轉;同向沒有作用
                int[] li = Limits.absRange(i, min, max);
                if (rev == 1) tmp[i][1] = clamp(CENTER2 - snap[i][1], li[0], li[1]);
                continue;
            }
            if (mode == 0 || mode == 2) setFrom(tmp[i], snap[j], rev, Limits.absRange(i, min, max));   // A 取 B 的鏡像
            if (mode == 0 || mode == 1) setFrom(tmp[j], snap[i], rev, Limits.absRange(j, min, max));   // B 取 A 的鏡像
        }

        // 重新整理畫面(也會把目前姿勢送給機器人)
        java.lang.reflect.Method m = outer.getClass().getDeclaredMethod("position_upgradeFrame_byTmpFrame");
        m.setAccessible(true);
        m.invoke(outer);
        return null;
    }

    /** dst 取 src 的內容:啟用、時間照抄;位置同向照抄、反向用 3000 - 位置 */
    static void setFrom(int[] dst, int[] src, int rev, int[] lim) {
        dst[0] = src[0];
        dst[1] = clamp(rev == 1 ? CENTER2 - src[1] : src[1], lim[0], lim[1]);
        dst[2] = src[2];
    }

    /** 「馬達參數」分頁的鏡像按鈕被按下 */
    static void onMirrorButton() {
        try {
            Object outer = Plus.outer();
            if (outer == null) {
                JOptionPane.showMessageDialog(null, "找不到 MotorAdj 的內部資料,無法鏡像。");
                return;
            }
            String msg = apply(outer);
            if (msg != null) {
                JOptionPane.showMessageDialog(null, msg, "鏡像", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(null, "鏡像失敗:" + t);
        }
    }

    // ================= 分頁畫面 =================

    static boolean install() {
        JTabbedPane tabs = null;
        for (Frame f : Frame.getFrames()) {
            tabs = CodeTab.findTabs(f);
            if (tabs != null) break;
        }
        if (tabs == null) return false;

        model = new DefaultTableModel(new Object[] {"啟用", "馬達 A", "馬達 B", "方向", "模式"}, 0) {
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : c == 1 || c == 2 ? Integer.class : String.class;
            }
        };
        table = new JTable(model);
        table.setRowHeight(26);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        Integer[] nums = new Integer[26];
        for (int i = 0; i < 26; i++) nums[i] = Integer.valueOf(i + 1);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<Integer>(nums)));
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<Integer>(nums)));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<String>(DIRS)));
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JComboBox<String>(MODES)));
        table.getColumnModel().getColumn(0).setMaxWidth(70);

        JLabel help = new JLabel("<html><b>設定哪兩顆馬達要鏡像。</b>設好後,到「馬達參數」分頁按「鏡像」按鈕,"
                + "<b>畫面上這一幀</b>的參數就會照下表變成鏡像(機器人連著的話,馬達也會跟著動)。<br>"
                + "<b>不會寫入</b>:請自己輸入要存的幀編號,再按原本的「寫入」。不滿意可以按「讀出」還原。<br><br>"
                + "・<b>方向</b>:同向 = 位置照抄;反向 = 以 1500 為中心反轉(新位置 = 3000 - 對方的位置)。偏掉的部分請用「馬達偏移量」調。<br>"
                + "・<b>模式</b>:互換 = A、B 對調;A→B = 只有 B 變成 A 的鏡像;B→A = 只有 A 變成 B 的鏡像。啟用與時間(速度)會一起帶過去。<br>"
                + "・<b>單顆自己鏡像</b>:A、B 選同一顆、方向選「反向」,這一顆會自己左右反轉。<br>"
                + "・同一顆馬達請不要出現在兩組啟用的配對裡。馬達編號 = 畫面上的 Ch 編號。</html>");
        help.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JButton add = new JButton("新增配對");
        JButton del = new JButton("刪除選取的配對");
        JButton clear = new JButton("清除全部");
        btns.add(add);
        btns.add(del);
        btns.add(clear);

        JPanel p = new JPanel(new BorderLayout());
        p.add(help, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        p.add(btns, BorderLayout.SOUTH);
        tabs.addTab("鏡像", p);

        model.addTableModelListener(new TableModelListener() {
            public void tableChanged(TableModelEvent e) { saveRows(); }
        });
        add.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                model.addRow(new Object[] {Boolean.TRUE, Integer.valueOf(1), Integer.valueOf(2), DIRS[0], MODES[0]});
            }
        });
        del.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table.isEditing()) table.getCellEditor().stopCellEditing();
                int[] rows = table.getSelectedRows();
                for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
            }
        });
        clear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (model.getRowCount() > 0 && JOptionPane.showConfirmDialog(null, "清除全部配對?", "鏡像",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) model.setRowCount(0);
            }
        });
        loadRows();
        return true;
    }
}
