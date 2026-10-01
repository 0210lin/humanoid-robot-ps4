import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 「動作程式」分頁:列出 custom.ino 每個 switch 裡的 case(從 case 到它的結尾),
 * 選一個就能直接編輯那一整段;按「更新到機器人」時寫回 custom.ino。
 */
public class CodeTab {

    static class Block {
        String key;      // 唯一識別:switch 名稱 + case 名稱
        String label;    // 顯示用
        String text;     // 原本的內容(以 \n 換行)
        String nl;       // 原檔的換行:"\n" 或 "\r\n"
        int start, end;  // 行號範圍(含)
    }

    static File customFile;                 // Micro_Robot/custom.ino
    static List<Block> blocks = new ArrayList<Block>();
    static final Map<String, String> edits = new LinkedHashMap<String, String>();

    static JComboBox<String> combo;
    static JTextArea area;
    static JLabel status;
    static JLabel checkLabel;                  // 幀編號檢查的摘要
    static FrameCheck.Result lastCheck;
    static boolean loading = false;
    static String curKey = null;

    // ================= 解析 =================

    static final Pattern SWITCH = Pattern.compile("^\\s*switch\\s*\\((.*)\\)\\s*\\{\\s*$");
    static final Pattern CASE = Pattern.compile("^\\s*(case\\b[^:]*:|default\\s*:)\\s*\\{?\\s*$");

    static String switchName(String expr) {
        expr = expr.trim();
        if (expr.equals("key")) return "按鍵";
        if (expr.equals("pad_getStickButtons()")) return "按下搖桿(L3/R3)";
        if (expr.equals("LS_DIR")) return "左搖桿方向";
        if (expr.equals("RS_DIR")) return "右搖桿方向";
        return "switch(" + expr + ")";
    }

    /** 把每一行的註解、字串去掉,只留程式碼,並算出每行開頭/結尾的大括號深度 */
    static List<Block> parse(List<String> lines) {
        int n = lines.size();
        String[] code = new String[n];
        int[] before = new int[n];
        int[] after = new int[n];
        boolean inBlock = false;
        int depth = 0;
        for (int i = 0; i < n; i++) {
            String s = lines.get(i);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (inBlock) {
                    if (c == '*' && k + 1 < s.length() && s.charAt(k + 1) == '/') { inBlock = false; k++; }
                    continue;
                }
                if (c == '/' && k + 1 < s.length() && s.charAt(k + 1) == '/') break;
                if (c == '/' && k + 1 < s.length() && s.charAt(k + 1) == '*') { inBlock = true; k++; continue; }
                if (c == '"' || c == '\'') {
                    char q = c;
                    k++;
                    while (k < s.length() && s.charAt(k) != q) { if (s.charAt(k) == '\\') k++; k++; }
                    continue;
                }
                sb.append(c);
            }
            code[i] = sb.toString().replace("\r", "");
            before[i] = depth;
            for (int k = 0; k < code[i].length(); k++) {
                char c = code[i].charAt(k);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            after[i] = depth;
        }

        List<Block> result = new ArrayList<Block>();
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();

        for (int i = 0; i < n; i++) {
            Matcher m = SWITCH.matcher(code[i]);
            if (!m.matches()) continue;
            String swName = switchName(m.group(1));
            int body = after[i];

            // switch 結束的那一行
            int swEnd = n - 1;
            for (int j = i + 1; j < n; j++) {
                if (after[j] < body) { swEnd = j; break; }
            }
            // 所有 case / default 的起始行
            List<Integer> starts = new ArrayList<Integer>();
            for (int j = i + 1; j < swEnd; j++) {
                if (before[j] == body && CASE.matcher(code[j]).matches()) starts.add(j);
            }
            // 疊在一起的 case(中間沒有任何程式)算同一組
            List<List<Integer>> groups = new ArrayList<List<Integer>>();
            for (int k = 0; k < starts.size(); k++) {
                boolean same = false;
                if (k > 0) {
                    same = true;
                    for (int j = starts.get(k - 1) + 1; j < starts.get(k); j++) {
                        if (code[j].trim().length() > 0) { same = false; break; }
                    }
                }
                if (same) groups.get(groups.size() - 1).add(starts.get(k));
                else { List<Integer> g = new ArrayList<Integer>(); g.add(starts.get(k)); groups.add(g); }
            }
            for (int g = 0; g < groups.size(); g++) {
                List<Integer> grp = groups.get(g);
                int s = grp.get(0);
                int e = (g + 1 < groups.size()) ? groups.get(g + 1).get(0) - 1 : swEnd - 1;
                // 去掉結尾的空行與純註解行
                while (e > s && (lines.get(e).trim().length() == 0 || code[e].trim().length() == 0)) e--;

                StringBuilder lab = new StringBuilder();
                for (int idx : grp) {
                    String t = code[idx].trim();
                    if (t.endsWith("{")) t = t.substring(0, t.length() - 1).trim();
                    if (t.endsWith(":")) t = t.substring(0, t.length() - 1).trim();
                    if (lab.length() > 0) lab.append(" / ");
                    lab.append(t);
                }
                Block b = new Block();
                b.label = swName + "  ›  " + lab;
                String key = swName + "|" + lab;
                Integer c = seen.get(key);
                seen.put(key, c == null ? 1 : c + 1);
                b.key = (c == null) ? key : key + "#" + (c + 1);
                b.start = s;
                b.end = e;
                b.nl = lines.get(s).endsWith("\r") ? "\r\n" : "\n";
                StringBuilder tx = new StringBuilder();
                for (int j = s; j <= e; j++) {
                    if (j > s) tx.append("\n");
                    tx.append(lines.get(j).replace("\r", ""));
                }
                b.text = tx.toString();
                result.add(b);
            }
        }
        return result;
    }

    static List<String> readLines() throws Exception {
        String content = new String(Files.readAllBytes(customFile.toPath()), StandardCharsets.UTF_8);
        return new ArrayList<String>(Arrays.asList(content.split("\n", -1)));
    }

    // ================= 寫回 custom.ino =================

    static synchronized Map<String, String> snapshotEdits() {
        return new LinkedHashMap<String, String>(edits);
    }

    /** 把修改寫回 custom.ino。回傳備份檔(失敗時用來還原) */
    static File applyToFile(Map<String, String> ed, File root) throws Exception {
        List<String> lines = readLines();
        List<Block> bl = parse(lines);
        Map<String, Block> byKey = new LinkedHashMap<String, Block>();
        for (Block b : bl) byKey.put(b.key, b);
        for (String k : ed.keySet()) {
            if (!byKey.containsKey(k)) {
                throw new Exception("在 custom.ino 找不到「" + k + "」,檔案可能已被改過。請按『重新載入』。");
            }
        }
        // 由後往前取代,行號才不會錯位
        List<Block> sorted = new ArrayList<Block>(bl);
        java.util.Collections.sort(sorted, new java.util.Comparator<Block>() {
            public int compare(Block a, Block b) { return b.start - a.start; }
        });
        for (Block b : sorted) {
            String t = ed.get(b.key);
            if (t == null || t.equals(b.text)) continue;
            String[] nlines = t.replace("\r", "").split("\n", -1);
            List<String> repl = new ArrayList<String>();
            for (String l : nlines) repl.add(b.nl.equals("\r\n") ? l + "\r" : l);
            for (int j = b.end; j >= b.start; j--) lines.remove(j);
            lines.addAll(b.start, repl);
        }
        File bak = new File(new File(root, "backup"), "custom.ino.bak_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
        bak.getParentFile().mkdirs();
        Files.copy(customFile.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("\n");
            out.append(lines.get(i));
        }
        Files.write(customFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        return bak;
    }

    static void restore(File bak) throws Exception {
        Files.copy(bak.toPath(), customFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ================= 分頁畫面 =================

    static JTabbedPane findTabs(Component c) {
        if (c instanceof JTabbedPane) return (JTabbedPane) c;
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                JTabbedPane r = findTabs(k);
                if (r != null) return r;
            }
        }
        return null;
    }

    static boolean install(File custom) {
        JTabbedPane tabs = null;
        for (Frame f : Frame.getFrames()) {
            tabs = findTabs(f);
            if (tabs != null) break;
        }
        if (tabs == null) return false;
        customFile = custom;

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.add(new JLabel("選擇要修改的動作:"));
        combo = new JComboBox<String>();
        combo.setRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foc) {
                Component c = super.getListCellRendererComponent(list, value, index, sel, foc);
                if (index >= 0 && index < blocks.size() && edits.containsKey(blocks.get(index).key)) {
                    ((JLabel) c).setText("● " + value);
                }
                return c;
            }
        });
        top.add(combo);
        JButton reload = new JButton("重新載入 custom.ino");
        JButton undo = new JButton("還原這一段");
        JButton pathBtn = new JButton("路徑設定…");
        top.add(reload);
        top.add(undo);
        top.add(pathBtn);
        pathBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { Settings.showDialog(null); }
        });

        area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));   // 邏輯字型「Monospaced」會自動找有中文的字型(Consolas 沒有中文字,會變亂碼)
        area.setTabSize(2);
        JScrollPane sp = new JScrollPane(area);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        status = new JLabel("改完後按右邊的按鈕,會把修改寫回 custom.ino,然後編譯並燒錄:");
        bottom.add(status);
        JButton upd = new JButton("更新到機器人(燒錄)");
        bottom.add(upd);
        Plus.updateButtons.add(upd);
        upd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Plus.triggerUpdate((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0);
            }
        });

        // 幀編號檢查:一行摘要 + 「詳細…」
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        checkLabel = new JLabel(" ");
        JButton detail = new JButton("詳細…");
        checkRow.add(checkLabel);
        checkRow.add(detail);
        detail.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                updateCheck();
                if (lastCheck == null) return;
                JTextArea ta = new JTextArea(FrameCheck.report(lastCheck));
                ta.setEditable(false);
                ta.setLineWrap(true);
                ta.setWrapStyleWord(true);
                ta.setCaretPosition(0);
                JScrollPane sp2 = new JScrollPane(ta);
                sp2.setPreferredSize(new java.awt.Dimension(640, 380));
                JOptionPane.showMessageDialog(null, sp2, "幀編號檢查", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JPanel south = new JPanel(new BorderLayout());
        south.add(checkRow, BorderLayout.NORTH);
        south.add(bottom, BorderLayout.SOUTH);
        new javax.swing.Timer(1500, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { updateCheck(); }
        }).start();
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        tabs.addTab("動作程式", p);

        combo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { if (!loading) showSelected(); }
        });
        area.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });
        reload.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!edits.isEmpty() && JOptionPane.showConfirmDialog(null,
                        "重新載入會放棄目前還沒寫入的修改,確定嗎?", "重新載入", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                reload();
            }
        });
        undo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int i = combo.getSelectedIndex();
                if (i < 0) return;
                synchronized (CodeTab.class) { edits.remove(blocks.get(i).key); }
                showSelected();
                combo.repaint();
            }
        });
        reload();
        return true;
    }

    /** 重新檢查幀編號(包含「動作程式」分頁裡還沒寫入的修改) */
    static void updateCheck() {
        try {
            if (checkLabel == null) return;
            lastCheck = FrameCheck.analyze();
            checkLabel.setText(lastCheck.summary);
            checkLabel.setForeground(lastCheck.color);
        } catch (Throwable t) {
            // 檢查失敗不影響其他功能
        }
    }

    static void changed() {
        if (loading) return;
        int i = combo.getSelectedIndex();
        if (i < 0 || i >= blocks.size()) return;
        Block b = blocks.get(i);
        String t = area.getText().replace("\r", "");
        synchronized (CodeTab.class) {
            if (t.equals(b.text)) edits.remove(b.key);
            else edits.put(b.key, t);
        }
        combo.repaint();
    }

    static void showSelected() {
        int i = combo.getSelectedIndex();
        if (i < 0 || i >= blocks.size()) { return; }
        Block b = blocks.get(i);
        String t;
        synchronized (CodeTab.class) { t = edits.containsKey(b.key) ? edits.get(b.key) : b.text; }
        loading = true;
        area.setText(t);
        area.setCaretPosition(0);
        loading = false;
    }

    /** 重新讀取 custom.ino 並清掉所有未寫入的修改 */
    static void reload() {
        try {
            List<Block> nb = parse(readLines());
            synchronized (CodeTab.class) { edits.clear(); }
            blocks = nb;
            loading = true;
            combo.removeAllItems();
            for (Block b : blocks) combo.addItem(b.label);
            loading = false;
            if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
            showSelected();
        } catch (Exception ex) {
            loading = false;
            JOptionPane.showMessageDialog(null, "讀取 custom.ino 失敗:" + ex.getMessage());
        }
    }

    static void reloadLater() {
        SwingUtilities.invokeLater(new Runnable() { public void run() { reload(); } });
    }
}
