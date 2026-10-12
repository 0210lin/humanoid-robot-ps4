import java.util.ArrayList;
import java.util.List;

/**
 * 「動作程式」的自動對齊:
 *   ・縮排:每層 2 個空格。大括號 { } 加減一層;case / default 標籤底下的內容多縮一層(跟 custom.ino 現有的寫法一樣)
 *   ・Tab 換成空格、行尾多餘的空白拿掉
 *   ・連續幾行的行尾 // 註解,對齊到同一欄
 *   ・只動「空白」:程式本身、註解的文字都不改。#define 這類 # 開頭的行維持靠左
 * 第一行的縮排當作基準(編輯器裡顯示的是 custom.ino 的一小段,不一定從最左邊開始)。
 */
public class Fmt {

    static final int INDENT = 2;
    static final int COMMENT_GAP = 2;      // 程式和行尾註解之間至少空幾格

    /** 一行拆成:縮排以外的程式部分、行尾註解(含 //)。找不到註解時 comment = null */
    static class Parts {
        String code;       // 不含前面的縮排、不含行尾註解、右邊修掉空白
        String comment;    // 以 // 開頭,沒有則 null
        boolean commentOnly;   // 整行都是註解(以 // 開頭)
        boolean pre;       // # 開頭的前置處理行
        boolean raw;       // 在 /* ... */ 區塊註解裡面:整行原封不動
    }

    /** 掃描一行,避開字串 / 字元常數裡的 // */
    static Parts split(String line) {
        Parts p = new Parts();
        String s = line.replace("\t", "  ");
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        s = s.substring(i);
        if (s.startsWith("#")) {
            p.pre = true;
            p.code = rtrim(s);
            return p;
        }
        if (s.startsWith("//")) {
            p.commentOnly = true;
            // 「被註解掉的程式」後面還有一個行尾註解(例如 // SetFrameRun(1, 100);   // 動作 1):拆成兩段方便對齊
            int j = s.indexOf("//", 2);
            // 第二個 // 前面要有至少 2 個空格才算行尾註解(「要用時把 // 拿掉」這種文字裡的 // 不拆)
            if (j > 2 && s.charAt(j - 1) == ' ' && s.charAt(j - 2) == ' ' && s.substring(2, j).trim().length() > 0) {
                p.code = rtrim(s.substring(0, j));
                p.comment = s.substring(j);
            } else {
                p.code = rtrim(s);
            }
            return p;
        }
        char q = 0;
        int cut = -1;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (q != 0) {
                if (c == '\\') k++;
                else if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '/' && k + 1 < s.length() && s.charAt(k + 1) == '/') {
                cut = k;
                break;
            }
        }
        if (cut >= 0) {
            p.code = rtrim(s.substring(0, cut));
            p.comment = s.substring(cut);
        } else {
            p.code = rtrim(s);
        }
        return p;
    }

    static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t' || s.charAt(e - 1) == '\r')) e--;
        return s.substring(0, e);
    }

    /** 去掉字串、字元常數和註解後,只留下程式的符號(用來數大括號) */
    static String bare(String code) {
        StringBuilder b = new StringBuilder();
        char q = 0;
        for (int k = 0; k < code.length(); k++) {
            char c = code.charAt(k);
            if (q != 0) {
                if (c == '\\') k++;
                else if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** 顯示寬度:中文字等寬字型裡佔 2 格 */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c >= 0x2E80 && c <= 0xFFEF) ? 2 : 1;
        }
        return w;
    }

    static boolean isCaseLabel(String bareCode) {
        String t = bareCode.trim();
        return t.matches("^(case\\b.*|default)\\s*:\\s*\\{?\\s*$") || t.matches("^(case\\b.*|default)\\s*:.*");
    }

    public static String format(String text) {
        String[] raw = text.replace("\r", "").split("\n", -1);
        // 基準縮排:第一個非空白行的縮排
        int base = 0;
        for (String l : raw) {
            if (l.trim().isEmpty()) continue;
            String t = l.replace("\t", "  ");
            int i = 0;
            while (i < t.length() && t.charAt(i) == ' ') i++;
            base = i;
            break;
        }

        List<Parts> parts = new ArrayList<Parts>();
        List<Integer> depths = new ArrayList<Integer>();
        List<Boolean> blank = new ArrayList<Boolean>();
        List<Integer> extras = new ArrayList<Integer>();
        // 每層大括號是否正處在 case 之中
        List<Boolean> caseOpen = new ArrayList<Boolean>();
        caseOpen.add(Boolean.FALSE);
        int depth = 0;

        boolean inBlockComment = false;     // 正在 /* ... */ 裡面
        int paren = 0;                      // 還沒關起來的左括號數(換行接下去的條件)

        for (String l : raw) {
            if (l.trim().isEmpty()) {
                parts.add(null);
                depths.add(0);
                extras.add(0);
                blank.add(Boolean.TRUE);
                continue;
            }
            if (inBlockComment) {                       // 區塊註解裡的行:原封不動
                Parts rp = new Parts();
                rp.raw = true;
                rp.code = rtrim(l);
                parts.add(rp);
                depths.add(0);
                extras.add(0);
                blank.add(Boolean.FALSE);
                if (l.contains("*/")) inBlockComment = false;
                continue;
            }
            Parts p = split(l);
            parts.add(p);
            blank.add(Boolean.FALSE);
            String trimmedL = l.trim();
            if (trimmedL.startsWith("/*")) {
                p.commentOnly = true;
                p.comment = null;
                p.code = rtrim(trimmedL);
                if (!trimmedL.contains("*/")) inBlockComment = true;
            }
            int myDepth = depth;
            int extra = 0;
            if (!p.pre && !p.commentOnly) {
                String b = bare(p.code).trim();
                // 換行接下去的條件(括號還沒關、或以 && || 開頭):多縮 4 格
                if (paren > 0 || b.startsWith("&&") || b.startsWith("||")) extra = 4;
                for (int k = 0; k < b.length(); k++) {
                    char c = b.charAt(k);
                    if (c == '(') paren++;
                    else if (c == ')') paren = Math.max(0, paren - 1);
                }
                // 這一行以 } 開頭:先退一層(如果這層裡有 case 開著,也一起收掉)
                int lead = 0;
                while (lead < b.length() && b.charAt(lead) == '}') lead++;
                int d = depth;
                for (int k = 0; k < lead; k++) {
                    if (caseOpen.get(caseOpen.size() - 1)) { d--; }
                    d--;
                    if (caseOpen.size() > 1) caseOpen.remove(caseOpen.size() - 1);
                }
                depth = Math.max(0, d);
                myDepth = depth;
                // case / default 標籤:前一個 case 的內容收掉,標籤本身放在 switch 內容那一層
                if (isCaseLabel(b)) {
                    if (caseOpen.get(caseOpen.size() - 1)) { depth = Math.max(0, depth - 1); }
                    myDepth = depth;
                    depth++;
                    caseOpen.set(caseOpen.size() - 1, Boolean.TRUE);
                }
                // 這一行剩下的符號:{ 加一層,中間的 } 減一層
                String rest = b.substring(lead);
                boolean isCase = isCaseLabel(b);
                for (int k = 0; k < rest.length(); k++) {
                    char c = rest.charAt(k);
                    if (c == '{') {
                        depth++;
                        caseOpen.add(Boolean.FALSE);
                    } else if (c == '}') {
                        if (caseOpen.get(caseOpen.size() - 1)) depth--;
                        depth = Math.max(0, depth - 1);
                        if (caseOpen.size() > 1) caseOpen.remove(caseOpen.size() - 1);
                    }
                }
                if (isCase) { /* 標籤那行本身已處理 */ }
            }
            depths.add(myDepth);
            extras.add(extra);
        }

        // 產生每行文字(先不處理行尾註解對齊)
        int n = raw.length;
        String[] head = new String[n];      // 縮排 + 程式
        String[] tail = new String[n];      // 行尾註解
        for (int i = 0; i < n; i++) {
            if (blank.get(i)) { head[i] = ""; tail[i] = null; continue; }
            Parts p = parts.get(i);
            if (p.pre || p.raw) { head[i] = p.code; tail[i] = null; continue; }
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < base + depths.get(i) * INDENT + extras.get(i); k++) sb.append(' ');
            sb.append(p.code);
            head[i] = sb.toString();
            tail[i] = p.comment;
        }

        // 對齊連續幾行的行尾註解
        int i = 0;
        while (i < n) {
            if (tail[i] == null) { i++; continue; }
            int j = i;
            int maxW = 0;
            while (j < n && tail[j] != null) {
                maxW = Math.max(maxW, width(head[j]));
                j++;
            }
            for (int k = i; k < j; k++) {
                StringBuilder sb = new StringBuilder(head[k]);
                int pad = maxW - width(head[k]) + COMMENT_GAP;
                for (int s = 0; s < pad; s++) sb.append(' ');
                sb.append(tail[k]);
                head[k] = sb.toString();
                tail[k] = null;
            }
            i = j;
        }

        StringBuilder out = new StringBuilder();
        for (int k = 0; k < n; k++) {
            if (k > 0) out.append("\n");
            out.append(rtrim(head[k]));
        }
        return out.toString();
    }

    /** 檢查用:拿掉所有空白和註解,只剩程式本身(比較整理前後有沒有改到程式) */
    public static String skeleton(String text) {
        StringBuilder b = new StringBuilder();
        for (String l : text.replace("\r", "").split("\n", -1)) {
            Parts p = split(l);
            if (p.commentOnly) continue;
            String c = p.code == null ? "" : p.code;
            b.append(c.replaceAll("\\s+", ""));
        }
        return b.toString();
    }
}
