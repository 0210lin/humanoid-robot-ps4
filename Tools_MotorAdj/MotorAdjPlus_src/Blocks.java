import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方塊(Scratch 式)編輯器的資料結構、產生程式碼、讀回程式碼。
 *
 * 一個 case 的內容 = 一串方塊(Node)。方塊有:
 *   ACTION  動作:SetFrameRun(幀, 時間)      (停用時產生成註解)
 *   WAIT    等待:delay(毫秒)
 *   RELAX   放鬆全部馬達:uart_disableMotor()
 *   REPEAT  重複直到:do { ... } while (條件);
 *   IF      如果 / 否則:if (條件) { ... } else { ... }(否則裡面放一個 IF 就是 else if)
 *   COMMENT 註解
 *   CODE    自訂程式碼(原樣保留,讀不懂的寫法也會變成這個)
 * 條件(Cond)= 幾個小條件用「而且」或「或」接起來,每個小條件可以加「不是」。
 */
public class Blocks {

    // ---------------- 資料結構 ----------------

    public static class Clause {
        public String kind = "CUSTOM";   // KEY_NONE KEY_ANY KEY_IS HOLD STICK LS_DIR RS_DIR LS_FLAG RS_FLAG CUSTOM
        public String param = "";        // 按鍵名稱(CIRCLE…)、方向(UP…)、或自訂文字
        public boolean not = false;
        public Clause() { }
        public Clause(String kind, String param, boolean not) { this.kind = kind; this.param = param; this.not = not; }
    }

    public static class Cond {
        public List<Clause> clauses = new ArrayList<Clause>();
        public boolean or = false;       // false = 而且(&&),true = 或(||)
        public Cond() { }
        public Cond(Clause c) { clauses.add(c); }
        public boolean usesSticks() {
            for (Clause c : clauses) {
                if (c.kind.equals("LS_DIR") || c.kind.equals("RS_DIR") || c.kind.equals("LS_FLAG") || c.kind.equals("RS_FLAG")) return true;
                if (c.kind.equals("CUSTOM") && (c.param.contains("LS_") || c.param.contains("RS_"))) return true;
            }
            return false;
        }
    }

    public static class Node {
        public String type;
        public String a = "", b = "";           // ACTION:a=幀 b=時間;WAIT:a=毫秒;COMMENT/CODE:a=文字(CODE 可多行)
        public boolean enabled = true;           // 停用 = 產生成註解
        public String note = "";                 // 行尾註解(不含 //)
        public String elseNote = "";             // IF 的「否則」那一行的行尾註解
        public Cond cond = new Cond();
        public List<Node> body = new ArrayList<Node>();
        public List<Node> elseBody = new ArrayList<Node>();
        public Node(String type) { this.type = type; }
    }

    /** 一個 case:標頭(例如 "case PAD_BTN_CIRCLE:")+ 內容 */
    public static class Script {
        public String header = "";
        public String headerNote = "";
        public boolean braced = false;           // 標頭是 "case X: {",最後還有一個 }
        public boolean braceNext = false;        // "case X:" 的下一行才是 "{"(Arduino IDE 自動排版的寫法),最後也有一個 }
        public String braceNote = "";
        public List<Node> body = new ArrayList<Node>();
    }

    // ---------------- 名稱對照 ----------------

    public static final String[] BUTTONS = {"UP", "DOWN", "LEFT", "RIGHT", "TRIANGLE", "CROSS", "CIRCLE", "SQUARE", "L1", "L2", "R1", "R2"};
    public static final String[] DIRS = {"UP", "DOWN", "LEFT", "RIGHT"};

    static boolean in(String[] arr, String s) {
        for (String x : arr) if (x.equals(s)) return true;
        return false;
    }

    // ---------------- 產生程式碼 ----------------

    static void indent(StringBuilder sb, int n) { for (int i = 0; i < n; i++) sb.append("  "); }

    static String clauseCode(Clause c) {
        String s;
        if (c.kind.equals("KEY_NONE")) s = "pad_getKey() == 0";
        else if (c.kind.equals("KEY_ANY")) s = "pad_getKey() > 0";
        else if (c.kind.equals("KEY_IS")) s = "pad_getKey() == PAD_BTN_" + c.param;
        else if (c.kind.equals("HOLD")) s = "keyHas(PAD_BTN_" + c.param + ")";
        else if (c.kind.equals("KEY_NONE_OR")) {
            StringBuilder k = new StringBuilder("(pad_getKey() == 0");
            for (String b : c.param.split(",")) if (!b.trim().isEmpty()) k.append(" || pad_getKey() == PAD_BTN_").append(b.trim());
            s = k.append(")").toString();
        }
        else if (c.kind.equals("STICK")) s = c.param.equals("L3+R3") ? "pad_getStickButtons() == (PAD_L3 | PAD_R3)" : "pad_getStickButtons() == PAD_" + c.param;
        else if (c.kind.equals("LS_DIR")) s = "LS_DIR == DIR_" + c.param;
        else if (c.kind.equals("RS_DIR")) s = "RS_DIR == DIR_" + c.param;
        else if (c.kind.equals("LS_FLAG")) s = "LS_" + c.param;
        else if (c.kind.equals("RS_FLAG")) s = "RS_" + c.param;
        else s = c.param.trim().isEmpty() ? "1" : c.param.trim();
        if (c.not) {
            boolean simple = c.kind.equals("LS_FLAG") || c.kind.equals("RS_FLAG") || c.kind.equals("HOLD");
            s = simple ? "!" + s : "!(" + s + ")";
        }
        return s;
    }

    public static String condCode(Cond c) {
        if (c.clauses.isEmpty()) return "1";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.clauses.size(); i++) {
            if (i > 0) sb.append(c.or ? " || " : " && ");
            String t = clauseCode(c.clauses.get(i));
            if (c.clauses.size() > 1 && c.clauses.get(i).kind.equals("CUSTOM") && (t.contains("&&") || t.contains("||"))) t = "(" + t + ")";
            sb.append(t);
        }
        return sb.toString();
    }

    static boolean anyStick(List<Node> body) {
        for (Node n : body) {
            if ((n.type.equals("REPEAT") || n.type.equals("IF")) && n.cond.usesSticks()) return true;
            if (anyStick(n.body) || anyStick(n.elseBody)) return true;
        }
        return false;
    }

    static boolean hasStickUpdate(List<Node> body) {
        for (Node n : body) {
            if (n.type.equals("CODE") && n.a.contains("custom_stickUpdate()")) return true;
        }
        return false;
    }

    static String tail(Node n) { return n.note.isEmpty() ? "" : "  // " + n.note; }

    static void genList(StringBuilder sb, List<Node> list, int d) {
        for (Node n : list) gen(sb, n, d);
    }

    static void gen(StringBuilder sb, Node n, int d) {
        String t = n.type;
        if (t.equals("ACTION")) {
            indent(sb, d);
            if (!n.enabled) sb.append("// ");
            sb.append("SetFrameRun(").append(n.a.trim()).append(", ").append(n.b.trim()).append(");").append(tail(n)).append("\n");
        } else if (t.equals("WAIT")) {
            indent(sb, d);
            if (!n.enabled) sb.append("// ");
            sb.append("delay(").append(n.a.trim()).append(");").append(tail(n)).append("\n");
        } else if (t.equals("RELAX")) {
            indent(sb, d);
            if (!n.enabled) sb.append("// ");
            sb.append("uart_disableMotor();").append(tail(n)).append("\n");
        } else if (t.equals("COMMENT")) {
            for (String l : n.a.split("\n", -1)) { indent(sb, d); sb.append("// ").append(l).append("\n"); }
        } else if (t.equals("CODE")) {
            for (String l : n.a.split("\n", -1)) { indent(sb, d); sb.append(l.trim()).append("\n"); }
        } else if (t.equals("REPEAT")) {
            indent(sb, d); sb.append("do {").append(tail(n)).append("\n");
            genList(sb, n.body, d + 1);
            if (anyStickCond(n) && !hasStickUpdate(n.body)) { indent(sb, d + 1); sb.append("custom_stickUpdate();\n"); }
            indent(sb, d); sb.append("} while (").append(condCode(n.cond)).append(");\n");
        } else if (t.equals("IF")) {
            genIf(sb, n, d, false);
        }
    }

    static boolean anyStickCond(Node rep) {
        return rep.cond.usesSticks() || anyStick(rep.body);
    }

    static void genIf(StringBuilder sb, Node n, int d, boolean chained) {
        if (!chained) indent(sb, d);
        sb.append("if (").append(condCode(n.cond)).append(") {").append(tail(n)).append("\n");
        genList(sb, n.body, d + 1);
        if (n.elseBody.size() == 1 && n.elseBody.get(0).type.equals("IF")) {
            indent(sb, d); sb.append("} else ");
            genIf(sb, n.elseBody.get(0), d, true);
            return;
        }
        if (!n.elseBody.isEmpty()) {
            indent(sb, d); sb.append("} else {").append(n.elseNote.isEmpty() ? "" : "  // " + n.elseNote).append("\n");
            genList(sb, n.elseBody, d + 1);
        }
        indent(sb, d); sb.append("}\n");
    }

    public static String generate(Script s, int baseIndent) {
        StringBuilder sb = new StringBuilder();
        indent(sb, baseIndent);
        sb.append(s.header).append(s.headerNote.isEmpty() ? "" : "  // " + s.headerNote).append("\n");
        if (s.braceNext) {
            indent(sb, baseIndent + 1);
            sb.append("{").append(s.braceNote.isEmpty() ? "" : "  // " + s.braceNote).append("\n");
            genList(sb, s.body, baseIndent + 2);
            indent(sb, baseIndent + 2);
            sb.append("break;\n");
            indent(sb, baseIndent + 1);
            sb.append("}\n");
            String o2 = sb.toString();
            return o2.endsWith("\n") ? o2.substring(0, o2.length() - 1) : o2;
        }
        genList(sb, s.body, baseIndent + 1);
        indent(sb, baseIndent + 1);
        sb.append("break;\n");
        if (s.braced) { indent(sb, baseIndent); sb.append("}\n"); }
        String out = sb.toString();
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    // ---------------- 讀回程式碼 ----------------

    static final Pattern P_ACTION = Pattern.compile("^SetFrameRun\\(\\s*([^,()]+?)\\s*,\\s*([^,()]+?)\\s*\\)\\s*;\\s*$");
    static final Pattern P_WAIT = Pattern.compile("^delay\\(\\s*([^()]+?)\\s*\\)\\s*;\\s*$");

    static class Line {
        String code;   // 不含行尾註解、已去掉兩側空白
        String note;   // 行尾註解文字(不含 //),沒有則 ""
        boolean commentOnly;
        String raw;    // 去掉縮排的原文
    }

    static Line splitLine(String l) {
        Line r = new Line();
        String s = l.trim();
        r.raw = s;
        r.note = "";
        if (s.startsWith("//")) {
            r.commentOnly = true;
            r.code = s;
            return r;
        }
        Fmt.Parts p = Fmt.split(l);
        r.code = p.code == null ? "" : p.code.trim();
        if (p.comment != null) r.note = p.comment.substring(2).trim();
        return r;
    }

    static List<Line> lines;
    static int pos;

    /** 把 case 的文字讀成 Script。回傳 null 代表連 case 標頭都找不到 */
    public static Script parse(String text) {
        lines = new ArrayList<Line>();
        for (String l : text.replace("\r", "").split("\n", -1)) {
            if (l.trim().isEmpty()) continue;
            lines.add(splitLine(l));
        }
        pos = 0;
        if (lines.isEmpty()) return null;
        Line h = lines.get(0);
        if (h.commentOnly || !h.code.matches("^(case\\b[^:]*:|default\\s*:)\\s*\\{?\\s*$")) return null;
        Script s = new Script();
        s.header = h.code;
        s.braced = h.code.endsWith("{");
        s.headerNote = h.note;
        pos = 1;
        // Arduino IDE 自動排版會把 "{" 換到下一行:case X:  /  {
        if (!s.braced && lines.size() > 1 && !lines.get(1).commentOnly && lines.get(1).code.equals("{")) {
            s.braceNext = true;
            s.braceNote = lines.get(1).note;
            pos = 2;
        }
        s.body = parseList(false);
        if ((s.braced || s.braceNext) && pos < lines.size() && lines.get(pos).code.equals("}")) pos++;   // case 的右大括號
        // 最後的 break; 由產生程式碼補上
        if (!s.body.isEmpty()) {
            Node last = s.body.get(s.body.size() - 1);
            if (last.type.equals("CODE") && last.a.trim().equals("break;") && last.note.isEmpty()) s.body.remove(s.body.size() - 1);
        }
        return s;
    }

    /** 讀一串方塊,直到遇到以 } 開頭的行(不消耗它)或結束 */
    static List<Node> parseList(boolean nested) {
        List<Node> out = new ArrayList<Node>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (!l.commentOnly && l.code.startsWith("}")) return out;
            out.add(parseOne());
        }
        return out;
    }

    static int braceDelta(String code) {
        String b = Fmt.bare(code);
        int d = 0;
        for (int i = 0; i < b.length(); i++) {
            if (b.charAt(i) == '{') d++;
            else if (b.charAt(i) == '}') d--;
        }
        return d;
    }

    static Node parseOne() {
        Line l = lines.get(pos);
        if (l.commentOnly) {
            pos++;
            // 被註解掉的動作:// SetFrameRun(1, 100);   // 動作 1
            String body = l.code.substring(2).trim();
            String note = "";
            Fmt.Parts p = Fmt.split("// " + body);
            if (p.comment != null) { note = p.comment.substring(2).trim(); }
            String codePart = p.comment != null ? p.code.substring(2).trim() : body;
            Matcher m = P_ACTION.matcher(codePart);
            if (m.matches()) { Node n = new Node("ACTION"); n.a = m.group(1); n.b = m.group(2); n.enabled = false; n.note = note; return n; }
            m = P_ACTION.matcher(codePart + ";");
            if (m.matches() && !codePart.endsWith(";")) { Node n = new Node("ACTION"); n.a = m.group(1); n.b = m.group(2); n.enabled = false; n.note = note; return n; }
            Node n = new Node("COMMENT");
            n.a = body;
            return n;
        }
        String c = l.code;
        Matcher m = P_ACTION.matcher(c);
        if (m.matches()) { pos++; Node n = new Node("ACTION"); n.a = m.group(1); n.b = m.group(2); n.note = l.note; return n; }
        m = P_WAIT.matcher(c);
        if (m.matches()) { pos++; Node n = new Node("WAIT"); n.a = m.group(1); n.note = l.note; return n; }
        if (c.equals("uart_disableMotor();")) { pos++; Node n = new Node("RELAX"); n.note = l.note; return n; }
        if (c.equals("do {")) return parseDo();
        if (c.startsWith("if") && c.matches("^if\\s*\\(.*\\)\\s*\\{$")) return parseIf();
        // 讀不懂:原樣保留(如果有未配對的大括號,連同整個區塊一起抓)
        Node n = new Node("CODE");
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        do {
            Line k = lines.get(pos);
            if (sb.length() > 0) sb.append("\n");
            sb.append(k.commentOnly ? k.code : k.code + (k.note.isEmpty() ? "" : "  // " + k.note));
            depth += k.commentOnly ? 0 : braceDelta(k.code);
            pos++;
        } while (depth > 0 && pos < lines.size());
        n.a = sb.toString();
        return n;
    }

    static Node parseDo() {
        Line start = lines.get(pos);
        pos++;
        Node n = new Node("REPEAT");
        n.note = start.note;
        n.body = parseList(true);
        if (pos < lines.size()) {
            Line e = lines.get(pos);
            Matcher m = Pattern.compile("^\\}\\s*while\\s*\\((.*)\\)\\s*;$").matcher(e.code);
            if (m.matches()) {
                pos++;
                n.cond = parseCond(m.group(1));
                return n;
            }
        }
        // 結尾不是 } while (...); —— 不是我認得的 do-while,整段當自訂程式碼
        return null_as_code(start);
    }

    static Node null_as_code(Line start) {
        Node n = new Node("CODE");
        n.a = start.code;
        return n;
    }

    static Node parseIf() {
        Line start = lines.get(pos);
        Matcher sm = Pattern.compile("^if\\s*\\((.*)\\)\\s*\\{$").matcher(start.code);
        sm.matches();
        pos++;
        Node n = new Node("IF");
        n.note = start.note;
        n.cond = parseCond(sm.group(1));
        n.body = parseList(true);
        if (pos < lines.size()) {
            Line e = lines.get(pos);
            if (e.code.equals("}")) { pos++; return n; }
            Matcher em = Pattern.compile("^\\}\\s*else\\s+if\\s*\\((.*)\\)\\s*\\{$").matcher(e.code);
            if (em.matches()) {
                // 把 "} else if (...) {" 當作新的 if 的開頭
                lines.set(pos, rewriteAsIf(e, em.group(1)));
                Node inner = parseIf();
                n.elseBody.add(inner);
                return n;
            }
            if (e.code.matches("^\\}\\s*else\\s*\\{$")) {
                pos++;
                n.elseNote = e.note;
                n.elseBody = parseList(true);
                if (pos < lines.size() && lines.get(pos).code.equals("}")) pos++;
                return n;
            }
        }
        return n;
    }

    static Line rewriteAsIf(Line e, String cond) {
        Line r = new Line();
        r.code = "if (" + cond + ") {";
        r.note = e.note;
        r.raw = r.code;
        return r;
    }

    // ---------------- 條件 ----------------

    static List<String> splitTop(String s, String op) {
        List<String> parts = new ArrayList<String>();
        int depth = 0, last = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == '\\') i++; else if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && s.startsWith(op, i)) {
                parts.add(s.substring(last, i).trim());
                last = i + op.length();
                i += op.length() - 1;
            }
        }
        parts.add(s.substring(last).trim());
        return parts;
    }

    /**
     * 最外層迴圈的條件:「這個 case 的按鍵還按著」就繼續(同時按別的鍵不會跳出去)。
     * header:case 標頭文字;label:CodeTab 的段落名稱(用來分辨左 / 右搖桿)。認不得的 case 回傳 null。
     */
    /**
     * 搖桿類的條件要再加一項「Share 沒被按」:按鍵類的 keyHas() 已經排除了 Share,
     * 但搖桿類的迴圈沒有看按鍵,不加的話推著搖桿時按 Share 會沒反應(比賽規則:Share = 全部放鬆)。
     */
    static Cond withStop(Cond c) {
        c.clauses.add(new Clause("CUSTOM", "pad_getKey() != PAD_BTN_STOP", false));
        return c;
    }

    public static Cond holdCond(String header, String label) {
        if (header == null) return null;
        String h = header.trim();
        if (h.endsWith("{")) h = h.substring(0, h.length() - 1).trim();
        Matcher m = Pattern.compile("^case\\s+PAD_BTN_([A-Z0-9]+)\\s*:$").matcher(h);
        if (m.matches()) {
            String b = m.group(1);
            if (in(BUTTONS, b)) return new Cond(new Clause("HOLD", b, false));
            if (b.equals("START") || b.equals("STOP")) return new Cond(new Clause("CUSTOM", "pad_getKey() == PAD_BTN_" + b, false));
            return null;
        }
        m = Pattern.compile("^case\\s+PAD_(L3|R3)\\s*:$").matcher(h);
        if (m.matches()) return withStop(new Cond(new Clause("CUSTOM", "(pad_getStickButtons() & PAD_" + m.group(1) + ")", false)));
        if (h.matches("^case\\s+PAD_L3\\s*\\|\\s*PAD_R3\\s*:$")) return withStop(new Cond(new Clause("STICK", "L3+R3", false)));
        m = Pattern.compile("^case\\s+DIR_(UP|DOWN|LEFT|RIGHT)\\s*:$").matcher(h);
        if (m.matches() && label != null) {
            if (label.contains("左搖桿")) return withStop(new Cond(new Clause("LS_DIR", m.group(1), false)));
            if (label.contains("右搖桿")) return withStop(new Cond(new Clause("RS_DIR", m.group(1), false)));
        }
        return null;
    }

    public static Cond parseCond(String s) {
        s = s.trim();
        List<String> andParts = splitTop(s, "&&");
        List<String> orParts = splitTop(s, "||");
        Cond c = new Cond();
        List<String> parts;
        if (andParts.size() > 1 && orParts.size() > 1) {
            c.clauses.add(new Clause("CUSTOM", s, false));
            return c;
        }
        if (orParts.size() > 1) { c.or = true; parts = orParts; } else { parts = andParts; }
        for (String p : parts) c.clauses.add(parseClause(p));
        return c;
    }

    static final Pattern P_KEYIS = Pattern.compile("^pad_getKey\\(\\)\\s*==\\s*PAD_BTN_([A-Z0-9]+)$");
    static final Pattern P_HOLD = Pattern.compile("^keyHas\\(\\s*PAD_BTN_([A-Z0-9]+)\\s*\\)$");
    static final Pattern P_STICK = Pattern.compile("^pad_getStickButtons\\(\\)\\s*==\\s*(PAD_L3|PAD_R3|\\(PAD_L3\\s*\\|\\s*PAD_R3\\))$");
    static final Pattern P_DIR = Pattern.compile("^(LS|RS)_DIR\\s*==\\s*DIR_(UP|DOWN|LEFT|RIGHT)$");
    static final Pattern P_FLAG = Pattern.compile("^(LS|RS)_(UP|DOWN|LEFT|RIGHT)$");

    static Clause parseClause(String s) {
        s = s.trim();
        boolean not = false;
        String t = s;
        // 外面包一層括號
        while (t.startsWith("(") && t.endsWith(")") && balanced(t.substring(1, t.length() - 1))) t = t.substring(1, t.length() - 1).trim();
        if (t.startsWith("!(") && t.endsWith(")") && balanced(t.substring(2, t.length() - 1))) { not = true; t = t.substring(2, t.length() - 1).trim(); }
        else if (t.startsWith("!") && !t.startsWith("!=")) { not = true; t = t.substring(1).trim(); }
        if (t.equals("pad_getKey() == 0")) return new Clause("KEY_NONE", "", not);
        if (t.equals("pad_getKey() > 0")) return new Clause("KEY_ANY", "", not);
        if (t.contains("||")) {                                    // (沒按鍵,或只按 A、B…)
            List<String> ps = splitTop(t, "||");
            StringBuilder btns = new StringBuilder();
            boolean ok = ps.size() > 1 && ps.get(0).trim().equals("pad_getKey() == 0");
            for (int i = 1; ok && i < ps.size(); i++) {
                Matcher km = P_KEYIS.matcher(ps.get(i).trim());
                if (km.matches() && in(BUTTONS, km.group(1))) { if (btns.length() > 0) btns.append(","); btns.append(km.group(1)); }
                else ok = false;
            }
            if (ok) return new Clause("KEY_NONE_OR", btns.toString(), not);
        }
        Matcher m = P_KEYIS.matcher(t);
        if (m.matches() && in(BUTTONS, m.group(1))) return new Clause("KEY_IS", m.group(1), not);
        m = P_HOLD.matcher(t);
        if (m.matches() && in(BUTTONS, m.group(1))) return new Clause("HOLD", m.group(1), not);
        m = P_STICK.matcher(t);
        if (m.matches()) { String g = m.group(1); return new Clause("STICK", g.equals("PAD_L3") ? "L3" : g.equals("PAD_R3") ? "R3" : "L3+R3", not); }
        m = P_DIR.matcher(t);
        if (m.matches()) return new Clause(m.group(1) + "_DIR", m.group(2), not);
        m = P_FLAG.matcher(t);
        if (m.matches()) return new Clause(m.group(1) + "_FLAG", m.group(2), not);
        return new Clause("CUSTOM", s, false);
    }

    static boolean balanced(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') d++;
            else if (s.charAt(i) == ')') { d--; if (d < 0) return false; }
        }
        return d == 0;
    }
}
