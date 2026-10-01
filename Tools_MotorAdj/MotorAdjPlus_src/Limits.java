import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import javax.swing.BoundedRangeModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableModel;

/**
 * 每一顆馬達各自的位置範圍(最小 / 最大)。
 *   ・預設 = 「設定」分頁的全域範圍(通常是 ±900,以中心 1500 為 0)
 *   ・每顆可以個別縮小;數值跟主畫面一樣是「相對中心」的數字(-900 ~ 900)
 *   ・用主畫面的捲軸、「-」「+」、直接輸入數字、鏡像時,都不會超出該顆的範圍
 *   ・人偶模式裡不限制(要讓你用手擺到極限位置),所以可以先用人偶模式把馬達擺到最大 / 最小,
 *     再到「各馬達範圍」視窗按「最小 ← 目前位置」「最大 ← 目前位置」
 * 範圍存在 MotorAdjPlus.properties(limit.1 ~ limit.26),沒有設定的馬達就是預設。
 * 原本的 MotorAdj 程式與設定檔(.config)完全不動。
 */
public class Limits {

    static final int N = 26;

    static Object outer, ms;
    static JScrollBar[] sb = new JScrollBar[N];
    static JFormattedTextField[] pos = new JFormattedTextField[N];
    static boolean busy = false;
    static boolean lastDummy = false;
    static boolean installed = false;

    // ================= 範圍資料 =================

    static int geti(String name) {
        try {
            return ((Integer) Plus.fld(ms, name)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    static int def()  { return geti("MotorPosDef"); }
    static int gMaxOff() { return geti("MotorPosMax") - def(); }
    static int gMinOff() { return geti("MotorPosMin") - def(); }

    /** 這顆馬達設定的範圍(相對中心)。沒設定 = 全域範圍。永遠落在全域範圍之內、最小 <= 最大 */
    static int[] range(int i) {
        int lo = gMinOff(), hi = gMaxOff();
        String s = Settings.raw("limit." + (i + 1));
        if (!s.isEmpty()) {
            try {
                String[] f = s.split(",");
                int a = Integer.parseInt(f[0].trim()), b = Integer.parseInt(f[1].trim());
                a = Math.max(lo, Math.min(hi, a));
                b = Math.max(lo, Math.min(hi, b));
                if (a <= b) {
                    lo = a;
                    hi = b;
                }
            } catch (Exception e) { }
        }
        return new int[] {lo, hi};
    }

    static boolean custom(int i) {
        int[] r = range(i);
        return r[0] != gMinOff() || r[1] != gMaxOff();
    }

    /** 目前實際生效的範圍:人偶模式不限制 */
    static int[] effective(int i) {
        if (DummyMode.isDummy()) return new int[] {gMinOff(), gMaxOff()};
        return range(i);
    }

    static void setRange(int i, int lo, int hi) {
        if (lo == gMinOff() && hi == gMaxOff()) {
            Settings.p.remove("limit." + (i + 1));
        } else {
            Settings.p.setProperty("limit." + (i + 1), lo + "," + hi);
        }
        try { Settings.save(); } catch (Exception e) { e.printStackTrace(); }
    }

    /** 給捲軸的提示文字用 */
    static String tip(int i) {
        if (!installed || i < 0 || i >= N) return "";
        int[] r = range(i);
        return ";這顆的範圍 " + r[0] + " ~ " + r[1] + (custom(i) ? "(已自訂)" : "");
    }

    // ================= 套用到捲軸 =================

    /** 讓捲軸的範圍符合這顆的設定(原本的程式每次換幀都會把範圍改回全域,所以由監聽器隨時改回來) */
    static void enforce(int i) {
        JScrollBar b = sb[i];
        if (b == null || busy) return;
        int[] r = effective(i);
        BoundedRangeModel m = b.getModel();
        if (m.getMinimum() == r[0] && m.getMaximum() == r[1]) return;
        busy = true;
        try {
            int v = Math.max(r[0], Math.min(r[1], m.getValue()));
            m.setRangeProperties(v, m.getExtent(), r[0], r[1], m.getValueIsAdjusting());
        } finally {
            busy = false;
        }
    }

    static void refreshAll() {
        if (!installed) return;
        for (int i = 0; i < N; i++) {
            enforce(i);
            if (sb[i] != null) {
                sb[i].setToolTipText("左右的 -/+ 按鈕:一次 ±" + Steps.unit() + ";點空白軌道:一次 ±" + Steps.block() + tip(i));
            }
        }
        if (table != null) reloadTable();
    }

    // ================= 輸入數字時,以這顆的範圍限制 =================

    /** 原本的「輸入數字」處理會拿全域範圍來限制;呼叫前先暫時換成這顆的範圍,呼叫完還原 */
    static Object wrap(final Object orig, final int i, Class<?> iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> {
            boolean swap = !DummyMode.isDummy();
            int oMax = 0, oMin = 0;
            Field fMax = null, fMin = null;
            if (swap) {
                try {
                    fMax = ms.getClass().getDeclaredField("MotorPosMax");
                    fMin = ms.getClass().getDeclaredField("MotorPosMin");
                    fMax.setAccessible(true);
                    fMin.setAccessible(true);
                    oMax = fMax.getInt(ms);
                    oMin = fMin.getInt(ms);
                    int[] r = range(i);
                    fMax.setInt(ms, def() + r[1]);
                    fMin.setInt(ms, def() + r[0]);
                } catch (Throwable t) {
                    swap = false;
                }
            }
            try {
                return method.invoke(orig, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                if (swap) {
                    fMax.setInt(ms, oMax);
                    fMin.setInt(ms, oMin);
                }
            }
        });
    }

    static void wrapListeners(JFormattedTextField t, int i) {
        for (java.awt.event.KeyListener l : t.getKeyListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) {
                t.removeKeyListener(l);
                t.addKeyListener((java.awt.event.KeyListener) wrap(l, i, java.awt.event.KeyListener.class));
            }
        }
        for (java.awt.event.FocusListener l : t.getFocusListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) {
                t.removeFocusListener(l);
                t.addFocusListener((java.awt.event.FocusListener) wrap(l, i, java.awt.event.FocusListener.class));
            }
        }
        for (java.awt.event.ActionListener l : t.getActionListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) {
                t.removeActionListener(l);
                t.addActionListener((java.awt.event.ActionListener) wrap(l, i, java.awt.event.ActionListener.class));
            }
        }
    }

    // ================= 安裝 =================

    static void install(Container panel) throws Exception {
        outer = Plus.outer();
        if (outer == null) return;
        ms = Plus.fld(outer, "MotorSet");
        Object ui = Plus.fld(outer, "UI_motorSetting");
        Field fs = ui.getClass().getDeclaredField("posScrollBar");
        Field ft = ui.getClass().getDeclaredField("posFormatTextField");
        fs.setAccessible(true);
        ft.setAccessible(true);
        JScrollBar[] bars = (JScrollBar[]) fs.get(ui);
        JFormattedTextField[] texts = (JFormattedTextField[]) ft.get(ui);
        for (int i = 0; i < N && i < bars.length; i++) {
            final int idx = i;
            sb[i] = bars[i];
            pos[i] = texts[i];
            sb[i].getModel().addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) { enforce(idx); }
            });
            wrapListeners(pos[i], i);
        }
        installed = true;

        JButton btn = new JButton("各馬達範圍…");
        btn.setBounds(150, 603, 120, 27);
        btn.setToolTipText("每顆馬達各自設定最小 / 最大位置(預設 ±900)");
        btn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { showDialog(); }
        });
        panel.add(btn);

        // 進出人偶模式時,更新生效的範圍(人偶模式不限制)
        new javax.swing.Timer(250, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                boolean d = DummyMode.isDummy();
                if (d != lastDummy) {
                    lastDummy = d;
                    refreshAll();
                }
            }
        }).start();
        refreshAll();
    }

    // ================= 設定視窗 =================

    static JDialog dlg;
    static JTable table;
    static DefaultTableModel model;
    static boolean loading = false;

    static int cur(int i) {
        try {
            return Integer.parseInt(pos[i].getText().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static void reloadTable() {
        if (model == null) return;
        loading = true;
        for (int i = 0; i < N; i++) {
            int[] r = range(i);
            model.setValueAt(Integer.valueOf(r[0]), i, 1);
            model.setValueAt(Integer.valueOf(r[1]), i, 2);
            model.setValueAt(Integer.valueOf(cur(i)), i, 3);
            model.setValueAt(custom(i) ? "自訂" : "預設", i, 4);
        }
        loading = false;
    }

    static void showDialog() {
        if (!installed) return;
        if (dlg != null) {
            dlg.setVisible(true);
            dlg.toFront();
            reloadTable();
            return;
        }
        Frame owner = Background.mainFrame();
        dlg = new JDialog(owner, "各馬達範圍", false);

        JLabel help = new JLabel("<html><b>每顆馬達各自的最小 / 最大位置</b>(數字跟主畫面一樣,中心是 0)。預設是「設定」分頁的 "
                + "<b>±900</b>。設好之後,捲軸、「-」「+」、直接輸入數字、鏡像都不會超出這顆的範圍。<br>"
                + "・<b>直接改表格</b>的「最小」「最大」。<br>"
                + "・<b>用人偶模式量</b>:點表格<b>編號旁邊的圈圈</b>,那一顆就進入人偶模式(放鬆,還沒進人偶模式會自動進入),用手把它擺到<b>最小的位置</b>,"
                + "按「最小 ← 目前位置」;再擺到最大的位置,按「最大 ← 目前位置」。"
                + "人偶模式裡不限制,可以擺到極限。<br>"
                + "・範圍不會超過「設定」分頁的全域範圍;原本就超出範圍的幀,請用「檢查所有幀」處理。</html>");
        help.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));

        model = new DefaultTableModel(new Object[] {"馬達", "最小", "最大", "目前位置", "狀態"}, N) {
            public Class<?> getColumnClass(int c) {
                return c >= 1 && c <= 3 || c == 0 ? Integer.class : String.class;
            }
            public boolean isCellEditable(int r, int c) { return c == 1 || c == 2; }
        };
        for (int i = 0; i < N; i++) model.setValueAt(Integer.valueOf(i + 1), i, 0);
        table = new JTable(model);
        table.setRowHeight(24);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(90);
        table.getColumnModel().getColumn(0).setHeaderValue("人偶 馬達");
        // 編號旁邊的圈圈:點一下 = 讓這顆進入人偶模式(放鬆、可以用手擺);已經是人偶的那顆實心
        final javax.swing.JRadioButton rbr = new javax.swing.JRadioButton();
        rbr.setOpaque(true);
        table.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.TableCellRenderer() {
            public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                rbr.setText(String.valueOf(v));
                rbr.setSelected(DummyMode.isDummy() && DummyMode.rb[r] != null && DummyMode.rb[r].isSelected());
                rbr.setBackground(sel ? t.getSelectionBackground() : t.getBackground());
                rbr.setForeground(sel ? t.getSelectionForeground() : t.getForeground());
                rbr.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                rbr.setToolTipText("點圈圈:讓這顆進入人偶模式(放鬆,可以用手擺)");
                return rbr;
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseReleased(java.awt.event.MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (r >= 0 && c == 0) selectDummy(r);
            }
        });
        reloadTable();
        table.getSelectionModel().setSelectionInterval(0, 0);

        model.addTableModelListener(new javax.swing.event.TableModelListener() {
            public void tableChanged(javax.swing.event.TableModelEvent e) {
                if (loading || e.getType() != javax.swing.event.TableModelEvent.UPDATE) return;
                int r = e.getFirstRow(), c = e.getColumn();
                if (r < 0 || r >= N || (c != 1 && c != 2)) return;
                editCell(r, c);
            }
        });

        JButton setMin = new JButton("最小 ← 目前位置");
        JButton setMax = new JButton("最大 ← 目前位置");
        JButton resetOne = new JButton("這顆還原預設");
        JButton resetAll = new JButton("全部還原預設");
        JButton check = new JButton("檢查所有幀");
        setMin.setToolTipText("把選取那一顆馬達,主畫面上目前的位置數字,設成它的最小值");
        setMax.setToolTipText("把選取那一顆馬達,主畫面上目前的位置數字,設成它的最大值");
        check.setToolTipText("找出已經超出各顆範圍的幀,可以一次壓回範圍內");

        setMin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { fromCurrent(true); }
        });
        setMax.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { fromCurrent(false); }
        });
        resetOne.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int r = table.getSelectedRow();
                if (r < 0) return;
                setRange(r, gMinOff(), gMaxOff());
                refreshAll();
            }
        });
        resetAll.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (JOptionPane.showConfirmDialog(dlg, "全部馬達的範圍都還原成預設(" + gMinOff() + " ~ " + gMaxOff() + ")?",
                        "各馬達範圍", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                for (int i = 0; i < N; i++) Settings.p.remove("limit." + (i + 1));
                try { Settings.save(); } catch (Exception ex) { ex.printStackTrace(); }
                refreshAll();
            }
        });
        check.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { checkFrames(); }
        });

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        btns.add(setMin);
        btns.add(setMax);
        btns.add(resetOne);
        btns.add(resetAll);
        btns.add(check);

        dlg.getContentPane().setLayout(new BorderLayout());
        dlg.getContentPane().add(help, BorderLayout.NORTH);
        dlg.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        dlg.getContentPane().add(btns, BorderLayout.SOUTH);
        dlg.setSize(760, 640);
        dlg.setLocationRelativeTo(owner);

        // 「目前位置」欄位定時更新(人偶模式量測時看得到即時數字)
        final javax.swing.Timer live = new javax.swing.Timer(300, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!dlg.isVisible() || table.isEditing()) return;
                table.repaint();
                loading = true;
                for (int i = 0; i < N; i++) model.setValueAt(Integer.valueOf(cur(i)), i, 3);
                loading = false;
            }
        });
        live.start();
        dlg.setVisible(true);
    }

    /** 讓第 i 顆進入人偶模式:還沒進人偶模式就先進入,再選這一顆(等於在主畫面按「人偶模式」再點那顆的圓點) */
    static void selectDummy(int i) {
        try {
            if (DummyMode.rb[i] == null) return;
            if (!DummyMode.isDummy()) {
                JButton db = Plus.findInAllWindows("人偶模式", "Dummy", "dummy");
                if (db == null) return;
                java.awt.event.MouseEvent me = new java.awt.event.MouseEvent(db, java.awt.event.MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1);
                for (java.awt.event.MouseListener l : db.getMouseListeners()) l.mouseReleased(me);
                if (!DummyMode.isDummy()) {
                    JOptionPane.showMessageDialog(dlg, "無法進入人偶模式。請先在主畫面確認已連上機器人、馬達已開啟,再按「人偶模式」。");
                    return;
                }
            }
            javax.swing.JRadioButton rb = DummyMode.rb[i];
            java.awt.event.MouseEvent me2 = new java.awt.event.MouseEvent(rb, java.awt.event.MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1);
            rb.setSelected(true);
            for (java.awt.event.MouseListener l : rb.getMouseListeners()) {
                if (l.getClass().getName().startsWith("main.MotorAdjust$")) l.mouseReleased(me2);
            }
            DummyMode.updateMarks();
            if (table != null) table.repaint();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void fromCurrent(boolean isMin) {
        int r = table.getSelectedRow();
        if (r < 0) {
            JOptionPane.showMessageDialog(dlg, "請先在表格選一顆馬達。");
            return;
        }
        int v = cur(r);
        int[] rg = range(r);
        int lo = rg[0], hi = rg[1];
        v = Math.max(gMinOff(), Math.min(gMaxOff(), v));
        if (isMin) {
            lo = v;
            if (hi < lo) hi = gMaxOff();
        } else {
            hi = v;
            if (lo > hi) lo = gMinOff();
        }
        setRange(r, lo, hi);
        refreshAll();
    }

    /** 表格裡直接改了某個數字 */
    static void editCell(int r, int c) {
        int[] rg = range(r);
        int lo = rg[0], hi = rg[1];
        try {
            int v = ((Number) model.getValueAt(r, c)).intValue();
            v = Math.max(gMinOff(), Math.min(gMaxOff(), v));
            if (c == 1) lo = v; else hi = v;
        } catch (Exception e) { }
        if (lo > hi) {
            JOptionPane.showMessageDialog(dlg, "最小值不能大於最大值。");
            refreshAll();
            return;
        }
        setRange(r, lo, hi);
        refreshAll();
    }

    /** 找出超出各顆範圍的幀 */
    static void checkFrames() {
        try {
            int[][][] d = (int[][][]) Plus.fld(ms, "MotorPosData");
            int frames = geti("MotorFramCnt");
            if (frames <= 0 || frames > d.length) frames = d.length;
            int def = def();
            int count = 0;
            StringBuilder sbd = new StringBuilder();
            for (int f = 0; f < frames; f++) {
                for (int m = 0; m < N && m < d[f].length; m++) {
                    int[] r = range(m);
                    int v = d[f][m][1] - def;
                    if (v < r[0] || v > r[1]) {
                        count++;
                        if (count <= 12) sbd.append("幀 ").append(f).append("、馬達 ").append(m + 1).append(":")
                                .append(v).append("(範圍 ").append(r[0]).append(" ~ ").append(r[1]).append(")\n");
                    }
                }
            }
            if (count == 0) {
                JOptionPane.showMessageDialog(dlg, "所有幀都在各顆的範圍內。");
                return;
            }
            Object[] opts = {"壓回範圍內", "不修改"};
            int ans = JOptionPane.showOptionDialog(dlg, "有 " + count + " 個數值超出範圍:\n" + sbd
                    + (count > 12 ? "…(還有 " + (count - 12) + " 個)\n" : "")
                    + "\n要把它們壓回各顆的範圍內嗎?(只改畫面上的資料,要再按「儲存設定」或「更新到機器人」才會存下來;"
                    + "改完請重新「讀出」那一幀)", "檢查所有幀", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, opts, opts[1]);
            if (ans != 0) return;
            for (int f = 0; f < frames; f++) {
                for (int m = 0; m < N && m < d[f].length; m++) {
                    int[] r = range(m);
                    d[f][m][1] = def + Math.max(r[0], Math.min(r[1], d[f][m][1] - def));
                }
            }
            JOptionPane.showMessageDialog(dlg, "已壓回範圍內。請重新「讀出」目前的幀。");
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(dlg, "檢查失敗:" + t);
        }
    }

    /** 給鏡像用:這顆的絕對位置範圍 {最小, 最大} */
    static int[] absRange(int i, int globalMin, int globalMax) {
        if (!installed) return new int[] {globalMin, globalMax};
        int[] r = range(i);
        return new int[] {def() + r[0], def() + r[1]};
    }
}
