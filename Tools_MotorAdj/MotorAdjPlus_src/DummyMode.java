import java.awt.Color;
import java.awt.Container;
import java.lang.reflect.Field;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;

/**
 * 人偶模式的畫面修正:
 *   MotorAdj 原本一按「人偶模式」,會把所有馬達的勾選框取消、捲軸變灰,看起來像「全部放電」,
 *   但實際上並沒有送任何指令給機器人(馬達還有力)。這裡改成:
 *   ・進入人偶模式時,維持原本的勾選與捲軸顏色(有力的馬達仍是勾選、藍色)
 *   ・被選為人偶(放鬆中、可以用手擺)的那顆馬達,勾選框標成橘色,一眼看出哪顆現在是軟的
 * 其他行為(選取、讀角度、離開時送出姿勢)完全不動。
 */
public class DummyMode {

    static final int N = 26;
    static final Color MARK = new Color(0xF5, 0xA6, 0x23);

    static Object outer;
    static JCheckBox[] cb = new JCheckBox[N];
    static JScrollBar[] sb = new JScrollBar[N];
    static JRadioButton[] rb = new JRadioButton[N];
    static boolean[] marked = new boolean[N];

    static Object get(String name) {
        try {
            Field f = outer.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(outer);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isDummy() {
        try {
            Object ms = Plus.fld(outer, "MotorSet");
            return Boolean.TRUE.equals(Plus.fld(ms, "isDummyMode"));
        } catch (Throwable t) {
            return false;
        }
    }

    static String nn(int i) {
        return String.format("%02d", i + 1);
    }

    /** 被標成橘色的勾選框,依目前選到的人偶更新 */
    static void updateMarks() {
        boolean dummy = isDummy();
        for (int i = 0; i < N; i++) {
            if (cb[i] == null) continue;
            boolean want = dummy && rb[i] != null && rb[i].isSelected();
            if (want && !marked[i]) {
                cb[i].setOpaque(true);
                cb[i].setBackground(MARK);
                cb[i].setToolTipText("人偶:這顆放鬆中,可以用手擺");
                marked[i] = true;
                cb[i].repaint();
            } else if (!want && marked[i]) {
                cb[i].setOpaque(false);
                cb[i].setBackground(null);
                cb[i].setToolTipText(null);
                marked[i] = false;
                cb[i].repaint();
            }
        }
    }

    static void install(Container panel) throws Exception {
        outer = Plus.outer();
        final JButton dummy = Plus.findExactIn(panel, "人偶模式", "Dummy", "dummy");
        if (outer == null || dummy == null) return;
        for (int i = 0; i < N; i++) {
            cb[i] = (JCheckBox) get("checkBoxCh" + nn(i));
            sb[i] = (JScrollBar) get("scrollBarPosCh" + nn(i));
            rb[i] = (JRadioButton) get("radioButtonCh" + nn(i));
        }

        java.awt.event.MouseListener orig = null;
        for (java.awt.event.MouseListener l : dummy.getMouseListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) orig = l;
        }
        if (orig == null) return;
        final java.awt.event.MouseListener original = orig;
        dummy.removeMouseListener(orig);
        dummy.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseReleased(java.awt.event.MouseEvent e) {
                boolean wasDummy = isDummy();
                boolean[] selected = new boolean[N];
                boolean[] enabled = new boolean[N];
                if (!wasDummy) {
                    for (int i = 0; i < N; i++) {
                        selected[i] = cb[i] != null && cb[i].isSelected();
                        enabled[i] = sb[i] != null && sb[i].isEnabled();
                    }
                }
                original.mouseReleased(e);       // 原本的動作(進入或離開人偶模式)
                if (!wasDummy && isDummy()) {
                    // 剛進入:把原本被取消的勾選、被弄灰的捲軸還原(機器人本來就沒有被關閉)
                    for (int i = 0; i < N; i++) {
                        if (cb[i] != null) cb[i].setSelected(selected[i]);
                        if (sb[i] != null) sb[i].setBackground(enabled[i] ? Color.BLUE : Color.LIGHT_GRAY);
                    }
                }
                updateMarks();
            }
        });

        // 選到哪一顆人偶,定時更新橘色標示(選取是用滑鼠點圓點,不一定會經過上面的監聽器)
        new javax.swing.Timer(250, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { updateMarks(); }
        }).start();
    }
}
