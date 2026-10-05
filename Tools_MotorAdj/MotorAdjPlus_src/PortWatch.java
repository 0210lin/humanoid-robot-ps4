import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import com.fazecast.jSerialComm.SerialPort;

/**
 * 串口清單自動更新:MotorAdj 只在啟動時抓一次串口,之後才插上 Micro 板,清單裡不會有。
 * 這裡每 2 秒看一次目前有哪些串口,有變就更新下拉選單(連線中不動,避免編號錯位)。
 * 原版的「串口連接」按鈕是用啟動時存下來的串口陣列(val$ports)開的,所以那個陣列也一起換掉。
 */
public class PortWatch {
    static volatile boolean busy = false;
    static javax.swing.Timer timer;

    static void install() {
        timer = new javax.swing.Timer(2000, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { poll(); }
        });
        timer.start();
    }

    static void poll() {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final SerialPort[] ports = SerialPort.getCommPorts();
                    final String[] names = new String[ports.length];
                    for (int i = 0; i < ports.length; i++) names[i] = ports[i].getSystemPortName();
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            try { apply(ports, names); } catch (Throwable t) { t.printStackTrace(); }
                        }
                    });
                } catch (Throwable t) {
                    // 抓不到就下次再試
                } finally {
                    busy = false;
                }
            }
        }, "PortWatch").start();
    }

    static void collect(Component c, List<Object[]> out) {
        if (c instanceof JButton) {
            for (java.awt.event.ActionListener l : ((JButton) c).getActionListeners()) {
                try {
                    Field f = l.getClass().getDeclaredField("val$ports");
                    f.setAccessible(true);
                    out.add(new Object[] {l, f});
                } catch (NoSuchFieldException e) {
                    // 不是這個
                }
            }
        }
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) collect(k, out);
    }

    /** 回傳有沒有更新(測試用) */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean apply(SerialPort[] ports, String[] names) throws Exception {
        Object outer = Plus.outer();
        if (outer == null) return false;
        Object ms = Plus.fld(outer, "MotorSet");
        if (Boolean.TRUE.equals(Plus.fld(ms, "SerialPort_isOpen"))) return false;   // 連線中不動

        String[] cur = (String[]) Plus.fld(ms, "UartPortStr");
        String[] now = new String[names.length + 1];
        now[0] = "None";
        System.arraycopy(names, 0, now, 1, names.length);
        if (Arrays.equals(cur, now)) return false;

        int old = ((Integer) Plus.fld(ms, "UartPort")).intValue();
        String keep = (old > 0 && old < cur.length) ? cur[old] : null;
        int idx = 0;
        if (keep != null) for (int i = 1; i < now.length; i++) if (now[i].equals(keep)) idx = i;

        // 1) 資料
        AddFrames.setField(ms, "UartPortStr", now);
        AddFrames.setField(ms, "UartPort", Integer.valueOf(idx));
        // 2) 「串口連接」按鈕用的陣列
        List<Object[]> ls = new ArrayList<Object[]>();
        for (Frame f : Frame.getFrames()) collect(f, ls);
        for (Object[] o : ls) ((Field) o[1]).set(o[0], ports);
        // 3) 下拉選單
        JComboBox box = (JComboBox) Plus.fld(outer, "comboBoxUartPort");
        box.setModel(new DefaultComboBoxModel(now));
        box.setSelectedIndex(idx);
        AddFrames.setField(ms, "UartPort", Integer.valueOf(idx));   // 選單的事件可能又改過
        box.repaint();
        System.out.println("串口清單已更新:" + Arrays.toString(now));
        return true;
    }
}
