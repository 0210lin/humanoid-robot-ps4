import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.util.Arrays;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * 「動作程式」分頁右上角的機器人 3D 模擬(取代原本的 26 根長條圖)。
 * 用「機器人 3D」分頁載入的模型和關節結構(零件共用、各自獨立的相機與選取),姿勢跟著動作模擬的播放(SequenceTab.poseNow)。
 * 還沒有機器人模型(沒有 robot_model / robot_stl 資料夾)時,改回顯示長條圖。
 */
public class SimView {
    static Viewport3D vp;
    static JPanel card;
    static CardLayout cl;
    static boolean have = false;
    static boolean fitted = false;
    static JLabel msg;
    static double[] lastPose = null;
    static long lastTry = 0;

    /** barsComp:原本的長條圖元件(找不到模型時顯示)。回傳要放進畫面的容器 */
    static JPanel install(Component barsComp) {
        vp = new Viewport3D();
        vp.setMinimumSize(new Dimension(200, 160));
        vp.yaw = Math.PI - 0.35;
        vp.pitch = 0.12;
        cl = new CardLayout();
        card = new JPanel(cl);
        JPanel barsHost = new JPanel(new java.awt.BorderLayout());
        barsHost.add(barsComp, java.awt.BorderLayout.CENTER);
        msg = new JLabel("", SwingConstants.CENTER);
        barsHost.add(msg, java.awt.BorderLayout.NORTH);
        card.add(barsHost, "bars");
        card.add(vp, "3d");
        cl.show(card, "bars");
        return card;
    }

    /** 依「機器人 3D」分頁目前載入的零件重建(零件的網格共用,擁有者和關節結構同步) */
    static void rebuild() {
        if (vp == null) return;
        if (!RobotTab.loaded || RobotTab.vp == null || RobotTab.vp.parts.isEmpty()) {
            have = false;
            cl.show(card, "bars");
            return;
        }
        vp.parts.clear();
        for (Viewport3D.Part p : RobotTab.vp.parts) {
            Viewport3D.Part c = new Viewport3D.Part(p.name, p.mesh);
            c.owner = p.owner;
            vp.parts.add(c);
        }
        vp.up = RobotTab.vp.up;
        vp.colorByOwner = false;
        vp.showFloor = true;
        if (!fitted) {
            vp.fitToModel();
            vp.dist *= 0.7;
            fitted = true;
        }
        lastPose = null;
        have = true;
        cl.show(card, "3d");
    }

    static double[] lastLive = null;
    static Object outerCache = null;

    /** 「馬達參數」分頁目前畫面上每顆馬達的位置(index 1..26;位置 0 = 還沒讀入,當作 1500) */
    static double[] livePose() {
        double[] p = new double[Rig.N + 1];
        Arrays.fill(p, 1500);
        try {
            if (outerCache == null) outerCache = Plus.outer();
            Object ms = Plus.fld(outerCache, "MotorSet");
            int[][] tmp = (int[][]) Plus.fld(ms, "MotorPosDataTmp");
            boolean any = RobotTab.showDisabled == null || RobotTab.showDisabled.isSelected();
            for (int m = 1; m <= Rig.N && m - 1 < tmp.length; m++) if ((tmp[m - 1][0] != 0 || any) && tmp[m - 1][1] >= 100) p[m] = tmp[m - 1][1];
        } catch (Throwable t) {
            outerCache = null;
        }
        return p;
    }

    static double[] simPose() {
        double[] now = SequenceTab.poseNow();
        double[] pos = new double[Rig.N + 1];
        Arrays.fill(pos, 1500);
        for (int m = 1; m <= Rig.N && m - 1 < now.length; m++) pos[m] = now[m - 1];
        return pos;
    }

    /**
     * 要畫的姿勢:播放中 = 動作模擬的即時姿勢;沒在播放 = 跟著「馬達參數」目前的姿勢(它有變就跟著變;沒變就停在上次播完的姿勢)。
     * 跟著「馬達參數」時,也把模擬的起點設成那個姿勢,下次按播放就從那裡開始。
     */
    static double[] currentPose() {
        if (!SequenceTab.running) {
            double[] live = livePose();
            if (lastLive == null || !Arrays.equals(live, lastLive)) {
                lastLive = live.clone();
                synchronized (SequenceTab.lock) {
                    for (int m = 1; m <= Rig.N; m++) { SequenceTab.from[m - 1] = live[m]; SequenceTab.to[m - 1] = live[m]; SequenceTab.tm[m - 1] = 1; }
                    SequenceTab.startNs = System.nanoTime();
                }
            }
        }
        return simPose();
    }

    /** 動作程式分頁顯示中,每 33 毫秒一次 */
    static void tick(Component bars) {
        if (vp == null) return;
        if (!have) {
            // 還沒載入模型:有模型資料夾就自動載入;載入完 RobotTab 會呼叫 rebuild()
            long now = System.currentTimeMillis();
            if (!RobotTab.loading && !RobotTab.loaded && now - lastTry > 5000) {
                lastTry = now;
                File d = RobotTab.defaultDir();
                if (d.isDirectory() && RobotTab.stlFiles(d).length > 0) {
                    msg.setText("正在載入機器人模型…");
                    RobotTab.loadModel(d);
                } else {
                    msg.setText("還沒有機器人模型(把 STL 放進 robot_stl 資料夾,見「機器人 3D」)。先顯示長條圖。");
                }
            } else if (RobotTab.loaded) {
                rebuild();
            }
            bars.repaint();
            return;
        }
        double[] pos = currentPose();
        if (lastPose != null && Arrays.equals(lastPose, pos)) return;
        lastPose = pos.clone();
        vp.mats = RobotTab.rig.matrices(pos, 0);
        vp.repaint();
    }
}
