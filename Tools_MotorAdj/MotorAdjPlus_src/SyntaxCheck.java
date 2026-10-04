import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 「動作程式」分頁的「檢查語法」:
 *   只編譯、不燒錄,也不會動你的 custom.ino。
 *   做法:把 Micro_Robot 資料夾複製到暫存資料夾,把「動作程式」分頁裡【還沒寫入】的修改套進暫存的 custom.ino,
 *   (motor.h 如果 Tools_MotorAdj 裡的比較新,也用比較新的),再用 arduino-cli 編譯。
 *   結果用中文列出:編譯通過(並顯示程式空間用量),或是哪一行、什麼錯誤、常見原因。
 */
public class SyntaxCheck {

    static boolean running = false;
    static JDialog dlg;
    static JTextArea out;

    static final Pattern DIAG = Pattern.compile("^(.*?)([\\w.\\-]+\\.(?:ino|h|cpp|c)):(\\d+):(?:(\\d+):)?\\s*(fatal error|error|warning):\\s*(.*)$");

    /** 常見錯誤的中文提示 */
    static String hint(String msg) {
        if (msg.contains("was not declared in this scope")) return "找不到這個名稱:可能拼錯了,或是少宣告。";
        if (msg.contains("expected ';'")) return "前一行可能少了分號 ;";
        if (msg.contains("expected '}' at end of input")) return "少了右大括號 }(大括號 { } 數量對不起來)。";
        if (msg.contains("expected declaration before '}'")) return "多了一個右大括號 },或前面少了左大括號 {。";
        if (msg.contains("expected primary-expression")) return "這裡的括號或運算式不完整(括號沒配對、少了數字或名稱)。";
        if (msg.contains("expected ')'")) return "少了右括號 )。";
        if (msg.contains("expected '(' ")) return "少了左括號 (。";
        if (msg.contains("too many arguments") || msg.contains("too few arguments")) return "函式的參數數量不對。例如 SetFrameRun(幀編號, 時間) 要剛好兩個。";
        if (msg.contains("stray '\\")) return "有看不懂的字元:常見是複製進來的全形符號(,;)「」或特殊空白。請改成半形。";
        if (msg.contains("jump to case label") || msg.contains("crosses initialization")) return "case 裡面宣告了變數:請把那段用 { } 包起來。";
        if (msg.contains("duplicate case value")) return "有兩個一樣的 case。";
        if (msg.contains("no matching function")) return "函式名稱或參數型別不對。";
        if (msg.contains("overflowed by") || msg.contains("text section exceeds")) return "程式太大,放不進 Micro。請減少幀數或除錯輸出。";
        return null;
    }

    static void show(String text, boolean ok) {
        if (dlg == null) {
            dlg = new JDialog((java.awt.Frame) null, "檢查語法", false);
            out = new JTextArea();
            out.setEditable(false);
            out.setLineWrap(true);
            out.setWrapStyleWord(true);
            out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            JScrollPane sp = new JScrollPane(out);
            sp.setPreferredSize(new Dimension(760, 440));
            JPanel p = new JPanel(new BorderLayout());
            p.add(sp, BorderLayout.CENTER);
            JButton close = new JButton("關閉");
            close.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) { dlg.setVisible(false); }
            });
            JPanel b = new JPanel();
            b.add(close);
            p.add(b, BorderLayout.SOUTH);
            dlg.getContentPane().add(p);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
        }
        out.setText(text);
        out.setCaretPosition(0);
        dlg.setTitle(ok ? "檢查語法:通過" : "檢查語法");
        if (!dlg.isVisible()) dlg.setVisible(true);
        dlg.toFront();
    }

    static void copyDir(File from, File to) throws Exception {
        to.mkdirs();
        File[] fs = from.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) continue;                 // 草稿碼資料夾只有檔案,沒有子資料夾
            Files.copy(f.toPath(), new File(to, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 按下「檢查語法」 */
    static void start(final JButton btn) {
        if (running) return;
        running = true;
        if (btn != null) btn.setEnabled(false);
        show("檢查中……(第一次約 20~40 秒,之後比較快)", false);
        final Map<String, String> edits = CodeTab.snapshotEdits();
        new Thread(new Runnable() {
            public void run() {
                String result;
                boolean ok = false;
                try {
                    String[] r = check(edits);
                    result = r[0];
                    ok = "1".equals(r[1]);
                } catch (Throwable t) {
                    result = "檢查失敗:" + t;
                }
                final String text = result;
                final boolean good = ok;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        show(text, good);
                        running = false;
                        if (btn != null) btn.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /** 回傳 {顯示的文字, "1" = 通過} */
    static String[] check(Map<String, String> edits) throws Exception {
        File cli = Settings.cli();
        File sketch = Settings.sketch();
        if (!cli.exists()) return new String[] {"找不到 arduino-cli:" + cli + "\n請先執行「安裝.bat」,或在「路徑設定」指定位置。", "0"};
        if (!sketch.exists()) return new String[] {"找不到 Arduino 程式資料夾:" + sketch + "\n請在「路徑設定」指定位置。", "0"};

        // 1. 複製草稿碼到暫存資料夾(資料夾名稱要和 .ino 一樣)
        File tmpRoot = new File(System.getProperty("java.io.tmpdir"), "plus_check");
        File tmpSketch = new File(tmpRoot, sketch.getName());
        File[] old = tmpSketch.listFiles();
        if (old != null) for (File f : old) f.delete();
        copyDir(sketch, tmpSketch);

        // 2. motor.h:Tools_MotorAdj 裡的比較新就用比較新的(跟「更新到機器人」的規則一樣)
        File ms = Settings.motorSrc();
        File md = new File(sketch, "motor.h");
        String motorNote = "motor.h:使用 Micro_Robot 裡的";
        if (ms.exists() && ms.lastModified() > md.lastModified()) {
            Files.copy(ms.toPath(), new File(tmpSketch, "motor.h").toPath(), StandardCopyOption.REPLACE_EXISTING);
            motorNote = "motor.h:使用 Tools_MotorAdj 裡比較新的(還沒更新到機器人)";
        }

        // 3. 把「動作程式」分頁還沒寫入的修改套進暫存的 custom.ino
        File custom = new File(tmpSketch, "custom.ino");
        String text = CodeTab.applyToText(edits);
        Files.write(custom.toPath(), text.getBytes(StandardCharsets.UTF_8));
        List<String> srcLines = new ArrayList<String>();
        for (String l : text.split("\n", -1)) srcLines.add(l.replace("\r", ""));

        // 4. 編譯(只編譯,不燒錄)
        File build = new File(tmpRoot, "build");
        ProcessBuilder pb = new ProcessBuilder(cli.getPath(), "compile", "-b", Plus.FQBN,
                "--build-path", build.getPath(), tmpSketch.getPath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("UTF-8")));
        List<String> raw = new ArrayList<String>();
        String line;
        while ((line = r.readLine()) != null) raw.add(line);
        int code = p.waitFor();

        StringBuilder sb = new StringBuilder();
        sb.append("(只編譯、不燒錄,也沒有改你的 custom.ino。").append(edits.isEmpty() ? "「動作程式」分頁沒有未寫入的修改,檢查的是目前的 custom.ino。" : "已包含「動作程式」分頁 " + edits.size() + " 段未寫入的修改。").append(")\n");
        sb.append(motorNote).append("\n\n");

        if (code == 0) {
            sb.append("✔ 編譯通過,語法沒有錯誤。\n\n");
            for (String l : raw) {
                if (l.startsWith("Sketch uses") || l.startsWith("Global variables") || l.contains("位元組")) sb.append(l).append("\n");
            }
            sb.append("\n(通過編譯只代表語法正確;動作對不對、幀編號是不是你要的,要燒進機器人實測。幀編號是否存在,看分頁下方的「幀編號」那一行。)\n");
            return new String[] {sb.toString(), "1"};
        }

        // 失敗:整理出錯誤清單
        int errors = 0;
        List<String> shown = new ArrayList<String>();
        for (String l : raw) {
            Matcher m = DIAG.matcher(l);
            if (!m.matches()) continue;
            String kind = m.group(5);
            if (kind.equals("warning")) continue;
            errors++;
            if (shown.size() >= 12) continue;
            String file = m.group(2);
            int ln = Integer.parseInt(m.group(3));
            String msg = m.group(6);
            StringBuilder e = new StringBuilder();
            e.append("✘ ").append(file).append(" 第 ").append(ln).append(" 行:").append(msg).append("\n");
            if (file.equals("custom.ino") && ln >= 1 && ln <= srcLines.size()) {
                e.append("    ").append(srcLines.get(ln - 1).trim()).append("\n");
            }
            String h = hint(msg);
            if (h != null) e.append("    提示:").append(h).append("\n");
            shown.add(e.toString());
        }
        sb.append("✘ 編譯失敗,共 ").append(errors).append(" 個錯誤。燒錄前請先修正。\n\n");
        if (shown.isEmpty()) {
            sb.append("沒有找到標準格式的錯誤行,以下是 arduino-cli 的原始輸出:\n\n");
            int from = Math.max(0, raw.size() - 30);
            for (int i = from; i < raw.size(); i++) sb.append(raw.get(i)).append("\n");
        } else {
            for (String s : shown) sb.append(s).append("\n");
            if (errors > shown.size()) sb.append("…還有 ").append(errors - shown.size()).append(" 個錯誤(通常修好前面的,後面的會跟著消失)。\n");
            sb.append("\n行號是「整份 custom.ino」的行號。可以在上方下拉選單找到對應的動作,或用「重新載入」後對照。\n");
        }
        return new String[] {sb.toString(), "0"};
    }
}
