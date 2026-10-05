import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 「設定」分頁重新排版:
 *   ・用不到的「感測器」那一組(編號、回傳值、開始 / 停止回傳)隱藏
 *   ・剩下的依用途分成三個區塊:連線、馬達位置範圍、外觀,每個區塊有標題外框
 *   ・右邊的三張卡片(機器人狀態、Micro 空間用量、環境檢查)往上移並放大
 * 原本的元件不刪除,只是改位置 / 隱藏,所以 MotorAdj 的功能完全照舊。
 */
public class SettingsLayout {

    static JLabel label(Container sp, String text) {
        for (Component c : sp.getComponents()) {
            if (c instanceof JLabel && text.equals(((JLabel) c).getText())) return (JLabel) c;
        }
        return null;
    }

    static AbstractButton button(Container sp, String text) {
        for (Component c : sp.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton) c).getText())) return (AbstractButton) c;
        }
        return null;
    }

    static void put(Component c, int x, int y, int w, int h) {
        if (c != null) c.setBounds(x, y, w, h);
    }

    static JPanel frame(String title, int x, int y, int w, int h) {
        JPanel p = new JPanel(null);
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setBounds(x, y, w, h);
        p.setOpaque(false);
        return p;
    }

    static void install(Container sp) {
        // ---------- 找出原本的元件 ----------
        JLabel lPort = label(sp, "串口編號");
        JLabel lType = label(sp, "機器型號");
        JLabel lMax = label(sp, "馬達位置最大值");
        JLabel lMin = label(sp, "馬達位置最小值");

        // 兩個下拉選單(串口、機型)與兩個數字欄位(最大值、最小值):用原本的位置判斷,
        // 感測器的欄位在右邊(x >= 600),用不到,直接隱藏
        JComboBox<?> cPort = null, cType = null;
        JFormattedTextField fMax = null, fMin = null;
        for (Component c : sp.getComponents()) {
            Rectangle b = c.getBounds();
            if (c instanceof JComboBox && b.x < 300 && c != Plus.themeBoxRef) {
                if (cPort == null || b.y < cPort.getY()) {
                    if (cPort != null) cType = cPort;
                    cPort = (JComboBox<?>) c;
                } else {
                    cType = (JComboBox<?>) c;
                }
            }
            if (c instanceof JFormattedTextField) {
                if (b.x >= 500) {
                    c.setVisible(false);                 // 感測器欄位
                } else if (b.y < 270) {
                    fMax = (JFormattedTextField) c;
                } else {
                    fMin = (JFormattedTextField) c;
                }
            }
        }
        // 順序保險:上面的是串口、下面的是機型
        if (cPort != null && cType != null && cPort.getY() > cType.getY()) {
            JComboBox<?> t = cPort;
            cPort = cType;
            cType = t;
        }

        AbstractButton bConnect = button(sp, "串口連接");
        AbstractButton bDisconnect = button(sp, "串口斷開");
        AbstractButton bLoad = button(sp, "載入馬達");
        AbstractButton bSave = button(sp, "儲存馬達");

        // ---------- 隱藏用不到的感測器那一組 ----------
        JLabel s1 = label(sp, "感測器編號");
        JLabel s2 = label(sp, "感測器回傳值");
        if (s1 != null) s1.setVisible(false);
        if (s2 != null) s2.setVisible(false);
        AbstractButton b1 = button(sp, "開始回傳");
        AbstractButton b2 = button(sp, "停止回傳");
        if (b1 != null) b1.setVisible(false);
        if (b2 != null) b2.setVisible(false);

        // ---------- 左邊:三個區塊 ----------
        final int X = 40, W = 380, PAD = 24;

        // 連線
        sp.add(frame("連線", X, 30, W, 178));
        put(lPort, X + PAD, 66, 90, 28);
        put(cPort, X + PAD + 100, 66, 210, 28);
        put(lType, X + PAD, 108, 90, 28);
        put(cType, X + PAD + 100, 108, 210, 28);
        put(bConnect, X + PAD, 154, 150, 32);
        put(bDisconnect, X + PAD + 166, 154, 150, 32);

        // 馬達位置範圍
        sp.add(frame("馬達位置範圍", X, 224, W, 178));
        put(lMax, X + PAD, 260, 150, 28);
        put(fMax, X + PAD + 210, 260, 100, 28);
        put(lMin, X + PAD, 302, 150, 28);
        put(fMin, X + PAD + 210, 302, 100, 28);
        put(bLoad, X + PAD, 348, 150, 32);
        put(bSave, X + PAD + 166, 348, 150, 32);

        // 外觀
        sp.add(frame("外觀", X, 418, W, 128));
        put(Plus.themeLabelRef, X + PAD, 452, 90, 28);
        put(Plus.themeBoxRef, X + PAD + 100, 452, 210, 28);
        put(Plus.bgButton, X + PAD, 496, 316, 32);

        // 教師功能(要老師簽發、綁定這台電腦的金鑰才能解鎖)
        try {
            License.installSettings(sp);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // ---------- 右邊:三張卡片 ----------
        if (StatusCards.card1 != null) StatusCards.card1.setBounds(450, 30, 560, 150);
        if (StatusCards.card2 != null) StatusCards.card2.setBounds(450, 196, 275, 350);
        if (StatusCards.card3 != null) StatusCards.card3.setBounds(735, 196, 275, 350);

        sp.revalidate();
        sp.repaint();
    }
}
