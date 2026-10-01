import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Window;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/**
 * 外觀:用 FlatLaf(現代扁平風)取代老式的 Metal。
 * 名稱:light(淺色,預設)、dark(深色)、classic(原本的樣子)。
 * 找不到 FlatLaf 時(MotorAdjPlus_lib 資料夾被刪掉)會維持原本的外觀,不影響功能。
 */
public class Theme {

    static final String FONT = "Microsoft JhengHei UI";
    static final Color GREEN = new Color(0x2E, 0x9E, 0x5B);
    static final Color RED = new Color(0xD6, 0x45, 0x45);

    static String current = "classic";

    static String name() {
        String t = Settings.raw("theme");
        return t.isEmpty() ? "light" : t;
    }

    static void setFonts(int size) {
        FontUIResource f = new FontUIResource(new Font(FONT, Font.PLAIN, size));
        UIDefaults d = UIManager.getDefaults();
        Enumeration<Object> keys = d.keys();
        while (keys.hasMoreElements()) {
            Object k = keys.nextElement();
            if (k instanceof String && ((String) k).endsWith(".font")) {
                UIManager.put(k, f);
            }
        }
        UIManager.put("defaultFont", f);
    }

    /** 設定外觀並更新所有視窗。必須在畫面執行緒(EDT)呼叫 */
    static void apply(String name) {
        try {
            if ("classic".equals(name)) {
                UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
            } else {
                Map<String, String> extra = new HashMap<String, String>();
                extra.put("@accentColor", "#2F6FED");
                com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(extra);
                UIManager.put("Button.arc", 10);
                UIManager.put("Component.arc", 10);
                UIManager.put("TextComponent.arc", 8);
                UIManager.put("ScrollBar.thumbArc", 999);
                // 捲軸兩端的箭頭:實心三角形 + 醒目的顏色(預設又細又淡,不容易看到)
                boolean darkMode = "dark".equals(name);
                Color arrow = darkMode ? new Color(0xA9, 0xCD, 0xF5) : new Color(0x1F, 0x4E, 0x79);
                UIManager.put("ScrollBar.arrowType", "triangle");
                UIManager.put("ScrollBar.buttonArrowColor", arrow);
                UIManager.put("ScrollBar.buttonHoverArrowColor", new Color(0x2F, 0x6F, 0xED));
                UIManager.put("ScrollBar.buttonPressedArrowColor", new Color(0x1B, 0x4F, 0xC4));
                UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
                UIManager.put("TabbedPane.showTabSeparators", Boolean.TRUE);
                UIManager.put("TabbedPane.tabHeight", 32);
                if ("dark".equals(name)) {
                    com.formdev.flatlaf.FlatDarkLaf.setup();
                } else {
                    com.formdev.flatlaf.FlatLightLaf.setup();
                }
                setFonts(13);
            }
            current = name;
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
                fixColors(w);
                w.repaint();
            }
            Background.apply();   // 遮罩的顏色會跟著外觀改變
            Steps.repaintAll();   // 馬達的 -/+ 按鈕重新上色
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static final Color ACCENT = new Color(0x3A, 0x7C, 0xC4);

    /** 馬達捲軸:未啟用(原本寫死的淺灰色)依外觀換成合適的灰色 */
    static Color trackOff() {
        return "dark".equals(current) ? new Color(0x4B, 0x50, 0x58) : new Color(0xDD, 0xE2, 0xE9);
    }

    /** 捲軸被程式設成「淺灰 / 藍色」時,換成符合外觀的顏色(程式每次啟用/關閉馬達都會重設,所以要監聽) */
    static void tweakScrollBar(final javax.swing.JScrollBar sb) {
        if (sb.getClientProperty("plusTheme") == null) {
            sb.putClientProperty("plusTheme", Boolean.TRUE);
            sb.addPropertyChangeListener("background", new java.beans.PropertyChangeListener() {
                public void propertyChange(java.beans.PropertyChangeEvent e) {
                    recolor(sb);
                }
            });
        }
        recolor(sb);
    }

    /** 記住捲軸是「未啟用(off)」還是「啟用(on)」,這樣切換外觀時也能重新上色 */
    static void recolor(javax.swing.JScrollBar sb) {
        Color bg = sb.getBackground();
        if (Color.LIGHT_GRAY.equals(bg)) {
            sb.putClientProperty("plusState", "off");
        } else if (Color.BLUE.equals(bg)) {
            sb.putClientProperty("plusState", "on");
        }
        Object st = sb.getClientProperty("plusState");
        if (st == null) {
            return;
        }
        boolean off = "off".equals(st);
        Color want;
        if ("classic".equals(current)) {
            want = off ? Color.LIGHT_GRAY : Color.BLUE;
        } else {
            want = off ? trackOff() : ACCENT;
        }
        if (!want.equals(bg)) {
            sb.setBackground(want);
        }
    }

    /** MotorAdj 把某些元件寫死成黃色、洋紅色、淺灰、藍色,改成符合外觀的顏色 */
    static void fixColors(Component c) {
        if (c instanceof javax.swing.JScrollBar) {
            tweakScrollBar((javax.swing.JScrollBar) c);
        }
        if (c instanceof JButton) {
            JButton b = (JButton) c;
            Color bg = b.getBackground();
            if ("classic".equals(current)) {
                // 還原成程式原本寫死的黃色 / 洋紅色
                Object orig = b.getClientProperty("plusOrigBg");
                if (orig instanceof Color) {
                    b.setBackground((Color) orig);
                    b.setForeground(Color.BLACK);
                }
            } else if (Color.YELLOW.equals(bg)) {
                b.putClientProperty("plusOrigBg", Color.YELLOW);
                style(b, GREEN);
            } else if (Color.MAGENTA.equals(bg)) {
                b.putClientProperty("plusOrigBg", Color.MAGENTA);
                style(b, RED);
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                fixColors(k);
            }
        }
    }

    static void style(AbstractButton b, Color bg) {
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
    }
}
