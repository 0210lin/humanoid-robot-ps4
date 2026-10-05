import java.awt.Container;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 批量匯出 / 批量載入動作幀(.frame)。
 * 一個檔案放一幀,格式跟 MotorAdj 原本單幀存檔相同:{"MotorFrameIdx":3,"MotorPos":[[啟用,位置,速度] × 26]}
 * 匯出:全部幀,檔名 frame_000.frame、frame_001.frame…
 * 載入:選檔案(可複選)或整個資料夾,依檔案裡的幀號放進去;載入前會先把目前全部幀自動備份。
 */
public class FrameIO {
    static final int MOTORS = 26;
    static File lastDir = null;

    static int[][][] data() throws Exception {
        return (int[][][]) Plus.fld(Plus.fld(Plus.outer(), "MotorSet"), "MotorPosData");
    }

    static File startDir() {
        if (lastDir != null && lastDir.isDirectory()) return lastDir;
        File d = new File(Settings.dir, "frames");
        return d.isDirectory() ? d : Settings.dir;
    }

    // ---------- 寫檔 / 讀檔 ----------

    static String frameJson(int idx, int[][] fr) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"MotorFrameIdx\":").append(idx).append(",\"MotorPos\":[\n");
        for (int m = 0; m < fr.length; m++) {
            sb.append("  [").append(fr[m][0]).append(",").append(fr[m][1]).append(",").append(fr[m][2]).append("]");
            sb.append(m + 1 < fr.length ? ",\n" : "\n");
        }
        sb.append("]}\n");
        return sb.toString();
    }

    static String fileName(int idx) { return String.format("frame_%03d.frame", idx); }

    /** 把全部幀寫進資料夾,回傳寫了幾個 */
    static int exportTo(File dir, int[][][] d) throws Exception {
        List<Integer> all = new ArrayList<Integer>();
        for (int f = 0; f < d.length; f++) all.add(Integer.valueOf(f));
        return exportTo(dir, d, all);
    }

    /** 只寫指定的幀號 */
    static int exportTo(File dir, int[][][] d, List<Integer> pick) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("無法建立資料夾:" + dir);
        for (Integer f : pick) {
            Files.write(new File(dir, fileName(f.intValue())).toPath(), frameJson(f.intValue(), d[f.intValue()]).getBytes(StandardCharsets.UTF_8));
        }
        return pick.size();
    }

    /** [3,5,10,11,12] → "3, 5, 10-12" */
    static String describe(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < l.size()) {
            int j = i;
            while (j + 1 < l.size() && l.get(j + 1).intValue() == l.get(j).intValue() + 1) j++;
            if (sb.length() > 0) sb.append(", ");
            sb.append(l.get(i));
            if (j > i) sb.append(j > i + 1 ? "-" : ", ").append(l.get(j));
            i = j + 1;
        }
        return sb.toString();
    }

    static final Pattern NUM = Pattern.compile("(\\d+)");

    /** 讀一個 .frame:回傳 {幀號, int[26][3]};失敗丟例外(訊息就是要給使用者看的) */
    static Object[] readFrame(File f) throws Exception {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        JSONObject o = new JSONObject(text);
        if (!o.has("MotorPos")) throw new Exception("沒有 MotorPos(不是幀檔?)");
        JSONArray a = o.getJSONArray("MotorPos");
        if (a.length() < MOTORS) throw new Exception("只有 " + a.length() + " 顆馬達(需要 " + MOTORS + " 顆)");
        int[][] fr = new int[MOTORS][3];
        for (int m = 0; m < MOTORS; m++) {
            JSONArray r = a.getJSONArray(m);
            if (r.length() < 3) throw new Exception("第 " + (m + 1) + " 顆馬達的資料不到 3 個數字");
            for (int k = 0; k < 3; k++) fr[m][k] = r.getInt(k);
        }
        int idx = -1;
        if (o.has("MotorFrameIdx")) idx = o.getInt("MotorFrameIdx");
        else {
            Matcher mt = NUM.matcher(f.getName());
            String last = null;
            while (mt.find()) last = mt.group(1);
            if (last != null) idx = Integer.parseInt(last);
        }
        if (idx < 0) throw new Exception("找不到幀號(檔案裡沒有 MotorFrameIdx,檔名也沒有數字)");
        return new Object[] {Integer.valueOf(idx), fr};
    }

    // ---------- 按鈕動作 ----------

    static String lastSpec = "";

    /** 解析「3, 5, 10-12」這種幀號清單;回傳排序、不重複的幀號。有錯丟例外(訊息給使用者看) */
    static List<Integer> parseSpec(String spec, int total) throws Exception {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<Integer>();
        String s = spec.replace('，', ',').replace('、', ',').replace('～', '-').replace('~', '-').replace('－', '-').replace('–', '-').trim();
        if (s.isEmpty()) throw new Exception("還沒填幀號");
        for (String part : s.split("[,\\s]+")) {
            if (part.isEmpty()) continue;
            Matcher m = Pattern.compile("^(\\d+)(?:-(\\d+))?$").matcher(part);
            if (!m.matches()) throw new Exception("看不懂「" + part + "」(寫法例如:3, 5, 10-12)");
            int a = Integer.parseInt(m.group(1));
            int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
            if (b < a) { int t = a; a = b; b = t; }
            if (b >= total) throw new Exception("幀 " + b + " 不存在(目前只有 0 ~ " + (total - 1) + ")");
            for (int i = a; i <= b; i++) set.add(Integer.valueOf(i));
        }
        return new ArrayList<Integer>(set);
    }

    static void doExport() {
        try {
            int[][][] d = data();
            // 先問要匯出哪幾號
            List<Integer> pick = null;
            String hint = "要匯出哪幾號幀?(目前有 0 ~ " + (d.length - 1) + ")\n用逗號分開,連續的可以用減號,例如:  3, 5, 10-12\n輸入 all 就是全部。";
            String init = lastSpec;
            while (pick == null) {
                Object r = JOptionPane.showInputDialog(null, hint, "批量匯出", JOptionPane.QUESTION_MESSAGE, null, null, init);
                if (r == null) return;
                String spec = r.toString().trim();
                init = spec;
                try {
                    if (spec.equalsIgnoreCase("all") || spec.equals("全部")) {
                        pick = new ArrayList<Integer>();
                        for (int i = 0; i < d.length; i++) pick.add(Integer.valueOf(i));
                    } else {
                        pick = parseSpec(spec, d.length);
                    }
                    lastSpec = spec;
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(null, ex.getMessage(), "批量匯出", JOptionPane.WARNING_MESSAGE);
                }
            }
            JFileChooser fc = new JFileChooser(startDir());
            fc.setDialogTitle("選擇要匯出到哪個資料夾(每一幀一個 .frame 檔)");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setApproveButtonText("匯出到這裡");
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
            File dir = fc.getSelectedFile();
            lastDir = dir;
            int exist = 0;
            for (Integer f : pick) if (new File(dir, fileName(f.intValue())).exists()) exist++;
            if (exist > 0 && JOptionPane.showConfirmDialog(null,
                    "資料夾裡已經有 " + exist + " 個同名的 .frame 檔,會被蓋掉。\n" + dir + "\n\n要繼續嗎?",
                    "批量匯出", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            int n = exportTo(dir, d, pick);
            JOptionPane.showMessageDialog(null, "已匯出 " + n + " 幀到:\n" + dir + "\n幀號:" + describe(pick), "批量匯出", JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(null, "匯出失敗:" + t.getMessage(), "批量匯出", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void doImport() {
        if (!License.require("批量載入幀")) return;
        try {
            JFileChooser fc = new JFileChooser(startDir());
            fc.setDialogTitle("選擇 .frame 檔(可複選),或選一個資料夾載入裡面全部的 .frame");
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new FileNameExtensionFilter("動作幀 (*.frame)", "frame"));
            fc.setApproveButtonText("載入");
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
            List<File> files = new ArrayList<File>();
            for (File f : fc.getSelectedFiles()) {
                if (f.isDirectory()) {
                    File[] in = f.listFiles();
                    if (in != null) for (File k : in) if (k.isFile() && k.getName().toLowerCase().endsWith(".frame")) files.add(k);
                    lastDir = f;
                } else {
                    files.add(f);
                    lastDir = f.getParentFile();
                }
            }
            Collections.sort(files);
            if (files.isEmpty()) {
                JOptionPane.showMessageDialog(null, "沒有找到 .frame 檔。", "批量載入", JOptionPane.WARNING_MESSAGE);
                return;
            }
            importFiles(files, true);
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(null, "載入失敗:" + t.getMessage(), "批量載入", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 先全部讀完、檢查過沒問題才會動資料。interactive=false 給測試用(不跳對話框) */
    static String importFiles(List<File> files, boolean interactive) throws Exception {
        Map<Integer, int[][]> got = new TreeMap<Integer, int[][]>();
        Map<Integer, String> from = new TreeMap<Integer, String>();
        List<String> errs = new ArrayList<String>();
        List<String> dups = new ArrayList<String>();
        for (File f : files) {
            try {
                Object[] r = readFrame(f);
                Integer idx = (Integer) r[0];
                if (got.containsKey(idx)) dups.add("幀 " + idx + ":" + from.get(idx) + " 和 " + f.getName() + "(用後面這個)");
                got.put(idx, (int[][]) r[1]);
                from.put(idx, f.getName());
            } catch (Throwable t) {
                errs.add(f.getName() + ":" + t.getMessage());
            }
        }
        if (!errs.isEmpty()) {
            String msg = "有 " + errs.size() + " 個檔案讀不了,所以整批都沒有載入:\n" + join(errs, 12);
            if (interactive) JOptionPane.showMessageDialog(null, msg, "批量載入", JOptionPane.ERROR_MESSAGE);
            return msg;
        }
        Object outer = Plus.outer();
        Object ms = Plus.fld(outer, "MotorSet");
        int[][][] cur = (int[][][]) Plus.fld(ms, "MotorPosData");
        int maxIdx = got.isEmpty() ? -1 : ((TreeMap<Integer, int[][]>) got).lastKey().intValue();
        int newLen = Math.max(cur.length, maxIdx + 1);
        if (newLen > AddFrames.HARD_LIMIT) {
            String msg = "幀號最大到 " + maxIdx + ",超過上限(" + AddFrames.HARD_LIMIT + " 幀)。";
            if (interactive) JOptionPane.showMessageDialog(null, msg, "批量載入", JOptionPane.ERROR_MESSAGE);
            return msg;
        }
        int over = 0, added = 0;
        for (Integer k : got.keySet()) if (k.intValue() < cur.length) over++; else added++;
        if (interactive) {
            StringBuilder sb = new StringBuilder();
            sb.append("要載入 ").append(got.size()).append(" 幀:覆蓋原有的 ").append(over).append(" 幀");
            if (newLen > cur.length) sb.append(",幀數從 ").append(cur.length).append(" 變成 ").append(newLen);
            sb.append("。\n");
            if (!dups.isEmpty()) sb.append("\n幀號重複:\n").append(join(dups, 6)).append("\n");
            if (newLen > AddFrames.limit()) sb.append("\n注意:超過目前的幀數上限 ").append(AddFrames.limit()).append(" 幀,Micro 空間可能不夠。\n");
            sb.append("\n載入前會先把目前全部幀備份到 backup 資料夾。要繼續嗎?");
            if (JOptionPane.showConfirmDialog(null, sb.toString(), "批量載入", JOptionPane.YES_NO_OPTION,
                    newLen > AddFrames.limit() ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return "cancel";
        }
        // 備份
        File bak = new File(new File(Settings.dir.getParentFile(), "backup"), "frames_before_import_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()));
        exportTo(bak, cur);
        // 新陣列:原有的複製;補出來的空隙用最後一幀的內容
        int[][][] nd = new int[newLen][MOTORS][3];
        int motors = cur[0].length;
        for (int f = 0; f < newLen; f++) {
            int[][] src = f < cur.length ? cur[f] : cur[cur.length - 1];
            for (int m = 0; m < motors && m < MOTORS; m++) System.arraycopy(src[m], 0, nd[f][m], 0, 3);
        }
        for (Map.Entry<Integer, int[][]> e : got.entrySet()) {
            int[][] fr = e.getValue();
            for (int m = 0; m < MOTORS; m++) System.arraycopy(fr[m], 0, nd[e.getKey().intValue()][m], 0, 3);
        }
        AddFrames.setField(ms, "MotorPosData", nd);
        if (newLen != cur.length) {
            AddFrames.setField(ms, "MotorFrameMax", Integer.valueOf(newLen));
            int board = ((Integer) Plus.fld(ms, "BoardType")).intValue();
            ((int[][]) Plus.fld(ms, "MotorDataBoundary"))[board][0] = newLen;
        }
        // 讓畫面顯示目前停的那一幀的新內容
        try {
            JFormattedTextField t = (JFormattedTextField) Plus.fld(outer, "FrameCntText");
            int at = 0;
            try { at = Integer.parseInt(t.getText().trim()); } catch (Exception e) { at = 0; }
            if (at < 0 || at >= newLen) at = 0;
            t.setText(String.valueOf(at));
            java.lang.reflect.Method m = outer.getClass().getDeclaredMethod("position_upgradeFrame");
            m.setAccessible(true);
            m.invoke(outer);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        AddFrames.refreshLabel();
        String done = "已載入 " + got.size() + " 幀(覆蓋 " + over + "、新增 " + added + "),現在共 " + newLen + " 幀。\n備份在:" + bak
                + "\n\n要寫進機器人,還要按「儲存設定」「轉換設定」「更新到機器人」。";
        if (interactive) JOptionPane.showMessageDialog(null, done, "批量載入", JOptionPane.INFORMATION_MESSAGE);
        return done;
    }

    static String join(List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) sb.append("  ").append(l.get(i)).append("\n");
        if (l.size() > max) sb.append("  …還有 ").append(l.size() - max).append(" 個\n");
        return sb.toString();
    }

    // ---------- 放按鈕 ----------

    static void install(Container panel) throws Exception {
        JButton load = Plus.findExactIn(panel, "載入馬達幀", "Load Motor Frame");
        JButton save = Plus.findExactIn(panel, "儲存馬達幀", "Save Motor Frame");
        if (load == null || save == null) {
            System.err.println("FrameIO:找不到「載入馬達幀」「儲存馬達幀」按鈕,批量匯出/載入沒有加上");
            return;
        }
        JButton exp = new JButton("批量匯出幀…");
        exp.setBounds(load.getX(), load.getY() + 32, load.getWidth(), load.getHeight());
        exp.setToolTipText("輸入要匯出的幀號(例如 3, 5, 10-12),每幀存成一個 .frame 檔到你選的資料夾");
        exp.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { doExport(); } });
        panel.add(exp);
        JButton imp = new JButton("批量載入幀…");
        imp.setBounds(save.getX(), save.getY() + 32, save.getWidth(), save.getHeight());
        imp.setToolTipText("選多個 .frame 檔(或一個資料夾),依檔案裡的幀號放進來;載入前會自動備份目前全部幀");
        imp.addActionListener(new java.awt.event.ActionListener() { public void actionPerformed(java.awt.event.ActionEvent e) { doImport(); } });
        panel.add(imp);
        panel.repaint();
    }
}
