import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JPanel;

/**
 * 3D 檢視:純 Java 自己畫(軟體光柵化 + 深度緩衝),不需要任何額外的程式庫。
 *   ・左鍵拖曳旋轉、右鍵(或 Alt + 左鍵)拖曳平移、滾輪縮放
 *   ・點一下選零件(Ctrl+點 = 加選 / 取消),Shift + 拖曳框選
 *   ・「點 3D 設轉軸」模式:下一次點擊把點到的表面座標交給 listener
 */
public class Viewport3D extends JPanel {
    static class Part {
        String name;
        Mesh mesh;
        Mesh raw;                         // 沒降面數的原始網格(伺服對照要比對頂點用)
        int owner = 0;
        boolean visible = true;
        boolean selected = false;
        Part(String name, Mesh mesh) { this.name = name; this.mesh = mesh; }
    }

    interface Listener {
        void selectionChanged();
        void pointPicked(double x, double y, double z);
    }

    final List<Part> parts = new ArrayList<Part>();
    double[][] mats;                      // 每顆馬達的零件群變換(index = owner)
    boolean colorByOwner = true;
    String up = "Y";
    boolean pickPointMode = false;
    interface LabelFunc { String label(Part p); }
    LabelFunc labelFunc = null;           // 回傳要畫在零件上的文字(null = 不畫)
    Listener listener;
    double[] pivotMarker = null;          // 目前編輯中的轉軸位置(畫一個十字)
    double[] axisMarker = null;

    // 相機
    double yaw = Math.PI - 0.5, pitch = 0.25, dist = 800;
    double[] target = {0, 0, 0};
    final double fovDeg = 38;

    BufferedImage img;
    int[] pix;
    float[] zbuf;                         // 存 1/z(越大越近)
    int[] idbuf;                          // 零件編號 + 1(0 = 沒有)
    int iw, ih;
    double lastFrameMs = 0;

    // 相機基底(每次畫之前算好)
    double[] eye = new double[3], right = new double[3], camUp = new double[3], fwd = new double[3];

    public Viewport3D() {
        setBackground(new Color(0x1b2430));
        setFocusable(true);
        MouseAdapter ma = new MouseAdapter() {
            int lx, ly, sx, sy;
            boolean moved, boxing, panning;
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                lx = sx = e.getX(); ly = sy = e.getY();
                moved = false;
                boxing = e.isShiftDown() && javax.swing.SwingUtilities.isLeftMouseButton(e);
                panning = javax.swing.SwingUtilities.isRightMouseButton(e) || (e.isAltDown() && javax.swing.SwingUtilities.isLeftMouseButton(e));
            }
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lx, dy = e.getY() - ly;
                if (Math.abs(e.getX() - sx) + Math.abs(e.getY() - sy) > 3) moved = true;
                if (boxing) {
                    boxRect = new int[] {Math.min(sx, e.getX()), Math.min(sy, e.getY()), Math.abs(e.getX() - sx), Math.abs(e.getY() - sy)};
                    repaint();
                } else if (panning) {
                    double k = dist * Math.tan(Math.toRadians(fovDeg / 2)) * 2 / Math.max(1, ih);
                    for (int i = 0; i < 3; i++) target[i] += (-right[i] * dx + camUp[i] * dy) * k;
                    repaint();
                } else if (moved) {
                    yaw -= dx * 0.008;
                    pitch += dy * 0.008;
                    pitch = Math.max(-1.5, Math.min(1.5, pitch));
                    repaint();
                }
                lx = e.getX(); ly = e.getY();
            }
            public void mouseReleased(MouseEvent e) {
                if (boxing) {
                    int[] r = boxRect;
                    boxRect = null;
                    if (r != null && r[2] > 3 && r[3] > 3) selectRect(r[0], r[1], r[0] + r[2], r[1] + r[3], e.isControlDown());
                    repaint();
                    return;
                }
                if (!moved && !panning && javax.swing.SwingUtilities.isLeftMouseButton(e)) click(e.getX(), e.getY(), e.isControlDown());
            }
            public void mouseWheelMoved(MouseWheelEvent e) {
                dist *= Math.pow(1.12, e.getWheelRotation());
                dist = Math.max(20, Math.min(100000, dist));
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    int[] boxRect = null;

    // ---------- 模型 ----------

    void clear() {
        parts.clear();
        repaint();
    }

    void fitToModel() {
        double[] mn = {1e30, 1e30, 1e30}, mx = {-1e30, -1e30, -1e30};
        for (Part p : parts) {
            for (int k = 0; k < 3; k++) { mn[k] = Math.min(mn[k], p.mesh.min[k]); mx[k] = Math.max(mx[k], p.mesh.max[k]); }
        }
        if (parts.isEmpty()) return;
        double sz = 0;
        for (int k = 0; k < 3; k++) { target[k] = (mn[k] + mx[k]) / 2; sz = Math.max(sz, mx[k] - mn[k]); }
        dist = sz / (2 * Math.tan(Math.toRadians(fovDeg / 2))) * 1.15;
        repaint();
    }

    // ---------- 相機 ----------

    void camera() {
        double e0, e1, e2;
        if ("Z".equals(up)) {
            e0 = Math.cos(pitch) * Math.sin(yaw); e1 = -Math.cos(pitch) * Math.cos(yaw); e2 = Math.sin(pitch);
        } else {
            e0 = Math.cos(pitch) * Math.sin(yaw); e1 = Math.sin(pitch); e2 = Math.cos(pitch) * Math.cos(yaw);
        }
        eye[0] = target[0] + dist * e0; eye[1] = target[1] + dist * e1; eye[2] = target[2] + dist * e2;
        fwd[0] = -e0; fwd[1] = -e1; fwd[2] = -e2;
        double[] upv = "Z".equals(up) ? new double[] {0, 0, 1} : new double[] {0, 1, 0};
        right[0] = fwd[1] * upv[2] - fwd[2] * upv[1];
        right[1] = fwd[2] * upv[0] - fwd[0] * upv[2];
        right[2] = fwd[0] * upv[1] - fwd[1] * upv[0];
        double rl = Math.sqrt(right[0] * right[0] + right[1] * right[1] + right[2] * right[2]);
        if (rl < 1e-9) { right[0] = 1; right[1] = 0; right[2] = 0; rl = 1; }
        for (int i = 0; i < 3; i++) right[i] /= rl;
        camUp[0] = right[1] * fwd[2] - right[2] * fwd[1];
        camUp[1] = right[2] * fwd[0] - right[0] * fwd[2];
        camUp[2] = right[0] * fwd[1] - right[1] * fwd[0];
    }

    // ---------- 畫 ----------

    static int[] palette = {0x6f8fb3, 0xb38f6f, 0x8fb36f, 0xb36f9a, 0x6fb3a8, 0xb3a86f, 0x8a6fb3, 0xb36f6f, 0x6fb38a, 0x6f9ab3};

    static final int SERVO_COLOR = 0xe0607e, PLATE_COLOR = 0x8b929c;

    int baseColor(Part p) {
        if (p.name != null && p.name.contains("舵機")) return SERVO_COLOR;      // 伺服馬達固定粉紅色
        if (!colorByOwner || p.owner == 0) return PLATE_COLOR;                   // 其他零件同一個灰色
        return palette[p.owner % palette.length];
    }

    void render() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        if (img == null || w != iw || h != ih) {
            iw = w; ih = h;
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            pix = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            zbuf = new float[w * h];
            idbuf = new int[w * h];
        }
        long t0 = System.nanoTime();
        camera();
        int bg = getBackground().getRGB();
        java.util.Arrays.fill(pix, bg);
        java.util.Arrays.fill(zbuf, 0f);
        java.util.Arrays.fill(idbuf, 0);
        double f = (ih / 2.0) / Math.tan(Math.toRadians(fovDeg / 2));
        double cx = iw / 2.0, cy = ih / 2.0;
        // 光線方向(相機座標,「從畫面往外」的方向取絕對值,雙面都亮)
        double lx = -0.35, ly = 0.55, lz = -0.75;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;

        for (int pi = 0; pi < parts.size(); pi++) {
            Part p = parts.get(pi);
            if (!p.visible) continue;
            double[] M = (mats != null && p.owner >= 0 && p.owner < mats.length && mats[p.owner] != null) ? mats[p.owner] : Rig.identity();
            // 相機座標 = view * M * v ;先把 M 乘上去(world),再減 eye、投影到 right/camUp/fwd
            double[] a = new double[12];                    // 3x4:相機座標 = A * [x y z 1]
            for (int r = 0; r < 3; r++) {
                double[] ax = r == 0 ? right : r == 1 ? camUp : fwd;
                for (int c = 0; c < 4; c++) {
                    double s = 0;
                    for (int k = 0; k < 3; k++) s += ax[k] * M[k * 4 + c];
                    if (c == 3) s -= (ax[0] * eye[0] + ax[1] * eye[1] + ax[2] * eye[2]);
                    a[r * 4 + c] = s;
                }
            }
            int base = baseColor(p);
            if (p.selected) base = blend(base, 0xff8c1a, 0.65);
            float[] t = p.mesh.tri;
            int n = p.mesh.n;
            float[] sx = new float[3], sy = new float[3], sz = new float[3];
            double[] cxv = new double[3], cyv = new double[3], czv = new double[3];
            for (int i = 0; i < n; i++) {
                boolean skip = false;
                for (int k = 0; k < 3; k++) {
                    double x = t[i * 9 + k * 3], y = t[i * 9 + k * 3 + 1], z = t[i * 9 + k * 3 + 2];
                    double X = a[0] * x + a[1] * y + a[2] * z + a[3];
                    double Y = a[4] * x + a[5] * y + a[6] * z + a[7];
                    double Z = a[8] * x + a[9] * y + a[10] * z + a[11];
                    if (Z < 5) { skip = true; break; }
                    cxv[k] = X; cyv[k] = Y; czv[k] = Z;
                    sx[k] = (float) (cx + f * X / Z);
                    sy[k] = (float) (cy - f * Y / Z);
                    sz[k] = (float) (1.0 / Z);
                }
                if (skip) continue;
                // 面法向量(相機座標)
                double ux = cxv[1] - cxv[0], uy = cyv[1] - cyv[0], uz = czv[1] - czv[0];
                double vx = cxv[2] - cxv[0], vy = cyv[2] - cyv[0], vz = czv[2] - czv[0];
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-12) continue;
                double d = Math.abs((nx * lx + ny * ly + nz * lz) / nl);
                double inten = 0.32 + 0.68 * d;
                int r = (int) Math.min(255, ((base >> 16) & 255) * inten);
                int g = (int) Math.min(255, ((base >> 8) & 255) * inten);
                int b = (int) Math.min(255, (base & 255) * inten);
                fillTri(sx, sy, sz, (r << 16) | (g << 8) | b, pi + 1);
            }
        }
        // 零件上的文字標籤(白底方塊)
        if (labelFunc != null) {
            Graphics2D lg = img.createGraphics();
            lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            lg.setFont(new java.awt.Font("Dialog", java.awt.Font.BOLD, 15));
            List<int[]> used = new ArrayList<int[]>();
            for (Part p : parts) {
                if (!p.visible) continue;
                String t = labelFunc.label(p);
                if (t == null || t.isEmpty()) continue;
                double[] M = (mats != null && p.owner >= 0 && p.owner < mats.length && mats[p.owner] != null) ? mats[p.owner] : Rig.identity();
                double[] c0 = {(p.mesh.min[0] + p.mesh.max[0]) / 2, (p.mesh.min[1] + p.mesh.max[1]) / 2, (p.mesh.min[2] + p.mesh.max[2]) / 2};
                double[] c = new double[3];
                for (int i = 0; i < 3; i++) c[i] = M[i * 4] * c0[0] + M[i * 4 + 1] * c0[1] + M[i * 4 + 2] * c0[2] + M[i * 4 + 3];
                double[] s = project(c);
                if (s == null) continue;
                int labX = (int) s[0], labY = (int) s[1];
                for (int tries = 0; tries < 10; tries++) {
                    boolean clash = false;
                    for (int[] u : used) if (Math.abs(u[0] - labX) < 40 && Math.abs(u[1] - labY) < 20) { clash = true; break; }
                    if (!clash) break;
                    labY += 20;
                }
                used.add(new int[] {labX, labY});
                int labW = lg.getFontMetrics().stringWidth(t) + 10;
                lg.setColor(new Color(255, 255, 255, 235));
                lg.fillRoundRect(labX - labW / 2, labY - 11, labW, 20, 8, 8);
                lg.setColor(new Color(20, 20, 20));
                lg.drawRoundRect(labX - labW / 2, labY - 11, labW, 20, 8, 8);
                lg.drawString(t, labX - labW / 2 + 5, labY + 5);
            }
            lg.dispose();
        }
        // 轉軸標記
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (pivotMarker != null) {
            double[] s = project(pivotMarker);
            if (s != null) {
                g2.setColor(new Color(0xffe000));
                g2.fillOval((int) s[0] - 5, (int) s[1] - 5, 10, 10);
                g2.setColor(Color.BLACK);
                g2.drawOval((int) s[0] - 5, (int) s[1] - 5, 10, 10);
                if (axisMarker != null) {
                    double len = dist * 0.18;
                    double[] a1 = {pivotMarker[0] - axisMarker[0] * len, pivotMarker[1] - axisMarker[1] * len, pivotMarker[2] - axisMarker[2] * len};
                    double[] a2 = {pivotMarker[0] + axisMarker[0] * len, pivotMarker[1] + axisMarker[1] * len, pivotMarker[2] + axisMarker[2] * len};
                    double[] p1 = project(a1), p2 = project(a2);
                    if (p1 != null && p2 != null) {
                        g2.setStroke(new java.awt.BasicStroke(3f));
                        g2.setColor(new Color(0xffe000));
                        g2.drawLine((int) p1[0], (int) p1[1], (int) p2[0], (int) p2[1]);
                        g2.setColor(new Color(0xff3030));
                        g2.fillOval((int) p2[0] - 4, (int) p2[1] - 4, 8, 8);
                    }
                }
            }
        }
        g2.dispose();
        lastFrameMs = (System.nanoTime() - t0) / 1e6;
    }

    static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** 世界座標 → 螢幕座標(在相機後面回傳 null) */
    double[] project(double[] p) {
        double dx = p[0] - eye[0], dy = p[1] - eye[1], dz = p[2] - eye[2];
        double X = right[0] * dx + right[1] * dy + right[2] * dz;
        double Y = camUp[0] * dx + camUp[1] * dy + camUp[2] * dz;
        double Z = fwd[0] * dx + fwd[1] * dy + fwd[2] * dz;
        if (Z < 5) return null;
        double f = (ih / 2.0) / Math.tan(Math.toRadians(fovDeg / 2));
        return new double[] {iw / 2.0 + f * X / Z, ih / 2.0 - f * Y / Z, Z};
    }

    /** 填三角形(深度緩衝,sz 是 1/z) */
    void fillTri(float[] sx, float[] sy, float[] sz, int color, int id) {
        float x0 = sx[0], y0 = sy[0], x1 = sx[1], y1 = sy[1], x2 = sx[2], y2 = sy[2];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (area > -1e-6f && area < 1e-6f) return;
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(iw - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = (int) Math.min(ih - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float inv = 1f / area;
        float z0 = sz[0], z1 = sz[1], z2 = sz[2];
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            int row = y * iw;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                float w2 = 1f - w0 - w1;
                if (w0 < -0.0005f || w1 < -0.0005f || w2 < -0.0005f) continue;
                float z = w0 * z0 + w1 * z1 + w2 * z2;
                int idx = row + x;
                if (z > zbuf[idx]) {
                    zbuf[idx] = z;
                    pix[idx] = color;
                    idbuf[idx] = id;
                }
            }
        }
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        render();
        g.drawImage(img, 0, 0, null);
        if (boxRect != null) {
            g.setColor(new Color(255, 200, 40, 70));
            g.fillRect(boxRect[0], boxRect[1], boxRect[2], boxRect[3]);
            g.setColor(new Color(255, 200, 40));
            g.drawRect(boxRect[0], boxRect[1], boxRect[2], boxRect[3]);
        }
        g.setColor(new Color(255, 255, 255, 150));
        g.drawString("左鍵拖曳=旋轉  右鍵拖曳=平移  滾輪=縮放  點=選零件(Ctrl=加選)  Shift+拖曳=框選", 8, getHeight() - 8);
    }

    // ---------- 選取 / 點取 ----------

    void click(int x, int y, boolean ctrl) {
        if (img == null || x < 0 || y < 0 || x >= iw || y >= ih) return;
        int idx = y * iw + x;
        if (pickPointMode) {
            if (idbuf[idx] != 0 && listener != null) {
                double[] w = unproject(x, y, 1.0 / zbuf[idx]);
                pickPointMode = false;
                setCursor(Cursor.getDefaultCursor());
                listener.pointPicked(w[0], w[1], w[2]);
            }
            return;
        }
        int id = idbuf[idx];
        if (!ctrl) for (Part p : parts) p.selected = false;
        if (id != 0) {
            Part p = parts.get(id - 1);
            p.selected = ctrl ? !p.selected : true;
        }
        if (listener != null) listener.selectionChanged();
        repaint();
    }

    void selectRect(int x0, int y0, int x1, int y1, boolean add) {
        if (img == null) return;
        Set<Integer> hit = new HashSet<Integer>();
        for (int y = Math.max(0, y0); y < Math.min(ih, y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(iw, x1); x++) {
                int id = idbuf[y * iw + x];
                if (id != 0) hit.add(Integer.valueOf(id));
            }
        if (!add) for (Part p : parts) p.selected = false;
        for (Integer id : hit) parts.get(id - 1).selected = true;
        if (listener != null) listener.selectionChanged();
    }

    /** 螢幕座標 + 深度(相機座標的 z)→ 世界座標 */
    double[] unproject(int px, int py, double z) {
        double f = (ih / 2.0) / Math.tan(Math.toRadians(fovDeg / 2));
        double X = (px + 0.5 - iw / 2.0) * z / f;
        double Y = (ih / 2.0 - (py + 0.5)) * z / f;
        double[] w = new double[3];
        for (int i = 0; i < 3; i++) w[i] = eye[i] + right[i] * X + camUp[i] * Y + fwd[i] * z;
        return w;
    }
}
