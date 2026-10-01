import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Properties;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 外掛的路徑設定。設定存在 MotorAdjPlus.jar 旁邊的 MotorAdjPlus.properties。
 * 沒有設定的項目,就用預設值(依 jar 所在位置推算)。
 */
public class Settings {
    static File dir;    // MotorAdjPlus.jar 所在資料夾(Tools_MotorAdj)
    static File root;   // 它的上一層
    static final Properties p = new Properties();

    static void init(File toolDir, File rootDir) {
        dir = toolDir;
        root = rootDir;
        load();
    }

    static File file() {
        return new File(dir, "MotorAdjPlus.properties");
    }

    static void load() {
        p.clear();
        try {
            FileInputStream in = new FileInputStream(file());
            try {
                p.load(new InputStreamReader(in, "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            // 沒有設定檔就用預設值
        }
    }

    static void save() throws Exception {
        FileOutputStream out = new FileOutputStream(file());
        try {
            p.store(new OutputStreamWriter(out, "UTF-8"), "MotorAdjPlus settings");
        } finally {
            out.close();
        }
    }

    // ---- 預設值 ----
    static File defSketch()   { return new File(root, "Micro_Robot"); }
    static File defMotorSrc() { return new File(dir, "motor.h"); }
    static File defCli()      { return new File(root, "Tools_Arduino\\arduino-cli.exe"); }

    // ---- 目前使用的值 ----
    static String raw(String k) { return p.getProperty(k, "").trim(); }

    static File sketch()   { return raw("sketchDir").isEmpty() ? defSketch()   : new File(raw("sketchDir")); }
    static File motorSrc() { return raw("motorSrc").isEmpty()  ? defMotorSrc() : new File(raw("motorSrc")); }
    static File cli()      { return raw("cli").isEmpty()       ? defCli()      : new File(raw("cli")); }
    static String port()   { return raw("port"); }

    // ================= 設定視窗 =================

    static JTextField addRow(JPanel panel, int row, String label, String value, final int mode, final String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), c);

        final JTextField tf = new JTextField(value, 38);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(tf, c);

        if (mode >= 0) {
            JButton b = new JButton("瀏覽…");
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(b, c);
            b.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    JFileChooser fc = new JFileChooser();
                    File cur = new File(tf.getText().trim());
                    if (cur.exists()) {
                        fc.setCurrentDirectory(cur.isDirectory() ? cur : cur.getParentFile());
                    }
                    if (mode == 0) {
                        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    } else if (mode == 1) {
                        fc.setFileFilter(new FileNameExtensionFilter("motor.h (*.h)", "h"));
                    } else {
                        fc.setFileFilter(new FileNameExtensionFilter("arduino-cli (*.exe)", "exe"));
                    }
                    if (fc.showOpenDialog(tf) == JFileChooser.APPROVE_OPTION) {
                        tf.setText(fc.getSelectedFile().getAbsolutePath());
                    }
                }
            });
        }
        if (hint != null) {
            c.gridy = row;
        }
        return tf;
    }

    static void showDialog(Component owner) {
        final JDialog d = new JDialog((java.awt.Frame) null, "路徑設定", true);
        JPanel form = new JPanel(new GridBagLayout());

        // 欄位預設是空白;空白 = 自動(依資料夾位置推算)。下方會顯示目前實際使用的位置。
        final JTextField fSketch = addRow(form, 0, "Arduino 程式資料夾(有 custom.ino):", raw("sketchDir"), 0, null);
        final JTextField fMotor = addRow(form, 1, "motor.h 來源(MotorAdj 輸出的):", raw("motorSrc"), 1, null);
        final JTextField fCli = addRow(form, 2, "arduino-cli 位置:", raw("cli"), 2, null);
        final JTextField fPort = addRow(form, 3, "序列埠(例如 COM7):", port(), -1, null);

        // 外觀
        GridBagConstraints tc = new GridBagConstraints();
        tc.insets = new Insets(4, 6, 4, 6);
        tc.gridy = 4;
        tc.gridx = 0;
        tc.anchor = GridBagConstraints.WEST;
        form.add(new JLabel("外觀:"), tc);
        final String[] themeNames = {"淺色(預設)", "深色", "原本的樣子"};
        final String[] themeKeys = {"light", "dark", "classic"};
        final javax.swing.JComboBox<String> fTheme = new javax.swing.JComboBox<String>(themeNames);
        for (int i = 0; i < themeKeys.length; i++) {
            if (themeKeys[i].equals(Theme.name())) fTheme.setSelectedIndex(i);
        }
        tc.gridx = 1;
        form.add(fTheme, tc);

        JLabel tip = new JLabel("<html>欄位留空 = 自動。目前實際使用:<br>"
                + "・程式資料夾:" + sketch().getPath() + "<br>"
                + "・motor.h 來源:" + motorSrc().getPath() + "<br>"
                + "・arduino-cli:" + cli().getPath() + "<br>"
                + "・序列埠:" + (port().isEmpty() ? "自動偵測 Arduino Micro" : port()) + "<br><br>"
                + "更新時:先把「motor.h 來源」複製到「程式資料夾」(來源比較新才複製),再編譯、燒錄。<br>"
                + "按「儲存」後會記住,下次開啟自動套用,不用每次重設。</html>");
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 5; c.gridwidth = 3; c.insets = new Insets(8, 6, 4, 6); c.anchor = GridBagConstraints.WEST;
        form.add(tip, c);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton ok = new JButton("儲存");
        JButton cancel = new JButton("取消");
        JButton reset = new JButton("還原預設");
        btns.add(reset);
        btns.add(ok);
        btns.add(cancel);

        d.getContentPane().setLayout(new java.awt.BorderLayout());
        d.getContentPane().add(form, java.awt.BorderLayout.CENTER);
        d.getContentPane().add(btns, java.awt.BorderLayout.SOUTH);

        reset.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                fSketch.setText("");
                fMotor.setText("");
                fCli.setText("");
                fPort.setText("");
                fTheme.setSelectedIndex(0);
            }
        });
        cancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                d.dispose();
            }
        });
        ok.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String st = fSketch.getText().trim();
                String ct = fCli.getText().trim();
                File s = st.isEmpty() ? defSketch() : new File(st);
                File cliFile = ct.isEmpty() ? defCli() : new File(ct);
                StringBuilder warn = new StringBuilder();
                if (!new File(s, "custom.ino").exists()) warn.append("・「Arduino 程式資料夾」裡找不到 custom.ino\n");
                if (!cliFile.exists()) warn.append("・找不到 arduino-cli(請先執行「安裝.bat」)\n");
                if (warn.length() > 0) {
                    int r = JOptionPane.showConfirmDialog(d, warn + "\n還是要儲存嗎?", "路徑設定",
                            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (r != JOptionPane.YES_OPTION) return;
                }
                if (!CodeTab.edits.isEmpty() && !new File(s, "custom.ino").equals(CodeTab.customFile)) {
                    int r = JOptionPane.showConfirmDialog(d, "變更資料夾會放棄「動作程式」分頁還沒寫入的修改,確定嗎?",
                            "路徑設定", JOptionPane.YES_NO_OPTION);
                    if (r != JOptionPane.YES_OPTION) return;
                }
                put("sketchDir", fSketch.getText().trim(), defSketch());
                put("motorSrc", fMotor.getText().trim(), defMotorSrc());
                put("cli", fCli.getText().trim(), defCli());
                p.setProperty("port", fPort.getText().trim());
                String newTheme = themeKeys[fTheme.getSelectedIndex()];
                boolean themeChanged = !newTheme.equals(Theme.name());
                p.setProperty("theme", newTheme.equals("light") ? "" : newTheme);
                try {
                    save();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(d, "無法儲存設定:" + ex.getMessage());
                    return;
                }
                if (themeChanged) {
                    Theme.apply(newTheme);
                }
                if (CodeTab.combo != null) {
                    CodeTab.customFile = new File(sketch(), "custom.ino");
                    CodeTab.reload();
                }
                d.dispose();
            }
        });

        d.pack();
        d.setMinimumSize(new Dimension(760, d.getHeight()));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /** 跟預設值一樣就不存(之後預設值改了也會跟著變) */
    static void put(String key, String value, File def) {
        if (value.isEmpty() || new File(value).equals(def)) {
            p.setProperty(key, "");
        } else {
            p.setProperty(key, value);
        }
    }
}
