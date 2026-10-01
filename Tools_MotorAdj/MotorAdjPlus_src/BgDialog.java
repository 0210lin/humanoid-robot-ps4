import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.TransferHandler;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 「背景設定」視窗:把 JPG / PNG 圖片拖進來(或按「選擇圖片…」)就能設成背景,
 * 上方有即時預覽,主視窗也會同時變化;按「取消」會還原。
 * 不同比例的圖片可以選顯示方式(蓋滿 / 完整顯示 / 拉伸),並調整縮放與位置
 * (滑桿,或直接在預覽上拖曳、滾輪縮放)。
 * 選到的圖片會複製一份到 Tools_MotorAdj/background 資料夾,原檔刪掉也不會不見。
 */
public class BgDialog {

    static final String[] KEYS = {"bgImage", "bgColor", "bgDim", "bgMode", "bgZoom", "bgX", "bgY"};
    static final Map<String, String> orig = new LinkedHashMap<String, String>();
    static final List<File> created = new ArrayList<File>();
    static Background.Pane pv;
    static JLabel hint;
    static JSlider dimS, zoomS, xS, yS;
    static JComboBox<String> modeBox;
    static BufferedImage pvImg;
    static String pvImgPath = "";
    static boolean busy = false;   // 程式自己改滑桿時,不要再觸發一次更新

    static final String[] MODE_NAMES = {"蓋滿視窗(會裁切)", "完整顯示(留邊)", "拉伸填滿(會變形)"};
    static final String[] MODE_KEYS = {"cover", "fit", "stretch"};

    static File bgDir() {
        File d = new File(Settings.dir, "background");
        d.mkdirs();
        return d;
    }

    static boolean isImage(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    /** 設成背景:先確認讀得出來,再複製一份到 background 資料夾。換新圖時位置與縮放回到預設 */
    static boolean setImage(java.awt.Component owner, File f) {
        try {
            if (!isImage(f) || ImageIO.read(f) == null) {
                JOptionPane.showMessageDialog(owner, "這個檔案讀不出來。請使用 JPG、PNG、GIF 或 BMP 圖片。", "背景設定", JOptionPane.WARNING_MESSAGE);
                return false;
            }
            String n = f.getName();
            String ext = n.substring(n.lastIndexOf('.'));
            File copy = new File(bgDir(), "bg_" + System.currentTimeMillis() + ext.toLowerCase());
            Files.copy(f.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            created.add(copy);
            Settings.p.setProperty("bgImage", copy.getAbsolutePath());
            resetAdjust();
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "無法讀取圖片:" + e.getMessage(), "背景設定", JOptionPane.WARNING_MESSAGE);
            return false;
        }
    }

    /** 縮放與位置回到預設(100%、置中) */
    static void resetAdjust() {
        busy = true;
        zoomS.setValue(100);
        xS.setValue(50);
        yS.setValue(50);
        busy = false;
        syncFromSliders();
    }

    static void syncFromSliders() {
        Settings.p.setProperty("bgDim", String.valueOf(dimS.getValue()));
        Settings.p.setProperty("bgZoom", String.valueOf(zoomS.getValue()));
        Settings.p.setProperty("bgX", String.valueOf(xS.getValue()));
        Settings.p.setProperty("bgY", String.valueOf(yS.getValue()));
        Settings.p.setProperty("bgMode", MODE_KEYS[Math.max(0, modeBox.getSelectedIndex())]);
        refresh();
    }

    /** 依目前設定更新預覽與主視窗 */
    static void refresh() {
        String path = Settings.raw("bgImage");
        if (!path.equals(pvImgPath)) {
            pvImg = null;
            pvImgPath = path;
            try {
                if (!path.isEmpty() && new File(path).exists()) pvImg = ImageIO.read(new File(path));
            } catch (Exception e) { }
        }
        pv.img = pvImg;
        pv.scaled = null;
        Background.load(pv);
        pv.repaint();
        hint.setVisible(pvImg == null && pv.color == null);
        boolean adj = pvImg != null && !"stretch".equals(pv.mode);
        zoomS.setEnabled(adj);
        xS.setEnabled(adj);
        yS.setEnabled(adj);
        Background.apply();
    }

    static void restoreOriginal() {
        for (Map.Entry<String, String> e : orig.entrySet()) Settings.p.setProperty(e.getKey(), e.getValue());
    }

    /** 一列滑桿:說明文字、滑桿、目前數值 */
    static JSlider addSlider(JPanel panel, int row, String label, int min, int max, int val, final String unit) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 12, 2, 12);
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), c);
        final JSlider s = new JSlider(min, max, val);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(s, c);
        final JLabel v = new JLabel(val + unit);
        v.setPreferredSize(new Dimension(56, 20));
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(v, c);
        s.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                v.setText(s.getValue() + unit);
                if (!busy) syncFromSliders();
            }
        });
        return s;
    }

    static void show() {
        orig.clear();
        for (String k : KEYS) orig.put(k, Settings.raw(k));
        created.clear();
        pvImgPath = "";
        pvImg = null;

        final JDialog d = new JDialog(Background.mainFrame(), "背景設定", true);

        // ---- 預覽(可以直接拖曳圖片、用滾輪縮放)----
        pv = new Background.Pane() {
            public Dimension getPreferredSize() { return new Dimension(520, 330); }
        };
        pv.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        hint = new JLabel("<html><div style='text-align:center'>把 JPG / PNG 圖片拖進這個視窗<br>或按下面的「選擇圖片…」</div></html>", JLabel.CENTER);
        pv.setLayout(new BorderLayout());
        pv.add(hint, BorderLayout.CENTER);

        // ---- 按鈕列 ----
        JButton pick = new JButton("選擇圖片…");
        JButton clearImg = new JButton("清除圖片");
        JButton pickColor = new JButton("選擇純色…");
        JButton clearColor = new JButton("清除純色");
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.add(pick);
        row1.add(clearImg);
        row1.add(pickColor);
        row1.add(clearColor);

        // ---- 顯示方式 + 重設 ----
        modeBox = new JComboBox<String>(MODE_NAMES);
        String curMode = Settings.raw("bgMode");
        for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i].equals(curMode)) modeBox.setSelectedIndex(i);
        JButton resetAdj = new JButton("重設位置與縮放");
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.add(new JLabel("顯示方式:"));
        row2.add(modeBox);
        row2.add(resetAdj);

        // ---- 滑桿 ----
        JPanel sliders = new JPanel(new GridBagLayout());
        dimS = addSlider(sliders, 0, "圖片遮罩", 0, 100, Background.readInt("bgDim", 75, 0, 100), "%");
        zoomS = addSlider(sliders, 1, "縮放", 100, 300, Background.readInt("bgZoom", 100, 100, 300), "%");
        xS = addSlider(sliders, 2, "水平位置", 0, 100, Background.readInt("bgX", 50, 0, 100), "");
        yS = addSlider(sliders, 3, "垂直位置", 0, 100, Background.readInt("bgY", 50, 0, 100), "");

        JLabel tip = new JLabel("<html>圖片比例和視窗不同時:「蓋滿」會裁掉多出來的部分,可以拖曳預覽或調位置決定留哪一塊;"
                + "「完整顯示」整張都看得到,兩側會留邊(留邊顏色用「選擇純色」設定)。滾輪可以縮放。遮罩越高,圖片越淡、文字越清楚。</html>");
        tip.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JButton ok = new JButton("確定");
        JButton cancel = new JButton("取消");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btns.add(ok);
        btns.add(cancel);

        JPanel mid = new JPanel();
        mid.setLayout(new javax.swing.BoxLayout(mid, javax.swing.BoxLayout.Y_AXIS));
        row1.setAlignmentX(0f);
        row2.setAlignmentX(0f);
        sliders.setAlignmentX(0f);
        tip.setAlignmentX(0f);
        mid.add(row1);
        mid.add(row2);
        mid.add(sliders);
        mid.add(tip);

        JPanel south = new JPanel(new BorderLayout());
        south.add(mid, BorderLayout.CENTER);
        south.add(btns, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        center.add(pv, BorderLayout.CENTER);

        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(center, BorderLayout.CENTER);
        d.getContentPane().add(south, BorderLayout.SOUTH);

        // ---- 拖放圖片 ----
        TransferHandler th = new TransferHandler() {
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                try {
                    Transferable t = s.getTransferable();
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        if (isImage(f)) return setImage(d, f);
                    }
                    JOptionPane.showMessageDialog(d, "請拖入 JPG、PNG、GIF 或 BMP 圖片。", "背景設定", JOptionPane.WARNING_MESSAGE);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return false;
            }
        };
        ((JComponent) d.getContentPane()).setTransferHandler(th);
        center.setTransferHandler(th);
        pv.setTransferHandler(th);

        // ---- 在預覽上拖曳移動、滾輪縮放 ----
        java.awt.event.MouseAdapter drag = new java.awt.event.MouseAdapter() {
            int sx, sy, startPx, startPy;
            public void mousePressed(java.awt.event.MouseEvent e) {
                sx = e.getX();
                sy = e.getY();
                startPx = xS.getValue();
                startPy = yS.getValue();
            }
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (pv.img == null || "stretch".equals(pv.mode)) return;
                int w = pv.getWidth(), h = pv.getHeight();
                int[] r = pv.imageRect(w, h);
                int slackX = Math.abs(r[2] - w), slackY = Math.abs(r[3] - h);
                busy = true;
                if (slackX > 0) {
                    double dir = r[2] > w ? -1 : 1;      // 圖比視窗大:往右拖 = 位置值變小
                    xS.setValue((int) Math.round(Math.max(0, Math.min(100, startPx + dir * (e.getX() - sx) * 100.0 / slackX))));
                }
                if (slackY > 0) {
                    double dir = r[3] > h ? -1 : 1;
                    yS.setValue((int) Math.round(Math.max(0, Math.min(100, startPy + dir * (e.getY() - sy) * 100.0 / slackY))));
                }
                busy = false;
                syncFromSliders();
            }
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                if (pv.img == null || "stretch".equals(pv.mode)) return;
                zoomS.setValue(zoomS.getValue() - e.getWheelRotation() * 10);
            }
        };
        pv.addMouseListener(drag);
        pv.addMouseMotionListener(drag);
        pv.addMouseWheelListener(drag);

        // ---- 動作 ----
        pick.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new FileNameExtensionFilter("圖片 (JPG, PNG, GIF, BMP)", "jpg", "jpeg", "png", "gif", "bmp"));
                if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) setImage(d, fc.getSelectedFile());
            }
        });
        clearImg.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Settings.p.setProperty("bgImage", "");
                refresh();
            }
        });
        pickColor.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Color cur = Background.parseColor(Settings.raw("bgColor"));
                Color c = JColorChooser.showDialog(d, "背景顏色", cur != null ? cur : Color.WHITE);
                if (c != null) {
                    Settings.p.setProperty("bgColor", String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
                    refresh();
                }
            }
        });
        clearColor.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Settings.p.setProperty("bgColor", "");
                refresh();
            }
        });
        modeBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!busy) syncFromSliders();
            }
        });
        resetAdj.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                resetAdjust();
            }
        });
        ok.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    Settings.save();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(d, "無法儲存:" + ex.getMessage());
                    return;
                }
                // 清掉沒用到的圖片副本(這次選過但最後沒採用的,以及被取代的舊圖)
                String finalImg = Settings.raw("bgImage");
                for (File f : created) if (!f.getAbsolutePath().equals(finalImg)) f.delete();
                String origImg = orig.get("bgImage");
                if (!origImg.isEmpty() && !origImg.equals(finalImg) && new File(origImg).getParentFile() != null
                        && new File(origImg).getParentFile().equals(bgDir())) new File(origImg).delete();
                d.dispose();
            }
        });
        cancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelAndClose(d);
            }
        });
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancelAndClose(d);
            }
        });
        d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        refresh();
        d.pack();
        d.setLocationRelativeTo(Background.mainFrame());
        d.setVisible(true);
    }

    static void cancelAndClose(JDialog d) {
        restoreOriginal();
        for (File f : created) f.delete();
        refresh();
        d.dispose();
    }
}
