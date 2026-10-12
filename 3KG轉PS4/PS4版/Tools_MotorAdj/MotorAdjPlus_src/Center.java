import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/**
 * 啟動時視窗最大化,內容置中、而且【依視窗大小等比例放大】(位置、大小、字體一起乘上同一個倍率,比例不變):
 *   MotorAdj 原本的三個分頁(設定、馬達參數、馬達偏移量)是固定座標排版,視窗變大不會自己縮放。
 *   這裡把每個分頁包進一個會置中的外框,視窗大小改變時,算出倍率(不小於 1,最多 3),
 *   把分頁裡每個元件的座標、尺寸、字體都乘上倍率。滑鼠點擊不受影響(沒有用繪圖縮放)。
 *   (「動作程式」「鏡像」分頁本來就會跟著視窗大小,不用處理。)
 * 設定:MotorAdjPlus.properties
 *   startMaximized=0   關掉「啟動時最大化」
 *   autoScale=0        關掉「等比例放大」(只置中)
 */
public class Center {

    static final int EXTRA_H = 24;           // 視窗夠大時多留一點高度,最下面那排按鈕才不會被切到
    static final float MAX_SCALE = 3f;

    static class Page {
        JPanel panel;                        // 原本的分頁內容(固定座標)
        JPanel wrap;                         // 置中的外框
        Dimension design;                    // 原本的大小
        float scale = 1f;
        Map<Component, Rectangle> bounds = new HashMap<Component, Rectangle>();   // 原本的座標(只有固定座標的容器裡的元件)
        Map<Component, Font> fonts = new HashMap<Component, Font>();              // 原本的字體
        List<Component> order = new ArrayList<Component>();
    }

    static final List<Page> pages = new ArrayList<Page>();

    static boolean scaleOn() {
        return !"0".equals(Settings.raw("autoScale"));
    }

    // ---------- 蒐集原本的座標與字體 ----------

    static void collect(Page pg, Container c) {
        boolean absolute = (c.getLayout() == null);
        for (Component k : c.getComponents()) {
            pg.order.add(k);
            pg.fonts.put(k, k.getFont());
            if (absolute) pg.bounds.put(k, k.getBounds());
            if (k instanceof JPanel) collect(pg, (Container) k);
        }
    }

    // ---------- 安裝 ----------

    /** 把固定座標的分頁包起來置中。必須在視窗最大化「之前」呼叫(要抓原本的大小) */
    static void install() {
        try {
            JFrame f = Background.mainFrame();
            if (f == null) return;
            JTabbedPane tabs = CodeTab.findTabs(f);
            if (tabs == null) return;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component c = tabs.getComponentAt(i);
                if (!(c instanceof JPanel)) continue;
                JPanel p = (JPanel) c;
                if (p.getLayout() != null) continue;          // 只處理固定座標(null 版面)的分頁
                Dimension d = p.getSize();
                if (d.width <= 0 || d.height <= 0) continue;

                final Page pg = new Page();
                pg.panel = p;
                pg.design = d;
                collect(pg, p);

                JPanel wrap = new JPanel(new GridBagLayout());
                pg.wrap = wrap;
                p.setPreferredSize(new Dimension(d.width, d.height + EXTRA_H));
                p.setMinimumSize(d);                          // 視窗是原本大小時,縮回原本的大小(不能縮成 0)
                tabs.setComponentAt(i, wrap);                 // 先換掉分頁內容,再把原本的面板放進外框
                wrap.add(p);                                  // GridBagLayout 預設置中、維持 preferredSize
                p.setVisible(true);                           // 原本被分頁元件設成隱藏,放進外框後要重新顯示
                wrap.addComponentListener(new java.awt.event.ComponentAdapter() {
                    public void componentResized(java.awt.event.ComponentEvent e) { fit(pg); }
                });
                pages.add(pg);
            }
            tabs.revalidate();
            tabs.repaint();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ---------- 等比例放大 ----------

    /** 依外框大小算倍率並套用 */
    static void fit(Page pg) {
        float s = 1f;
        if (scaleOn()) {
            int w = pg.wrap.getWidth(), h = pg.wrap.getHeight();
            if (w > 0 && h > 0) {
                s = Math.min((float) w / pg.design.width, (float) h / (pg.design.height + EXTRA_H));
                s = Math.max(1f, Math.min(MAX_SCALE, s));
                s = (float) Math.floor(s * 100f) / 100f;     // 無條件捨去:進位的話高度會比視窗多一點點,外框就退回最小尺寸,下面的按鈕被切到
            }
        }
        if (Math.abs(s - pg.scale) < 0.01f) return;
        apply(pg, s);
    }

    static void apply(Page pg, float s) {
        pg.scale = s;
        for (Component k : pg.order) {
            Rectangle r = pg.bounds.get(k);
            if (r != null) {
                k.setBounds(Math.round(r.x * s), Math.round(r.y * s), Math.round(r.width * s), Math.round(r.height * s));
            }
            Font of = pg.fonts.get(k);
            if (of != null) {
                k.setFont(s == 1f ? of : of.deriveFont(of.getSize2D() * s));
            }
        }
        pg.panel.setPreferredSize(new Dimension(Math.round(pg.design.width * s), Math.round((pg.design.height + EXTRA_H) * s)));
        pg.panel.setMinimumSize(new Dimension(Math.round(pg.design.width * s), Math.round(pg.design.height * s)));   // 最小尺寸也跟著放大,否則空間差一點點就會被退回原本大小而切掉
        pg.wrap.revalidate();
        pg.wrap.repaint();
    }

    // ---------- 外觀切換時,字體要先還原、切完再重新套用 ----------

    static void beforeThemeChange() {
        for (Page pg : pages) {
            for (Component k : pg.order) {
                Font of = pg.fonts.get(k);
                if (of != null) k.setFont(of);
            }
        }
    }

    static void afterThemeChange() {
        for (Page pg : pages) {
            for (Component k : pg.order) pg.fonts.put(k, k.getFont());   // 重新記下新外觀的字體
            float s = pg.scale;
            pg.scale = 1f;
            apply(pg, s);
        }
    }

    /** 啟動時最大化(視窗仍有標題列和關閉鈕) */
    static void maximizeAtStart() {
        try {
            if ("0".equals(Settings.raw("startMaximized"))) return;
            JFrame f = Background.mainFrame();
            if (f == null) return;
            f.setExtendedState(f.getExtendedState() | Frame.MAXIMIZED_BOTH);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
