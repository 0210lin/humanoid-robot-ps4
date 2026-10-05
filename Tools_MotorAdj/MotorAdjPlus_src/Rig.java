import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 機器人的關節設定(26 顆馬達)與運動學。座標都是「零位姿勢」(馬達 1500)的座標,單位公釐。
 *
 * 馬達(joints):每顆馬達有 轉動方向 sign、「1 個位置單位 = 幾度」degPer、零位 zero,以及它對應的伺服零件 servo、
 *   轉軸位置 pivot / 方向 axis(「伺服對照」自動算出)。
 *
 * 兩種模式:
 *   ・簡單模式(nodes == null):每顆馬達就是一個關節,零件的 owner = 馬達編號(0 = 身體),
 *     parent = 這個關節掛在哪顆馬達上。
 *   ・結構模式(nodes != null):零件的 owner = 結構節點編號(1..n,0 = 身體)。節點有三種動作:
 *       rot:繞軸(通過 pivot、方向 axis)轉 ψ
 *       arc:平行四邊形連桿推動的整塊平移。曲柄繞中心 pivot 轉 ψ,被推動的那一點 point 跟著畫圓弧,
 *           整塊零件平移「point 轉完的位移」(不會轉動,像縮放儀)
 *     ψ = 驅動它的馬達角度(乘上 ±1,因為兩顆馬達的軸可能相反)的平均。
 *     這樣可以描述腿的雙平行四邊形、手臂兩顆夾著同一個軸的雙馬達等。
 */
public class Rig {
    static final int N = 26;

    static class Joint {
        int motor;
        String name = "";
        int parent = 0;
        double[] pivot = {0, 0, 0};
        double[] axis = {1, 0, 0};
        int sign = 1;
        double degPer = 0.1636;
        int zero = 1500;
        String servo = "";                 // 這顆馬達對應的伺服馬達零件名稱(「伺服對照」指定的)
        Joint(int m) { motor = m; name = "馬達 " + m; }
    }

    static class Node {
        String name = "";
        int parent = 0;                    // 0 = 身體,其他 = 節點編號(1..n)
        String type = "rot";               // "rot" 或 "arc"
        int[] motors = new int[0];
        int[] mult = new int[0];           // 每顆馬達的轉動方向相對於這個節點的 axis(+1 / -1)
        double[] axis = {1, 0, 0};
        double[] pivot = {0, 0, 0};        // rot:轉軸通過的點;arc:曲柄的轉動中心
        double[] point = {0, 0, 0};        // arc:被推動的那一點
    }

    Joint[] joints = new Joint[N + 1];                       // 1..26
    List<Node> nodes = null;                                 // null = 簡單模式
    Map<String, Integer> owner = new LinkedHashMap<String, Integer>();   // 零件名稱 → 馬達 / 節點編號(0 = 身體)
    String up = "Y";                                         // 模型的「上」是哪個軸:Y 或 Z
    int budget = 250000;                                      // 全部零件加起來最多幾個三角形(降面數用)

    Rig() {
        for (int m = 1; m <= N; m++) joints[m] = new Joint(m);
    }

    /** 可以指定零件的目標數:簡單模式 = 26 顆馬達;結構模式 = 節點數 */
    int targetCount() { return nodes == null ? N : nodes.size(); }

    String targetName(int i) {
        if (i == 0) return "身體(不動)";
        if (nodes == null) return joints[i].name;
        return nodes.get(i - 1).name;
    }

    // ---------- 存讀檔 ----------

    static JSONArray arr(double a, double b, double c) throws Exception {
        JSONArray r = new JSONArray();
        r.put(a);
        r.put(b);
        r.put(c);
        return r;
    }

    static double[] vec(JSONArray a, double[] def) throws Exception {
        if (a == null || a.length() < 3) return def;
        return new double[] {a.getDouble(0), a.getDouble(1), a.getDouble(2)};
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("up", up);
        o.put("budget", budget);
        JSONArray ja = new JSONArray();
        for (int m = 1; m <= N; m++) {
            Joint j = joints[m];
            JSONObject jo = new JSONObject();
            jo.put("motor", j.motor);
            jo.put("name", j.name);
            jo.put("parent", j.parent);
            jo.put("pivot", arr(j.pivot[0], j.pivot[1], j.pivot[2]));
            jo.put("axis", arr(j.axis[0], j.axis[1], j.axis[2]));
            jo.put("sign", j.sign);
            jo.put("degPer", j.degPer);
            jo.put("zero", j.zero);
            jo.put("servo", j.servo);
            ja.put(jo);
        }
        o.put("joints", ja);
        if (nodes != null) {
            JSONArray na = new JSONArray();
            for (Node n : nodes) {
                JSONObject no = new JSONObject();
                no.put("name", n.name);
                no.put("parent", n.parent);
                no.put("type", n.type);
                JSONArray ms = new JSONArray();
                for (int i = 0; i < n.motors.length; i++) ms.put(n.motors[i]);
                JSONArray mu = new JSONArray();
                for (int i = 0; i < n.mult.length; i++) mu.put(n.mult[i]);
                no.put("motors", ms);
                no.put("mult", mu);
                no.put("axis", arr(n.axis[0], n.axis[1], n.axis[2]));
                no.put("pivot", arr(n.pivot[0], n.pivot[1], n.pivot[2]));
                no.put("point", arr(n.point[0], n.point[1], n.point[2]));
                na.put(no);
            }
            o.put("nodes", na);
        }
        JSONObject ow = new JSONObject();
        for (Map.Entry<String, Integer> e : owner.entrySet()) if (e.getValue().intValue() != 0) ow.put(e.getKey(), e.getValue().intValue());
        o.put("owner", ow);
        return o;
    }

    void save(File f) throws Exception {
        Files.write(f.toPath(), toJson().toString(2).getBytes(StandardCharsets.UTF_8));
    }

    static Rig load(File f) throws Exception {
        Rig r = new Rig();
        JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        r.up = o.optString("up", "Y");
        r.budget = o.optInt("budget", 250000);
        JSONArray ja = o.optJSONArray("joints");
        if (ja != null) {
            for (int i = 0; i < ja.length(); i++) {
                JSONObject jo = ja.getJSONObject(i);
                int m = jo.getInt("motor");
                if (m < 1 || m > N) continue;
                Joint j = r.joints[m];
                j.name = jo.optString("name", j.name);
                j.parent = jo.optInt("parent", 0);
                j.pivot = vec(jo.optJSONArray("pivot"), j.pivot);
                j.axis = vec(jo.optJSONArray("axis"), j.axis);
                j.sign = jo.optInt("sign", 1) < 0 ? -1 : 1;
                j.degPer = jo.optDouble("degPer", 0.1636);
                j.zero = jo.optInt("zero", 1500);
                j.servo = jo.optString("servo", "");
            }
        }
        JSONArray na = o.optJSONArray("nodes");
        if (na != null) {
            r.nodes = new ArrayList<Node>();
            for (int i = 0; i < na.length(); i++) {
                JSONObject no = na.getJSONObject(i);
                Node n = new Node();
                n.name = no.optString("name", "節點 " + (i + 1));
                n.parent = no.optInt("parent", 0);
                n.type = no.optString("type", "rot");
                JSONArray ms = no.optJSONArray("motors"), mu = no.optJSONArray("mult");
                int k = ms == null ? 0 : ms.length();
                n.motors = new int[k];
                n.mult = new int[k];
                for (int q = 0; q < k; q++) { n.motors[q] = ms.getInt(q); n.mult[q] = (mu != null && q < mu.length() && mu.getInt(q) < 0) ? -1 : 1; }
                n.axis = vec(no.optJSONArray("axis"), n.axis);
                n.pivot = vec(no.optJSONArray("pivot"), n.pivot);
                n.point = vec(no.optJSONArray("point"), n.point);
                r.nodes.add(n);
            }
        }
        JSONObject ow = o.optJSONObject("owner");
        if (ow != null) {
            java.util.Iterator<?> it = ow.keys();
            while (it.hasNext()) { String k = String.valueOf(it.next()); r.owner.put(k, Integer.valueOf(ow.getInt(k))); }
        }
        return r;
    }

    // ---------- 運動學 ----------

    static double[] identity() {
        double[] m = new double[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = s;
            }
        return r;
    }

    static double[] translation(double x, double y, double z) {
        double[] m = identity();
        m[3] = x; m[7] = y; m[11] = z;
        return m;
    }

    static double[] applyPoint(double[] m, double[] p) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = m[i * 4] * p[0] + m[i * 4 + 1] * p[1] + m[i * 4 + 2] * p[2] + m[i * 4 + 3];
        return r;
    }

    /** 繞「通過 pivot、方向 axis」的軸轉 angle 弧度(Rodrigues) */
    static double[] rotateAbout(double[] axis, double[] pivot, double angle) {
        double len = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (len < 1e-9) return identity();
        double x = axis[0] / len, y = axis[1] / len, z = axis[2] / len;
        double c = Math.cos(angle), s = Math.sin(angle), t = 1 - c;
        double[] m = identity();
        m[0] = t * x * x + c;     m[1] = t * x * y - s * z; m[2] = t * x * z + s * y;
        m[4] = t * x * y + s * z; m[5] = t * y * y + c;     m[6] = t * y * z - s * x;
        m[8] = t * x * z - s * y; m[9] = t * y * z + s * x; m[10] = t * z * z + c;
        for (int i = 0; i < 3; i++) m[i * 4 + 3] = pivot[i] - (m[i * 4] * pivot[0] + m[i * 4 + 1] * pivot[1] + m[i * 4 + 2] * pivot[2]);
        return m;
    }

    double angleRad(int motor, double pos) {
        Joint j = joints[motor];
        return Math.toRadians(j.sign * (pos - j.zero) * j.degPer);
    }

    /**
     * 每個零件群(index = 零件的 owner)目前的變換矩陣,index 0 = 身體 = 單位矩陣。
     * pos[m] = 馬達 m 的位置值(null = 全部在零位)。only > 0:只轉這一顆馬達(編輯時預覽用),其他在零位。
     */
    double[][] matrices(double[] pos, int only) {
        if (nodes != null) return nodeMatrices(pos, only);
        double[][] r = new double[N + 1][];
        r[0] = identity();
        boolean[] done = new boolean[N + 1];
        done[0] = true;
        for (int m = 1; m <= N; m++) resolve(m, pos, only, r, done, 0);
        return r;
    }

    private double[] resolve(int m, double[] pos, int only, double[][] r, boolean[] done, int depth) {
        if (done[m]) return r[m];
        Joint j = joints[m];
        int p = j.parent;
        double[] parent;
        if (p < 0 || p > N || p == m || depth > N) parent = identity();         // 設定錯誤(掛到自己、繞圈)就當作掛在身體上
        else parent = resolve(p, pos, only, r, done, depth + 1);
        double ang = 0;
        if (pos != null && (only <= 0 || only == m)) ang = angleRad(m, pos[m]);
        r[m] = mul(parent, rotateAbout(j.axis, j.pivot, ang));
        done[m] = true;
        return r[m];
    }

    /** 這個節點目前的 ψ(弧度):驅動它的馬達(乘上 ±1)的平均 */
    double nodeAngle(Node n, double[] pos, int only) {
        if (pos == null) return 0;
        double sum = 0;
        int cnt = 0;
        for (int k = 0; k < n.motors.length; k++) {
            int m = n.motors[k];
            if (m < 1 || m > N) continue;
            if (only > 0 && only != m) continue;
            sum += n.mult[k] * angleRad(m, pos[m]);
            cnt++;
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    double[] nodeLocal(Node n, double psi) {
        if ("arc".equals(n.type)) {
            double[] s2 = applyPoint(rotateAbout(n.axis, n.pivot, psi), n.point);
            return translation(s2[0] - n.point[0], s2[1] - n.point[1], s2[2] - n.point[2]);
        }
        return rotateAbout(n.axis, n.pivot, psi);
    }

    private double[][] nodeMatrices(double[] pos, int only) {
        int cnt = nodes.size();
        double[][] r = new double[cnt + 1][];
        r[0] = identity();
        boolean[] done = new boolean[cnt + 1];
        done[0] = true;
        for (int i = 1; i <= cnt; i++) resolveNode(i, pos, only, r, done, 0);
        return r;
    }

    private double[] resolveNode(int i, double[] pos, int only, double[][] r, boolean[] done, int depth) {
        if (done[i]) return r[i];
        Node n = nodes.get(i - 1);
        int p = n.parent;
        double[] parent;
        if (p < 0 || p > nodes.size() || p == i || depth > nodes.size()) parent = identity();
        else parent = resolveNode(p, pos, only, r, done, depth + 1);
        r[i] = mul(parent, nodeLocal(n, nodeAngle(n, pos, only)));
        done[i] = true;
        return r[i];
    }
}
