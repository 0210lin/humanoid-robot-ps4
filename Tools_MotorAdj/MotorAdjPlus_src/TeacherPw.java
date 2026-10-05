import java.awt.FlowLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

/**
 * 教師密碼:輸入密碼就解鎖(解鎖到關掉 MotorAdj 為止)。
 *   ・密碼不是明文存的:pw.dat(在 Tools_MotorAdj 資料夾)裡只有「加鹽的雜湊」,而且用老師的私鑰簽過章,
 *     學生沒辦法自己做一個有效的 pw.dat 來換成自己的密碼。
 *   ・老師換密碼:執行 %USERPROFILE%\MotorAdjPlus_teacher\set_password.bat(要有私鑰),會產生新的 pw.dat。
 *   ・猜錯 5 次要等 30 秒。
 * 注意:雜湊放在 pw.dat 裡,有心的學生可以拿去離線猜,所以請用夠長、不容易猜的密碼(建議 8 個字以上)。
 */
public class TeacherPw {
    static final String HEADER = "MotorAdjPlus-Password-1";
    static final int ITER = 120000;

    static volatile boolean session = false;
    static int fails = 0;
    static long lockedUntil = 0;

    static File pwFile() {
        String o = System.getProperty("plus.pwFile");
        if (o != null && !o.isEmpty()) return new File(o);
        return new File(Settings.dir, "pw.dat");
    }

    static byte[] derive(char[] pw, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, ITER, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static String sigPayload(String salt, String hash) { return "pw1|" + salt + "|" + hash; }

    /** 讀 pw.dat 並驗證簽章;回傳 {salt, hash},失敗回傳 null(原因放在 lastProblem) */
    static String lastProblem = "";

    static byte[][] load() {
        try {
            File f = pwFile();
            if (!f.isFile()) { lastProblem = "還沒有設定教師密碼(找不到 pw.dat)"; return null; }
            String salt = null, hash = null, sig = null;
            boolean head = false;
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\r?\n")) {
                line = line.trim();
                if (line.equals(HEADER)) { head = true; continue; }
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim();
                if (k.equals("salt")) salt = v;
                else if (k.equals("hash")) hash = v;
                else if (k.equals("sig")) sig = v;
            }
            if (!head || salt == null || hash == null || sig == null) { lastProblem = "pw.dat 格式不對"; return null; }
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(License.PUBLIC_KEY)));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(pk);
            s.update(sigPayload(salt, hash).getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(sig))) { lastProblem = "pw.dat 的簽章不正確(被改過,或不是老師產生的)"; return null; }
            return new byte[][] {Base64.getDecoder().decode(salt), Base64.getDecoder().decode(hash)};
        } catch (Throwable t) {
            lastProblem = "讀取 pw.dat 失敗:" + t.getMessage();
            return null;
        }
    }

    static boolean configured() { return load() != null; }

    static boolean isUnlocked() { return session; }

    static void lock() { session = false; }

    /** 試密碼。成功回傳 null(並解鎖);失敗回傳要顯示的訊息 */
    static synchronized String attempt(char[] pw) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return "猜錯太多次了,請 " + ((lockedUntil - now + 999) / 1000) + " 秒後再試。";
        byte[][] d = load();
        if (d == null) return lastProblem;
        try {
            byte[] h = derive(pw, d[0]);
            if (MessageDigest.isEqual(h, d[1])) {
                session = true;
                fails = 0;
                return null;
            }
        } catch (Throwable t) {
            return "驗證失敗:" + t.getMessage();
        }
        fails++;
        try { Thread.sleep(600); } catch (InterruptedException e) { }
        if (fails >= 5) {
            fails = 0;
            lockedUntil = System.currentTimeMillis() + 30000;
            return "密碼不對。已經錯了 5 次,請 30 秒後再試。";
        }
        return "密碼不對。";
    }

    /** 老師換密碼用(KeyTool setpw):回傳整份 pw.dat 的文字 */
    static String makeFile(java.security.PrivateKey pk, char[] pw) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s64 = Base64.getEncoder().encodeToString(salt);
        String h64 = Base64.getEncoder().encodeToString(derive(pw, salt));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pk);
        s.update(sigPayload(s64, h64).getBytes(StandardCharsets.UTF_8));
        return HEADER + "\nsalt=" + s64 + "\nhash=" + h64 + "\nsig=" + Base64.getEncoder().encodeToString(s.sign()) + "\n";
    }

    // ---------- 畫面 ----------

    /** 功能要用之前:跳出密碼框,解鎖成功回傳 true */
    static boolean prompt(String feature) {
        if (!configured()) {
            JOptionPane.showMessageDialog(null, "「" + feature + "」是教師功能。\n\n" + lastProblem + "。\n請老師先設定密碼(set_password.bat)。", "教師功能", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String msg = "「" + feature + "」是教師功能,請輸入教師密碼:";
        while (true) {
            JPasswordField f = new JPasswordField(18);
            Object[] body = {msg, f};
            JOptionPane op = new JOptionPane(body, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            JDialog d = op.createDialog(null, "教師功能");
            d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                public void windowGainedFocus(java.awt.event.WindowEvent e) { }
            });
            f.addAncestorListener(new javax.swing.event.AncestorListener() {
                public void ancestorAdded(javax.swing.event.AncestorEvent e) { e.getComponent().requestFocusInWindow(); }
                public void ancestorRemoved(javax.swing.event.AncestorEvent e) { }
                public void ancestorMoved(javax.swing.event.AncestorEvent e) { }
            });
            d.setVisible(true);
            d.dispose();
            Object v = op.getValue();
            if (!(v instanceof Integer) || ((Integer) v).intValue() != JOptionPane.OK_OPTION) return false;
            String err = attempt(f.getPassword());
            f.setText("");
            if (err == null) { License.refreshButton(); return true; }
            msg = err + "請再輸入一次,或按取消:";
        }
    }

    /** 「設定」分頁的「教師解鎖」按鈕 */
    static void showDialog() {
        final JDialog dlg = new JDialog((java.awt.Frame) null, "教師解鎖", true);
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        final JLabel state = new JLabel();
        state.setAlignmentX(0f);
        p.add(state);
        p.add(javax.swing.Box.createVerticalStrut(10));
        JLabel l = new JLabel("教師密碼:");
        l.setAlignmentX(0f);
        p.add(l);
        final JPasswordField f = new JPasswordField(20);
        f.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        f.setAlignmentX(0f);
        p.add(f);
        p.add(javax.swing.Box.createVerticalStrut(10));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setAlignmentX(0f);
        final JButton ok = new JButton("解鎖");
        JButton lock = new JButton("鎖回去");
        JButton adv = new JButton("進階(金鑰檔)…");
        JButton close = new JButton("關閉");
        row.add(ok);
        row.add(lock);
        row.add(adv);
        row.add(close);
        p.add(row);

        final Runnable refresh = new Runnable() { public void run() {
            License.invalidate();
            boolean u = License.unlocked();
            state.setText(u ? "狀態:已解鎖(到關掉 MotorAdj 為止)" : "狀態:尚未解鎖");
            License.refreshButton();
        } };
        refresh.run();
        java.awt.event.ActionListener go = new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) {
            String err = attempt(f.getPassword());
            f.setText("");
            if (err != null) JOptionPane.showMessageDialog(dlg, err, "教師解鎖", JOptionPane.WARNING_MESSAGE);
            refresh.run();
        } };
        ok.addActionListener(go);
        f.addActionListener(go);
        lock.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { lock(); refresh.run(); } });
        adv.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { License.showDialog(); refresh.run(); } });
        close.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { dlg.dispose(); } });
        dlg.setContentPane(p);
        dlg.getRootPane().setDefaultButton(ok);
        dlg.pack();
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);
    }
}
