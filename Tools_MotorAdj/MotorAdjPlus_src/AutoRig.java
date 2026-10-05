import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 從 STL 零件自動推出機器人的結構:
 *   ・伺服馬達 = 同一個零件的多個複本。在其中一顆(基準)找出輸出軸的位置和方向,其他每一顆用「剛體對應」換算
 *   ・零件之間的接觸關係(兩零件的表面靠得很近 = 接在一起)
 *   ・每顆伺服馬達帶動的那一段 = 從「輸出軸兩端附近接觸到的零件」往外,沿著接觸關係擴展,遇到其他伺服馬達就停(那顆伺服屬於這一段,但它帶動的下一段不算)
 *   ・某顆伺服屬於哪一段,就掛在帶動那一段的伺服下面
 */
public class AutoRig {
    // ---------- 向量 ----------
    static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] norm(double[] a) { double l = len(a); return l < 1e-12 ? a : new double[] {a[0] / l, a[1] / l, a[2] / l}; }

    // ---------- 點到三角形的最短距離平方(Ericson) ----------
    static double ptTri2(double px, double py, double pz, float[] t, int o) {
        double ax = t[o], ay = t[o + 1], az = t[o + 2], bx = t[o + 3], by = t[o + 4], bz = t[o + 5], cx = t[o + 6], cy = t[o + 7], cz = t[o + 8];
        double abx = bx - ax, aby = by - ay, abz = bz - az, acx = cx - ax, acy = cy - ay, acz = cz - az;
        double apx = px - ax, apy = py - ay, apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) return d2(apx, apy, apz);
        double bpx = px - bx, bpy = py - by, bpz = pz - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) return d2(bpx, bpy, bpz);
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) { double v = d1 / (d1 - d3); return d2(px - (ax + v * abx), py - (ay + v * aby), pz - (az + v * abz)); }
        double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
        double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) return d2(cpx, cpy, cpz);
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) { double w = d2 / (d2 - d6); return d2(px - (ax + w * acx), py - (ay + w * acy), pz - (az + w * acz)); }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) { double w = (d4 - d3) / ((d4 - d3) + (d5 - d6)); return d2(px - (bx + w * (cx - bx)), py - (by + w * (cy - by)), pz - (bz + w * (cz - bz))); }
        double denom = 1.0 / (va + vb + vc);
        double v = vb * denom, w = vc * denom;
        return d2(px - (ax + abx * v + acx * w), py - (ay + aby * v + acy * w), pz - (az + abz * v + acz * w));
    }
    static double d2(double x, double y, double z) { return x * x + y * y + z * z; }

    // ---------- 網格加速 ----------
    static class Grid {
        Mesh m;
        double cell;
        Map<Long, List<Integer>> cells = new HashMap<Long, List<Integer>>();
        Grid(Mesh m, double cell, double pad) {
            this.m = m;
            this.cell = cell;
            for (int i = 0; i < m.n; i++) {
                double[] mn = {1e30, 1e30, 1e30}, mx = {-1e30, -1e30, -1e30};
                for (int v = 0; v < 3; v++) for (int k = 0; k < 3; k++) { double c = m.tri[i * 9 + v * 3 + k]; mn[k] = Math.min(mn[k], c); mx[k] = Math.max(mx[k], c); }
                int x0 = (int) Math.floor((mn[0] - pad) / cell), x1 = (int) Math.floor((mx[0] + pad) / cell);
                int y0 = (int) Math.floor((mn[1] - pad) / cell), y1 = (int) Math.floor((mx[1] + pad) / cell);
                int z0 = (int) Math.floor((mn[2] - pad) / cell), z1 = (int) Math.floor((mx[2] + pad) / cell);
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                    long key = key(x, y, z);
                    List<Integer> l = cells.get(key);
                    if (l == null) { l = new ArrayList<Integer>(); cells.put(key, l); }
                    l.add(i);
                }
            }
        }
        static long key(int x, int y, int z) { return ((long) (x + 4096) << 40) ^ ((long) (y + 4096) << 20) ^ (long) (z + 4096); }
        /** p 到這個網格最近的距離是否 <= tol(只看 tol 範圍內) */
        boolean near(double x, double y, double z, double tol) {
            List<Integer> l = cells.get(key((int) Math.floor(x / cell), (int) Math.floor(y / cell), (int) Math.floor(z / cell)));
            if (l == null) return false;
            double t2 = tol * tol;
            for (int i : l) if (ptTri2(x, y, z, m.tri, i * 9) <= t2) return true;
            return false;
        }
    }

    // ---------- 結果 ----------
    static class Result {
        List<String> names = new ArrayList<String>();
        boolean[] isServo;
        int[] servoOf;                    // 零件 index → 伺服編號(0..S-1),不是伺服 = -1
        List<Integer> servos = new ArrayList<Integer>();      // 伺服零件的 index
        double[][] pivot, axis;           // 每顆伺服的轉軸(世界座標)
        int[] linkOfPart;                 // 零件 index → 帶動它的伺服(servo index,0..S-1),-1 = 身體
        int[] parent;                     // 伺服 i 掛在哪顆伺服 j 下面(-1 = 身體)
        List<List<Integer>> contacts = new ArrayList<List<Integer>>();
        List<List<Integer>> seeds = new ArrayList<List<Integer>>();
        List<String> notes = new ArrayList<String>();
    }

    /** meshes:全部零件(原始解析度);servoKey:伺服零件名稱包含的字 */
    static Result analyze(List<Mesh> meshes, String servoKey, double tol) {
        int n = meshes.size();
        Result r = new Result();
        for (Mesh m : meshes) r.names.add(m.name);
        r.isServo = new boolean[n];
        r.servoOf = new int[n];
        java.util.Arrays.fill(r.servoOf, -1);
        for (int i = 0; i < n; i++) if (meshes.get(i).name.contains(servoKey)) { r.isServo[i] = true; r.servoOf[i] = r.servos.size(); r.servos.add(i); }
        int S = r.servos.size();
        // 基準伺服的轉軸:找輸出軸(圓形特徵),算法見 refShaft
        Mesh ref = meshes.get(r.servos.get(0));
        double[][] refShaft = refShaft(ref);
        r.pivot = new double[S][];
        r.axis = new double[S][];
        for (int s = 0; s < S; s++) {
            Mesh m = meshes.get(r.servos.get(s));
            double[][] rt = rigid(ref, m);               // {R 的 3 列..., t}
            if (rt == null) { r.notes.add("伺服 " + m.name + " 和基準的形狀對不起來"); r.pivot[s] = refShaft[0]; r.axis[s] = refShaft[1]; continue; }
            r.pivot[s] = apply(rt, refShaft[0]);
            r.axis[s] = norm(rotate(rt, refShaft[1]));
        }
        // 接觸關係
        Grid[] grids = new Grid[n];
        for (int i = 0; i < n; i++) grids[i] = new Grid(meshes.get(i), 8.0, tol + 0.05);
        boolean[][] touch = new boolean[n][n];
        for (int i = 0; i < n; i++) r.contacts.add(new ArrayList<Integer>());
        for (int a = 0; a < n; a++) {
            Mesh A = meshes.get(a);
            for (int b = a + 1; b < n; b++) {
                Mesh B = meshes.get(b);
                boolean ov = true;
                for (int k = 0; k < 3; k++) if (A.max[k] + tol < B.min[k] || B.max[k] + tol < A.min[k]) { ov = false; break; }
                if (!ov) continue;
                int c = countNear(A, grids[b], tol, 3) + countNear(B, grids[a], tol, 3);
                if (c >= 3) { touch[a][b] = touch[b][a] = true; r.contacts.get(a).add(b); r.contacts.get(b).add(a); }
            }
        }
        // 每顆伺服:輸出軸兩端附近接觸到的零件 = 種子
        r.linkOfPart = new int[n];
        java.util.Arrays.fill(r.linkOfPart, -1);
        int[] size = new int[n];
        List<List<Integer>> linkParts = new ArrayList<List<Integer>>();
        for (int s = 0; s < S; s++) {
            int si = r.servos.get(s);
            Mesh sm = meshes.get(si);
            double[] P = r.pivot[s], A = r.axis[s];
            // 伺服沿軸向的範圍
            double lo = 1e30, hi = -1e30;
            for (int i = 0; i < sm.n * 3; i++) {
                double[] p = {sm.tri[i * 3], sm.tri[i * 3 + 1], sm.tri[i * 3 + 2]};
                double t = dot(sub(p, P), A);
                lo = Math.min(lo, t); hi = Math.max(hi, t);
            }
            List<Integer> seed = new ArrayList<Integer>();
            for (int b : r.contacts.get(si)) {
                if (r.isServo[b]) continue;
                Mesh B = meshes.get(b);
                boolean hit = false;
                for (int i = 0; i < B.n * 3 && !hit; i++) {
                    double[] p = {B.tri[i * 3], B.tri[i * 3 + 1], B.tri[i * 3 + 2]};
                    double[] d = sub(p, P);
                    double t = dot(d, A);
                    double[] perp = {d[0] - t * A[0], d[1] - t * A[1], d[2] - t * A[2]};
                    double rad = len(perp);
                    if (rad < 9.0 && (t < lo + 4.0 || t > hi - 4.0) && grids[si].near(p[0], p[1], p[2], tol)) hit = true;
                }
                if (hit) seed.add(b);
            }
            r.seeds.add(seed);
            // 擴展
            boolean[] in = new boolean[n];
            List<Integer> queue = new ArrayList<Integer>();
            for (int b : seed) { in[b] = true; queue.add(b); }
            for (int qi = 0; qi < queue.size(); qi++) {
                int cur = queue.get(qi);
                if (r.isServo[cur]) continue;            // 遇到其他伺服:屬於這一段,但不再往它帶動的那一邊擴展
                for (int nb : r.contacts.get(cur)) {
                    if (nb == si || in[nb]) continue;
                    in[nb] = true;
                    queue.add(nb);
                }
            }
            linkParts.add(queue);
        }
        // 每個零件歸到「最小的那一段」(有擴展過頭的話,範圍小的比較可能是對的)
        int[] best = new int[n];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        for (int s = 0; s < S; s++) {
            for (int p : linkParts.get(s)) {
                if (linkParts.get(s).size() < best[p]) { best[p] = linkParts.get(s).size(); r.linkOfPart[p] = s; }
            }
        }
        // 掛在哪顆伺服下面
        r.parent = new int[S];
        for (int s = 0; s < S; s++) r.parent[s] = r.linkOfPart[r.servos.get(s)];
        return r;
    }

    static int countNear(Mesh A, Grid gb, double tol, int stopAt) {
        int c = 0;
        for (int i = 0; i < A.n; i++) {
            for (int v = 0; v < 3; v++) {
                if (gb.near(A.tri[i * 9 + v * 3], A.tri[i * 9 + v * 3 + 1], A.tri[i * 9 + v * 3 + 2], tol)) { c++; if (c >= stopAt) return c; }
            }
        }
        return c;
    }

    // ---------- 剛體對應(頂點順序一致的複本) ----------
    static double[] vtx(Mesh m, int i) { return new double[] {m.tri[i * 3], m.tri[i * 3 + 1], m.tri[i * 3 + 2]}; }

    static double[][] frame(double[] a, double[] b, double[] c) {
        double[] e1 = norm(sub(b, a));
        double[] w = sub(c, a);
        double d = dot(w, e1);
        double[] e2 = norm(new double[] {w[0] - d * e1[0], w[1] - d * e1[1], w[2] - d * e1[2]});
        return new double[][] {e1, e2, cross(e1, e2)};
    }

    /** 回傳 {R 第 0 列, R 第 1 列, R 第 2 列, t},使 inst ≈ R * ref + t;對不起來回傳 null */
    static double[][] rigid(Mesh ref, Mesh inst) {
        if (ref.n != inst.n) return null;
        int nv = ref.n * 3;
        int ia = 0, ib = 0, ic = 0;
        double best = -1;
        for (int i = 0; i < nv; i++) { double d = len(sub(vtx(ref, i), vtx(ref, ia))); if (d > best) { best = d; ib = i; } }
        best = -1;
        for (int i = 0; i < nv; i++) { double ar = len(cross(sub(vtx(ref, ib), vtx(ref, ia)), sub(vtx(ref, i), vtx(ref, ia)))); if (ar > best) { best = ar; ic = i; } }
        double[][] fr = frame(vtx(ref, ia), vtx(ref, ib), vtx(ref, ic));
        double[][] fi = frame(vtx(inst, ia), vtx(inst, ib), vtx(inst, ic));
        double[][] R = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double s = 0; for (int k = 0; k < 3; k++) s += fi[k][i] * fr[k][j]; R[i][j] = s; }
        double[] A = vtx(ref, ia), Ai = vtx(inst, ia);
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) t[i] = Ai[i] - (R[i][0] * A[0] + R[i][1] * A[1] + R[i][2] * A[2]);
        double maxErr = 0;
        int step = Math.max(1, nv / 400);
        for (int i = 0; i < nv; i += step) {
            double[] p = vtx(ref, i), q = vtx(inst, i);
            double e = 0;
            for (int k = 0; k < 3; k++) { double rr = R[k][0] * p[0] + R[k][1] * p[1] + R[k][2] * p[2] + t[k]; e += (rr - q[k]) * (rr - q[k]); }
            maxErr = Math.max(maxErr, Math.sqrt(e));
        }
        if (maxErr > 0.05) return null;
        return new double[][] {R[0], R[1], R[2], t};
    }

    static double[] apply(double[][] rt, double[] p) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = rt[i][0] * p[0] + rt[i][1] * p[1] + rt[i][2] * p[2] + rt[3][i];
        return r;
    }

    static double[] rotate(double[][] rt, double[] p) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = rt[i][0] * p[0] + rt[i][1] * p[1] + rt[i][2] * p[2];
        return r;
    }

    // ---------- 基準伺服的輸出軸:找同心圓 ----------
    /** 回傳 {pivot, axis}(基準伺服自己的座標)。作法:最薄的那個方向的兩個面上,找圓形特徵的中心 */
    static double[][] refShaft(Mesh m) {
        // 軸向 = 兩個端面上有「圓形特徵」(同心圓)的方向。對 3 個方向各試,取圓最明顯的
        double[][] bestRes = null;
        double bestScore = -1;
        java.util.Random rnd = new java.util.Random(7);
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, w = (axis + 2) % 3;
            double total = 0;
            double[] centers = new double[4];
            int nc = 0;
            for (int side = 0; side < 2; side++) {
                double plane = side == 0 ? m.min[axis] : m.max[axis];
                List<double[]> pts = new ArrayList<double[]>();
                java.util.Set<String> seen = new java.util.HashSet<String>();
                for (int i = 0; i < m.n * 3; i++) {
                    if (Math.abs(m.tri[i * 3 + axis] - plane) < 3.0) {
                        double a = m.tri[i * 3 + u], b = m.tri[i * 3 + w];
                        if (seen.add(Math.round(a * 1000) + "," + Math.round(b * 1000))) pts.add(new double[] {a, b});
                    }
                }
                if (pts.size() < 20) continue;
                double bs = 0, bcx = 0, bcy = 0;
                for (int it = 0; it < 30000; it++) {
                    double[] p = pts.get(rnd.nextInt(pts.size())), q = pts.get(rnd.nextInt(pts.size())), r = pts.get(rnd.nextInt(pts.size()));
                    double dd = 2 * (p[0] * (q[1] - r[1]) + q[0] * (r[1] - p[1]) + r[0] * (p[1] - q[1]));
                    if (Math.abs(dd) < 1e-6) continue;
                    double ux = ((p[0] * p[0] + p[1] * p[1]) * (q[1] - r[1]) + (q[0] * q[0] + q[1] * q[1]) * (r[1] - p[1]) + (r[0] * r[0] + r[1] * r[1]) * (p[1] - q[1])) / dd;
                    double uy = ((p[0] * p[0] + p[1] * p[1]) * (r[0] - q[0]) + (q[0] * q[0] + q[1] * q[1]) * (p[0] - r[0]) + (r[0] * r[0] + r[1] * r[1]) * (q[0] - p[0])) / dd;
                    double rad = Math.hypot(p[0] - ux, p[1] - uy);
                    if (rad < 2.5 || rad > 14) continue;
                    int in = 0;
                    for (double[] s : pts) if (Math.abs(Math.hypot(s[0] - ux, s[1] - uy) - rad) < 0.03) in++;
                    if (in > bs) { bs = in; bcx = ux; bcy = uy; }
                }
                // 同心圓的數量:以這個中心,不同半徑裡各有幾個點的圈
                java.util.Map<Integer, Integer> rings = new java.util.TreeMap<Integer, Integer>();
                for (double[] s : pts) { double rr = Math.hypot(s[0] - bcx, s[1] - bcy); if (rr < 10) rings.merge((int) Math.round(rr * 10), 1, Integer::sum); }
                int good = 0;
                for (int c : rings.values()) if (c >= 12) good++;
                total += good;
                centers[nc * 2] = bcx; centers[nc * 2 + 1] = bcy; nc++;
            }
            if (nc == 2 && total > bestScore && Math.abs(centers[0] - centers[2]) < 0.5 && Math.abs(centers[1] - centers[3]) < 0.5) {
                bestScore = total;
                double[] p = new double[3], a = new double[3];
                p[axis] = (m.min[axis] + m.max[axis]) / 2;
                p[u] = (centers[0] + centers[2]) / 2;
                p[w] = (centers[1] + centers[3]) / 2;
                a[axis] = 1;
                bestRes = new double[][] {p, a};
            }
        }
        if (bestRes == null) {
            double[] p = {(m.min[0] + m.max[0]) / 2, (m.min[1] + m.max[1]) / 2, (m.min[2] + m.max[2]) / 2};
            bestRes = new double[][] {p, {1, 0, 0}};
        }
        return bestRes;
    }
}
