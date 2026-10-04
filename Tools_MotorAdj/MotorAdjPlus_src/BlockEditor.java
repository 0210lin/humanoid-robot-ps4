import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 方塊編輯器(Scratch 式):把動作、重複、如果 / 否則、條件拖來拖去拼成一個 case 的內容,
 * 右下角即時顯示產生的程式碼,按「套用」就寫回「動作程式」分頁的編輯區(再用「檢查語法」「更新到機器人」)。
 * 拖曳:從左邊拖新方塊進來;拖工作區裡的方塊可以移動;拖回左邊 = 刪除;雙擊左邊的方塊 = 加到最後面;右鍵有更多選項。
 */
public class BlockEditor {

    public interface Applier { void apply(String newText); }

    // ---------------- 狀態 ----------------
    static Blocks.Script script;
    static String baseIndent = "";
    static Applier applier;
    static JDialog dlg;
    static JPanel workspace;
    static JTextArea preview;
    static JScrollPane palette, wsScroll;
    static DragLayer layer;
    static int frameMax = 0;
    static Blocks.Cond caseHold = null;   // 這個 case 的「按鍵還按著」條件(最外層迴圈用);認不得的 case 是 null
    static final List<Blocks.Script> undoStack = new ArrayList<Blocks.Script>();
    static final List<Blocks.Script> redoStack = new ArrayList<Blocks.Script>();
    static JButton undoBtn, redoBtn;
    static JLabel msg;           // 視窗下方的提示(例如「已自動更新迴圈條件」)
    static boolean english = true;   // 邏輯用語用英文(AND / OR / NOT…);可以用右下角按鈕切換成中文

    static String T(String zh, String en) { return english ? en : zh; }

    static String kindTip(String k) {
        if (k.equals("KEY_NONE")) return "沒有按任何按鍵(搖桿不算)";
        if (k.equals("KEY_ANY")) return "有按任何一個按鍵(搖桿不算)";
        if (k.equals("HOLD")) return "有按著這個鍵;可以同時按著別的鍵";
        if (k.equals("KEY_IS")) return "只按著這個鍵,沒有按別的鍵";
        if (k.equals("KEY_NONE_OR")) return "沒有按鍵,或只按著你選的那幾個鍵(做組合鍵用)";
        if (k.equals("STICK")) return "按下搖桿本身(L3 / R3)";
        if (k.equals("LS_DIR")) return "左搖桿推的主要方向(斜推時哪個軸推得多就算哪個)";
        if (k.equals("RS_DIR")) return "右搖桿推的主要方向(斜推時哪個軸推得多就算哪個)";
        if (k.equals("LS_FLAG")) return "左搖桿有往這個方向推(斜推時相鄰兩個方向都算)";
        if (k.equals("RS_FLAG")) return "右搖桿有往這個方向推(斜推時相鄰兩個方向都算)";
        return "自己寫 C++ 條件";
    }

    static void say(String s) {
        if (msg != null) msg.setText(s);
    }

    /**
     * 拖進一個「如果 按著 X」的組合鍵方塊時,自動把外面那個「重複」的條件更新成「允許按 X」:
     * 條件裡的「沒有按任何鍵」改成「沒按鍵,或只按 X」,已經是這種就把 X 加進去。
     * (不然一按 X,外面的迴圈就因為「有按鍵」而跳出去,組合鍵永遠進不去。)
     */
    static void autoAllowCombo(SlotView slot, String key) {
        SlotView cur = slot;
        while (cur != null) {
            if (cur.owner != null && cur.owner.type.equals("REPEAT")) {
                for (Blocks.Clause c : cur.owner.cond.clauses) {
                    if (c.kind.equals("KEY_NONE") && !c.not) {
                        c.kind = "KEY_NONE_OR";
                        c.param = key;
                        say("已自動把外面「重複」的條件改成「沒按鍵,或只按 " + btnName(key).replace(" ", "") + "」,這樣按下它才不會跳出迴圈。");
                        return;
                    }
                    if (c.kind.equals("KEY_NONE_OR") && !contains(c.param, key)) {
                        c.param = toggle(c.param, key, true);
                        say("已自動把 " + btnName(key).replace(" ", "") + " 加進外面「重複」的條件。");
                        return;
                    }
                }
                return;
            }
            cur = cur.parentSlot;
        }
    }

    // ---------------- 顏色與名稱 ----------------
    static Color colorOf(String type) {
        if (type.equals("ACTION")) return new Color(0x3A7CC4);
        if (type.equals("WAIT")) return new Color(0x2E9E8F);
        if (type.equals("RELAX")) return new Color(0xD64545);
        if (type.equals("REPEAT")) return new Color(0xE08A2E);
        if (type.equals("IF")) return new Color(0xC9A227);
        if (type.equals("COMMENT")) return new Color(0x7A8493);
        return new Color(0x8B5FBF);   // CODE
    }

    static String btnName(String b) {
        if (english) {
            if (b.equals("UP")) return "↑ D-pad Up";
            if (b.equals("DOWN")) return "↓ D-pad Down";
            if (b.equals("LEFT")) return "← D-pad Left";
            if (b.equals("RIGHT")) return "→ D-pad Right";
            if (b.equals("TRIANGLE")) return "△ Triangle";
            if (b.equals("CROSS")) return "× Cross";
            if (b.equals("CIRCLE")) return "○ Circle";
            if (b.equals("SQUARE")) return "□ Square";
            return b;
        }
        if (b.equals("UP")) return "↑ 方向鍵上";
        if (b.equals("DOWN")) return "↓ 方向鍵下";
        if (b.equals("LEFT")) return "← 方向鍵左";
        if (b.equals("RIGHT")) return "→ 方向鍵右";
        if (b.equals("TRIANGLE")) return "△ 三角";
        if (b.equals("CROSS")) return "× 叉叉";
        if (b.equals("CIRCLE")) return "○ 圈圈";
        if (b.equals("SQUARE")) return "□ 方塊";
        return b;
    }

    static String dirName(String d) {
        if (english) return d;
        if (d.equals("UP")) return "上";
        if (d.equals("DOWN")) return "下";
        if (d.equals("LEFT")) return "左";
        return "右";
    }

    static final String[][] KINDS = {
        {"KEY_NONE", "沒有按任何鍵", "no button pressed"},
        {"KEY_ANY", "有按任何鍵", "any button pressed"},
        {"HOLD", "有按著(可同時按別的)", "holding (others allowed)"},
        {"KEY_IS", "只按著(沒按別的)", "only holding (nothing else)"},
        {"KEY_NONE_OR", "沒按鍵,或只按", "none, or only"},
        {"STICK", "搖桿按壓", "stick press"},
        {"LS_DIR", "左搖桿的方向是", "left stick direction is"},
        {"RS_DIR", "右搖桿的方向是", "right stick direction is"},
        {"LS_FLAG", "左搖桿有往這邊推", "left stick pushed toward"},
        {"RS_FLAG", "右搖桿有往這邊推", "right stick pushed toward"},
        {"CUSTOM", "自訂條件", "custom condition"},
    };

    static String kindLabel(String k) {
        for (String[] kv : KINDS) if (kv[0].equals(k)) return kv[1];
        return k;
    }

    // ---------------- 複製(復原用) ----------------
    static Blocks.Cond copyCond(Blocks.Cond c) {
        Blocks.Cond r = new Blocks.Cond();
        r.or = c.or;
        for (Blocks.Clause k : c.clauses) r.clauses.add(new Blocks.Clause(k.kind, k.param, k.not));
        return r;
    }

    static Blocks.Node copyNode(Blocks.Node n) {
        Blocks.Node r = new Blocks.Node(n.type);
        r.a = n.a; r.b = n.b; r.enabled = n.enabled; r.note = n.note; r.elseNote = n.elseNote;
        r.cond = copyCond(n.cond);
        for (Blocks.Node k : n.body) r.body.add(copyNode(k));
        for (Blocks.Node k : n.elseBody) r.elseBody.add(copyNode(k));
        return r;
    }

    static Blocks.Script copyScript(Blocks.Script s) {
        Blocks.Script r = new Blocks.Script();
        r.header = s.header; r.headerNote = s.headerNote; r.braced = s.braced; r.braceNext = s.braceNext; r.braceNote = s.braceNote;
        for (Blocks.Node k : s.body) r.body.add(copyNode(k));
        return r;
    }

    static void snap() {
        undoStack.add(copyScript(script));
        if (undoStack.size() > 60) undoStack.remove(0);
        redoStack.clear();
        updateUndoButtons();
    }

    static void updateUndoButtons() {
        if (undoBtn != null) undoBtn.setEnabled(!undoStack.isEmpty());
        if (redoBtn != null) redoBtn.setEnabled(!redoStack.isEmpty());
    }

    // ---------------- 產生預覽 ----------------
    static String currentCode() {
        String g = Fmt.format(Blocks.generate(script, 0));
        if (baseIndent.isEmpty()) return g;
        StringBuilder sb = new StringBuilder();
        String[] ls = g.split("\n", -1);
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(ls[i].isEmpty() ? "" : baseIndent + ls[i]);
        }
        return sb.toString();
    }

    static void updatePreview() {
        if (preview != null) {
            preview.setText(currentCode());
            preview.setCaretPosition(0);
        }
    }

    // ---------------- 方塊樣板(左邊的選單) ----------------
    static class Tpl {
        String label; String type; String tip; String key;
        Tpl(String label, String type, String tip) { this.label = label; this.type = type; this.tip = tip; }
        Tpl(String label, String type, String tip, String key) { this(label, type, tip); this.key = key; }
        Blocks.Node make() {
            Blocks.Node n = new Blocks.Node(type);
            if (type.equals("ACTION")) { n.a = "1"; n.b = "100"; }
            else if (type.equals("WAIT")) { n.a = "100"; }
            else if (type.equals("COMMENT")) { n.a = "說明文字"; }
            else if (type.equals("CODE")) { n.a = "// 在這裡寫程式"; }
            else if (type.equals("REPEAT")) { n.cond = new Blocks.Cond(new Blocks.Clause("KEY_ANY", "", false)); }
            else if (type.equals("IF")) {
                if (key != null) n.cond = new Blocks.Cond(new Blocks.Clause("HOLD", key, false));
                else n.cond = new Blocks.Cond(new Blocks.Clause("KEY_ANY", "", false));
            } else if (type.equals("IF_ELSE")) {
                n.type = "IF";
                n.cond = new Blocks.Cond(new Blocks.Clause("KEY_ANY", "", false));
                n.a = "ELSE";   // 標記:顯示「否則」區(裡面還沒有方塊也要顯示)
            }
            return n;
        }
    }

    static List<Object[]> paletteSections() {
        List<Object[]> s = new ArrayList<Object[]>();
        s.add(new Object[] {"動作", new Tpl[] {
            new Tpl("動作 (播放一幀)", "ACTION", "SetFrameRun(幀, 時間)"),
            new Tpl("等待", "WAIT", "delay(毫秒)"),
            new Tpl("放鬆全部馬達", "RELAX", "uart_disableMotor()"),
        }});
        s.add(new Object[] {"控制", new Tpl[] {
            new Tpl("重複(do while)", "REPEAT", "做完裡面的方塊,條件還成立就再做一次"),
            new Tpl("如果 (if)", "IF", "條件成立才做"),
            new Tpl("如果…否則 (if else)", "IF_ELSE", "條件成立做上面,不成立做下面"),
        }});
        Tpl[] combos = new Tpl[Blocks.BUTTONS.length];
        for (int i = 0; i < combos.length; i++) {
            combos[i] = new Tpl("如果 有按著 " + btnName(Blocks.BUTTONS[i]), "IF", "組合鍵:有按著這個鍵就做裡面的方塊", Blocks.BUTTONS[i]);
        }
        s.add(new Object[] {"組合鍵(拖進去就是『如果按著…』)", combos});
        s.add(new Object[] {"其他", new Tpl[] {
            new Tpl("註解", "COMMENT", "只是說明文字,不會執行"),
            new Tpl("自訂程式碼", "CODE", "自己寫 C++,原樣放進去"),
        }});
        return s;
    }

    // ---------------- 拖曳 ----------------
    static class DragLayer extends JComponent {
        String ghost = null; Color ghostColor = Color.GRAY; Point at = new Point(); Rectangle indicator = null; boolean deleteMode = false;
        DragLayer() { setOpaque(false); }
        public boolean contains(int x, int y) { return false; }     // 不擋住下面的滑鼠事件
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (indicator != null) {
                g.setColor(new Color(0x00, 0xB8, 0xD4));
                g.fillRoundRect(indicator.x, indicator.y - 2, indicator.width, 5, 5, 5);
            }
            if (ghost != null) {
                g.setFont(getFont().deriveFont(Font.BOLD, 13f));
                int w = g.getFontMetrics().stringWidth(ghost) + 24;
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.85f));
                g.setColor(deleteMode ? new Color(0xD6, 0x45, 0x45) : ghostColor);
                g.fillRoundRect(at.x + 8, at.y + 8, w, 30, 12, 12);
                g.setColor(Color.WHITE);
                g.drawString(deleteMode ? "放開 = 刪除" : ghost, at.x + 20, at.y + 28);
            }
            g.dispose();
        }
    }

    static Blocks.Node dragNode;      // 正在拖的(工作區裡的方塊)
    static Tpl dragTpl;               // 正在拖的(左邊的新方塊)
    static Point pressPoint;
    static boolean dragging;
    static SlotView targetSlot;
    static int targetIndex;
    static boolean overPalette;

    static void beginPress(MouseEvent e) { pressPoint = e.getLocationOnScreen(); dragging = false; }

    static void dragMoved(MouseEvent e, String text, Color color) {
        Point sp = e.getLocationOnScreen();
        if (!dragging) {
            if (pressPoint == null || pressPoint.distance(sp) < 5) return;
            dragging = true;
            layer.ghost = text;
            layer.ghostColor = color;
            layer.setVisible(true);
        }
        Point lp = new Point(sp);
        SwingUtilities.convertPointFromScreen(lp, layer);
        layer.at = lp;
        locateTarget(sp);
        layer.deleteMode = overPalette && dragNode != null;
        layer.repaint();
    }

    static boolean insideScreen(Component c, Point screen) {
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, c);
        return p.x >= 0 && p.y >= 0 && p.x < c.getWidth() && p.y < c.getHeight();
    }

    static boolean ownedBy(SlotView s, Blocks.Node n) {
        SlotView cur = s;
        while (cur != null) {
            if (cur.owner == n) return true;
            cur = cur.parentSlot;
        }
        return false;
    }

    static void locateTarget(Point screen) {
        targetSlot = null;
        targetIndex = 0;
        layer.indicator = null;
        overPalette = insideScreen(palette, screen);
        if (overPalette || !insideScreen(wsScroll, screen)) return;
        Point wp = new Point(screen);
        SwingUtilities.convertPointFromScreen(wp, workspace);
        Component c = SwingUtilities.getDeepestComponentAt(workspace, wp.x, wp.y);
        SlotView slot = null;
        while (c != null && c != workspace) {
            if (c instanceof SlotView) { slot = (SlotView) c; break; }
            c = c.getParent();
        }
        if (slot == null) slot = rootSlot;
        if (dragNode != null && ownedBy(slot, dragNode)) return;
        Point sp = new Point(screen);
        SwingUtilities.convertPointFromScreen(sp, slot);
        int idx = 0;
        Component[] kids = slot.getComponents();
        List<Component> blocks = new ArrayList<Component>();
        for (Component k : kids) if (k instanceof BlockView) blocks.add(k);
        for (Component k : blocks) {
            if (sp.y > k.getY() + k.getHeight() / 2) idx++;
        }
        targetSlot = slot;
        targetIndex = idx;
        int y;
        if (blocks.isEmpty()) y = 6;
        else if (idx < blocks.size()) y = blocks.get(idx).getY() - 1;
        else y = blocks.get(blocks.size() - 1).getY() + blocks.get(blocks.size() - 1).getHeight() + 1;
        Point lp = SwingUtilities.convertPoint(slot, 6, y, layer);
        layer.indicator = new Rectangle(lp.x, lp.y, Math.max(40, slot.getWidth() - 12), 5);
    }

    static boolean removeFrom(List<Blocks.Node> list, Blocks.Node n) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == n) { list.remove(i); return true; }
            if (removeFrom(list.get(i).body, n) || removeFrom(list.get(i).elseBody, n)) return true;
        }
        return false;
    }

    static int indexOfIn(List<Blocks.Node> list, Blocks.Node n) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == n) return i;
        return -1;
    }

    static void endDrag(MouseEvent e) {
        boolean was = dragging;
        dragging = false;
        pressPoint = null;
        layer.setVisible(false);
        layer.ghost = null;
        layer.indicator = null;
        if (!was) { dragNode = null; dragTpl = null; return; }
        try {
            if (dragTpl != null) {
                if (targetSlot != null) {
                    snap();
                    List<Blocks.Node> list = targetSlot.nodes();
                    list.add(Math.min(targetIndex, list.size()), dragTpl.make());
                    if (dragTpl.key != null) autoAllowCombo(targetSlot, dragTpl.key);
                    if (dragTpl.type.equals("REPEAT") && targetSlot.owner == null && caseHold != null) {
                        Blocks.Node added = list.get(Math.min(targetIndex, list.size() - 1));
                        if (added.type.equals("REPEAT")) {
                            added.cond = copyCond(caseHold);
                            say("已自動填入最外層迴圈的條件:這個 case 的按鍵一直按著就繼續(同時按別的鍵也不會跳出去)。");
                        }
                    }
                    refresh();
                }
            } else if (dragNode != null) {
                if (overPalette) {
                    snap();
                    removeFrom(script.body, dragNode);
                    refresh();
                } else if (targetSlot != null) {
                    snap();
                    List<Blocks.Node> dest = targetSlot.nodes();
                    int idx = targetIndex;
                    int old = indexOfIn(dest, dragNode);
                    if (old >= 0 && old < idx) idx--;
                    removeFrom(script.body, dragNode);
                    dest.add(Math.min(idx, dest.size()), dragNode);
                    refresh();
                }
            }
        } finally {
            dragNode = null;
            dragTpl = null;
        }
    }

    // ---------------- 工作區的元件 ----------------
    static SlotView rootSlot;

    /** 一串方塊的容器 */
    static class SlotView extends JPanel {
        Blocks.Node owner;           // 這個容器屬於哪個方塊(根容器為 null)
        boolean isElse;
        SlotView parentSlot;
        List<Blocks.Node> rootList;
        SlotView(Blocks.Node owner, boolean isElse, SlotView parentSlot, List<Blocks.Node> rootList) {
            this.owner = owner; this.isElse = isElse; this.parentSlot = parentSlot; this.rootList = rootList;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        }
        List<Blocks.Node> nodes() {
            if (owner == null) return rootList;
            return isElse ? owner.elseBody : owner.body;
        }
        public Dimension getMinimumSize() { return new Dimension(120, 30); }
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(d.width, 220), Math.max(d.height, 34));
        }
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (nodes().isEmpty()) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x88, 0x88, 0x88, 140));
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {5f, 4f}, 0f));
                g.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 10, 10);
                g.setFont(getFont().deriveFont(12f));
                g.drawString("把方塊拖到這裡", 14, getHeight() / 2 + 4);
                g.dispose();
            }
        }
    }

    /** 一個方塊(圓角、有顏色) */
    static class BlockView extends JPanel {
        Blocks.Node node;
        BlockView(Blocks.Node n) {
            this.node = n;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = colorOf(node.type);
            if (!node.enabled) c = new Color(c.getRed(), c.getGreen(), c.getBlue(), 90);
            g.setColor(c);
            g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 14, 14);
            g.setColor(new Color(0, 0, 0, 50));
            g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 14, 14);
            g.dispose();
        }
    }

    static JLabel whiteLabel(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(Color.WHITE);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        return l;
    }

    interface Setter { void set(String v); }

    static JTextField field(String value, int cols, final Setter s, final boolean frame) {
        final JTextField f = new JTextField(value, cols);
        f.setMargin(new java.awt.Insets(1, 3, 1, 3));
        checkFrame(f, frame);
        f.getDocument().addDocumentListener(new DocumentListener() {
            void ch() { s.set(f.getText()); updatePreview(); checkFrame(f, frame); }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        return f;
    }

    static Color UIManagerColor() {
        Color c = javax.swing.UIManager.getColor("TextField.foreground");
        return c != null ? c : Color.BLACK;
    }

    static void checkFrame(JTextField f, boolean frame) {
        if (!frame) return;
        boolean bad = false;
        try {
            int v = Integer.parseInt(f.getText().trim());
            if (v < 0 || (frameMax > 0 && v >= frameMax)) bad = true;
        } catch (Exception ex) { bad = !f.getText().trim().matches("^[A-Za-z_][A-Za-z_0-9]*$"); }
        f.setForeground(bad ? new Color(0xD6, 0x45, 0x45) : UIManagerColor());
        f.setToolTipText(bad ? "這個幀不存在(motor.h 只有 " + frameMax + " 幀,編號從 0 開始)" : null);
    }

    // ---------------- 條件編輯 ----------------
    static JComponent condEditor(final Blocks.Node n) {
        final Blocks.Cond c = n.cond;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.setOpaque(false);
        for (int i = 0; i < c.clauses.size(); i++) {
            final Blocks.Clause cl = c.clauses.get(i);
            if (i > 0) {
                JButton op = new JButton(c.or ? T("或", "OR") : T("而且", "AND"));
                op.setMargin(new java.awt.Insets(0, 6, 0, 6));
                op.setToolTipText("按一下切換「而且」/「或」");
                op.addActionListener(new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) { snap(); c.or = !c.or; refresh(); }
                });
                row.add(op);
            }
            final JCheckBox not = new JCheckBox(T("不是", "NOT"), cl.not);
            not.setOpaque(false); not.setForeground(Color.WHITE);
            not.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) { snap(); cl.not = not.isSelected(); updatePreview(); }
            });
            row.add(not);
            final JComboBox<String> kind = new JComboBox<String>();
            int sel = 0;
            for (int k = 0; k < KINDS.length; k++) { kind.addItem(T(KINDS[k][1], KINDS[k][2])); if (KINDS[k][0].equals(cl.kind)) sel = k; }
            kind.setSelectedIndex(sel);
            kind.setToolTipText(kindTip(cl.kind));
            kind.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    String nk = KINDS[kind.getSelectedIndex()][0];
                    if (nk.equals(cl.kind)) return;
                    snap();
                    cl.kind = nk;
                    cl.param = defaultParam(nk);
                    refresh();
                }
            });
            row.add(kind);
            row.add(paramEditor(cl));
            if (c.clauses.size() > 1) {
                JButton del = new JButton("×");
                del.setMargin(new java.awt.Insets(0, 4, 0, 4));
                del.setToolTipText("移除這個條件");
                del.addActionListener(new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) { snap(); c.clauses.remove(cl); refresh(); }
                });
                row.add(del);
            }
        }
        JButton add = new JButton(T("+ 條件", "+ condition"));
        add.setMargin(new java.awt.Insets(0, 6, 0, 6));
        add.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                snap();
                c.clauses.add(new Blocks.Clause("KEY_NONE", "", false));
                refresh();
            }
        });
        row.add(add);
        return row;
    }

    static String defaultParam(String kind) {
        if (kind.equals("HOLD") || kind.equals("KEY_IS")) return "CIRCLE";
        if (kind.equals("KEY_NONE_OR")) return "CIRCLE";
        if (kind.equals("STICK")) return "L3";
        if (kind.endsWith("_DIR") || kind.endsWith("_FLAG")) return "UP";
        if (kind.equals("CUSTOM")) return "1";
        return "";
    }

    static JComponent paramEditor(final Blocks.Clause cl) {
        String k = cl.kind;
        if (k.equals("HOLD") || k.equals("KEY_IS")) {
            final JComboBox<String> b = new JComboBox<String>();
            int sel = 0;
            for (int i = 0; i < Blocks.BUTTONS.length; i++) { b.addItem(btnName(Blocks.BUTTONS[i])); if (Blocks.BUTTONS[i].equals(cl.param)) sel = i; }
            b.setSelectedIndex(sel);
            b.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) { cl.param = Blocks.BUTTONS[b.getSelectedIndex()]; updatePreview(); }
            });
            return b;
        }
        if (k.equals("KEY_NONE_OR")) {
            final JButton btn = new JButton(namesOf(cl.param));
            btn.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    JPopupMenu pm = new JPopupMenu();
                    for (final String bn : Blocks.BUTTONS) {
                        final JCheckBoxMenuItem it = new JCheckBoxMenuItem(btnName(bn), contains(cl.param, bn));
                        it.addActionListener(new java.awt.event.ActionListener() {
                            public void actionPerformed(java.awt.event.ActionEvent ev) {
                                snap();
                                cl.param = toggle(cl.param, bn, it.isSelected());
                                btn.setText(namesOf(cl.param));
                                updatePreview();
                            }
                        });
                        pm.add(it);
                    }
                    pm.show(btn, 0, btn.getHeight());
                }
            });
            return btn;
        }
        if (k.equals("STICK")) {
            final JComboBox<String> b = new JComboBox<String>(new String[] {"L3", "R3", "L3+R3"});
            b.setSelectedItem(cl.param);
            b.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) { cl.param = (String) b.getSelectedItem(); updatePreview(); }
            });
            return b;
        }
        if (k.endsWith("_DIR") || k.endsWith("_FLAG")) {
            final JComboBox<String> b = new JComboBox<String>();
            int sel = 0;
            for (int i = 0; i < Blocks.DIRS.length; i++) { b.addItem(dirName(Blocks.DIRS[i])); if (Blocks.DIRS[i].equals(cl.param)) sel = i; }
            b.setSelectedIndex(sel);
            b.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) { cl.param = Blocks.DIRS[b.getSelectedIndex()]; updatePreview(); }
            });
            return b;
        }
        if (k.equals("CUSTOM")) {
            return field(cl.param, 22, new Setter() { public void set(String v) { cl.param = v; } }, false);
        }
        JLabel none = new JLabel("");
        return none;
    }

    static boolean contains(String csv, String b) {
        for (String s : csv.split(",")) if (s.trim().equals(b)) return true;
        return false;
    }

    static String toggle(String csv, String b, boolean on) {
        List<String> out = new ArrayList<String>();
        for (String s : csv.split(",")) if (!s.trim().isEmpty() && !s.trim().equals(b)) out.add(s.trim());
        if (on) out.add(b);
        StringBuilder sb = new StringBuilder();
        for (String s : out) { if (sb.length() > 0) sb.append(","); sb.append(s); }
        return sb.toString();
    }

    static String namesOf(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String s : csv.split(",")) {
            if (s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("、");
            String nm = btnName(s.trim());
            sb.append(nm.contains(" ") ? nm.substring(0, nm.indexOf(' ')) : nm);
        }
        return sb.length() == 0 ? "(選按鍵)" : sb.toString();
    }

    // ---------------- 建立畫面 ----------------
    // ---------- 程式碼樣式的方塊 ----------
    static JLabel codeLabel(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(Color.WHITE);
        l.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        return l;
    }

    static java.awt.event.ActionListener act(final Runnable r) {
        return new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    /** 條件的白話說明(滑鼠停在條件上會顯示) */
    static String describe(Blocks.Cond c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.clauses.size(); i++) {
            Blocks.Clause k = c.clauses.get(i);
            if (i > 0) sb.append(c.or ? T(" 或 ", " OR ") : T(" 而且 ", " AND "));
            String s;
            String bn = btnName(k.param);
            if (k.kind.equals("KEY_NONE")) s = T("沒有按任何按鍵", "no button is held");
            else if (k.kind.equals("KEY_ANY")) s = T("有按任何一個按鍵", "any button is held");
            else if (k.kind.equals("HOLD")) s = T(bn + " 被按著(可以同時按別的)", bn + " is held (others allowed)");
            else if (k.kind.equals("KEY_IS")) s = T("只有 " + bn + " 被按著", "only " + bn + " is held");
            else if (k.kind.equals("KEY_NONE_OR")) s = T("沒按鍵,或只按 " + namesOf(k.param), "no button, or only " + namesOf(k.param));
            else if (k.kind.equals("STICK")) s = T(k.param + " 被按下", k.param + " is pressed");
            else if (k.kind.equals("LS_DIR")) s = T("左搖桿主要往 " + dirName(k.param), "left stick points " + dirName(k.param));
            else if (k.kind.equals("RS_DIR")) s = T("右搖桿主要往 " + dirName(k.param), "right stick points " + dirName(k.param));
            else if (k.kind.equals("LS_FLAG")) s = T("左搖桿有往 " + dirName(k.param) + " 推", "left stick pushed " + dirName(k.param));
            else if (k.kind.equals("RS_FLAG")) s = T("右搖桿有往 " + dirName(k.param) + " 推", "right stick pushed " + dirName(k.param));
            else s = T("自訂條件:", "custom: ") + k.param;
            if (k.not) s = T("不是(", "NOT (") + s + ")";
            sb.append(s);
        }
        return sb.toString();
    }

    static void addCond(JTextField f, String code) {
        String cur = f.getText().trim();
        f.setText(cur.isEmpty() || cur.equals("1") ? code : cur + " && " + code);
    }

    static JPopupMenu condMenu(final JTextField f) {
        JPopupMenu pm = new JPopupMenu();
        JMenuItem none = new JMenuItem(T("沒有按任何鍵(所有按鍵都放開)     pad_getKey() == 0", "no button held (all released)     pad_getKey() == 0"));
        none.addActionListener(act(new Runnable() { public void run() { addCond(f, "pad_getKey() == 0"); } }));
        pm.add(none);
        JMenuItem any = new JMenuItem(T("有按任何鍵(至少按著一顆)     pad_getKey() > 0", "any button held (at least one)     pad_getKey() > 0"));
        any.addActionListener(act(new Runnable() { public void run() { addCond(f, "pad_getKey() > 0"); } }));
        pm.add(any);
        javax.swing.JMenu hold = new javax.swing.JMenu(T("有按著某個鍵(同時按別的也算)", "holding a button (others may be held too)"));
        for (final String bn : Blocks.BUTTONS) {
            JMenuItem h = new JMenuItem(btnName(bn) + "     keyHas(PAD_BTN_" + bn + ")");
            h.addActionListener(act(new Runnable() { public void run() { addCond(f, "keyHas(PAD_BTN_" + bn + ")"); } }));
            hold.add(h);
        }
        pm.add(hold);
        javax.swing.JMenu st = new javax.swing.JMenu(T("搖桿按壓", "stick press"));
        String[][] sts = {{"L3", "pad_getStickButtons() == PAD_L3"}, {"R3", "pad_getStickButtons() == PAD_R3"}, {"L3+R3", "pad_getStickButtons() == (PAD_L3 | PAD_R3)"}};
        for (final String[] s : sts) {
            JMenuItem it = new JMenuItem(s[0] + "     " + s[1]);
            it.addActionListener(act(new Runnable() { public void run() { addCond(f, s[1]); } }));
            st.add(it);
        }
        pm.add(st);
        String[][] sticks = {{"LS_FLAG", T("左搖桿有往這邊推(斜推兩邊都算)", "left stick pushed toward (diagonal counts both)"), "LS_"},
                             {"RS_FLAG", T("右搖桿有往這邊推(斜推兩邊都算)", "right stick pushed toward (diagonal counts both)"), "RS_"}};
        for (final String[] sk : sticks) {
            javax.swing.JMenu m = new javax.swing.JMenu(sk[1]);
            for (final String d : Blocks.DIRS) {
                JMenuItem it = new JMenuItem(dirName(d) + "     " + sk[2] + d);
                it.addActionListener(act(new Runnable() { public void run() { addCond(f, sk[2] + d); } }));
                m.add(it);
            }
            pm.add(m);
        }
        pm.addSeparator();
        JMenuItem clear = new JMenuItem(T("清掉(永遠成立)", "clear (always true)"));
        clear.addActionListener(act(new Runnable() { public void run() { f.setText("1"); } }));
        pm.add(clear);
        return pm;
    }

    /** 條件:一行可以直接改的程式碼 + ▾ 常用條件選單 */
    static void addCondEditor(JPanel row, final Blocks.Node n) {
        final JTextField f = new JTextField(Blocks.condCode(n.cond), Math.max(36, Blocks.condCode(n.cond).length() + 2));
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        f.setMargin(new java.awt.Insets(1, 3, 1, 3));
        f.setToolTipText(describe(n.cond));
        f.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                n.cond = Blocks.parseCond(f.getText());
                f.setToolTipText(describe(n.cond));
                updatePreview();
            }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        row.add(f);
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() { if (autoFixLoops()) refresh(); }
                });
            }
        });
        final JButton b = new JButton(T("插入", "insert"));
        b.setMargin(new java.awt.Insets(0, 5, 0, 5));
        b.setToolTipText(T("插入常用條件(不用背語法)", "insert a common condition"));
        b.addActionListener(act(new Runnable() { public void run() { condMenu(f).show(b, 0, b.getHeight()); } }));
        row.add(b);
    }

    static JPanel codeRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** 「+ 組合」:按著另一個鍵時,這一步換成另一個動作(自動變成 if / else,並更新外面迴圈的條件) */
    static JButton comboButton(final Blocks.Node n, final SlotView parent) {
        final JButton b = new JButton(T("+ 組合", "+ combo"));
        b.setMargin(new java.awt.Insets(0, 6, 0, 6));
        b.setToolTipText(T("按著另一個鍵時,這一步換成別的動作(自動產生 if / else,並更新外面迴圈的條件)",
                           "when another button is also held, play a different action here (creates if / else and updates the loop condition)"));
        b.addActionListener(act(new Runnable() {
            public void run() {
                JPopupMenu pm = new JPopupMenu();
                for (final String bn : Blocks.BUTTONS) {
                    JMenuItem it = new JMenuItem(T("按著 ", "when holding ") + btnName(bn));
                    it.addActionListener(act(new Runnable() { public void run() { makeCombo(n, parent, bn); } }));
                    pm.add(it);
                }
                pm.show(b, 0, b.getHeight());
            }
        }));
        return b;
    }

    static void makeCombo(Blocks.Node n, SlotView parent, String key) {
        List<Blocks.Node> host = findList(script.body, n);
        if (host == null) return;
        snap();
        int idx = indexOfIn(host, n);
        Blocks.Node ifn = new Blocks.Node("IF");
        ifn.cond = new Blocks.Cond(new Blocks.Clause("HOLD", key, false));
        Blocks.Node alt = copyNode(n);
        alt.note = "";
        ifn.body.add(alt);
        ifn.elseBody.add(n);
        host.set(idx, ifn);
        autoAllowCombo(parent, key);
        refresh();
    }

    /** 動作最前面的 //:點一下在「註解掉(先不執行)」和「啟用」之間切換 */
    static JButton commentToggle(final Blocks.Node n) {
        final JButton b = new JButton("//");
        b.setMargin(new java.awt.Insets(0, 4, 0, 4));
        b.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        b.setFocusable(false);
        if (!n.enabled) {
            b.setBackground(new Color(0xF5, 0xA6, 0x23));
            b.setForeground(Color.BLACK);
            b.setToolTipText(T("已經註解掉了(不會執行)。點一下取消註解", "commented out (not executed). click to uncomment"));
        } else {
            b.setToolTipText(T("點一下把這一行註解掉(先不執行)", "click to comment this line out (disable it)"));
        }
        b.addActionListener(act(new Runnable() {
            public void run() { snap(); n.enabled = !n.enabled; refresh(); }
        }));
        return b;
    }

    static BlockView makeBlock(final Blocks.Node n, SlotView parent) {
        final BlockView bv = new BlockView(n);
        JPanel header = codeRow();
        List<Component> grips = new ArrayList<Component>();   // 可以抓來拖的部分(不含輸入欄位)
        String t = n.type;
        final Color col = colorOf(t);
        if (t.equals("ACTION")) {
            header.add(commentToggle(n));
            JLabel l1 = codeLabel("SetFrameRun("); header.add(l1); grips.add(l1);
            header.add(field(n.a, 4, new Setter() { public void set(String v) { n.a = v; } }, true));
            JLabel l2 = codeLabel(","); header.add(l2); grips.add(l2);
            header.add(field(n.b, 4, new Setter() { public void set(String v) { n.b = v; } }, false));
            JLabel l3 = codeLabel(");"); header.add(l3); grips.add(l3);
            JLabel l4 = codeLabel("//"); header.add(l4); grips.add(l4);
            header.add(noteField(n));
            header.add(comboButton(n, parent));
        } else if (t.equals("WAIT")) {
            header.add(commentToggle(n));
            JLabel l1 = codeLabel("delay("); header.add(l1); grips.add(l1);
            header.add(field(n.a, 5, new Setter() { public void set(String v) { n.a = v; } }, false));
            JLabel l2 = codeLabel(");"); header.add(l2); grips.add(l2);
            JLabel l3 = codeLabel("//"); header.add(l3); grips.add(l3);
            header.add(noteField(n));
        } else if (t.equals("RELAX")) {
            header.add(commentToggle(n));
            JLabel l1 = codeLabel("uart_disableMotor();"); header.add(l1); grips.add(l1);
            JLabel l3 = codeLabel("//"); header.add(l3); grips.add(l3);
            header.add(noteField(n));
        } else if (t.equals("COMMENT")) {
            JLabel l1 = codeLabel("//"); header.add(l1); grips.add(l1);
            header.add(field(n.a, 40, new Setter() { public void set(String v) { n.a = v; } }, false));
        } else if (t.equals("CODE")) {
            JLabel l1 = whiteLabel(T("自訂程式碼", "custom code")); header.add(l1); grips.add(l1);
            final JTextArea ta = new JTextArea(n.a, Math.max(1, n.a.split("\n", -1).length), 40);
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            ta.getDocument().addDocumentListener(new DocumentListener() {
                void ch() { n.a = ta.getText(); updatePreview(); }
                public void insertUpdate(DocumentEvent e) { ch(); }
                public void removeUpdate(DocumentEvent e) { ch(); }
                public void changedUpdate(DocumentEvent e) { ch(); }
            });
            header.add(ta);
        } else if (t.equals("REPEAT")) {
            JLabel l1 = codeLabel("do {"); header.add(l1); grips.add(l1);
        } else if (t.equals("IF")) {
            JLabel l1 = codeLabel("if ("); header.add(l1); grips.add(l1);
            addCondEditor(header, n);
            JLabel l2 = codeLabel(") {"); header.add(l2); grips.add(l2);
        }
        bv.add(header);

        List<Component> footGrips = new ArrayList<Component>();
        if (t.equals("REPEAT") || t.equals("IF")) {
            SlotView inner = new SlotView(n, false, parent, null);
            fill(inner, n.body);
            bv.add(indentWrap(inner));
            if (t.equals("REPEAT")) {
                JPanel foot = codeRow();
                JLabel f1 = codeLabel("} while ("); foot.add(f1); footGrips.add(f1);
                addCondEditor(foot, n);
                JLabel f2 = codeLabel(");"); foot.add(f2); footGrips.add(f2);
                if (caseHold != null) {
                    JButton fill = new JButton(T("自動填 case 按鍵", "auto: case button"));
                    fill.setMargin(new java.awt.Insets(0, 6, 0, 6));
                    fill.setToolTipText(T("把這個迴圈的條件換成「這個 case 的按鍵還按著」(同時按別的鍵也不會跳出去)",
                                         "set this loop's condition to: the case's own button is still held (other buttons do not stop it)"));
                    fill.addActionListener(new java.awt.event.ActionListener() {
                        public void actionPerformed(java.awt.event.ActionEvent e) {
                            snap();
                            n.cond = copyCond(caseHold);
                            say("已填入:這個 case 的按鍵一直按著就繼續。");
                            refresh();
                        }
                    });
                    foot.add(fill);
                }
                bv.add(foot);
            } else {
                boolean hasElse = !n.elseBody.isEmpty() || "ELSE".equals(n.a);
                if (hasElse) {
                    JPanel eh = codeRow();
                    JLabel el = codeLabel("} else {"); eh.add(el); footGrips.add(el);
                    JButton rm = new JButton(T("移除 else", "remove else"));
                    rm.setMargin(new java.awt.Insets(0, 6, 0, 6));
                    rm.addActionListener(new java.awt.event.ActionListener() {
                        public void actionPerformed(java.awt.event.ActionEvent e) {
                            snap();
                            // 把 else 裡的方塊接在這個方塊後面,不要弄丟
                            List<Blocks.Node> host = findList(script.body, n);
                            if (host != null) { int at = indexOfIn(host, n) + 1; host.addAll(at, n.elseBody); }
                            n.elseBody = new ArrayList<Blocks.Node>();
                            n.a = "";
                            refresh();
                        }
                    });
                    eh.add(rm);
                    bv.add(eh);
                    SlotView es = new SlotView(n, true, parent, null);
                    fill(es, n.elseBody);
                    bv.add(indentWrap(es));
                    JPanel end = codeRow();
                    JLabel e1 = codeLabel("}"); end.add(e1); footGrips.add(e1);
                    bv.add(end);
                } else {
                    JPanel end = codeRow();
                    JLabel e1 = codeLabel("}"); end.add(e1); footGrips.add(e1);
                    JButton add = new JButton(T("+ 加上 else", "+ add else"));
                    add.setMargin(new java.awt.Insets(0, 6, 0, 6));
                    add.addActionListener(new java.awt.event.ActionListener() {
                        public void actionPerformed(java.awt.event.ActionEvent e) { snap(); n.a = "ELSE"; refresh(); }
                    });
                    end.add(add);
                    bv.add(end);
                }
            }
        }

        // 拖曳:抓程式碼文字的部分(不含輸入欄位)
        MouseAdapter drag = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { popup(e, n); return; }
                dragNode = n; dragTpl = null; beginPress(e);
            }
            public void mouseDragged(MouseEvent e) { if (dragNode == n) dragMoved(e, titleOf(n), col); }
            public void mouseReleased(MouseEvent e) { if (dragNode == n) endDrag(e); }
        };
        header.addMouseListener(drag);
        header.addMouseMotionListener(drag);
        grips.addAll(footGrips);
        for (Component g : grips) { g.addMouseListener(drag); g.addMouseMotionListener(drag); }
        return bv;
    }

    static JTextField noteField(final Blocks.Node n) {
        JTextField f = field(n.note, 10, new Setter() { public void set(String v) { n.note = v; } }, false);
        f.setToolTipText("備註(行尾的 // 註解),可以不填");
        return f;
    }

    static List<Blocks.Node> findList(List<Blocks.Node> list, Blocks.Node n) {
        for (Blocks.Node k : list) {
            if (k == n) return list;
            List<Blocks.Node> r = findList(k.body, n);
            if (r != null) return r;
            r = findList(k.elseBody, n);
            if (r != null) return r;
        }
        return null;
    }

    static String titleOf(Blocks.Node n) {
        if (n.type.equals("ACTION")) return "動作 幀 " + n.a;
        if (n.type.equals("WAIT")) return "等待 " + n.a + " 毫秒";
        if (n.type.equals("RELAX")) return "放鬆全部馬達";
        if (n.type.equals("REPEAT")) return "重複";
        if (n.type.equals("IF")) return "如果";
        if (n.type.equals("COMMENT")) return "註解";
        return "自訂程式碼";
    }

    static JPanel indentWrap(SlotView inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 4));
        JPanel bg = new JPanel(new BorderLayout()) {
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(0, 0, 0, 38));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
        };
        bg.setOpaque(false);
        bg.add(inner, BorderLayout.CENTER);
        p.add(bg, BorderLayout.CENTER);
        return p;
    }

    static void fill(SlotView slot, List<Blocks.Node> list) {
        for (Blocks.Node n : list) slot.add(makeBlock(n, slot));
    }

    static void popup(MouseEvent e, final Blocks.Node n) {
        JPopupMenu pm = new JPopupMenu();
        JMenuItem del = new JMenuItem("刪除這個方塊");
        del.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent ev) { snap(); removeFrom(script.body, n); refresh(); }
        });
        pm.add(del);
        if (n.type.equals("ACTION") || n.type.equals("WAIT") || n.type.equals("RELAX")) {
            JMenuItem en = new JMenuItem(n.enabled ? "停用(變成註解,先不執行)" : "啟用");
            en.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent ev) { snap(); n.enabled = !n.enabled; refresh(); }
            });
            pm.add(en);
        }
        JMenuItem dup = new JMenuItem("複製");
        dup.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent ev) {
                snap();
                List<Blocks.Node> host = findList(script.body, n);
                if (host != null) host.add(indexOfIn(host, n) + 1, copyNode(n));
                refresh();
            }
        });
        pm.add(dup);
        pm.show(e.getComponent(), e.getX(), e.getY());
    }

    // ---------- 迴圈條件自動跟上組合鍵 ----------
    /** 收集 IF 條件裡用到的「按著某個鍵」(不含「不是」) */
    static void collectKeys(List<Blocks.Node> list, java.util.Set<String> keys) {
        for (Blocks.Node n : list) {
            if (n.type.equals("IF")) {
                for (Blocks.Clause c : n.cond.clauses) {
                    if (!c.not && (c.kind.equals("HOLD") || c.kind.equals("KEY_IS")) && Blocks.in(Blocks.BUTTONS, c.param)) keys.add(c.param);
                }
            }
            collectKeys(n.body, keys);
            collectKeys(n.elseBody, keys);
        }
    }

    static boolean allowKeys(Blocks.Node rep, java.util.Set<String> keys) {
        if (rep.cond.or) return false;
        boolean changed = false;
        for (Blocks.Clause c : rep.cond.clauses) {
            if (c.kind.equals("KEY_NONE") && !c.not) {
                c.kind = "KEY_NONE_OR";
                c.param = "";
                for (String k : keys) c.param = toggle(c.param, k, true);
                return true;
            }
            if (c.kind.equals("KEY_NONE_OR") && !c.not) {
                for (String k : keys) if (!contains(c.param, k)) { c.param = toggle(c.param, k, true); changed = true; }
                return changed;
            }
        }
        return changed;
    }

    static boolean fixList(List<Blocks.Node> list) {
        boolean ch = false;
        for (Blocks.Node n : list) {
            if (n.type.equals("REPEAT")) {
                java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
                collectKeys(n.body, keys);
                if (!keys.isEmpty() && allowKeys(n, keys)) ch = true;
            }
            if (fixList(n.body)) ch = true;
            if (fixList(n.elseBody)) ch = true;
        }
        return ch;
    }

    /**
     * 「重複」裡面如果有『如果 按著 X』,外面的迴圈條件不能是「沒有按任何鍵」,
     * 不然一按 X 迴圈就跳出去、組合永遠進不去。這裡自動把它改成「沒按鍵,或只按 X」。
     * 回傳有沒有改動。
     */
    static boolean autoFixLoops() {
        boolean ch = fixList(script.body);
        if (ch) say("已自動更新「重複」的條件,讓組合鍵進得去(按下組合鍵時迴圈不會跳出去)。");
        return ch;
    }

    static void refresh() {
        autoFixLoops();
        workspace.removeAll();
        JLabel hat = new JLabel(("  " + T("當收到這個 case:", "when this case runs:") + "  ") + script.header + "  ") {
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x2F, 0x6F, 0xED));
                g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 14, 14);
                g.dispose();
                super.paintComponent(g0);
            }
        };
        hat.setForeground(Color.WHITE);
        hat.setFont(hat.getFont().deriveFont(Font.BOLD, 14f));
        hat.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        hat.setAlignmentX(Component.LEFT_ALIGNMENT);
        workspace.add(hat);
        rootSlot = new SlotView(null, false, null, script.body);
        rootSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
        fill(rootSlot, script.body);
        workspace.add(rootSlot);
        JLabel end = new JLabel("  " + T("結束(程式會自動加上 break;)", "end (break; is added automatically)"));
        end.setForeground(new Color(0x88, 0x88, 0x88));
        end.setAlignmentX(Component.LEFT_ALIGNMENT);
        workspace.add(end);
        workspace.add(javax.swing.Box.createVerticalGlue());
        workspace.revalidate();
        workspace.repaint();
        updatePreview();
        updateUndoButtons();
    }

    static JComponent paletteChip(final Tpl tpl) {
        final Color col = colorOf(tpl.type.equals("IF_ELSE") ? "IF" : tpl.type);
        JLabel l = new JLabel(tpl.label) {
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(col);
                g.fillRoundRect(0, 1, getWidth(), getHeight() - 2, 12, 12);
                g.dispose();
                super.paintComponent(g0);
            }
        };
        l.setForeground(Color.WHITE);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        l.setToolTipText(tpl.tip + "(拖進右邊,或雙擊加到最後面)");
        l.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragTpl = tpl; dragNode = null; beginPress(e); }
            public void mouseDragged(MouseEvent e) { if (dragTpl == tpl) dragMoved(e, tpl.label, col); }
            public void mouseReleased(MouseEvent e) { if (dragTpl == tpl) endDrag(e); }
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    snap();
                    script.body.add(tpl.make());
                    refresh();
                }
            }
        };
        l.addMouseListener(ma);
        l.addMouseMotionListener(ma);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    // ---------------- 開啟視窗 ----------------
    /** caseText:目前這一段的文字。回傳 null 代表成功開啟,否則是不能開的原因 */
    public static String open(String caseText, Applier ap) {
        return open(caseText, null, ap);
    }

    /** label:「動作程式」分頁下拉選單的名稱(用來分辨左 / 右搖桿的方向 case) */
    public static String open(String caseText, String label, Applier ap) {
        Blocks.Script s0 = Blocks.parse(caseText);
        if (s0 == null) return "這一段不是以 case 開頭的動作,沒辦法用方塊編輯。";
        // 保險:讀成方塊再產生回來,程式本身必須和原本一樣,才讓你編輯(不然套用後會改到程式)
        String back = Fmt.format(Blocks.generate(s0, 0));
        if (!Fmt.skeleton(caseText).equals(Fmt.skeleton(back))) {
            return "這一段有方塊沒辦法完整表示的寫法,為了不弄壞程式,不開放方塊編輯。\n(請直接用文字編輯,或把這段的內容告訴我,我再讓方塊支援它。)";
        }
        script = s0;
        caseHold = Blocks.holdCond(s0.header, label);
        english = !"zh".equals(Settings.raw("blockLang"));
        applier = ap;
        // 原本的縮排當基準
        int ind = 0;
        for (String l : caseText.replace("\r", "").split("\n", -1)) {
            if (l.trim().isEmpty()) continue;
            String t = l.replace("\t", "  ");
            while (ind < t.length() && t.charAt(ind) == ' ') ind++;
            break;
        }
        StringBuilder bi = new StringBuilder();
        for (int i = 0; i < ind; i++) bi.append(' ');
        baseIndent = bi.toString();
        undoStack.clear();
        redoStack.clear();
        try { frameMax = FrameCheck.readFrameMax(Settings.sketch()); } catch (Throwable t) { frameMax = 0; }

        java.awt.Frame owner = Background.mainFrame();
        dlg = new JDialog(owner, "方塊編輯器", true);
        dlg.setSize(1180, 740);
        dlg.setLocationRelativeTo(owner);

        // 左:方塊選單
        JPanel pal = new JPanel();
        pal.setLayout(new BoxLayout(pal, BoxLayout.Y_AXIS));
        pal.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (Object[] sec : paletteSections()) {
            JLabel h = new JLabel((String) sec[0]);
            h.setFont(h.getFont().deriveFont(Font.BOLD, 12f));
            h.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 2));
            h.setAlignmentX(Component.LEFT_ALIGNMENT);
            pal.add(h);
            for (Tpl tpl : (Tpl[]) sec[1]) {
                JComponent chip = paletteChip(tpl);
                pal.add(chip);
                pal.add(javax.swing.Box.createVerticalStrut(5));
            }
        }
        palette = new JScrollPane(pal);
        palette.setPreferredSize(new Dimension(250, 100));
        palette.setBorder(BorderFactory.createTitledBorder("方塊(拖到右邊)"));

        // 中:工作區
        workspace = new JPanel();
        workspace.setLayout(new BoxLayout(workspace, BoxLayout.Y_AXIS));
        workspace.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        wsScroll = new JScrollPane(workspace);
        wsScroll.getVerticalScrollBar().setUnitIncrement(16);
        wsScroll.setBorder(BorderFactory.createTitledBorder("動作(從上到下依序執行)"));

        // 下:程式碼預覽
        preview = new JTextArea();
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane ps = new JScrollPane(preview);
        ps.setBorder(BorderFactory.createTitledBorder("產生的程式碼(自動更新,按「套用」會放進編輯區)"));
        ps.setPreferredSize(new Dimension(100, 190));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, wsScroll, ps);
        split.setResizeWeight(0.7);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        undoBtn = new JButton("復原");
        redoBtn = new JButton("重做");
        JButton ok = new JButton("套用到這一段");
        JButton cancel = new JButton("取消");
        msg = new JLabel(" ");
        msg.setForeground(new Color(0x00, 0xB8, 0xD4));
        btns.add(msg);
        final JButton langBtn = new JButton(english ? "English → 中文" : "中文 → English");
        langBtn.setToolTipText("切換方塊上的用語:英文(AND / OR / NOT…)或中文");
        langBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                english = !english;
                Settings.p.setProperty("blockLang", english ? "en" : "zh");
                try { Settings.save(); } catch (Exception ex) { ex.printStackTrace(); }
                langBtn.setText(english ? "English → 中文" : "中文 → English");
                refresh();
            }
        });
        btns.add(langBtn);
        btns.add(undoBtn);
        btns.add(redoBtn);
        btns.add(ok);
        btns.add(cancel);
        undoBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (undoStack.isEmpty()) return;
                redoStack.add(copyScript(script));
                script = undoStack.remove(undoStack.size() - 1);
                refresh();
            }
        });
        redoBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (redoStack.isEmpty()) return;
                undoStack.add(copyScript(script));
                script = redoStack.remove(redoStack.size() - 1);
                refresh();
            }
        });
        ok.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                autoFixLoops();
                String code = currentCode();
                dlg.dispose();
                if (applier != null) applier.apply(code);
            }
        });
        cancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { dlg.dispose(); }
        });

        JPanel main = new JPanel(new BorderLayout());
        main.add(palette, BorderLayout.WEST);
        main.add(split, BorderLayout.CENTER);
        main.add(btns, BorderLayout.SOUTH);
        dlg.getContentPane().add(main);

        layer = new DragLayer();
        dlg.setGlassPane(layer);
        layer.setVisible(false);

        refresh();
        dlg.setVisible(true);
        return null;
    }
}
