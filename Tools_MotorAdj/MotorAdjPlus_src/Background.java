import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Frame;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;

/**
 * 自訂背景:圖片或純色。
 * 做法:在視窗最底層鋪一個背景元件,再把各分頁的面板改成透明,背景就會透出來。
 * 圖片上面會蓋一層半透明的遮罩(用目前外觀的底色),讓文字、按鈕不會被圖片干擾。
 * 設定(存在 MotorAdjPlus.properties):bgImage、bgColor(#RRGGBB)、bgDim(0~100,預設 75)
 */
public class Background {

    static final Integer LAYER = Integer.valueOf(-31000);   // 比內容層(-30000)更後面

    static class Pane extends JComponent {
        BufferedImage img;       // 原圖
        BufferedImage scaled;    // 縮放後(快取)
        int scaledW = -1, scaledH = -1;
        Color color;             // 純色背景(沒有圖片時;有圖片時是留邊的顏色)
        int dim = 75;            // 遮罩濃度 0~100
        String mode = "cover";   // cover = 蓋滿(裁切)、fit = 完整顯示(留邊)、stretch = 拉伸
        int zoom = 100;          // 縮放 100~300(%)
        int px = 50, py = 50;    // 位置 0~100(50 = 置中)

        /** 圖片畫在哪裡:{x, y, 寬, 高};stretch 時是整個視窗 */
        int[] imageRect(int w, int h) {
            int iw = img.getWidth(), ih = img.getHeight();
            if ("stretch".equals(mode)) {
                return new int[] {0, 0, w, h};
            }
            double s0 = "fit".equals(mode) ? Math.min((double) w / iw, (double) h / ih)
                                           : Math.max((double) w / iw, (double) h / ih);
            double s = s0 * Math.max(100, Math.min(300, zoom)) / 100.0;
            int dw = Math.max(1, (int) Math.round(iw * s)), dh = Math.max(1, (int) Math.round(ih * s));
            int x = dw <= w ? (int) Math.round((w - dw) * px / 100.0) : -(int) Math.round((dw - w) * px / 100.0);
            int y = dh <= h ? (int) Math.round((h - dh) * py / 100.0) : -(int) Math.round((dh - h) * py / 100.0);
            return new int[] {x, y, dw, dh};
        }

        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            Color base = UIManager.getColor("Panel.background");
            if (base == null) base = Color.LIGHT_GRAY;
            if (img != null) {
                g.setColor(color != null ? color : base);       // 留邊的部分
                g.fillRect(0, 0, w, h);
                int[] r = imageRect(w, h);
                if (scaled == null || scaledW != r[2] || scaledH != r[3]) {
                    scaled = scale(img, r[2], r[3]);
                    scaledW = r[2];
                    scaledH = r[3];
                }
                g.drawImage(scaled, r[0], r[1], null);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(100, dim)) / 100f));
                g.setColor(base);
                g.fillRect(0, 0, w, h);
            } else {
                g.setColor(color != null ? color : base);
                g.fillRect(0, 0, w, h);
            }
            g.dispose();
        }
    }

    static Pane pane;
    static String loadedImagePath = "";

    /** 把圖片縮放成 w x h(結果會快取,拖曳滑桿時才不會卡) */
    static BufferedImage scale(BufferedImage src, int w, int h) {
        if (w <= 0 || h <= 0 || (w == src.getWidth() && h == src.getHeight())) return src;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static int readInt(String key, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(Settings.raw(key))));
        } catch (Exception e) {
            return def;
        }
    }

    /** 把設定讀進 Pane */
    static void load(Pane p) {
        p.dim = readInt("bgDim", 75, 0, 100);
        String m = Settings.raw("bgMode");
        p.mode = (m.equals("fit") || m.equals("stretch")) ? m : "cover";
        p.zoom = readInt("bgZoom", 100, 100, 300);
        p.px = readInt("bgX", 50, 0, 100);
        p.py = readInt("bgY", 50, 0, 100);
        p.color = parseColor(Settings.raw("bgColor"));
    }

    static Color parseColor(String s) {
        try {
            s = s.trim();
            if (s.startsWith("#")) s = s.substring(1);
            return new Color(Integer.parseInt(s, 16));
        } catch (Exception e) {
            return null;
        }
    }

    static JFrame mainFrame() {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame && f.isVisible()) return (JFrame) f;
        }
        return null;
    }

    /** 依目前設定套用背景。必須在畫面執行緒(EDT)呼叫 */
    static void apply() {
        try {
            JFrame f = mainFrame();
            if (f == null) return;
            String imgPath = Settings.raw("bgImage");
            Color col = parseColor(Settings.raw("bgColor"));
            int dim = 75;
            try { dim = Integer.parseInt(Settings.raw("bgDim")); } catch (Exception e) { }

            BufferedImage img = null;
            if (!imgPath.isEmpty()) {
                File ff = new File(imgPath);
                if (ff.exists()) {
                    img = ImageIO.read(ff);
                }
            }
            boolean enabled = img != null || col != null;

            JLayeredPane lp = f.getLayeredPane();
            if (enabled) {
                if (pane == null) {
                    pane = new Pane();
                    lp.add(pane, LAYER);
                    lp.addComponentListener(new java.awt.event.ComponentAdapter() {
                        public void componentResized(java.awt.event.ComponentEvent e) {
                            if (pane != null) pane.setBounds(0, 0, e.getComponent().getWidth(), e.getComponent().getHeight());
                        }
                    });
                }
                pane.img = img;
                pane.scaled = null;
                load(pane);
                pane.setBounds(0, 0, lp.getWidth(), lp.getHeight());
            } else if (pane != null) {
                lp.remove(pane);
                pane = null;
            }
            makeTransparent(f, enabled);
            lp.repaint();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** 面板改成透明(背景才看得到);關閉時還原原本的不透明設定 */
    static void makeTransparent(Component c, boolean on) {
        if (c instanceof JPanel || c instanceof JTabbedPane) {
            JComponent jc = (JComponent) c;
            if (on) {
                if (jc.getClientProperty("plusOrigOpaque") == null) {
                    jc.putClientProperty("plusOrigOpaque", Boolean.valueOf(jc.isOpaque()));
                }
                jc.setOpaque(false);
            } else {
                Object o = jc.getClientProperty("plusOrigOpaque");
                if (o instanceof Boolean) {
                    jc.setOpaque(((Boolean) o).booleanValue());
                    jc.putClientProperty("plusOrigOpaque", null);
                }
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                makeTransparent(k, on);
            }
        }
    }
}
