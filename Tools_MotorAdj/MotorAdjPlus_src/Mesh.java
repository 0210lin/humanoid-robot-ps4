import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 三角形網格(從 STL 讀進來)。tri 每個三角形 9 個 float:x0 y0 z0 x1 y1 z1 x2 y2 z2(單位:公釐,原始座標)。
 * 支援二進位與文字 STL;太多三角形時用「頂點叢集」降面數(同一格裡的頂點合併成一個)。
 */
public class Mesh {
    String name;
    float[] tri;
    int n;                        // 三角形數
    float[] min = new float[3], max = new float[3];

    Mesh(String name, float[] tri, int n) {
        this.name = name;
        this.tri = tri;
        this.n = n;
        bounds();
    }

    void bounds() {
        for (int k = 0; k < 3; k++) { min[k] = Float.MAX_VALUE; max[k] = -Float.MAX_VALUE; }
        for (int i = 0; i < n * 3; i++) {
            for (int k = 0; k < 3; k++) {
                float v = tri[i * 3 + k];
                if (v < min[k]) min[k] = v;
                if (v > max[k]) max[k] = v;
            }
        }
        if (n == 0) for (int k = 0; k < 3; k++) { min[k] = 0; max[k] = 0; }
    }

    // ---------- 讀 STL ----------

    static Mesh load(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (b.length >= 84) {
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            long cnt = bb.getInt(80) & 0xffffffffL;
            if (84 + cnt * 50 == b.length) {
                float[] t = new float[(int) cnt * 9];
                int p = 84;
                for (int i = 0; i < cnt; i++) {
                    p += 12;                                         // 法向量不用,自己算
                    for (int k = 0; k < 9; k++) { t[i * 9 + k] = bb.getFloat(p); p += 4; }
                    p += 2;
                }
                return new Mesh(name, t, (int) cnt);
            }
        }
        // 文字 STL:找每個 "vertex x y z"
        String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        float[] t = new float[9 * 1024];
        int cnt = 0;
        int pos = 0;
        int len = s.length();
        while (true) {
            int v = s.indexOf("vertex", pos);
            if (v < 0) break;
            pos = v + 6;
            float[] xyz = new float[3];
            for (int k = 0; k < 3; k++) {
                while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
                int e = pos;
                while (e < len && !Character.isWhitespace(s.charAt(e))) e++;
                xyz[k] = Float.parseFloat(s.substring(pos, e));
                pos = e;
            }
            if (cnt + 3 > t.length / 3) t = java.util.Arrays.copyOf(t, t.length * 2);
            System.arraycopy(xyz, 0, t, cnt * 3, 3);
            cnt++;
        }
        int tris = cnt / 3;
        return new Mesh(name, java.util.Arrays.copyOf(t, tris * 9), tris);
    }

    // ---------- 降面數(頂點叢集) ----------

    /** 降到大約 target 個三角形以下(不會少於 4 個) */
    Mesh decimate(int target) {
        if (n <= target || n <= 12) return this;
        float size = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        if (size <= 0) return this;
        int g = 512;
        Mesh best = this;
        for (int iter = 0; iter < 14; iter++) {
            Mesh m = cluster(size / g);
            best = m;
            if (m.n <= target || g <= 3) break;
            g = Math.max(3, (int) (g * 0.78));
        }
        return best;
    }

    Mesh cluster(float cell) {
        Map<Long, Integer> ids = new HashMap<Long, Integer>();
        int vcount = n * 3;
        int[] vid = new int[vcount];
        double[] sum = new double[Math.max(16, vcount) * 3];
        int[] cnt = new int[Math.max(16, vcount)];
        int next = 0;
        for (int i = 0; i < vcount; i++) {
            float x = tri[i * 3], y = tri[i * 3 + 1], z = tri[i * 3 + 2];
            long ix = (long) Math.floor((x - min[0]) / cell), iy = (long) Math.floor((y - min[1]) / cell), iz = (long) Math.floor((z - min[2]) / cell);
            long key = (ix << 42) ^ (iy << 21) ^ iz;
            Integer id = ids.get(key);
            if (id == null) { id = Integer.valueOf(next++); ids.put(key, id); }
            vid[i] = id.intValue();
            sum[id * 3] += x; sum[id * 3 + 1] += y; sum[id * 3 + 2] += z;
            cnt[id]++;
        }
        float[] out = new float[n * 9];
        int on = 0;
        for (int t = 0; t < n; t++) {
            int a = vid[t * 3], b = vid[t * 3 + 1], c = vid[t * 3 + 2];
            if (a == b || b == c || a == c) continue;
            int[] q = {a, b, c};
            for (int k = 0; k < 3; k++) {
                int id = q[k];
                out[on * 9 + k * 3] = (float) (sum[id * 3] / cnt[id]);
                out[on * 9 + k * 3 + 1] = (float) (sum[id * 3 + 1] / cnt[id]);
                out[on * 9 + k * 3 + 2] = (float) (sum[id * 3 + 2] / cnt[id]);
            }
            on++;
        }
        return new Mesh(name, java.util.Arrays.copyOf(out, on * 9), on);
    }
}
