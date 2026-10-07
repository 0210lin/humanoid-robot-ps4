import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 「機器人 3D」分頁:把 SolidWorks 匯出的 STL 零件畫成 3D 機器人,26 顆馬達的位置轉成關節角度,機器人跟著動。
 *   ・姿勢來源:「馬達參數」目前畫面 / 「動作程式」的模擬播放 / 零位
 *   ・設定:零件指定給哪顆馬達、每顆馬達的轉軸位置與方向、轉動方向、1 單位幾度、零位。存在模型資料夾的 rig.json
 * 只畫姿勢,不做重力、碰撞、平衡。
 */
public class RobotTab {
    static Viewport3D vp;
    static Rig rig = new Rig();
    static File modelDir = null;
    static JPanel root;
    static boolean loaded = false, loading = false;

    static JLabel modelLabel;
    static JComboBox<String> sourceBox, upBox;
    static JCheckBox showDisabled;
    static JSpinner budgetSp;
    static JTabbedPane side;

    // 零件
    static DefaultListModel<Viewport3D.Part> partModel = new DefaultListModel<Viewport3D.Part>();
    static JList<Viewport3D.Part> partList;
    static JTextField filter;
    static JComboBox<String> assignBox;
    static JCheckBox hideAssigned, onlySelected, colorBox;
    static boolean syncingList = false;

    // 關節
    static JComboBox<String> motorBox, parentBox;
    static JTextField nameField;
    static JSpinner[] pivotSp = new JSpinner[3], axisSp = new JSpinner[3];
    static JSpinner degSp, zeroSp;
    static JCheckBox revBox, previewBox;
    static JSlider previewSl;
    static JLabel jointInfo, pickInfo;
    static boolean updatingFields = false;
    static int pickMode = 0;                 // 0 = 沒有;1 = 一點;2 = 兩點取中點
    static double[] firstPick = null;

    static javax.swing.Timer poseTimer, saveTimer;
    static double[] lastPose = null;
    static int lastMode = -1, lastOnly = -2;

    static String motorLabel(int m) {
        if (m == 0) return "0  身體(不動)";
        return m + "  " + rig.joints[m].name;
    }

    // ================= 載入模型 =================

    /** 專案裡放 STL 零件的資料夾:robot_model 優先,沒有就用 robot_stl */
    static File defaultDir() {
        File a = new File(Settings.dir.getParentFile(), "robot_model");
        if (a.isDirectory()) return a;
        File b = new File(Settings.dir.getParentFile(), "robot_stl");
        return b.isDirectory() ? b : a;
    }

    static File[] stlFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return new File[0];
        List<File> l = new ArrayList<File>();
        for (File f : fs) if (f.isFile() && f.getName().toLowerCase().endsWith(".stl")) l.add(f);
        Collections.sort(l);
        return l.toArray(new File[0]);
    }

    static void setModelText(final String s) {
        SwingUtilities.invokeLater(new Runnable() { public void run() { modelLabel.setText("<html>" + s + "</html>"); } });
    }

    /** 背景載入資料夾裡全部的 STL(讀檔、降面數),再回到畫面更新 */
    static void loadModel(final File dir) {
        if (loading) return;
        final File[] files = stlFiles(dir);
        if (files.length == 0) {
            JOptionPane.showMessageDialog(root, "這個資料夾裡沒有 .stl 檔:\n" + dir, "機器人 3D", JOptionPane.WARNING_MESSAGE);
            return;
        }
        loading = true;
        modelDir = dir;
        final int budget = rig.budget;
        new Thread(new Runnable() {
            public void run() {
                final List<Viewport3D.Part> parts = new ArrayList<Viewport3D.Part>();
                final List<String> errors = new ArrayList<String>();
                try {
                    // 先讀 rig.json(有的話)
                    File rf = new File(dir, "rig.json");
                    Rig r = rf.isFile() ? Rig.load(rf) : new Rig();
                    r.budget = Math.max(2000, budget == 250000 && rf.isFile() ? r.budget : budget);
                    List<Mesh> raw = new ArrayList<Mesh>();
                    long total = 0;
                    for (int i = 0; i < files.length; i++) {
                        setModelText("讀取中… " + (i + 1) + " / " + files.length + "  " + files[i].getName());
                        try {
                            Mesh m = Mesh.load(files[i]);
                            raw.add(m);
                            total += m.n;
                        } catch (Throwable t) {
                            errors.add(files[i].getName() + ":" + t.getMessage());
                        }
                    }
                    long cnt = 0;
                    for (int i = 0; i < raw.size(); i++) {
                        Mesh m = raw.get(i);
                        int target = total <= r.budget ? m.n : (int) Math.max(12, (long) m.n * r.budget / Math.max(1, total));
                        Mesh d = m.decimate(target);
                        cnt += d.n;
                        Viewport3D.Part p = new Viewport3D.Part(m.name, d);
                        p.raw = m;
                        Integer o = r.owner.get(m.name);
                        p.owner = o == null ? 0 : o.intValue();
                        parts.add(p);
                    }
                    final long fTotal = total, fCnt = cnt;
                    final Rig fr = r;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            rig = fr;
                            vp.parts.clear();
                            vp.parts.addAll(parts);
                            vp.up = rig.up;
                            upBox.setSelectedItem(rig.up);
                            budgetSp.setValue(Integer.valueOf(rig.budget));
                            vp.fitToModel();
                            refreshParts();
                            refreshMotorBoxes();
                            loadFields();
                            updateServoLabels();
                            loaded = true;
                            loading = false;
                            SimView.rebuild();
                            lastPose = null;
                            String msg = parts.size() + " 個零件," + fTotal + " 個三角形" + (fTotal > fCnt ? "(降到 " + fCnt + ")" : "") + "。資料夾:" + dir.getName();
                            if (!errors.isEmpty()) msg += "<br>有 " + errors.size() + " 個檔讀不了:" + errors.get(0);
                            modelLabel.setText("<html>" + msg + "</html>");
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                    loading = false;
                    setModelText("載入失敗:" + t);
                }
            }
        }, "RobotTab-load").start();
    }

    // ================= 設定存檔 =================

    static void changed() {
        if (saveTimer != null) saveTimer.restart();
        lastPose = null;
        SimView.rebuild();
    }

    static void saveNow() {
        if (modelDir == null || !loaded) return;
        try {
            rig.owner.clear();
            for (Viewport3D.Part p : vp.parts) if (p.owner != 0) rig.owner.put(p.name, Integer.valueOf(p.owner));
            rig.up = vp.up;
            rig.save(new File(modelDir, "rig.json"));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ================= 零件清單 =================

    static void refreshParts() {
        partModel.clear();
        String f = filter == null ? "" : filter.getText().trim().toLowerCase();
        for (Viewport3D.Part p : vp.parts) if (f.isEmpty() || p.name.toLowerCase().contains(f)) partModel.addElement(p);
        applyVisibility();
        syncListFromSelection();
    }

    static void applyVisibility() {
        boolean any = false;
        for (Viewport3D.Part p : vp.parts) any |= p.selected;
        for (Viewport3D.Part p : vp.parts) {
            boolean v = true;
            if (hideAssigned != null && hideAssigned.isSelected() && p.owner != 0) v = false;
            if (onlySelected != null && onlySelected.isSelected() && any && !p.selected) v = false;
            p.visible = v;
        }
        vp.colorByOwner = colorBox == null || colorBox.isSelected();
        vp.repaint();
    }

    static void syncListFromSelection() {
        syncingList = true;
        try {
            partList.clearSelection();
            for (int i = 0; i < partModel.size(); i++) if (partModel.get(i).selected) partList.addSelectionInterval(i, i);
        } finally {
            syncingList = false;
        }
        partList.repaint();
    }

    static List<Viewport3D.Part> selectedParts() {
        List<Viewport3D.Part> l = new ArrayList<Viewport3D.Part>();
        for (Viewport3D.Part p : vp.parts) if (p.selected) l.add(p);
        return l;
    }

    static void assignSelected(int motor) {
        List<Viewport3D.Part> sel = selectedParts();
        if (sel.isEmpty()) { JOptionPane.showMessageDialog(root, "先在 3D 畫面或清單裡選零件(點一下、Ctrl+點加選、Shift+拖曳框選)。", "零件指定", JOptionPane.INFORMATION_MESSAGE); return; }
        for (Viewport3D.Part p : sel) p.owner = motor;
        for (Viewport3D.Part p : vp.parts) p.selected = false;
        refreshParts();
        changed();
        updateJointInfo();
    }

    // ================= 關節欄位 =================

    static int curMotor() { return Math.max(1, motorBox.getSelectedIndex() + 1); }

    static void refreshMotorBoxes() {
        int a = motorBox.getSelectedIndex(), b = parentBox.getSelectedIndex(), c = assignBox.getSelectedIndex();
        int d = mirrorTarget == null ? -1 : mirrorTarget.getSelectedIndex();
        updatingFields = true;
        try {
            motorBox.removeAllItems();
            parentBox.removeAllItems();
            assignBox.removeAllItems();
            if (mirrorTarget != null) mirrorTarget.removeAllItems();
            for (int m = 0; m <= Rig.N; m++) {
                if (m >= 1) { motorBox.addItem(motorLabel(m)); if (mirrorTarget != null) mirrorTarget.addItem(motorLabel(m)); }
                parentBox.addItem(motorLabel(m));
            }
            for (int t = 0; t <= rig.targetCount(); t++) assignBox.addItem(t == 0 ? "0  身體(不動)" : t + "  " + rig.targetName(t));
            motorBox.setSelectedIndex(Math.max(0, a));
            parentBox.setSelectedIndex(Math.max(0, b));
            assignBox.setSelectedIndex(Math.max(0, c));
            if (mirrorTarget != null) mirrorTarget.setSelectedIndex(Math.max(0, d));
        } finally {
            updatingFields = false;
        }
    }

    static void loadFields() {
        updatingFields = true;
        try {
            Rig.Joint j = rig.joints[curMotor()];
            nameField.setText(j.name);
            parentBox.setSelectedIndex(j.parent);
            for (int k = 0; k < 3; k++) { pivotSp[k].setValue(Double.valueOf(j.pivot[k])); axisSp[k].setValue(Double.valueOf(j.axis[k])); }
            degSp.setValue(Double.valueOf(j.degPer));
            zeroSp.setValue(Integer.valueOf(j.zero));
            revBox.setSelected(j.sign < 0);
            previewSl.setMinimum(j.zero - 1000);
            previewSl.setMaximum(j.zero + 1000);
            previewSl.setValue(j.zero);
        } finally {
            updatingFields = false;
        }
        updateJointInfo();
        updateMarkers();
        updatePreviewText();
    }

    static void storeFields() {
        if (updatingFields) return;
        Rig.Joint j = rig.joints[curMotor()];
        j.name = nameField.getText().trim().isEmpty() ? "馬達 " + j.motor : nameField.getText().trim();
        j.parent = Math.max(0, parentBox.getSelectedIndex());
        for (int k = 0; k < 3; k++) { j.pivot[k] = ((Number) pivotSp[k].getValue()).doubleValue(); j.axis[k] = ((Number) axisSp[k].getValue()).doubleValue(); }
        j.degPer = ((Number) degSp.getValue()).doubleValue();
        j.zero = ((Number) zeroSp.getValue()).intValue();
        j.sign = revBox.isSelected() ? -1 : 1;
        updateMarkers();
        updatePreviewText();
        changed();
    }

    static void updateJointInfo() {
        int m = curMotor();
        int n = 0;
        if (rig.nodes != null) {
            for (Viewport3D.Part p : vp.parts) {
                if (p.owner < 1 || p.owner > rig.nodes.size()) continue;
                for (int mm : rig.nodes.get(p.owner - 1).motors) if (mm == m) { n++; break; }
            }
            jointInfo.setText("<html>馬達 " + m + " 驅動 " + n + " 個零件" + (n == 0 ? "(沒有零件由它驅動)" : "") + "<br><font size=-1>這台機器人用「結構模式」(平行四邊形腿、雙馬達手臂):轉軸和掛載由 rig.json 的結構決定,這裡只調轉動方向、1 單位幾度、零位。</font></html>");
            return;
        }
        for (Viewport3D.Part p : vp.parts) if (p.owner == m) n++;
        Rig.Joint j = rig.joints[m];
        double[] ax = j.axis;
        boolean badAxis = Math.abs(ax[0]) + Math.abs(ax[1]) + Math.abs(ax[2]) < 1e-9;
        jointInfo.setText("<html>馬達 " + m + " 帶動 " + n + " 個零件" + (n == 0 ? "(還沒指定零件)" : "") + (badAxis ? "<br><font color=red>轉軸方向不能全是 0</font>" : "") + "</html>");
    }

    static void updateMarkers() {
        boolean jointTab = side != null && side.getSelectedIndex() == 2;
        if (jointTab) {
            Rig.Joint j = rig.joints[curMotor()];
            vp.pivotMarker = j.pivot.clone();
            double l = Math.sqrt(j.axis[0] * j.axis[0] + j.axis[1] * j.axis[1] + j.axis[2] * j.axis[2]);
            vp.axisMarker = l < 1e-9 ? null : new double[] {j.axis[0] / l, j.axis[1] / l, j.axis[2] / l};
        } else {
            vp.pivotMarker = null;
            vp.axisMarker = null;
        }
        vp.repaint();
    }

    static void setPivot(double x, double y, double z) {
        updatingFields = true;
        pivotSp[0].setValue(Double.valueOf(Math.round(x * 100) / 100.0));
        pivotSp[1].setValue(Double.valueOf(Math.round(y * 100) / 100.0));
        pivotSp[2].setValue(Double.valueOf(Math.round(z * 100) / 100.0));
        updatingFields = false;
        storeFields();
    }

    static void startPick(int mode) {
        pickMode = mode;
        firstPick = null;
        vp.pickPointMode = true;
        vp.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        pickInfo.setText(mode == 1 ? "到 3D 畫面點轉軸的位置(點零件的表面)。" : "到 3D 畫面點第 1 個點(例如轉軸孔的一側)。");
        lastPose = null;
    }

    // ================= 姿勢 =================

    /** 依姿勢來源算目前每顆馬達的位置值(index 1..26);null = 零位 */
    static double[] currentPose(int[] modeOut, int[] onlyOut) {
        boolean jointTab = side != null && side.getSelectedIndex() == 2;
        modeOut[0] = 0;
        onlyOut[0] = 0;
        if (vp.pickPointMode) return null;                         // 點取座標時一律零位,座標才是零位座標
        if (jointTab) {
            if (previewBox.isSelected()) {
                int m = curMotor();
                double[] p = new double[Rig.N + 1];
                Arrays.fill(p, 1500);
                p[m] = previewSl.getValue();
                onlyOut[0] = m;
                modeOut[0] = 3;
                return p;
            }
            return null;
        }
        int src = sourceBox.getSelectedIndex();
        double[] p = new double[Rig.N + 1];
        Arrays.fill(p, 1500);
        if (src == 0) {
            modeOut[0] = 1;
            try {
                Object ms = Plus.fld(Plus.outer(), "MotorSet");
                int[][] tmp = (int[][]) Plus.fld(ms, "MotorPosDataTmp");
                for (int m = 1; m <= Rig.N && m - 1 < tmp.length; m++) { p[m] = (tmp[m - 1][0] != 0 || showDisabled.isSelected()) ? tmp[m - 1][1] : rig.joints[m].zero; if (p[m] < 100) p[m] = rig.joints[m].zero; }   // 位置 0 = 還沒讀入幀,當作零位
            } catch (Throwable t) {
                return null;
            }
            return p;
        }
        if (src == 1) {
            modeOut[0] = 2;
            double[] now = SequenceTab.poseNow();
            for (int m = 1; m <= Rig.N && m - 1 < now.length; m++) p[m] = now[m - 1];
            return p;
        }
        modeOut[0] = 4;
        return null;
    }

    static void tick() {
        if (!loaded || root == null || !root.isShowing()) return;
        int[] mo = new int[1], oo = new int[1];
        double[] pose = currentPose(mo, oo);
        boolean same = mo[0] == lastMode && oo[0] == lastOnly && ((pose == null && lastPose == null) || (pose != null && lastPose != null && Arrays.equals(pose, lastPose)));
        if (same) return;
        lastMode = mo[0];
        lastOnly = oo[0];
        lastPose = pose == null ? null : pose.clone();
        vp.mats = rig.matrices(pose, oo[0]);
        vp.repaint();
    }

    // ================= 畫面 =================

    static JSpinner dspin(double v, double min, double max, double step) {
        return new JSpinner(new SpinnerNumberModel(v, min, max, step));
    }

    static void row(JPanel p, int y, String label, Component c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(2, 4, 2, 4);
        p.add(new JLabel(label), g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; g.insets = new Insets(2, 4, 2, 4);
        p.add(c, g);
    }

    static void full(JPanel p, int y, Component c) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; g.insets = new Insets(3, 4, 3, 4);
        p.add(c, g);
    }

    static JPanel flow(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (Component c : cs) p.add(c);
        return p;
    }

    static ChangeListener cl() {
        return new ChangeListener() { public void stateChanged(ChangeEvent e) { storeFields(); } };
    }

    static boolean install() {
        JTabbedPane tabs = null;
        for (Frame f : Frame.getFrames()) {
            tabs = CodeTab.findTabs(f);
            if (tabs != null) break;
        }
        if (tabs == null) return false;

        vp = new Viewport3D();
        vp.setMinimumSize(new Dimension(300, 300));
        vp.listener = new Viewport3D.Listener() {
            public void selectionChanged() { syncListFromSelection(); applyVisibility(); }
            public void pointPicked(double x, double y, double z) {
                if (pickMode == 2 && firstPick == null) {
                    firstPick = new double[] {x, y, z};
                    vp.pickPointMode = true;
                    vp.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
                    pickInfo.setText("再點第 2 個點(轉軸孔的另一側),轉軸會放在兩點中間。");
                    return;
                }
                if (pickMode == 2 && firstPick != null) { x = (x + firstPick[0]) / 2; y = (y + firstPick[1]) / 2; z = (z + firstPick[2]) / 2; }
                pickMode = 0;
                firstPick = null;
                setPivot(x, y, z);
                pickInfo.setText("轉軸位置已設定:" + String.format("%.1f, %.1f, %.1f", x, y, z));
                lastPose = null;
            }
        };

        // ---- 左:3D 畫面 + 視角按鈕 ----
        JPanel viewBox = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        String[] vn = {"正面", "背面", "左側", "右側", "上方", "重設視角"};
        for (int i = 0; i < vn.length; i++) {
            final int k = i;
            JButton b = new JButton(vn[i]);
            b.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { setView(k); } });
            bar.add(b);
        }
        viewBox.add(bar, BorderLayout.NORTH);
        viewBox.add(vp, BorderLayout.CENTER);

        // ---- 右:三個小分頁 ----
        side = new JTabbedPane();

        // 1) 模型與姿勢
        JPanel p1 = new JPanel(new GridBagLayout());
        modelLabel = new JLabel("<html>還沒載入模型。把 SolidWorks 匯出的 STL(每個零件一個檔)放進資料夾,按「載入模型資料夾…」。</html>");
        JButton loadBtn = new JButton("載入模型資料夾…");
        JButton reloadBtn = new JButton("重新載入");
        upBox = new JComboBox<String>(new String[] {"Y", "Z"});
        budgetSp = new JSpinner(new SpinnerNumberModel(250000, 2000, 2000000, 10000));
        sourceBox = new JComboBox<String>(new String[] {"「馬達參數」目前畫面", "「動作程式」的模擬播放", "零位(全部 1500)"});
        full(p1, 0, modelLabel);
        full(p1, 1, flow(loadBtn, reloadBtn));
        row(p1, 2, "模型的「上」是哪個軸", upBox);
        row(p1, 3, "三角形上限(降面數)", budgetSp);
        row(p1, 4, "機器人姿勢跟著", sourceBox);
        JLabel tip = new JLabel("<html><font size=-1>「馬達參數」:拖捲軸就會動。<br>「動作程式」:右邊按播放時跟著動。<br>三角形上限改了要按「重新載入」才會生效。</font></html>");
        showDisabled = new JCheckBox("沒勾「啟用」的馬達也讓機器人跟著動", true);
        showDisabled.setToolTipText("取消勾選的話,「馬達參數」分頁沒勾「啟用」的馬達會當作在零位(1500)");
        full(p1, 5, showDisabled);
        full(p1, 6, tip);
        GridBagConstraints fill = new GridBagConstraints();
        fill.gridx = 0; fill.gridy = 7; fill.weighty = 1;
        p1.add(new JLabel(), fill);
        side.addTab("模型與姿勢", new JScrollPane(p1));

        // 2) 零件指定
        JPanel p2 = new JPanel(new BorderLayout(4, 4));
        p2.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        filter = new JTextField();
        partList = new JList<Viewport3D.Part>(partModel);
        partList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        partList.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean foc) {
                Component c = super.getListCellRendererComponent(list, v, i, sel, foc);
                Viewport3D.Part p = (Viewport3D.Part) v;
                ((JLabel) c).setText(p.name + (p.owner == 0 ? "" : "   → " + (p.owner <= rig.targetCount() ? rig.targetName(p.owner) : String.valueOf(p.owner))));
                return c;
            }
        });
        partList.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (syncingList || e.getValueIsAdjusting()) return;
                for (Viewport3D.Part p : vp.parts) p.selected = false;
                for (Viewport3D.Part p : partList.getSelectedValuesList()) p.selected = true;
                applyVisibility();
            }
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshParts(); }
            public void removeUpdate(DocumentEvent e) { refreshParts(); }
            public void changedUpdate(DocumentEvent e) { refreshParts(); }
        });
        assignBox = new JComboBox<String>();
        JButton assignBtn = new JButton("選中的零件 → 指定給這顆");
        JButton unassigned = new JButton("選取還沒指定的");
        hideAssigned = new JCheckBox("隱藏已指定的零件");
        onlySelected = new JCheckBox("只顯示選中的");
        colorBox = new JCheckBox("依歸屬上色(彩色)", false);
        JPanel top2 = new JPanel(new BorderLayout(4, 4));
        top2.add(new JLabel("搜尋零件:"), BorderLayout.WEST);
        top2.add(filter, BorderLayout.CENTER);
        JPanel bot2 = new JPanel(new GridBagLayout());
        full(bot2, 0, new JLabel("<html><font size=-1>先指定離身體最遠的(手掌、腳掌),再用「隱藏已指定的零件」把它們藏起來,再框選下一段。</font></html>"));
        full(bot2, 1, assignBox);
        full(bot2, 2, assignBtn);
        full(bot2, 3, unassigned);
        full(bot2, 4, hideAssigned);
        full(bot2, 5, onlySelected);
        full(bot2, 6, colorBox);
        p2.add(top2, BorderLayout.NORTH);
        p2.add(new JScrollPane(partList), BorderLayout.CENTER);
        p2.add(bot2, BorderLayout.SOUTH);
        side.addTab("零件指定", p2);

        // 3) 關節設定
        JPanel p3 = new JPanel(new GridBagLayout());
        motorBox = new JComboBox<String>();
        parentBox = new JComboBox<String>();
        nameField = new JTextField();
        for (int k = 0; k < 3; k++) { pivotSp[k] = dspin(0, -100000, 100000, 1); axisSp[k] = dspin(k == 0 ? 1 : 0, -1, 1, 0.1); }
        degSp = dspin(0.1636, 0.0001, 5, 0.005);
        zeroSp = new JSpinner(new SpinnerNumberModel(1500, 0, 5000, 1));
        revBox = new JCheckBox("轉動方向反過來");
        previewBox = new JCheckBox("預覽這一顆(其他關節在零位)");
        previewSl = new JSlider(500, 2500, 1500);
        jointInfo = new JLabel(" ");
        pickInfo = new JLabel("<html><font size=-1> </font></html>");
        JButton pick1 = new JButton("點 3D 設轉軸位置");
        JButton pick2 = new JButton("點兩點取中點");
        JButton fromSel = new JButton("用選中零件的中心");
        JButton ax = new JButton("X"), ay = new JButton("Y"), az = new JButton("Z"), flip = new JButton("反向");
        JButton allDeg = new JButton("全部馬達用這個「度/單位」");
        row(p3, 0, "馬達", motorBox);
        row(p3, 1, "名稱", nameField);
        row(p3, 2, "掛在哪顆馬達上", parentBox);
        full(p3, 3, jointInfo);
        row(p3, 4, "轉軸位置 X", pivotSp[0]);
        row(p3, 5, "轉軸位置 Y", pivotSp[1]);
        row(p3, 6, "轉軸位置 Z", pivotSp[2]);
        full(p3, 7, flow(pick1, pick2));
        full(p3, 8, fromSel);
        full(p3, 9, pickInfo);
        row(p3, 10, "轉軸方向 X", axisSp[0]);
        row(p3, 11, "轉軸方向 Y", axisSp[1]);
        row(p3, 12, "轉軸方向 Z", axisSp[2]);
        full(p3, 13, flow(new JLabel("設成:"), ax, ay, az, flip));
        full(p3, 14, revBox);
        row(p3, 15, "1 個單位 = 幾度", degSp);
        row(p3, 16, "零位(馬達值)", zeroSp);
        full(p3, 17, allDeg);
        full(p3, 18, previewBox);
        full(p3, 19, previewSl);
        mirrorTarget = new JComboBox<String>();
        mirrorAxis = new JComboBox<String>(new String[] {"X", "Y", "Z"});
        JButton mirrorBtn = new JButton("把這顆複製成鏡像 → 到對面馬達");
        mirrorBtn.setToolTipText("依身體左右對稱面,把這顆的轉軸位置和方向鏡射給對面那顆馬達(轉動方向會反過來)。複製完用「預覽」確認方向對不對。零件還是要自己指定。");
        full(p3, 20, new JLabel("<html><font size=-1>鏡像複製:左右對稱的馬達(例如右肩/左肩)只要設一邊,另一邊用複製。</font></html>"));
        row(p3, 21, "對面馬達", mirrorTarget);
        row(p3, 22, "左右對稱的軸", mirrorAxis);
        full(p3, 23, mirrorBtn);
        GridBagConstraints fill3 = new GridBagConstraints();
        fill3.gridx = 0; fill3.gridy = 24; fill3.weighty = 1;
        p3.add(new JLabel(), fill3);
        side.addTab("關節設定", new JScrollPane(p3));
        // 4) 伺服對照:點 3D 畫面裡的伺服馬達,告訴程式它是第幾號馬達;轉軸位置和方向自動算
        JPanel p4 = new JPanel(new GridBagLayout());
        servoKey = new JTextField("舵機");
        servoMotor = new JComboBox<String>();
        servoShow = new JCheckBox("在 3D 畫面上標出伺服編號 / 馬達編號", true);
        servoList = new javax.swing.JTextArea(10, 20);
        servoList.setEditable(false);
        servoList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JButton servoSet = new JButton("選中的伺服 = 上面這顆馬達");
        JButton servoClear = new JButton("取消這顆馬達的對照");
        JLabel servoTip = new JLabel("<html><font size=-1>做法:<br>1. 在 3D 畫面點一顆伺服馬達(白底「舵N」)<br>2. 選它是第幾號馬達<br>3. 按「選中的伺服 = 這顆馬達」<br>轉軸位置和方向會自動算好(所有伺服都是同一個零件的複本)。<br>按完會自動跳到下一個馬達編號。<br>轉動方向(正反)之後在「關節設定」用預覽確認。</font></html>");
        full(p4, 0, servoTip);
        row(p4, 1, "伺服零件名稱包含", servoKey);
        full(p4, 2, servoShow);
        row(p4, 3, "指定成馬達", servoMotor);
        full(p4, 4, servoSet);
        full(p4, 5, servoClear);
        full(p4, 6, new JLabel("目前的對照:"));
        GridBagConstraints g4 = new GridBagConstraints();
        g4.gridx = 0; g4.gridy = 7; g4.gridwidth = 2; g4.fill = GridBagConstraints.BOTH; g4.weightx = 1; g4.weighty = 1; g4.insets = new Insets(3, 4, 3, 4);
        p4.add(new JScrollPane(servoList), g4);
        side.addTab("伺服對照", p4);
        servoShow.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { updateServoLabels(); } });
        servoKey.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateServoLabels(); }
            public void removeUpdate(DocumentEvent e) { updateServoLabels(); }
            public void changedUpdate(DocumentEvent e) { updateServoLabels(); }
        });
        servoSet.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { assignServo(servoMotor.getSelectedIndex() + 1); } });
        servoClear.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            rig.joints[servoMotor.getSelectedIndex() + 1].servo = "";
            changed();
            updateServoLabels();
        } });
        updateServoLabels();

        side.setMinimumSize(new Dimension(300, 200));

        JSplitPane both = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewBox, side);
        both.setResizeWeight(0.7);
        both.setContinuousLayout(true);
        root = new JPanel(new BorderLayout());
        root.add(both, BorderLayout.CENTER);
        tabs.addTab("機器人 3D", root);

        refreshMotorBoxes();

        // ---- 事件 ----
        loadBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            JFileChooser fc = new JFileChooser(modelDir != null ? modelDir : (defaultDir().isDirectory() ? defaultDir() : Settings.dir));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("選擇放 STL 零件的資料夾");
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) { rig.budget = ((Number) budgetSp.getValue()).intValue(); loadModel(fc.getSelectedFile()); }
        } });
        reloadBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            saveNow();
            rig.budget = ((Number) budgetSp.getValue()).intValue();
            File d = modelDir != null ? modelDir : defaultDir();
            if (!d.isDirectory()) { JOptionPane.showMessageDialog(root, "還沒選模型資料夾。", "機器人 3D", JOptionPane.INFORMATION_MESSAGE); return; }
            loadModel(d);
        } });
        upBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            vp.up = (String) upBox.getSelectedItem();
            setView(0);
            changed();
        } });
        sourceBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { lastPose = null; } });
        side.addChangeListener(new ChangeListener() { public void stateChanged(ChangeEvent e) { lastPose = null; updateMarkers(); } });
        assignBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { assignSelected(Math.max(0, assignBox.getSelectedIndex())); } });
        unassigned.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            for (Viewport3D.Part p : vp.parts) p.selected = p.owner == 0 && p.visible;
            syncListFromSelection();
            applyVisibility();
        } });
        java.awt.event.ActionListener vis = new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { applyVisibility(); } };
        hideAssigned.addActionListener(vis);
        onlySelected.addActionListener(vis);
        colorBox.addActionListener(vis);
        motorBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { if (!updatingFields) { loadFields(); lastPose = null; } } });
        parentBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { storeFields(); } });
        revBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { storeFields(); } });
        previewBox.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { lastPose = null; } });
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { storeFields(); }
            public void removeUpdate(DocumentEvent e) { storeFields(); }
            public void changedUpdate(DocumentEvent e) { storeFields(); }
        });
        for (int k = 0; k < 3; k++) { pivotSp[k].addChangeListener(cl()); axisSp[k].addChangeListener(cl()); }
        degSp.addChangeListener(cl());
        zeroSp.addChangeListener(new ChangeListener() { public void stateChanged(ChangeEvent e) {
            if (updatingFields) return;
            storeFields();
            int z = ((Number) zeroSp.getValue()).intValue();
            updatingFields = true;
            previewSl.setMinimum(z - 1000); previewSl.setMaximum(z + 1000); previewSl.setValue(z);
            updatingFields = false;
        } });
        previewSl.addChangeListener(new ChangeListener() { public void stateChanged(ChangeEvent e) { lastPose = null; updatePreviewText(); } });
        pick1.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { startPick(1); } });
        pick2.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { startPick(2); } });
        fromSel.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            List<Viewport3D.Part> sel = selectedParts();
            if (sel.isEmpty()) { pickInfo.setText("<html><font size=-1>先選零件(例如伺服馬達的輸出軸那個零件)。</font></html>"); return; }
            double[] mn = {1e30, 1e30, 1e30}, mx = {-1e30, -1e30, -1e30};
            for (Viewport3D.Part p : sel) for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], p.mesh.min[k]); mx[k] = Math.max(mx[k], p.mesh.max[k]); }
            setPivot((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2);
            pickInfo.setText("<html><font size=-1>已設成選中零件的外框中心。</font></html>");
        } });
        ax.addActionListener(axisSetter(1, 0, 0));
        ay.addActionListener(axisSetter(0, 1, 0));
        az.addActionListener(axisSetter(0, 0, 1));
        flip.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            updatingFields = true;
            for (int k = 0; k < 3; k++) axisSp[k].setValue(Double.valueOf(-((Number) axisSp[k].getValue()).doubleValue()));
            updatingFields = false;
            storeFields();
        } });
        mirrorBtn.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            mirrorJoint(curMotor(), mirrorTarget.getSelectedIndex() + 1, mirrorAxis.getSelectedIndex());
        } });
        allDeg.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            double d = ((Number) degSp.getValue()).doubleValue();
            for (int m = 1; m <= Rig.N; m++) rig.joints[m].degPer = d;
            changed();
            pickInfo.setText("<html><font size=-1>26 顆馬達的「度/單位」都設成 " + d + "。</font></html>");
        } });

        saveTimer = new javax.swing.Timer(700, new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { saveNow(); } });
        saveTimer.setRepeats(false);
        poseTimer = new javax.swing.Timer(40, new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { tick(); } });
        poseTimer.start();

        // 第一次打開這個分頁時,如果專案裡有 robot_model 資料夾就自動載入
        tabs.addChangeListener(new ChangeListener() { public void stateChanged(ChangeEvent e) {
            if (!loaded && !loading && root.isShowing() && defaultDir().isDirectory() && stlFiles(defaultDir()).length > 0) loadModel(defaultDir());
        } });
        return true;
    }

    static JTextField servoKey;
    static JComboBox<String> servoMotor;
    static JCheckBox servoShow;
    static javax.swing.JTextArea servoList;
    static Viewport3D.Part refServo = null;
    static double[][] refShaftCache = null;
    static String refShaftFor = null;

    static boolean isServo(Viewport3D.Part p) {
        String k = servoKey == null ? "舵機" : servoKey.getText().trim();
        return !k.isEmpty() && p.name.contains(k);
    }

    static String shortServoName(String n) {
        int i = n.lastIndexOf('-');
        return i >= 0 && i + 1 < n.length() ? n.substring(i + 1) : n;
    }

    /** 3D 畫面上伺服零件的標籤:已對照的寫「馬達 N」,還沒對照的寫「舵k」 */
    static void updateServoLabels() {
        if (vp == null) return;
        if (servoShow != null && servoShow.isSelected()) {
            vp.labelFunc = new Viewport3D.LabelFunc() {
                public String label(Viewport3D.Part p) {
                    if (!isServo(p)) return null;
                    for (int m = 1; m <= Rig.N; m++) if (p.name.equals(rig.joints[m].servo)) return "馬達" + m;
                    return "舵" + shortServoName(p.name);
                }
            };
        } else {
            vp.labelFunc = null;
        }
        if (servoMotor != null && servoMotor.getItemCount() == 0) for (int m = 1; m <= Rig.N; m++) servoMotor.addItem("馬達 " + m);
        if (servoList != null) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int m = 1; m <= Rig.N; m++) {
                String s = rig.joints[m].servo;
                if (s != null && !s.isEmpty()) { sb.append("馬達 ").append(m).append(" = 舵").append(shortServoName(s)).append("\n"); n++; }
            }
            int servos = 0;
            for (Viewport3D.Part p : vp.parts) if (isServo(p)) servos++;
            sb.append("\n已對照 ").append(n).append(" 顆(共 ").append(servos).append(" 顆伺服)");
            servoList.setText(sb.toString());
        }
        vp.repaint();
    }

    /** 選中的那顆伺服 = 馬達 motor:轉軸位置和方向用「和基準伺服的剛體對應」自動算 */
    static void assignServo(int motor) {
        List<Viewport3D.Part> sel = selectedParts();
        if (sel.size() != 1) { JOptionPane.showMessageDialog(root, "請先在 3D 畫面點選「剛好一顆」伺服馬達(現在選了 " + sel.size() + " 個零件)。", "伺服對照", JOptionPane.INFORMATION_MESSAGE); return; }
        Viewport3D.Part inst = sel.get(0);
        if (!isServo(inst)) { JOptionPane.showMessageDialog(root, "選到的不是伺服馬達(零件名稱要包含「" + servoKey.getText().trim() + "」)。", "伺服對照", JOptionPane.INFORMATION_MESSAGE); return; }
        Viewport3D.Part ref = null;
        for (Viewport3D.Part p : vp.parts) if (isServo(p) && p.raw != null) { ref = p; break; }
        if (ref == null || inst.raw == null) { JOptionPane.showMessageDialog(root, "找不到基準伺服(要先載入模型)。", "伺服對照", JOptionPane.WARNING_MESSAGE); return; }
        if (refShaftCache == null || !ref.name.equals(refShaftFor)) { refShaftCache = AutoRig.refShaft(ref.raw); refShaftFor = ref.name; }
        double[][] rt = AutoRig.rigid(ref.raw, inst.raw);
        if (rt == null) { JOptionPane.showMessageDialog(root, "這顆伺服和基準伺服的形狀對不起來(不是同一個零件的複本?),沒辦法自動算轉軸。", "伺服對照", JOptionPane.WARNING_MESSAGE); return; }
        double[] pv = AutoRig.apply(rt, refShaftCache[0]);
        double[] ax = AutoRig.norm(AutoRig.rotate(rt, refShaftCache[1]));
        for (int m = 1; m <= Rig.N; m++) if (m != motor && inst.name.equals(rig.joints[m].servo)) rig.joints[m].servo = "";
        Rig.Joint j = rig.joints[motor];
        j.servo = inst.name;
        for (int k = 0; k < 3; k++) { j.pivot[k] = Math.round(pv[k] * 100) / 100.0; j.axis[k] = Math.round(ax[k] * 1000) / 1000.0; }
        if (j.name.startsWith("馬達 ")) j.name = "馬達 " + motor + "(舵" + shortServoName(inst.name) + ")";
        for (Viewport3D.Part p : vp.parts) p.selected = false;
        changed();
        refreshMotorBoxes();
        loadFields();
        refreshParts();
        updateServoLabels();
        if (servoMotor != null && motor < Rig.N) servoMotor.setSelectedIndex(motor);       // 自動跳到下一顆馬達,連續指定比較快
    }

    static JComboBox<String> mirrorTarget, mirrorAxis;

    /** 預覽那一行字:顯示滑桿目前的位置值和換算出來的角度,拿來跟實機的角度對照 */
    static void updatePreviewText() {
        if (previewBox == null || previewSl == null) return;
        Rig.Joint j = rig.joints[curMotor()];
        double deg = j.sign * (previewSl.getValue() - j.zero) * j.degPer;
        previewBox.setText(String.format("預覽這一顆(其他在零位)  位置 %d = %+.1f 度", previewSl.getValue(), deg));
    }

    /**
     * 把馬達 from 的關節鏡射給馬達 to:轉軸位置對「身體左右對稱面」鏡射、轉軸方向鏡射、轉動方向反過來
     * (鏡射會讓旋轉方向反過來,所以同樣的馬達值會動出鏡像的動作)。對稱面通過所有零件外框的中心。
     */
    static void mirrorJoint(int from, int to, int axis) {
        if (rig.nodes != null) { pickInfo.setText("<html><font size=-1>結構模式不用鏡像複製(轉軸由結構決定)。</font></html>"); return; }
        if (from == to) { pickInfo.setText("<html><font size=-1>對面馬達不能是自己。</font></html>"); return; }
        if (vp.parts.isEmpty()) { pickInfo.setText("<html><font size=-1>還沒載入模型。</font></html>"); return; }
        double mn = 1e30, mx = -1e30;
        for (Viewport3D.Part p : vp.parts) { mn = Math.min(mn, p.mesh.min[axis]); mx = Math.max(mx, p.mesh.max[axis]); }
        double c = (mn + mx) / 2;
        Rig.Joint a = rig.joints[from], b = rig.joints[to];
        b.pivot = a.pivot.clone();
        b.pivot[axis] = 2 * c - a.pivot[axis];
        b.axis = a.axis.clone();
        b.axis[axis] = -a.axis[axis];
        b.sign = -a.sign;
        b.degPer = a.degPer;
        b.zero = a.zero;
        if (b.name.startsWith("馬達 ")) b.name = a.name + "(鏡像)";
        changed();
        refreshMotorBoxes();
        pickInfo.setText("<html><font size=-1>已把馬達 " + from + " 鏡射給馬達 " + to + "(對稱面在 " + "XYZ".charAt(axis) + " = " + String.format("%.1f", c) + ")。請切到馬達 " + to + " 用「預覽」確認轉動方向;掛在哪顆馬達上、零件指定要自己設。</font></html>");
    }

    static java.awt.event.ActionListener axisSetter(final double x, final double y, final double z) {
        return new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            updatingFields = true;
            axisSp[0].setValue(Double.valueOf(x)); axisSp[1].setValue(Double.valueOf(y)); axisSp[2].setValue(Double.valueOf(z));
            updatingFields = false;
            storeFields();
        } };
    }

    /** 0 正面 1 背面 2 左側 3 右側 4 上方 5 重設 */
    static void setView(int k) {
        boolean z = "Z".equals(vp.up);
        double[] yaws = {Math.PI, 0, -Math.PI / 2, Math.PI / 2, Math.PI, Math.PI - 0.5};   // 正面 = 有膝蓋的那一側 = 模型的 -Z 側
        double[] pitches = {0, 0, 0, 0, 1.45, 0.25};
        vp.yaw = yaws[k];
        vp.pitch = pitches[k];
        vp.repaint();
    }
}
