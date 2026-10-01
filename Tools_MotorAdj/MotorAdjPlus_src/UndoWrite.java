import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFormattedTextField;

/**
 * 「復原寫入」:按「寫入」之前,先把目標幀目前存的內容記下來;
 * 不小心按到「寫入」時,按「復原寫入」就會把那一幀還原成寫入之前的樣子(可以連續復原多次)。
 * 只記在記憶體裡,關掉 MotorAdj 就清空。
 */
public class UndoWrite {

    static class Snap {
        int frame;
        int[][] rows;   // [馬達][啟用, 位置, 時間]
    }

    static final int MAX = 100;
    static final List<Snap> stack = new ArrayList<Snap>();       // 復原用
    static final List<Snap> redoStack = new ArrayList<Snap>();   // 重做用
    static JButton undoBtn, redoBtn;
    static Object outer;

    static void refreshButton() {
        if (undoBtn != null) {
            undoBtn.setEnabled(!stack.isEmpty());
            undoBtn.setToolTipText(stack.isEmpty() ? "還沒有可以復原的寫入"
                    : "復原:把第 " + stack.get(stack.size() - 1).frame + " 幀還原成寫入之前的內容(還可以復原 " + stack.size() + " 次)");
        }
        if (redoBtn != null) {
            redoBtn.setEnabled(!redoStack.isEmpty());
            redoBtn.setToolTipText(redoStack.isEmpty() ? "沒有可以重做的步驟"
                    : "重做:把第 " + redoStack.get(redoStack.size() - 1).frame + " 幀重新套用剛才復原的內容(還可以重做 " + redoStack.size() + " 次)");
        }
    }

    /** 目前這一幀的內容(複製一份) */
    static Snap current(int frame) throws Exception {
        Object ms = Plus.fld(outer, "MotorSet");
        int[][][] data = (int[][][]) Plus.fld(ms, "MotorPosData");
        Snap s = new Snap();
        s.frame = frame;
        s.rows = new int[data[frame].length][];
        for (int i = 0; i < data[frame].length; i++) {
            s.rows[i] = data[frame][i].clone();
        }
        return s;
    }

    /** 把某個紀錄的內容放回那一幀,並讓畫面切到那一幀 */
    static void restore(Snap s) throws Exception {
        Object ms = Plus.fld(outer, "MotorSet");
        int[][][] data = (int[][][]) Plus.fld(ms, "MotorPosData");
        for (int i = 0; i < s.rows.length; i++) {
            System.arraycopy(s.rows[i], 0, data[s.frame][i], 0, s.rows[i].length);
        }
        JFormattedTextField t = (JFormattedTextField) Plus.fld(outer, "FrameCntText");
        t.setText(String.valueOf(s.frame));
        java.lang.reflect.Method m = outer.getClass().getDeclaredMethod("position_upgradeFrame");
        m.setAccessible(true);
        m.invoke(outer);
    }

    static void push(List<Snap> list, Snap s) {
        list.add(s);
        if (list.size() > MAX) list.remove(0);
    }

    /** 在「寫入」按鈕執行原本的動作之前,先記下目標幀的內容 */
    static void snapshot() throws Exception {
        Object ms = Plus.fld(outer, "MotorSet");
        int[][][] data = (int[][][]) Plus.fld(ms, "MotorPosData");
        JFormattedTextField t = (JFormattedTextField) Plus.fld(outer, "FrameCntText");
        int frame;
        try {
            frame = Integer.parseInt(t.getText().trim());
        } catch (Exception e) {
            return;   // 幀號不是數字,原本的程式會自己提示
        }
        if (frame < 0 || frame >= data.length) return;
        push(stack, current(frame));
        redoStack.clear();          // 做了新的寫入,就不能再「重做」了(跟一般的復原一樣)
        refreshButton();
    }

    /** 復原:退回最近一次的寫入(同時記下退回前的內容,讓「下一步」可以回來) */
    static void undo() {
        try {
            if (stack.isEmpty()) return;
            Snap s = stack.remove(stack.size() - 1);
            push(redoStack, current(s.frame));
            restore(s);
            refreshButton();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** 重做:把剛才復原的內容重新套用 */
    static void redo() {
        try {
            if (redoStack.isEmpty()) return;
            Snap r = redoStack.remove(redoStack.size() - 1);
            push(stack, current(r.frame));
            restore(r);
            refreshButton();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** panel:「馬達參數」分頁的面板 */
    static void install(Container panel) throws Exception {
        outer = Plus.outer();
        final JButton write = Plus.findExactIn(panel, "寫入", "Write", "write");
        if (outer == null || write == null) return;

        // 把「寫入」的原本監聽器換成「先記錄、再執行原本動作」
        java.awt.event.MouseListener orig = null;
        for (java.awt.event.MouseListener l : write.getMouseListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) orig = l;
        }
        if (orig == null) return;
        final java.awt.event.MouseListener original = orig;
        write.removeMouseListener(orig);
        write.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (!write.isEnabled()) return;
                try {
                    snapshot();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                original.mouseReleased(e);
            }
        });

        undoBtn = new JButton("← 復原");
        undoBtn.setBounds(write.getX() + write.getWidth() + 8, write.getY(), 100, write.getHeight());
        undoBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { undo(); }
        });
        panel.add(undoBtn);

        redoBtn = new JButton("重做 →");
        redoBtn.setBounds(undoBtn.getX() + undoBtn.getWidth() + 6, write.getY(), 100, write.getHeight());
        redoBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { redo(); }
        });
        panel.add(redoBtn);
        refreshButton();
        panel.repaint();
    }
}
