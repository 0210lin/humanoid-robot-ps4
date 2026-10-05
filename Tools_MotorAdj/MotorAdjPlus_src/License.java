import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 教師金鑰:有些功能不開放給學生,要有老師簽發的金鑰檔才能用。
 *   ・金鑰檔綁定「某一台電腦」:裡面寫了那台電腦的識別碼,複製到別台電腦就無效
 *   ・用 RSA 簽章,程式裡只放公鑰;沒有老師的私鑰,學生無法自己做出有效的金鑰檔
 *   ・金鑰檔放在每台電腦自己的使用者資料夾(%APPDATA%\MotorAdjPlus\teacher.key),不在專案資料夾裡,
 *     所以把整個專案資料夾複製給學生,金鑰不會跟著過去
 * 注意:這是用來擋「一般情況」。Java 程式可以被反編譯,有心人還是可能改掉檢查,沒辦法 100% 防。
 */
public class License {
    /** 老師的公鑰(私鑰只在老師手上,絕對不要放進專案) */
    static final String PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvd9IJ9oa9WgJLPGUIzCobg2PJqX3EAd6fT0Aa1sWaInuCzzk0milskz7PEWD/xv5ODQgYDNiMQAKS2ewrkjISeKNUPK9gKypxwvVAJ3EWGh4uYaEO0iHPwsGaiaOyI/CbzI6116Qdhf6nFxPqqxMVNOyfhYggBpbHXXVn/8mnxwKeMm+4fuqwJytuuAz3oIFV3hdvK9sl09HxcGNS11xsFaQTL67ZJUsVoqwnLvCM0x5bXLTNZgiGJPq9Wr5AqJcXZ1oPTbDc6cgjMmuiVL4QqdeRWvn2se+Lr5sfzrDgsunzWYRZYcxBY/qXihzfg5O0ZAxT/yIWrA3GBp3rEQlyQIDAQAB";

    static final String HEADER = "MotorAdjPlus-License-1";

    // ---------- 狀態 ----------

    static class Info {
        boolean ok = false;
        String name = "";
        String machine = "";
        String expires = "never";
        String message = "";
    }

    static Info cached = null;
    static long cachedAt = 0;

    static File keyFile() {
        String o = System.getProperty("plus.licenseFile");
        if (o != null && !o.isEmpty()) return new File(o);
        String appData = System.getenv("APPDATA");
        File base = (appData != null && !appData.isEmpty()) ? new File(appData) : new File(System.getProperty("user.home"));
        return new File(new File(base, "MotorAdjPlus"), "teacher.key");
    }

    /**
     * 教師鎖的總開關。false = 完全不鎖(所有功能都能用,「設定」分頁也不顯示「教師功能」區塊)。
     * 之後要啟用:改成 true,重新編譯、打包 MotorAdjPlus.jar(密碼檔 pw.dat 用 set_password.bat 產生)。
     */
    static final boolean ENABLED = false;

    /** 是否已解鎖(結果會快取 5 秒,避免一直讀檔) */
    static boolean unlocked() {
        if (!ENABLED) return true;
        if (TeacherPw.isUnlocked()) return true;     // 輸入過教師密碼(到關掉 MotorAdj 為止)
        long now = System.currentTimeMillis();
        if (cached == null || now - cachedAt > 5000) {
            cached = check(keyFile());
            cachedAt = now;
        }
        return cached.ok;
    }

    static void invalidate() { cached = null; }

    /** 功能要用之前呼叫:沒解鎖就跳說明,回傳 false */
    static boolean require(String feature) {
        if (unlocked()) return true;
        return TeacherPw.prompt(feature);     // 跳出密碼框;輸入正確就解鎖並繼續
    }

    // ---------- 本機識別碼 ----------

    static String rawMachine() {
        try {
            Process p = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid").redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            for (String line : bo.toString("UTF-8").split("\r?\n")) {
                line = line.trim();
                if (line.startsWith("MachineGuid")) {
                    String[] parts = line.split("\\s+");
                    String g = parts[parts.length - 1].trim();
                    if (g.length() >= 8) return g;
                }
            }
        } catch (Throwable t) {
            // 退而求其次
        }
        String host = System.getenv("COMPUTERNAME");
        return "fallback|" + (host == null ? "" : host) + "|" + System.getProperty("os.name") + "|" + System.getProperty("user.name");
    }

    /** 形如 A1B2-C3D4-E5F6-7890-ABCD 的識別碼(由電腦的安裝識別碼雜湊而來,看不出原始值) */
    static String machineId() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("MotorAdjPlus|" + rawMachine()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                if (i > 0 && i % 2 == 0) sb.append('-');
                sb.append(String.format("%02X", h[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    static String normId(String s) { return s == null ? "" : s.replaceAll("[^0-9A-Za-z]", "").toUpperCase(); }

    // ---------- 驗證 ----------

    static String payload(String name, String machine, String expires) {
        return "v1|" + name + "|" + machine + "|" + expires;
    }

    static Info check(File f) {
        Info r = new Info();
        try {
            if (!f.isFile()) { r.message = "還沒有金鑰檔"; return r; }
            String name = null, machine = null, expires = "never", sig = null;
            boolean head = false;
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\r?\n")) {
                line = line.trim();
                if (line.equals(HEADER)) { head = true; continue; }
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim();
                if (k.equals("name")) name = v;
                else if (k.equals("machine")) machine = v;
                else if (k.equals("expires")) expires = v;
                else if (k.equals("sig")) sig = v;
            }
            if (!head || name == null || machine == null || sig == null) { r.message = "金鑰檔格式不對"; return r; }
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(pk);
            s.update(payload(name, machine, expires).getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(sig))) { r.message = "金鑰檔的簽章不正確(被改過,或不是老師簽發的)"; return r; }
            r.name = name;
            r.machine = machine;
            r.expires = expires;
            if (!normId(machine).equals(normId(machineId()))) { r.message = "這份金鑰是給別台電腦的(綁定的電腦不同)"; return r; }
            if (!expires.equals("never")) {
                Date d = new SimpleDateFormat("yyyy-MM-dd").parse(expires);
                if (new Date().getTime() > d.getTime() + 24L * 3600 * 1000) { r.message = "金鑰已過期(" + expires + ")"; return r; }
            }
            r.ok = true;
            r.message = "已解鎖:" + name + (expires.equals("never") ? "" : "(到 " + expires + ")");
        } catch (Throwable t) {
            r.message = "讀取金鑰檔失敗:" + t.getMessage();
        }
        return r;
    }

    // ---------- 授權碼(把金鑰檔內容變成一串文字,可以貼在訊息裡傳) ----------

    static final String CODE_PREFIX = "MAP1-";

    static String toCode(String keyText) {
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(keyText.getBytes(StandardCharsets.UTF_8));
    }

    static String fromCode(String code) throws Exception {
        String c = code == null ? "" : code.replaceAll("\\s+", "");
        if (!c.startsWith(CODE_PREFIX)) throw new Exception("這不是授權碼(應該以 " + CODE_PREFIX + " 開頭)");
        return new String(Base64.getUrlDecoder().decode(c.substring(CODE_PREFIX.length())), StandardCharsets.UTF_8);
    }

    /** 驗證金鑰檔內容,可以用就存成這台電腦的金鑰。回傳驗證結果(ok=false 時沒有存) */
    static Info importText(String keyText) {
        File tmp = null;
        try {
            tmp = File.createTempFile("mapkey", ".key");
            Files.write(tmp.toPath(), keyText.getBytes(StandardCharsets.UTF_8));
            Info i = check(tmp);
            if (i.ok) {
                File dst = keyFile();
                dst.getParentFile().mkdirs();
                Files.copy(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            invalidate();
            return i;
        } catch (Throwable t) {
            Info r = new Info();
            r.message = "處理失敗:" + t.getMessage();
            return r;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    static void pasteCode(java.awt.Component owner, Runnable after) {
        javax.swing.JTextArea ta = new javax.swing.JTextArea(7, 52);
        ta.setLineWrap(true);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(ta);
        Object[] msg = {"把老師給的授權碼整串貼進來(MAP1- 開頭,有換行也沒關係):", sp};
        if (JOptionPane.showConfirmDialog(owner, msg, "貼上授權碼", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            Info i = importText(fromCode(ta.getText()));
            if (!i.ok) JOptionPane.showMessageDialog(owner, "這個授權碼不能用:" + i.message, "教師解鎖", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "這個授權碼不能用:" + (ex instanceof IllegalArgumentException ? "內容不完整或有缺字(請重新複製整串)" : ex.getMessage()), "教師解鎖", JOptionPane.WARNING_MESSAGE);
        }
        if (after != null) after.run();
    }

    // ---------- 畫面 ----------

    static JButton btn;

    static void refreshButton() {
        if (btn == null) return;
        btn.setText(unlocked() ? "教師:已解鎖" : "教師解鎖…");
    }

    static void install(Container panel) { /* 教師按鈕改放在「設定」分頁,見 installSettings */ }

    static void installSettings(Container panel) {
        if (!ENABLED) return;
        btn = new JButton("教師解鎖…");
        btn.setBounds(40 + 24, 594, 316, 32);
        btn.setToolTipText("教師功能(送到機器人、批量載入幀、各馬達範圍、路徑設定)輸入教師密碼(或有老師簽發的金鑰)");
        btn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { TeacherPw.showDialog(); }
        });
        panel.add(btn);                                                // 先放按鈕、再放外框(跟「設定」分頁其他區塊一樣,外框在後面才不會擋住點擊)
        panel.add(SettingsLayout.frame("教師功能", 40, 560, 380, 78));
        refreshButton();
        new javax.swing.Timer(3000, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { refreshButton(); }
        }).start();
    }

    static void showDialog() {
        final JDialog dlg = new JDialog((java.awt.Frame) null, "教師解鎖", true);
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        final JLabel state = new JLabel();
        state.setAlignmentX(0f);
        p.add(state);
        p.add(javax.swing.Box.createVerticalStrut(10));
        JLabel l1 = new JLabel("這台電腦的識別碼(拿給老師簽發金鑰檔用):");
        l1.setAlignmentX(0f);
        p.add(l1);
        final JTextField id = new JTextField(machineId(), 26);
        id.setEditable(false);
        id.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        id.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, id.getPreferredSize().height));
        id.setAlignmentX(0f);
        p.add(id);
        p.add(javax.swing.Box.createVerticalStrut(10));
        JLabel l2 = new JLabel("<html>金鑰檔會存在這台電腦的使用者資料夾,不在專案資料夾裡,<br>所以複製專案給別人,金鑰不會跟著過去。</html>");
        l2.setAlignmentX(0f);
        p.add(l2);
        p.add(javax.swing.Box.createVerticalStrut(12));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setAlignmentX(0f);
        JButton copy = new JButton("複製識別碼");
        JButton imp = new JButton("匯入金鑰檔…");
        JButton paste = new JButton("貼上授權碼…");
        JButton remove = new JButton("移除金鑰(鎖回去)");
        JButton close = new JButton("關閉");
        row.add(copy);
        row.add(paste);
        row.add(imp);
        p.add(row);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row2.setAlignmentX(0f);
        row2.add(remove);
        row2.add(close);
        p.add(javax.swing.Box.createVerticalStrut(6));
        p.add(row2);

        final Runnable refresh = new Runnable() { public void run() {
            invalidate();
            Info i = check(keyFile());
            state.setText((i.ok ? "狀態:" : "狀態:尚未解鎖(") + i.message + (i.ok ? "" : ")"));
            refreshButton();
        } };
        refresh.run();
        copy.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(id.getText()), null);
        } });
        paste.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { pasteCode(dlg, refresh); } });
        imp.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("選擇老師給的金鑰檔(teacher.key)");
            fc.setFileFilter(new FileNameExtensionFilter("金鑰檔 (*.key)", "key"));
            if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            Info i = check(fc.getSelectedFile());
            if (!i.ok) { JOptionPane.showMessageDialog(dlg, "這份金鑰不能用:" + i.message, "教師解鎖", JOptionPane.WARNING_MESSAGE); return; }
            try {
                File dst = keyFile();
                dst.getParentFile().mkdirs();
                Files.copy(fc.getSelectedFile().toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "存檔失敗:" + ex.getMessage(), "教師解鎖", JOptionPane.ERROR_MESSAGE);
                return;
            }
            refresh.run();
        } });
        remove.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            keyFile().delete();
            refresh.run();
        } });
        close.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { dlg.dispose(); } });
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);
    }
}
