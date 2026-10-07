import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.File;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * 「馬達參數」分頁右下角空白處的機器人 3D 模擬。
 * 跟「動作程式」分頁的模擬同一套模型和姿勢來源(SimView.currentPose):沒在播放時跟著畫面上的滑桿,播放時跟著動作。
 * 要在 Center.install 之前加進分頁(用原始座標,之後 Center 會跟著視窗等比例放大)。
 */
public class MotorSim {
    static Viewport3D vp;
    static Object lastRig = null;
    static int lastParts = -1;
    static double[] lastPose = null;
    static boolean fitted = false;
    static long lastTry = 0;

    static void install(Container panel) {
        int dw = panel.getWidth(), dh = panel.getHeight();
        if (dw <= 0 || dh <= 0) return;
        Rectangle r = biggestFree(panel, dw, dh, dw * 2 / 3, dh * 3 / 10);
        if (r == null || r.width < 150 || r.height < 120) {
            System.err.println("MotorSim:「馬達參數」分頁右下角找不到夠大的空地,沒有放 3D 模擬");
            return;
        }
        vp = new Viewport3D();
        vp.yaw = Math.PI - 0.35;
        vp.pitch = 0.12;
        vp.setBorder(BorderFactory.createTitledBorder("機器人模擬(跟著滑桿 / 動作)"));
        vp.setBounds(r.x + 4, r.y + 2, r.width - 8, r.height - 4);
        panel.add(vp);
        Timer t = new Timer(66, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { tick(); }
        });
        t.start();
    }

    /** 在固定座標的面板裡,找 x >= minX、y >= minY 的最大空矩形(4 像素的格子) */
    static Rectangle biggestFree(Container panel, int dw, int dh, int minX, int minY) {
        final int g = 4;
        int cols = dw / g, rows = dh / g;
        boolean[][] occ = new boolean[rows][cols];
        for (Component c : panel.getComponents()) {
            if (!c.isVisible()) continue;
            Rectangle b = c.getBounds();
            if (b.width > dw - 40 && b.height > dh - 60) continue;          // 整頁大小的背景容器
            for (int y = Math.max(0, (b.y - 2) / g); y <= Math.min(rows - 1, (b.y + b.height + 2) / g); y++)
                for (int x = Math.max(0, (b.x - 2) / g); x <= Math.min(cols - 1, (b.x + b.width + 2) / g); x++) occ[y][x] = true;
        }
        int x0 = minX / g, y0 = minY / g;
        int[] h = new int[cols];
        Rectangle best = null;
        long bestA = 0;
        for (int y = y0; y < rows; y++) {
            for (int x = x0; x < cols; x++) h[x] = occ[y][x] ? 0 : h[x] + 1;
            for (int x = x0; x < cols; x++) {                                // 直方圖裡找最大矩形(這裡 cols 很小,直接 O(n^2))
                int mn = Integer.MAX_VALUE;
                for (int x2 = x; x2 < cols; x2++) {
                    mn = Math.min(mn, h[x2]);
                    if (mn == 0) break;
                    long a = (long) mn * (x2 - x + 1);
                    if (a > bestA) {
                        bestA = a;
                        best = new Rectangle(x * g, (y - mn + 1) * g, (x2 - x + 1) * g, mn * g);
                    }
                }
            }
        }
        return best;
    }

    static void tick() {
        if (vp == null || !vp.isShowing()) return;
        if (!RobotTab.loaded) {
            long now = System.currentTimeMillis();
            if (!RobotTab.loading && now - lastTry > 5000) {
                lastTry = now;
                File d = RobotTab.defaultDir();
                if (d.isDirectory() && RobotTab.stlFiles(d).length > 0) RobotTab.loadModel(d);
            }
            return;
        }
        if (lastRig != RobotTab.rig || lastParts != RobotTab.vp.parts.size()) rebuild();
        double[] pos = SimView.currentPose();
        if (lastPose != null && Arrays.equals(lastPose, pos)) return;
        lastPose = pos.clone();
        vp.mats = RobotTab.rig.matrices(pos, 0);
        vp.repaint();
    }

    static void rebuild() {
        vp.parts.clear();
        for (Viewport3D.Part p : RobotTab.vp.parts) {
            Viewport3D.Part c = new Viewport3D.Part(p.name, p.mesh);
            c.owner = p.owner;
            vp.parts.add(c);
        }
        vp.up = RobotTab.vp.up;
        vp.colorByOwner = false;
        if (!fitted && vp.getWidth() > 0) {
            vp.fitToModel();
            vp.dist *= 0.8;
            fitted = true;
        }
        lastRig = RobotTab.rig;
        lastParts = RobotTab.vp.parts.size();
        lastPose = null;
    }
}
