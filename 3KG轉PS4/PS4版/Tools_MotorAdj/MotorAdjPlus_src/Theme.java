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

    /**
     * 介面字體。預設「Noto Sans TC」(比微軟正黑體粗一點);電腦沒裝的話退回「微軟正黑體 UI」。
     * MotorAdjPlus.properties 加 fontName=字體名稱 可以換(例如 Microsoft JhengHei UI)
     */
    static String font() {
        String s = Settings.raw("fontName");
        if (!s.isEmpty()) return s;
        if (installed("Noto Sans TC")) return "Noto Sans TC";
        return "Microsoft JhengHei UI";
    }

    static java.util.Set<String> fontNames;

    static boolean installed(String name) {
        if (fontNames == null) {
            fontNames = new java.util.HashSet<String>(java.util.Arrays.asList(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        }
        return fontNames.contains(name);
    }

    // 配色:依外觀切換(淺色、深色用預設;科技風用霓虹色)
    static Color GREEN = new Color(0x2E, 0x9E, 0x5B);
    static Color RED = new Color(0xD6, 0x45, 0x45);
    static Color ACCENT = new Color(0x3A, 0x7C, 0xC4);

    static String current = "classic";

    static boolean tech() {
        return "tech".equals(current);
    }

    /** 依外觀設定配色(馬達按鈕、捲軸、狀態卡片都會用到) */
    static void setPalette(String name) {
        if ("tech".equals(name)) {
            GREEN = new Color(0x00, 0xE6, 0x76);
            RED = new Color(0xFF, 0x3D, 0x5A);
            ACCENT = new Color(0x00, 0xB8, 0xD4);
            StatusCards.OK = new Color(0x00, 0xE6, 0x76);
            StatusCards.WARN = new Color(0xFF, 0xB3, 0x00);
            StatusCards.BAD = new Color(0xFF, 0x4D, 0x6D);
        } else {
            GREEN = new Color(0x2E, 0x9E, 0x5B);
            RED = new Color(0xD6, 0x45, 0x45);
            ACCENT = new Color(0x3A, 0x7C, 0xC4);
            StatusCards.OK = new Color(0x2E, 0x9E, 0x5B);
            StatusCards.WARN = new Color(0xD9, 0x82, 0x00);
            StatusCards.BAD = new Color(0xD6, 0x45, 0x45);
        }
    }

    static String name() {
        String t = Settings.raw("theme");
        return t.isEmpty() ? "light" : t;
    }

    static void setFonts(int size) {
        FontUIResource f = new FontUIResource(new Font(font(), Font.PLAIN, size));
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
                setPalette(name);
                Map<String, String> extra = new HashMap<String, String>();
                if ("tech".equals(name)) {
                    // 科技風:深藍黑底、青色霓虹重點色、細邊框
                    extra.put("@accentColor", "#00E5FF");
                    extra.put("@background", "#0B1220");
                    extra.put("@foreground", "#CFEFFF");
                    extra.put("@componentBackground", "#0F1B2E");
                    extra.put("@buttonBackground", "#12233B");
                    extra.put("@selectionBackground", "#00B8D4");
                    extra.put("@selectionForeground", "#001018");
                    extra.put("Component.borderColor", "#1E5A78");
                    extra.put("Component.focusedBorderColor", "#00E5FF");
                    extra.put("Button.borderColor", "#1E7A96");
                    extra.put("Button.hoverBorderColor", "#00E5FF");
                    extra.put("TextField.borderColor", "#1E5A78");
                    extra.put("TabbedPane.underlineColor", "#00E5FF");
                    extra.put("TabbedPane.background", "#0B1220");
                    extra.put("TabbedPane.selectedBackground", "#0F1B2E");
                    extra.put("TabbedPane.hoverColor", "#12233B");
                    extra.put("ProgressBar.background", "#12233B");
                    extra.put("ProgressBar.foreground", "#00E5FF");
                    extra.put("ScrollBar.thumb", "#1E7A96");
                    extra.put("ScrollBar.track", "#0F1B2E");
                    extra.put("TitledBorder.titleColor", "#00E5FF");
                    extra.put("Separator.foreground", "#1E5A78");
                    extra.put("ToolTip.background", "#0F1B2E");
                    extra.put("ToolTip.foreground", "#CFEFFF");
                } else {
                    extra.put("@accentColor", "#2F6FED");
                }
                com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(extra);
                UIManager.put("Button.arc", 10);
                UIManager.put("Component.arc", 10);
                UIManager.put("TextComponent.arc", 8);
                UIManager.put("ScrollBar.thumbArc", 999);
                // 捲軸兩端的箭頭:實心三角形 + 醒目的顏色(預設又細又淡,不容易看到)
                boolean darkMode = "dark".equals(name) || "tech".equals(name);
                Color arrow = darkMode ? new Color(0xA9, 0xCD, 0xF5) : new Color(0x1F, 0x4E, 0x79);
                UIManager.put("ScrollBar.arrowType", "triangle");
                UIManager.put("ScrollBar.buttonArrowColor", arrow);
                UIManager.put("ScrollBar.buttonHoverArrowColor", new Color(0x2F, 0x6F, 0xED));
                UIManager.put("ScrollBar.buttonPressedArrowColor", new Color(0x1B, 0x4F, 0xC4));
                UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
                UIManager.put("TabbedPane.showTabSeparators", Boolean.TRUE);
                UIManager.put("TabbedPane.tabHeight", 32);
                if ("dark".equals(name) || "tech".equals(name)) {
                    com.formdev.flatlaf.FlatDarkLaf.setup();
                } else {
                    com.formdev.flatlaf.FlatLightLaf.setup();
                }
                setFonts(13);
                if ("tech".equals(name)) {
                    // 數字欄位用等寬字型,像儀表板的讀數
                    FontUIResource mono = new FontUIResource(new Font("Consolas", Font.BOLD, 14));
                    UIManager.put("FormattedTextField.font", mono);
                    UIManager.put("TitledBorder.border", javax.swing.BorderFactory.createLineBorder(new Color(0x1E, 0x7A, 0x96)));
                }
            }
            current = name;
            Center.beforeThemeChange();   // 放大過的字體先還原,不然換外觀後字體會卡在舊的
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
                fixColors(w);
                w.repaint();
            }
            Center.afterThemeChange();    // 記下新外觀的字體,再依目前視窗大小重新放大
            Background.apply();   // 遮罩的顏色會跟著外觀改變
            Steps.repaintAll();   // 馬達的 -/+ 按鈕重新上色
            Plus.syncThemeBox(name);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }


    /** 馬達捲軸:未啟用(原本寫死的淺灰色)依外觀換成合適的灰色 */
    static Color trackOff() {
        if ("tech".equals(current)) return new Color(0x16, 0x2A, 0x44);
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
            } else if (b.getClientProperty("plusOrigBg") instanceof Color) {
                // 已經被我們上過色:依目前外觀的綠 / 紅重新上色
                Color orig = (Color) b.getClientProperty("plusOrigBg");
                style(b, Color.YELLOW.equals(orig) ? GREEN : RED);
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
