import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 幀編號檢查:掃描「動作程式」裡所有 SetFrameRun(幀編號, 時間) 的幀編號(含還沒寫入的修改),
 *   ・編號超過 Arduino 上 motor.h 的幀數:機器人會「默默忽略」那個動作,不會有任何錯誤提示
 *   ・哪些幀完全沒有被用到(之後想省 Micro 空間時,知道哪些可以刪)
 * 被 // 註解掉的 SetFrameRun 不算。
 */
public class FrameCheck {

    static class Result {
        Map<Integer, List<String>> used = new TreeMap<Integer, List<String>>();   // 幀 -> 在哪些 case 用到
        List<String> problems = new ArrayList<String>();                          // 問題(紅/橘)
        int arduinoFrames = -1;     // motor.h 的 MOTOR_FRAME_MAX(-1 = 讀不到)
        int adjFrames = -1;         // MotorAdj 目前的幀數(-1 = 讀不到)
        boolean bad = false;        // 有「完全不存在」的幀(紅色)
        String unusedText = "";
        int unusedCount = 0;
        String summary = "";
        Color color = Color.GRAY;
    }

    static int readFrameMax(File sketch) {
        try {
            String h = new String(Files.readAllBytes(new File(sketch, "motor.h").toPath()), "UTF-8");
            int k = h.indexOf("MOTOR_FRAME_MAX");
            if (k < 0) return -1;
            k += "MOTOR_FRAME_MAX".length();
            while (k < h.length() && !Character.isDigit(h.charAt(k))) k++;
            int e = k;
            while (e < h.length() && Character.isDigit(h.charAt(e))) e++;
            return Integer.parseInt(h.substring(k, e));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 去掉 // 註解(字串裡的 // 不處理,custom.ino 沒有這種寫法) */
    static String stripComment(String line) {
        int i = line.indexOf("//");
        return i >= 0 ? line.substring(0, i) : line;
    }

    /** 找出文字裡所有 SetFrameRun(數字, …) 的數字 */
    static List<Integer> framesIn(String text) {
        List<Integer> out = new ArrayList<Integer>();
        for (String raw : text.split("\n")) {
            String line = stripComment(raw);
            int from = 0;
            while (true) {
                int k = line.indexOf("SetFrameRun", from);
                if (k < 0) break;
                int p = line.indexOf('(', k);
                if (p < 0) break;
                int i = p + 1;
                while (i < line.length() && line.charAt(i) == ' ') i++;
                int j = i;
                while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
                if (j > i) {
                    try { out.add(Integer.valueOf(line.substring(i, j))); } catch (Exception e) { }
                }
                from = p + 1;
            }
        }
        return out;
    }

    static String ranges(List<Integer> nums) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < nums.size()) {
            int j = i;
            while (j + 1 < nums.size() && nums.get(j + 1) == nums.get(j) + 1) j++;
            if (sb.length() > 0) sb.append("、");
            sb.append(nums.get(i));
            if (j > i) sb.append("~").append(nums.get(j));
            i = j + 1;
        }
        return sb.toString();
    }

    static Result analyze() {
        Result r = new Result();
        Map<String, String> ed = CodeTab.snapshotEdits();
        for (CodeTab.Block b : CodeTab.blocks) {
            String text = ed.containsKey(b.key) ? ed.get(b.key) : b.text;
            for (Integer f : framesIn(text)) {
                List<String> where = r.used.get(f);
                if (where == null) {
                    where = new ArrayList<String>();
                    r.used.put(f, where);
                }
                if (!where.contains(b.label)) where.add(b.label);
            }
        }
        r.arduinoFrames = readFrameMax(Settings.sketch());
        try { r.adjFrames = AddFrames.frameCount(); } catch (Throwable t) { r.adjFrames = -1; }

        int missing = 0, pending = 0;
        for (Map.Entry<Integer, List<String>> e : r.used.entrySet()) {
            int f = e.getKey().intValue();
            String where = e.getValue().get(0) + (e.getValue().size() > 1 ? " 等 " + e.getValue().size() + " 處" : "");
            if (r.arduinoFrames >= 0 && f >= r.arduinoFrames) {
                if (r.adjFrames >= 0 && f < r.adjFrames) {
                    pending++;
                    r.problems.add("幀 " + f + "(" + where + "):MotorAdj 有這一幀,但 Arduino 的 motor.h 還沒有 → 請「轉換設定」後「更新到機器人」");
                } else {
                    missing++;
                    r.problems.add("幀 " + f + "(" + where + "):這一幀還不存在(Arduino 只有 " + r.arduinoFrames
                            + " 幀)→ 機器人會**默默忽略**這個動作");
                }
            }
        }
        r.bad = missing > 0;

        // 完全沒被用到的幀
        int total = Math.max(r.adjFrames, r.arduinoFrames);
        List<Integer> unused = new ArrayList<Integer>();
        for (int f = 0; f < total; f++) {
            if (!r.used.containsKey(Integer.valueOf(f))) unused.add(Integer.valueOf(f));
        }
        r.unusedCount = unused.size();
        r.unusedText = ranges(unused);

        if (r.arduinoFrames < 0) {
            r.summary = "幀編號檢查:讀不到 motor.h,無法檢查";
            r.color = Color.GRAY;
        } else if (missing > 0) {
            r.summary = "幀編號:有 " + missing + " 個編號不存在,機器人會忽略那些動作(按「詳細」)";
            r.color = new Color(0xD6, 0x45, 0x45);
        } else if (pending > 0) {
            r.summary = "幀編號:" + pending + " 個編號還沒轉換到 Arduino(按「詳細」)";
            r.color = new Color(0xD9, 0x82, 0x00);
        } else {
            r.summary = "幀編號:都有效(用到 " + r.used.size() + " 幀,未使用 " + r.unusedCount + " 幀)";
            r.color = new Color(0x2E, 0x9E, 0x5B);
        }
        return r;
    }

    /** 詳細報告文字 */
    static String report(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Arduino 的 motor.h 有 ").append(r.arduinoFrames).append(" 幀");
        if (r.adjFrames >= 0) sb.append(";MotorAdj 目前有 ").append(r.adjFrames).append(" 幀");
        sb.append("\n\n");
        if (r.problems.isEmpty()) {
            sb.append("【問題】沒有。所有用到的幀編號都存在。\n\n");
        } else {
            sb.append("【問題】\n");
            for (String p : r.problems) sb.append("  ・").append(p).append("\n");
            sb.append("\n");
        }
        sb.append("【用到的幀(").append(r.used.size()).append(" 幀)】\n  ");
        List<Integer> usedList = new ArrayList<Integer>(r.used.keySet());
        sb.append(ranges(usedList)).append("\n\n");
        sb.append("【完全沒被動作程式用到的幀(").append(r.unusedCount).append(" 幀)】\n  ");
        sb.append(r.unusedText.isEmpty() ? "(沒有)" : r.unusedText).append("\n");
        sb.append("\n※ 只統計 SetFrameRun(數字, …) 這種直接寫數字的;被 // 註解掉的不算。\n");
        sb.append("※ 沒被用到的幀每幀佔 Micro 約 156 位元組,確定不需要時可以刪掉來省空間。");
        return sb.toString();
    }
}
