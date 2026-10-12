import java.awt.Color;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/**
 * 「設定」分頁右下角的三張卡片:
 *   1. 機器人狀態:有沒有偵測到 Arduino Micro、程式和機器人上次上傳的是否一樣、上次上傳時間
 *   2. Micro 空間用量:程式空間 / 記憶體用了多少、還能放幾幀
 *   3. 環境檢查:Java、arduino-cli、Arduino AVR 支援、程式資料夾
 */
public class StatusCards {

    static Color OK = new Color(0x2E, 0x9E, 0x5B);
    static Color WARN = new Color(0xD9, 0x82, 0x00);
    static Color BAD = new Color(0xD6, 0x45, 0x45);

    static JPanel host;
    static JPanel card1, card2, card3;   // 三張卡片(版面程式會調整位置)
    static JLabel stPort, stAdj, stSync, stLast, stFiles;
    static JProgressBar flashBar, ramBar;
    static JLabel flashTxt, ramTxt, frameTxt, planTxt, planTxt2;
    static JLabel evJava, evCli, evAvr, evSketch, evPort;
    static volatile String port = null;
    static volatile boolean portChecked = false;
    static int tick = 0;

    // ---------- 版面小工具 ----------

    static JLabel val() {
        JLabel l = new JLabel(" ");
        return l;
    }

    static JPanel card(String title, int x, int y, int w, int h) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setBounds(x, y, w, h);
        return p;
    }

    static void row(JPanel p, int r, String key, java.awt.Component v) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 8, 2, 8);
        c.gridy = r;
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        JLabel k = new JLabel(key);
        k.setForeground(Color.GRAY);
        p.add(k, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(v, c);
    }

    /** 讓卡片裡的內容靠上排列(多出來的高度留在下面) */
    static void filler(JPanel p, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weighty = 1;
        c.fill = GridBagConstraints.VERTICAL;
        p.add(new JLabel(" "), c);
    }

    static void setText(final JLabel l, final String text, final Color color) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                l.setText(text);
                l.setForeground(color);
            }
        });
    }

    static String time(long ms) {
        return new SimpleDateFormat("MM/dd HH:mm:ss").format(new Date(ms));
    }

    // ---------- 安裝 ----------

    static void install(Container sp) {
        host = (JPanel) sp;

        // 1. 機器人狀態
        JPanel c1 = card("機器人狀態", 440, 200, 570, 140);
        card1 = c1;
        stPort = val(); stAdj = val(); stSync = val(); stLast = val(); stFiles = val();
        row(c1, 0, "Arduino Micro", stPort);
        row(c1, 1, "MotorAdj 串口", stAdj);
        row(c1, 2, "程式同步", stSync);
        row(c1, 3, "上次上傳", stLast);
        row(c1, 4, "檔案修改", stFiles);
        sp.add(c1);

        // 2. Micro 空間
        JPanel c2 = card("Micro 空間用量", 440, 350, 280, 210);
        card2 = c2;
        flashBar = new JProgressBar(0, 100);
        flashBar.setStringPainted(true);
        ramBar = new JProgressBar(0, 100);
        ramBar.setStringPainted(true);
        flashTxt = val(); ramTxt = val(); frameTxt = val(); planTxt = val(); planTxt2 = val();
        row(c2, 0, "程式空間", flashBar);
        row(c2, 1, "", flashTxt);
        row(c2, 2, "記憶體", ramBar);
        row(c2, 3, "", ramTxt);
        row(c2, 4, "目前幀數", frameTxt);
        row(c2, 5, "預估", planTxt);
        row(c2, 6, "", planTxt2);
        filler(c2, 7);
        sp.add(c2);

        // 3. 環境檢查
        JPanel c3 = card("環境檢查", 730, 350, 280, 210);
        card3 = c3;
        evJava = val(); evCli = val(); evAvr = val(); evSketch = val(); evPort = val();
        row(c3, 0, "Java", evJava);
        row(c3, 1, "arduino-cli", evCli);
        row(c3, 2, "AVR 支援", evAvr);
        row(c3, 3, "程式資料夾", evSketch);
        row(c3, 4, "Micro 序列埠", evPort);
        JButton recheck = new JButton("重新檢查");
        recheck.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { checkEnvironment(); detectPort(); }
        });
        GridBagConstraints bc = new GridBagConstraints();
        bc.gridy = 5; bc.gridx = 0; bc.gridwidth = 2; bc.insets = new Insets(8, 8, 4, 8); bc.anchor = GridBagConstraints.WEST;
        c3.add(recheck, bc);
        filler(c3, 6);
        sp.add(c3);

        sp.repaint();
        refreshLocal();
        checkEnvironment();
        detectPort();

        // 這個分頁有顯示時,才定時更新(避免背景一直在跑)
        new javax.swing.Timer(2000, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (host == null || !host.isShowing()) return;
                refreshLocal();
                if (++tick % 3 == 0) detectPort();
            }
        }).start();
    }

    // ---------- 區域資料(很快,在畫面執行緒更新) ----------

    static void refreshLocal() {
        try {
            File sk = Settings.sketch();
            File ino = new File(sk, "custom.ino");
            File mh = new File(sk, "motor.h");

            // 偵測到的序列埠
            if (!portChecked) {
                stPort.setText("偵測中…");
                stPort.setForeground(Color.GRAY);
            } else if (port != null) {
                stPort.setText("已偵測到(" + port + ")");
                stPort.setForeground(OK);
            } else {
                stPort.setText("沒有偵測到(USB 有接好嗎?)");
                stPort.setForeground(BAD);
            }

            // MotorAdj 自己的串口連線
            try {
                Object outer = Plus.outer();
                Object ms = Plus.fld(outer, "MotorSet");
                Object open = Plus.fld(ms, "SerialPort_isOpen");
                boolean o = Boolean.TRUE.equals(open);
                stAdj.setText(o ? "已連線(要更新到機器人時會自動中斷)" : "未連線");
                stAdj.setForeground(o ? WARN : Color.GRAY);
            } catch (Throwable t) {
                stAdj.setText("-");
            }

            // 程式同步狀態
            String last = Settings.raw("lastUpload");
            long lastTime = 0;
            try { lastTime = Long.parseLong(Settings.raw("lastUploadTime")); } catch (Exception e) { }
            StringBuilder pending = new StringBuilder();
            int edits = 0;
            for (Map.Entry<String, String> en : CodeTab.snapshotEdits().entrySet()) edits++;
            if (edits > 0) pending.append("動作程式分頁有 ").append(edits).append(" 段修改還沒寫入;");
            File src = Settings.motorSrc();
            if (src.exists() && mh.exists() && src.lastModified() > mh.lastModified()
                    && !java.util.Arrays.equals(Files.readAllBytes(src.toPath()), Files.readAllBytes(mh.toPath()))) {
                pending.append("有新輸出的 motor.h 還沒複製;");
            }
            String cur = Plus.sketchHash(sk);
            if (last.isEmpty()) {
                stSync.setText("尚未上傳過(按一次「更新到機器人」就會記住)");
                stSync.setForeground(WARN);
            } else if (!cur.equals(last) || pending.length() > 0) {
                String why = pending.length() > 0 ? pending.toString() : "";
                if (!cur.equals(last)) {
                    if (lastTime > 0 && ino.lastModified() > lastTime) why += "custom.ino 改過;";
                    if (lastTime > 0 && mh.lastModified() > lastTime) why += "motor.h 改過;";
                    if (why.isEmpty()) why = "內容和上次上傳的不同;";
                }
                stSync.setText("有尚未上傳的變更:" + why.replaceAll(";$", ""));
                stSync.setForeground(WARN);
            } else {
                stSync.setText("已同步(和上次上傳的一樣)");
                stSync.setForeground(OK);
            }
            stLast.setText(lastTime > 0 ? time(lastTime) : "-");
            stLast.setForeground(Color.GRAY);
            stFiles.setText("custom.ino " + (ino.exists() ? time(ino.lastModified()) : "找不到")
                    + "   motor.h " + (mh.exists() ? time(mh.lastModified()) : "找不到"));
            stFiles.setForeground(Color.GRAY);

            refreshSpace();
        } catch (Throwable t) {
            // 畫面更新失敗不影響其他功能
        }
    }

    static void refreshSpace() {
        int used = Background.readInt("flashUsed", 0, 0, 100000000);
        int max = Background.readInt("flashMax", 28672, 1, 100000000);
        int ramUsed = Background.readInt("ramUsed", 0, 0, 100000000);
        int ramMax = Background.readInt("ramMax", 2560, 1, 100000000);
        int frames = Background.readInt("flashFrames", 0, 0, 100000);
        int nowFrames = frames;
        try { nowFrames = AddFrames.frameCount(); } catch (Throwable t) { }

        if (used <= 0) {
            flashBar.setValue(0);
            flashBar.setString("-");
            flashTxt.setText("按一次「更新到機器人」後會顯示");
            flashTxt.setForeground(Color.GRAY);
        } else {
            int pct = used * 100 / max;
            flashBar.setValue(pct);
            flashBar.setString(pct + "%");
            flashBar.setForeground(pct >= 95 ? BAD : pct >= 85 ? WARN : OK);
            flashTxt.setText(String.format("%,d / %,d 位元組", used, max));
            flashTxt.setForeground(Color.GRAY);
        }
        if (ramUsed <= 0) {
            ramBar.setValue(0);
            ramBar.setString("-");
            ramTxt.setText(" ");
        } else {
            int pct = ramUsed * 100 / ramMax;
            ramBar.setValue(pct);
            ramBar.setString(pct + "%");
            ramBar.setForeground(pct >= 90 ? BAD : pct >= 75 ? WARN : OK);
            ramTxt.setText(String.format("%,d / %,d 位元組", ramUsed, ramMax));
            ramTxt.setForeground(Color.GRAY);
        }
        frameTxt.setText(nowFrames + " 幀(上限 " + AddFrames.limit() + ")");
        int lim = AddFrames.limit();
        int pb = AddFrames.projectedBytes(lim);
        if (pb < 0) {
            planTxt.setText("-");
            planTxt2.setText(" ");
        } else {
            int pct = pb * 100 / max;
            planTxt.setText("加到 " + lim + " 幀 → 約 " + pct + "%");
            planTxt.setForeground(pct >= 95 ? BAD : pct >= 85 ? WARN : Color.GRAY);
            planTxt2.setText(String.format("剩約 %,d 位元組給你的程式", Math.max(0, max - pb)));
            planTxt2.setForeground(Color.GRAY);
        }
    }

    // ---------- 要跑外部程式的檢查(在背景執行緒) ----------

    static String runCli(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = Settings.cli().getPath();
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static void detectPort() {
        new Thread(new Runnable() {
            public void run() {
                String out = runCli("board", "list");
                String found = null;
                if (out != null) {
                    for (String l : out.split("\n")) {
                        String low = l.toLowerCase();
                        if (low.contains("micro") || low.contains("leonardo")) {
                            found = l.trim().split("\\s+")[0];
                            break;
                        }
                    }
                }
                port = found;
                portChecked = true;
                final String f = found;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        refreshLocal();
                        if (f != null) setText(evPort, "正常(" + f + ")", OK);
                        else setText(evPort, "沒有偵測到", BAD);
                    }
                });
            }
        }).start();
    }

    static void checkEnvironment() {
        setText(evJava, "檢查中…", Color.GRAY);
        setText(evCli, "檢查中…", Color.GRAY);
        setText(evAvr, "檢查中…", Color.GRAY);
        setText(evSketch, "檢查中…", Color.GRAY);
        new Thread(new Runnable() {
            public void run() {
                setText(evJava, "正常(" + System.getProperty("java.version") + ")", OK);

                File cli = Settings.cli();
                if (!cli.exists()) {
                    setText(evCli, "缺少 → 請執行「安裝.bat」", BAD);
                    setText(evAvr, "-", Color.GRAY);
                } else {
                    String v = runCli("version");
                    String ver = "";
                    if (v != null) {
                        for (String tok : v.split("\\s+")) {
                            if (tok.length() > 0 && Character.isDigit(tok.charAt(0)) && tok.contains(".")) { ver = tok; break; }
                        }
                    }
                    setText(evCli, "正常" + (ver.isEmpty() ? "" : "(" + ver + ")"), OK);

                    String cores = runCli("core", "list");
                    boolean avr = cores != null && cores.contains("arduino:avr");
                    setText(evAvr, avr ? "正常" : "缺少 → 請執行「安裝.bat」", avr ? OK : BAD);
                }

                File sk = Settings.sketch();
                boolean ino = new File(sk, "custom.ino").exists(), mh = new File(sk, "motor.h").exists();
                if (ino && mh) setText(evSketch, "正常", OK);
                else setText(evSketch, "缺少 " + (ino ? "" : "custom.ino ") + (mh ? "" : "motor.h"), BAD);
            }
        }).start();
    }
}
