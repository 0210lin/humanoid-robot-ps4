import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

/**
 * 快速新增幀:MotorAdj 的幀數上限是設定檔的 mmax,載入時才配置資料,原本要手動改設定檔再重新載入。
 * 這裡直接在記憶體裡把資料擴充:新增的幀內容 = 複製目前畫面上的這一幀,並切到第一個新幀讓你編輯。
 * 新增後要記得「儲存設定」(幀數會存進設定檔的 mmax)和「轉換設定」(輸出 motor.h)。
 *
 * Arduino 那邊不用改:motor.h 裡的 MOTOR_FRAME_MAX 會跟著變。限制是 Micro 的程式空間
 * (每幀 156 位元組),所以預設只加到 100 幀,留空間給之後的呼叫與邏輯。
 */
public class AddFrames {

    static final int HARD_LIMIT = 1000;     // 絕對上限(防呆)
    static final int DEFAULT_LIMIT = 100;   // 預設幀數上限(留空間給程式)
    static final int BYTES_PER_FRAME = 156; // Micro 上每一幀佔的空間:26 顆 × 3 個數字 × 2 位元組
    static JButton total;   // 按鈕本身就顯示「幀數 / 上限」
    static Object outer;

    static int limit() {
        return Background.readInt("frameLimit", DEFAULT_LIMIT, 1, HARD_LIMIT);
    }

    static int frameCount() throws Exception {
        Object ms = Plus.fld(outer, "MotorSet");
        return ((int[][][]) Plus.fld(ms, "MotorPosData")).length;
    }

    /** 依最近一次編譯的結果,推算「有 n 幀」時 Micro 程式空間會用到多少位元組(沒有編譯紀錄回傳 -1) */
    static int projectedBytes(int n) {
        int used = Background.readInt("flashUsed", 0, 0, 10000000);
        int frames = Background.readInt("flashFrames", 0, 0, 100000);
        if (used <= 0 || frames <= 0) return -1;
        return used + (n - frames) * BYTES_PER_FRAME;
    }

    static String projectedText(int n) {
        int b = projectedBytes(n);
        int max = Background.readInt("flashMax", 28672, 1000, 10000000);
        if (b < 0) return "";
        return "約 " + (b * 100 / max) + "%,還剩約 " + Math.max(0, max - b) + " 位元組給程式";
    }

    static void refreshLabel() {
        try {
            if (total != null && outer != null) {
                int n = frameCount();
                int lim = limit();
                total.setText("+ 新增幀(" + n + "/" + lim + ")");
                total.setForeground(n > lim ? java.awt.Color.RED : javax.swing.UIManager.getColor("Button.foreground"));
                String p = projectedText(n);
                total.setToolTipText("在最後多一幀,內容複製目前畫面上的這一幀,並切過去讓你編輯(新增後記得「儲存設定」「轉換設定」)。Micro 程式空間:" + (p.isEmpty() ? "按一次「更新到機器人」後會顯示" : p)
                        + "(每幀約 " + BYTES_PER_FRAME + " 位元組)");
            }
        } catch (Throwable t) {
            // 忽略
        }
    }

    /** 在最後新增 n 幀,內容複製目前畫面上的這一幀;切到第一個新幀 */
    static void add(int n) {
        try {
            if (n <= 0) return;
            Object ms = Plus.fld(outer, "MotorSet");
            int[][][] data = (int[][][]) Plus.fld(ms, "MotorPosData");
            int[][] tmp = (int[][]) Plus.fld(ms, "MotorPosDataTmp");   // 畫面上目前的內容
            int oldMax = data.length;
            int newMax = oldMax + n;
            if (newMax > HARD_LIMIT) {
                JOptionPane.showMessageDialog(null, "幀數最多 " + HARD_LIMIT + " 幀。", "新增幀", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (newMax > limit()) {
                String p = projectedText(newMax);
                int r = JOptionPane.showConfirmDialog(null,
                        "新增後會有 " + newMax + " 幀,超過目前的上限 " + limit() + " 幀。\n"
                        + "上限是為了留 Micro 的空間給你之後寫的呼叫與邏輯。\n"
                        + (p.isEmpty() ? "" : "推算新增後:" + p + "。\n")
                        + "空間不夠時,「更新到機器人」編譯會失敗。\n\n還是要新增嗎?",
                        "新增幀", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) return;
            }
            int motors = data[0].length, params = data[0][0].length;
            int[][][] nd = new int[newMax][motors][params];
            for (int f = 0; f < oldMax; f++) {
                for (int m = 0; m < motors; m++) {
                    System.arraycopy(data[f][m], 0, nd[f][m], 0, params);
                }
            }
            for (int f = oldMax; f < newMax; f++) {
                for (int m = 0; m < motors; m++) {
                    System.arraycopy(tmp[m], 0, nd[f][m], 0, params);
                }
            }
            setField(ms, "MotorPosData", nd);
            setField(ms, "MotorFrameMax", Integer.valueOf(newMax));
            int board = ((Integer) Plus.fld(ms, "BoardType")).intValue();
            int[][] boundary = (int[][]) Plus.fld(ms, "MotorDataBoundary");
            boundary[board][0] = newMax;          // 0 = BT_IDX_FRAMEMAX:「寫入」「讀出」檢查幀號用的上限

            // 切到第一個新幀,讓你直接編輯
            JFormattedTextField t = (JFormattedTextField) Plus.fld(outer, "FrameCntText");
            t.setText(String.valueOf(oldMax));
            java.lang.reflect.Method m = outer.getClass().getDeclaredMethod("position_upgradeFrame");
            m.setAccessible(true);
            m.invoke(outer);
            refreshLabel();
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(null, "新增幀失敗:" + t, "新增幀", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void setField(Object o, String name, Object v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    /** panel:「馬達參數」分頁的面板 */
    static void install(Container panel) throws Exception {
        outer = Plus.outer();
        JButton write = Plus.findExactIn(panel, "寫入", "Write", "write");
        if (outer == null || write == null) return;

        // 放在左下角的空白處(馬達 19 的下面、「捲軸微調」的上面),上下都留空間,不會擠在一起
        int x = write.getX();
        int y = 326;
        int h = 28;

        total = new JButton("+ 新增幀");
        total.setBounds(x, y, 190, h);
        total.setMargin(new java.awt.Insets(0, 2, 0, 2));
        total.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { add(1); }
        });
        panel.add(total);
        refreshLabel();

        // 載入設定等動作會重新配置資料,所以定時更新按鈕上的「幀數/上限」
        new javax.swing.Timer(1000, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { refreshLabel(); }
        }).start();
        panel.repaint();
    }
}
