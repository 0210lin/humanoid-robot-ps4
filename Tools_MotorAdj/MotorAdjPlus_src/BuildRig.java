import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * 把這台機器人的結構(腿的雙平行四邊形、手臂的雙馬達、腰)寫進 rig.json 的 nodes / owner。
 * 馬達↔伺服的對照讀 rig.json 的 joints(使用者在「伺服對照」指定的)。孔位從零件的幾何算出來,不寫死。
 */
public class BuildRig {
    static Map<String, Mesh> meshes = new LinkedHashMap<String, Mesh>();
    static Rig rig;
    static Map<Integer, String> servoName = new HashMap<Integer, String>();   // 馬達 → 伺服零件名稱

    static String P(String n) { return "mix - " + n; }

    static List<double[]> circles(Mesh m, int axis, int maxCount) {
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        double plane = m.min[axis];
        List<double[]> pts = new ArrayList<double[]>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < m.n * 3; i++) {
            if (Math.abs(m.tri[i * 3 + axis] - plane) < 0.2) {
                double a = m.tri[i * 3 + u], b = m.tri[i * 3 + w];
                if (seen.add(Math.round(a * 500) + "," + Math.round(b * 500))) pts.add(new double[] {a, b});
            }
        }
        List<double[]> found = new ArrayList<double[]>();
        Random rnd = new Random(3);
        List<double[]> remaining = new ArrayList<double[]>(pts);
        for (int round = 0; round < maxCount; round++) {
            double bs = 0, bcx = 0, bcy = 0, br = 0;
            for (int it = 0; it < 40000 && remaining.size() > 10; it++) {
                double[] p = remaining.get(rnd.nextInt(remaining.size())), q = remaining.get(rnd.nextInt(remaining.size())), r = remaining.get(rnd.nextInt(remaining.size()));
                double dd = 2 * (p[0] * (q[1] - r[1]) + q[0] * (r[1] - p[1]) + r[0] * (p[1] - q[1]));
                if (Math.abs(dd) < 1e-6) continue;
                double ux = ((p[0] * p[0] + p[1] * p[1]) * (q[1] - r[1]) + (q[0] * q[0] + q[1] * q[1]) * (r[1] - p[1]) + (r[0] * r[0] + r[1] * r[1]) * (p[1] - q[1])) / dd;
                double uy = ((p[0] * p[0] + p[1] * p[1]) * (r[0] - q[0]) + (q[0] * q[0] + q[1] * q[1]) * (p[0] - r[0]) + (r[0] * r[0] + r[1] * r[1]) * (q[0] - p[0])) / dd;
                double rad = Math.hypot(p[0] - ux, p[1] - uy);
                if (rad < 1.0 || rad > 8) continue;
                int in = 0;
                for (double[] s : remaining) if (Math.abs(Math.hypot(s[0] - ux, s[1] - uy) - rad) < 0.04) in++;
                if (in > bs) { bs = in; bcx = ux; bcy = uy; br = rad; }
            }
            if (bs < 10) break;
            found.add(new double[] {bcx, bcy, br, bs});
            final double fx = bcx, fy = bcy, fr = br;
            List<double[]> next = new ArrayList<double[]>();
            for (double[] s : remaining) if (Math.abs(Math.hypot(s[0] - fx, s[1] - fy) - fr) >= 0.04) next.add(s);
            remaining = next;
        }
        return found;
    }

    /** 曲柄板(板厚方向 X):回傳 {S(伺服軸孔 Y,Z), B(最遠的小孔 Y,Z)} */
    static double[][] crankHoles(String part) {
        List<double[]> cs = circles(meshes.get(P(part)), 0, 8);
        double[] s = null;
        for (double[] c : cs) if (c[2] > 2.3 && c[2] < 3.0 && (s == null || c[3] > s[3])) s = c;
        if (s == null) throw new RuntimeException("找不到伺服軸孔: " + part);
        double[] b = null;
        double bd = -1;
        for (double[] c : cs) if (c[2] > 1.1 && c[2] < 1.5) { double d = Math.hypot(c[0] - s[0], c[1] - s[1]); if (d > bd) { bd = d; b = c; } }
        return new double[][] {{s[0], s[1]}, {b[0], b[1]}};
    }

    /** 假連桿板:兩個大軸承孔(r≈4),回傳 {Y 較小的, Y 較大的} */
    static double[][] dummyHoles(String part) {
        List<double[]> cs = circles(meshes.get(P(part)), 0, 8);
        List<double[]> big = new ArrayList<double[]>();
        for (double[] c : cs) if (c[2] > 3.5 && c[2] < 4.5) big.add(c);
        if (big.size() != 2) throw new RuntimeException("假連桿的軸承孔不是 2 個: " + part + " " + big.size());
        double[] a = big.get(0), b = big.get(1);
        if (a[0] > b[0]) { double[] t = a; a = b; b = t; }
        return new double[][] {{a[0], a[1]}, {b[0], b[1]}};
    }

    static List<Rig.Node> nodes = new ArrayList<Rig.Node>();
    static Map<String, Integer> owner = new LinkedHashMap<String, Integer>();

    static int add(String name, int parent, String type, int[] motors, double[] axis, double[] pivot, double[] point, String... parts) {
        Rig.Node n = new Rig.Node();
        n.name = name;
        n.parent = parent;
        n.type = type;
        n.motors = motors;
        n.mult = new int[motors.length];
        for (int i = 0; i < motors.length; i++) {
            double[] ma = rig.joints[motors[i]].axis;
            n.mult[i] = (ma[0] * axis[0] + ma[1] * axis[1] + ma[2] * axis[2]) >= 0 ? 1 : -1;
        }
        n.axis = axis.clone();
        n.pivot = pivot.clone();
        n.point = point == null ? new double[3] : point.clone();
        nodes.add(n);
        int id = nodes.size();
        for (String p : parts) {
            String key = p.startsWith("@") ? servoName.get(Integer.parseInt(p.substring(1))) : P(p);
            if (key == null || !meshes.containsKey(key)) throw new RuntimeException("找不到零件: " + p);
            if (owner.containsKey(key)) throw new RuntimeException("零件重複指定: " + p);
            owner.put(key, Integer.valueOf(id));
        }
        return id;
    }

    static double[] v3(double x, double y, double z) { return new double[] {x, y, z}; }

    static void leg(String side, int hipNode, int mKnee, int mAnkleBr, int mFoot, String[] cr1, String[] du1, String[] cr2, String[] du2,
                    String[] kneeParts, String[] ankleParts, String[] footParts, double xc) {
        // 第一個平行四邊形(馬達 mKnee):曲柄板 cr1(一端是伺服軸 S 在膝蓋塊上,另一端 B 在大腿上)、假連桿板 du1
        double[][] c1 = crankHoles(cr1[0]);
        double[][] d1 = dummyHoles(du1[0]);
        double[] S = v3(xc, c1[0][0], c1[0][1]), B = v3(xc, c1[1][0], c1[1][1]);
        double[] P1 = v3(xc, d1[0][0], d1[0][1]), A = v3(xc, d1[1][0], d1[1][1]);       // P1 在膝蓋塊上(Y 較小),A 在大腿上
        double[] ax1 = rig.joints[mKnee].axis;
        System.out.printf("%s腿 平行四邊形1: S=(%.1f,%.1f) B=(%.1f,%.1f) P1=(%.1f,%.1f) A=(%.1f,%.1f)  S→B=(%.1f,%.1f) P1→A=(%.1f,%.1f)%n", side, S[1], S[2], B[1], B[2], P1[1], P1[2], A[1], A[2], B[1] - S[1], B[2] - S[2], A[1] - P1[1], A[2] - P1[2]);
        int knee = add(side + "膝蓋塊(馬達" + mKnee + ")", hipNode, "arc", new int[] {mKnee}, ax1, B, S, kneeParts);
        add(side + "曲柄1(馬達" + mKnee + ")", hipNode, "rot", new int[] {mKnee}, ax1, B, null, cr1);
        add(side + "假連桿1(馬達" + mKnee + ")", hipNode, "rot", new int[] {mKnee}, ax1, A, null, du1);
        // 第二個平行四邊形(馬達 mAnkleBr):伺服軸 S2 在膝蓋塊上,曲柄板另一端 B2 在腳踝支架上;假連桿 P1' 在膝蓋塊上、A2 在腳踝支架上
        double[][] c2 = crankHoles(cr2[0]);
        double[][] d2 = dummyHoles(du2[0]);
        double[] S2 = v3(xc, c2[0][0], c2[0][1]), B2 = v3(xc, c2[1][0], c2[1][1]);
        double[] P2 = v3(xc, d2[1][0], d2[1][1]), A2 = v3(xc, d2[0][0], d2[0][1]);      // P2 在膝蓋塊上(Y 較大),A2 在腳踝支架上
        double[] ax2 = rig.joints[mAnkleBr].axis;
        System.out.printf("%s腿 平行四邊形2: S2=(%.1f,%.1f) B2=(%.1f,%.1f) P1'=(%.1f,%.1f) A2=(%.1f,%.1f)  S2→B2=(%.1f,%.1f) P1'→A2=(%.1f,%.1f)%n", side, S2[1], S2[2], B2[1], B2[2], P2[1], P2[2], A2[1], A2[2], B2[1] - S2[1], B2[2] - S2[2], A2[1] - P2[1], A2[2] - P2[2]);
        int ankle = add(side + "腳踝支架(馬達" + mAnkleBr + ")", knee, "arc", new int[] {mAnkleBr}, ax2, S2, B2, ankleParts);
        add(side + "曲柄2(馬達" + mAnkleBr + ")", knee, "rot", new int[] {mAnkleBr}, ax2, S2, null, cr2);
        add(side + "假連桿2(馬達" + mAnkleBr + ")", knee, "rot", new int[] {mAnkleBr}, ax2, P2, null, du2);
        add(side + "腳掌(馬達" + mFoot + ")", ankle, "rot", new int[] {mFoot}, rig.joints[mFoot].axis, rig.joints[mFoot].pivot, null, footParts);
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        rig = Rig.load(new File(dir, "rig.json"));
        for (int m = 1; m <= 26; m++) if (!rig.joints[m].servo.isEmpty()) servoName.put(m, rig.joints[m].servo);
        for (File f : dir.listFiles()) if (f.getName().toLowerCase().endsWith(".stl")) { Mesh m = Mesh.load(f); meshes.put(m.name, m); }
        Rig.Joint[] J = rig.joints;

        // 上半身(腰轉軸 馬達15 帶動)
        int upper = add("上半身(馬達15 腰)", 0, "rot", new int[] {15}, J[15].axis, J[15].pivot, null,
                "身體-2", "控制板-1", "連接塊-1", "連接塊-2", "連接塊-7", "頭蓋-1", "撈手-1", "@1", "@2", "@8");
        // 右手臂(馬達 1+2 肩、3+18 手肘,各兩顆夾著同一個軸)
        int rs = add("右上臂(馬達1 肩,2 並聯)", upper, "rot", new int[] {1}, J[1].axis, J[1].pivot, null, "手臂3-1", "手臂3-2", "撈手臂支撐-1");
        add("右前臂(馬達3 手肘,18 並聯)", rs, "rot", new int[] {3}, J[3].axis, J[3].pivot, null, "手掌-1", "手掌-2", "手掌中-1", "撈手掌支撐-1", "@18", "@3");
        // 左手臂(馬達 8、9、10、22 依序)
        int l8 = add("左肩(馬達8)", upper, "rot", new int[] {8}, J[8].axis, J[8].pivot, null, "甩手-1");
        int l9 = add("左臂2(馬達9)", l8, "rot", new int[] {9}, J[9].axis, J[9].pivot, null, "甩手3-1", "甩手3-2", "@9", "@10");
        int l10 = add("左臂3(馬達10)", l9, "rot", new int[] {10}, J[10].axis, J[10].pivot, null, "甩手2-1", "甩手2-2");
        add("左手(馬達22)", l10, "rot", new int[] {22}, J[22].axis, J[22].pivot, null, "手掌-3", "手掌-4", "手掌中 甩-1", "@22");
        // 右腿(馬達 4 髖、5 膝蓋推動、6 腳踝支架推動、7 腳掌)
        int rh = add("右髖(馬達4)", 0, "rot", new int[] {4}, J[4].axis, J[4].pivot, null, "連趕踝關節 (鏡射)-1");
        leg("右", rh, 5, 6, 7, new String[] {"軸虛連趕-1", "軸虛連趕-2"}, new String[] {"虛虛連趕-1", "虛虛連趕-2"},
                new String[] {"軸虛連趕-3", "軸虛連趕-4"}, new String[] {"虛虛連趕-3", "虛虛連趕-4", "虛虛中-2"},
                new String[] {"膝蓋-1", "膝蓋-2", "膝蓋中-1", "連桿墊片-1", "連桿墊片-2", "連桿墊片-3", "連桿墊片-4", "@5", "@6"},
                new String[] {"踝關節-1", "連接塊-3", "連接塊-4"}, new String[] {"腳底板-1", "@7"}, J[5].pivot[0]);
        // 左腿(馬達 11 髖、26 髖旋轉、12 膝蓋推動、13 腳踝支架推動、14 腳掌)
        // 馬達 26(髖旋轉)在真機上不存在(測試用),所以左髖和右髖一樣只有 1 軸:「旋轉髖關節 1、2」當作一個固定的髖支架
        // 對稱版(robot_stl_sym):左髖支架是右髖支架的鏡像(1 個零件);原版是 2 個零件
        String[] lhParts = meshes.containsKey(P("連趕踝關節 (鏡射)-3")) ? new String[] {"連趕踝關節 (鏡射)-3"} : new String[] {"旋轉髖關節1-2", "旋轉髖關節2-2"};
        int lh = add("左髖(馬達11)", 0, "rot", new int[] {11}, J[11].axis, J[11].pivot, null, lhParts);
        leg("左", lh, 12, 13, 14, new String[] {"軸虛連趕-6", "軸虛連趕-9"}, new String[] {"虛虛連趕-5", "虛虛連趕-9"},
                new String[] {"軸虛連趕-7", "軸虛連趕-8"}, new String[] {"虛虛連趕-6", "虛虛連趕-8"},
                new String[] {"膝蓋-3", "膝蓋-4", "連桿墊片-5", "連桿墊片-6", "連桿墊片-7", "連桿墊片-8", "@12", "@13"},
                new String[] {"連趕踝關節 (鏡射)-2"}, new String[] {"腳底板-2", "連接塊-5", "連接塊-6", "@14"}, J[12].pivot[0]);

        rig.nodes = nodes;
        rig.owner.clear();
        rig.owner.putAll(owner);
        List<String> rest = new ArrayList<String>();
        for (String k : meshes.keySet()) if (!owner.containsKey(k)) rest.add(k.replace("mix - ", ""));
        System.out.println("節點 " + nodes.size() + " 個;指定了 " + owner.size() + " 個零件;留在身體(不動)的 " + rest.size() + " 個: " + rest);
        File rf = new File(dir, "rig.json");
        Files.copy(rf.toPath(), new File(dir, "rig.json.bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        rig.save(rf);
        System.out.println("已寫入 " + rf);
    }
}
