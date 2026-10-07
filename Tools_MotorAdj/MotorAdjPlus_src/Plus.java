import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * MotorAdj 外掛啟動器:啟動原本的 MotorAdj,並在「轉換設定」按鈕下方加一顆
 * 「更新到機器人」按鈕。按下後:中斷串口 -> 複製 motor.h -> 編譯 -> 燒錄到 Arduino Micro。
 * 原本的 MotorAdj_release.jar 完全沒有修改。
 */
public class Plus {
    static final String FQBN = "arduino:avr:micro";

    static File toolDir;   // Tools_MotorAdj
    static File root;      // 專案根目錄(HumanoidRobot_PS4)
    static JDialog logDlg;
    static JTextArea logArea;
    static JButton updateBtn;

    public static void main(String[] args) throws Exception {
        File jar = new File(Plus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        toolDir = jar.getParentFile();
        if (System.getProperty("plus.root") != null) {   // 測試用
            toolDir = new File(System.getProperty("plus.root"), "Tools_MotorAdj");
        }
        root = toolDir.getParentFile();
        Settings.init(toolDir, root);

        main.MotorAdjust.main(args);

        // 套用外觀(排在 MotorAdj 建立視窗之後,在畫面執行緒執行)
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                Theme.apply(Theme.name());
            }
        });

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                final javax.swing.Timer[] t = new javax.swing.Timer[1];
                t[0] = new javax.swing.Timer(500, new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (inject()) {
                            t[0].stop();
                        }
                    }
                });
                t[0].start();
            }
        });
    }

    // ---------- 在 MotorAdj 視窗加按鈕 ----------

    static JButton findButton(Component c, String... keys) {
        if (c instanceof JButton) {
            String text = ((JButton) c).getText();
            if (text != null) {
                for (String k : keys) {
                    if (text.toLowerCase().contains(k.toLowerCase())) {
                        return (JButton) c;
                    }
                }
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                JButton r = findButton(k, keys);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 文字完全相同的按鈕(findButton 是「包含」,會把「串口斷開」之類也找出來) */
    static JButton findExactIn(Component c, String... texts) {
        if (c instanceof JButton) {
            String t = ((JButton) c).getText();
            for (String x : texts) {
                if (x.equals(t)) {
                    return (JButton) c;
                }
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                JButton r = findExactIn(k, texts);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    static JButton findExact(String... texts) {
        for (Frame f : Frame.getFrames()) {
            JButton b = findExactIn(f, texts);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    static JButton findInAllWindows(String... keys) {
        for (Frame f : Frame.getFrames()) {
            JButton b = findButton(f, keys);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    static boolean inject() {
        JButton export = findInAllWindows("轉換設定", "export");
        if (export == null) {
            return false;
        }
        Container p = export.getParent();
        updateBtn = new JButton("更新到機器人(燒錄)");
        updateBtn.setBounds(export.getX() - 113, export.getY() + 32, export.getWidth() + 113, export.getHeight());
        updateBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                triggerUpdate((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0);
            }
        });
        updateButtons.add(updateBtn);
        p.add(updateBtn);

        // 「路徑設定…」按鈕:放在「載入設定」按鈕的正下方
        JButton load = findInAllWindows("載入設定");
        JButton setBtn = new JButton("路徑設定…");
        int sx = (load != null) ? load.getX() : export.getX() - 228;
        setBtn.setBounds(sx, export.getY() + 32, 102, export.getHeight());
        setBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (License.require("路徑設定")) Settings.showDialog(null);
            }
        });
        p.add(setBtn);

        // 「鏡像」按鈕:放在左下角(「鏡像」分頁設定要鏡像的馬達配對)
        JButton mirrorBtn = new JButton("鏡像(畫面)");
        mirrorBtn.setBounds(20, export.getY() + 32, 120, export.getHeight());
        mirrorBtn.setToolTipText("依「鏡像」分頁的設定,把畫面上這一幀變成鏡像(不寫入)");
        mirrorBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                MirrorTab.onMirrorButton();
            }
        });
        p.add(mirrorBtn);

        // 「背景…」按鈕:放在「設定」分頁(串口連接那一頁)的左下方
        try {
            JButton conn = findExact("串口連接", "Connect");
            if (conn != null) {
                Container sp = conn.getParent();
                bgButton = new JButton("背景…");
                final JButton bgBtn = bgButton;
                bgBtn.setBounds(conn.getX(), 400, 234, 27);
                bgBtn.setToolTipText("自訂背景:拖入 JPG / PNG 圖片,有預覽");
                bgBtn.addActionListener(new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        BgDialog.show();
                    }
                });
                sp.add(bgBtn);

                // 外觀選單:放在「背景…」的下面,選了馬上換,並記住
                final String[] themeKeys = {"light", "dark", "tech", "classic"};
                final String[] themeNames = {"淺色(預設)", "深色", "科技風", "原本的樣子"};
                themeLabelRef = new javax.swing.JLabel("外觀");
                javax.swing.JLabel themeLabel = themeLabelRef;
                themeLabel.setBounds(conn.getX(), 438, 60, 27);
                sp.add(themeLabel);
                final javax.swing.JComboBox<String> themeBox = new javax.swing.JComboBox<String>(themeNames);
                themeBoxRef = themeBox;
                for (int i = 0; i < themeKeys.length; i++) {
                    if (themeKeys[i].equals(Theme.name())) themeBox.setSelectedIndex(i);
                }
                themeBox.setBounds(conn.getX() + 66, 438, 168, 27);
                themeBox.addActionListener(new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        String key = themeKeys[Math.max(0, themeBox.getSelectedIndex())];
                        if (syncing || key.equals(Theme.current)) return;
                        Settings.p.setProperty("theme", key.equals("light") ? "" : key);
                        try { Settings.save(); } catch (Exception ex) { ex.printStackTrace(); }
                        Theme.apply(key);
                    }
                });
                sp.add(themeBox);
                sp.repaint();

                // 右下角的三張卡片:機器人狀態、Micro 空間用量、環境檢查
                try {
                    StatusCards.install(sp);
                } catch (Throwable t2) {
                    t2.printStackTrace();
                }

                // 「設定」分頁重新排版:隱藏用不到的感測器,其餘分成「連線」「馬達位置範圍」「外觀」三個區塊
                try {
                    SettingsLayout.install(sp);
                } catch (Throwable t3) {
                    t3.printStackTrace();
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 馬達捲軸:顯示箭頭(±1),並設定點軌道一次 ±N(預設 30)
        try {
            Steps.install(p);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 「復原寫入」:不小心按到「寫入」時可以反悔
        try {
            UndoWrite.install(p);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 「+ 新增幀」:快速增加幀數
        try {
            AddFrames.install(p);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 人偶模式:進入時維持原本的勾選與顏色,並標示被放鬆的那顆
        try {
            DummyMode.install(p);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 每顆馬達各自的位置範圍(預設 ±900)
        try {
            Limits.install(p);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        p.repaint();

        try {
            License.install(p);   // 「教師解鎖…」:教師功能需要老師簽發、綁定這台電腦的金鑰檔
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            QuickFrames.install(p);   // quick test of two frames (top right of the motor tab)
        } catch (Throwable tq) {
            tq.printStackTrace();
        }
        try {
            FrameIO.install(p);   // 批量匯出 / 批量載入 .frame
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            MotorSim.install(p);   // 馬達參數分頁右下角的機器人 3D 模擬
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            hookDisableAll();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            CodeTab.install(new File(Settings.sketch(), "custom.ino"));
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            MirrorTab.install();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            SequenceTab.install();
        } catch (Throwable ts) {
            ts.printStackTrace();
        }
        try {
            RobotTab.install();   // 3D robot tab (STL model + joint setup)   // 「動作序列」:依序打 SetFrameRun(幀, 毫秒); 就能播出動作(模擬或真的送給機器人)
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            QuickFrames.installEverywhere();   // quick go-to-frame on every tab
        } catch (Throwable tq2) {
            tq2.printStackTrace();
        }
        try {
            PortWatch.install();   // 串口清單自動更新(之後才插上板子也抓得到)
        } catch (Throwable t) {
            t.printStackTrace();
        }
        Background.apply();   // 所有分頁都建好之後,套用自訂背景
        Center.install();         // 固定座標的分頁置中(視窗放大時比例不變)
        Center.maximizeAtStart(); // 啟動時最大化
        try {
            DefaultConfig.autoLoad();   // 預設設定檔:啟動後自動載入
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return true;
    }

    /** MotorAdjust 的實例(從「開啟馬達」按鈕的原始監聽器取得) */
    static Object outer() throws Exception {
        JButton en = findInAllWindows("開啟馬達", "enable all");
        if (en == null) {
            return null;
        }
        for (java.awt.event.MouseListener l : en.getMouseListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) {
                java.lang.reflect.Field f = l.getClass().getDeclaredField("this$0");
                f.setAccessible(true);
                return f.get(l);
            }
        }
        return null;
    }

    static final java.util.List<JButton> updateButtons = new java.util.ArrayList<JButton>();
    static JButton bgButton;                       // 「設定」分頁的「背景…」按鈕
    static javax.swing.JLabel themeLabelRef;       // 「外觀」文字
    static javax.swing.JComboBox<String> themeBoxRef;   // 外觀下拉選單

    static boolean syncing = false;

    /** 外觀被別的方式改變時(例如「路徑設定」視窗),讓「設定」分頁的外觀選單顯示一致 */
    static void syncThemeBox(String name) {
        if (themeBoxRef == null) return;
        String[] keys = {"light", "dark", "tech", "classic"};
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(name) && themeBoxRef.getSelectedIndex() != i) {
                syncing = true;
                themeBoxRef.setSelectedIndex(i);
                syncing = false;
            }
        }
    }

    /** 兩個「更新到機器人」按鈕(馬達參數分頁、動作程式分頁)共用 */
    static void triggerUpdate() {
        triggerUpdate(false);
    }

    /** force = true:就算沒有變動也重新編譯並上傳(按住 Shift 再按按鈕) */
    static void triggerUpdate(final boolean force) {
        for (JButton b : updateButtons) {
            b.setEnabled(false);
        }
        final java.util.Map<String, String> edits = CodeTab.snapshotEdits();
        new Thread(new Runnable() {
            public void run() {
                try {
                    runUpdate(edits, force);
                } catch (Throwable t) {
                    log("[錯誤] " + t);
                } finally {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            for (JButton b : updateButtons) {
                                b.setEnabled(true);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- 修正「關閉馬達」:只關閉馬達,不把關閉狀態寫進該幀的資料 ----------

    static void hookDisableAll() throws Exception {
        final JButton b = findInAllWindows("關閉馬達", "disable all");
        if (b == null) {
            return;
        }
        java.awt.event.MouseListener orig = null;
        for (java.awt.event.MouseListener l : b.getMouseListeners()) {
            if (l.getClass().getName().startsWith("main.MotorAdjust$")) {
                orig = l;
            }
        }
        if (orig == null) {
            return;
        }
        java.lang.reflect.Field f = orig.getClass().getDeclaredField("this$0");
        f.setAccessible(true);
        final Object outer = f.get(orig);
        b.removeMouseListener(orig);
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (!b.isEnabled()) {
                    return;
                }
                try {
                    disableAllKeepData(outer);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    static Object fld(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void disableAllKeepData(Object outer) throws Exception {
        Object ms = fld(outer, "MotorSet");
        int frame = ((Integer) fld(ms, "MotorFramCnt")).intValue();
        int n = ((Integer) fld(ms, "MotorCountPerFrameMax")).intValue();
        int[][][] data = (int[][][]) fld(ms, "MotorPosData");
        int[][] tmp = (int[][]) fld(ms, "MotorPosDataTmp");

        java.lang.reflect.Method send = outer.getClass().getDeclaredMethod("uart_sendMotorCmd", int.class, int.class);
        send.setAccessible(true);
        java.lang.reflect.Method en = outer.getClass().getDeclaredMethod("enable_motor", boolean.class);
        en.setAccessible(true);

        // 先記住原本的「啟用」值
        int[] saveData = new int[n];
        int[] saveTmp = new int[n];
        for (int i = 0; i < n; i++) {
            saveData[i] = data[frame][i][0];
            saveTmp[i] = tmp[i][0];
        }

        // 暫時設成 0,只為了把「關閉」指令送給機器人
        for (int i = 0; i < n; i++) {
            data[frame][i][0] = 0;
            tmp[i][0] = 0;
            send.invoke(outer, frame, i);
        }
        en.invoke(outer, false);   // 畫面顯示成關閉

        // 還原資料:不寫入。之後按「讀出」就會讀回原本的設定並恢復動作
        for (int i = 0; i < n; i++) {
            data[frame][i][0] = saveData[i];
            tmp[i][0] = saveTmp[i];
        }
    }

    // ---------- 記錄視窗 ----------

    static void showLog() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (logDlg == null) {
                    logDlg = new JDialog((Frame) null, "更新到機器人", false);
                    logArea = new JTextArea();
                    logArea.setEditable(false);
                    logDlg.add(new JScrollPane(logArea));
                    logDlg.setSize(760, 420);
                    logDlg.setLocationRelativeTo(null);
                }
                logArea.setText("");
                logDlg.setVisible(true);
                logDlg.toFront();
            }
        });
    }

    static void closeLogLater(final int delayMs) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                javax.swing.Timer t = new javax.swing.Timer(delayMs, new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (logDlg != null) {
                            logDlg.setVisible(false);
                        }
                    }
                });
                t.setRepeats(false);
                t.start();
            }
        });
    }

    static void log(final String s) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (logArea != null) {
                    logArea.append(s + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }
        });
    }

    // ---------- 更新流程 ----------

    /** 程式資料夾內容的指紋(檔名 + 內容),用來判斷和上次上傳的是否一樣 */
    static String sketchHash(File sketch) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        md.update(sketch.getAbsolutePath().getBytes("UTF-8"));
        File[] fs = sketch.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String n) {
                String l = n.toLowerCase();
                return l.endsWith(".ino") || l.endsWith(".h") || l.endsWith(".hpp") || l.endsWith(".c") || l.endsWith(".cpp");
            }
        });
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (File f : fs) {
                md.update(f.getName().getBytes("UTF-8"));
                md.update(Files.readAllBytes(f.toPath()));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static void runUpdate(java.util.Map<String, String> edits, boolean force) throws Exception {
        showLog();
        Thread.sleep(300);
        log("=== 更新到機器人 ===");

        File cli = Settings.cli();
        File sketch = Settings.sketch();
        File src = Settings.motorSrc();
        File dst = new File(sketch, "motor.h");
        if (!cli.exists()) {
            log("[錯誤] 找不到 arduino-cli:" + cli);
            log("請先執行「安裝.bat」,或在「路徑設定」指定位置。");
            return;
        }
        if (!sketch.exists()) {
            log("[錯誤] 找不到 Arduino 程式資料夾:" + sketch);
            log("請在「路徑設定」指定位置。");
            return;
        }

        // 2. 複製 motor.h(只有 Tools_MotorAdj 裡的比較新才複製;直接輸出到 Micro_Robot 的不會被蓋掉)
        if (src.exists() && src.lastModified() > dst.lastModified()) {
            File bak = new File(root, "backup");
            bak.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            Files.copy(dst.toPath(), new File(bak, "motor.h.bak_" + ts).toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log("已複製新的 motor.h(舊的已備份到 backup)");
        } else {
            log("使用 " + dst + "(沒有比較新的 " + src + ")");
        }

        // 2.5 把「動作程式」分頁的修改寫回 custom.ino
        File customBackup = null;
        if (!edits.isEmpty()) {
            try {
                customBackup = CodeTab.applyToFile(edits, root);
                log("已把 " + edits.size() + " 段修改寫入 custom.ino(舊版已備份到 backup)");
            } catch (Exception ex) {
                log("[錯誤] 寫入 custom.ino 失敗:" + ex.getMessage());
                return;
            }
        }

        // 2.8 沒有變動就不用上傳(motor.h、custom.ino 等內容和上次成功上傳的一樣)
        final String hash = sketchHash(sketch);
        if (!force && hash.equals(Settings.raw("lastUpload"))) {
            log("\nmotor.h 和 custom.ino 都沒有變動,程式和機器人上次上傳的一樣,不用重新上傳。");
            log("(要強制重新上傳:按住 Shift 再按「更新到機器人」)");
            return;   // 沒有上傳,視窗保持開著(不自動關閉)
        }

        // 3. 編譯
        log("\n[編譯中...]");
        if (run(cli.getPath(), "compile", "-b", FQBN, sketch.getPath()) != 0) {
            log("\n[錯誤] 編譯失敗,沒有燒錄。");
            if (customBackup != null) {
                CodeTab.restore(customBackup);
                log("已把 custom.ino 還原成修改前的樣子。你的修改還留在「動作程式」分頁,請修正後再按一次。");
            }
            return;
        }
        if (customBackup != null) {
            CodeTab.reloadLater();   // 修改已經存進 custom.ino,重新載入分頁
        }

        // 4. 中斷串口(序列埠一次只能一個程式用),然後找序列埠並燒錄
        final JButton dis = findInAllWindows("串口斷開", "disconnect");
        if (dis != null && dis.isEnabled()) {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    dis.doClick();
                }
            });
            log("\n已中斷串口連線");
            Thread.sleep(800);
        }
        log("\n[尋找 Arduino Micro...]");
        String port = Settings.port().isEmpty() ? findPort(cli) : Settings.port();
        if (port == null) {
            log("[錯誤] 找不到 Arduino Micro 的序列埠,請確認 USB 已接上。");
            return;
        }
        log("[燒錄中] " + port);
        if (run(cli.getPath(), "upload", "-p", port, "-b", FQBN, sketch.getPath()) != 0) {
            log("\n[錯誤] 燒錄失敗。請確認 USB 已接上、沒有其他程式占用序列埠。");
            return;
        }
        Settings.p.setProperty("lastUpload", hash);   // 記住這次上傳的內容
        Settings.p.setProperty("lastUploadTime", String.valueOf(System.currentTimeMillis()));
        try {
            Settings.save();
        } catch (Exception ex) {
            log("(無法記錄上傳狀態:" + ex.getMessage() + ")");
        }
        log("\n[完成] 動作已更新到機器人。請按「串口連接」重新連線。");
        closeLogLater(1500);   // 成功後自動關閉記錄視窗(失敗時不會關,讓你看錯誤)
    }

    static String findPort(File cli) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cli.getPath(), "board", "list");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), nativeCharset()));
        String line;
        String port = null;
        while ((line = r.readLine()) != null) {
            String l = line.toLowerCase();
            if (port == null && (l.contains("micro") || l.contains("leonardo"))) {
                port = line.trim().split("\\s+")[0];
            }
        }
        p.waitFor();
        return port;
    }

    /**
     * 記下編譯結果裡的程式空間用量(「Sketch 使用 22704 位元組(79%)…最大為 28672 位元組」)
     * 以及當時 motor.h 有幾幀,「新增幀」用它推算還剩多少空間。
     */
    static void noteSize(String line) {
        try {
            if (line.startsWith("全域變數") || line.startsWith("Global variables")) {
                // 「全域變數使用 791 位元組 (30%) …保留 1769 位元組給區域變數. 最大 2560 位元組」→ 記憶體用量
                java.util.List<Integer> r = new java.util.ArrayList<Integer>();
                int a = 0;
                while (a < line.length()) {
                    if (Character.isDigit(line.charAt(a))) {
                        int b = a;
                        while (b < line.length() && Character.isDigit(line.charAt(b))) b++;
                        r.add(Integer.valueOf(line.substring(a, b)));
                        a = b;
                    } else {
                        a++;
                    }
                }
                if (r.size() >= 4) {
                    Settings.p.setProperty("ramUsed", String.valueOf(r.get(0)));
                    Settings.p.setProperty("ramMax", String.valueOf(r.get(3)));
                    Settings.save();
                }
                return;
            }
            if (!line.startsWith("Sketch") || !(line.contains("位元組") || line.contains("bytes"))) return;
            java.util.List<Integer> g = new java.util.ArrayList<Integer>();
            int i = 0;
            while (i < line.length()) {
                if (Character.isDigit(line.charAt(i))) {
                    int j = i;
                    while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
                    g.add(Integer.valueOf(line.substring(i, j)));
                    i = j;
                } else {
                    i++;
                }
            }
            if (g.size() < 3) return;
            String h = new String(Files.readAllBytes(new File(Settings.sketch(), "motor.h").toPath()), "UTF-8");
            int k = h.indexOf("MOTOR_FRAME_MAX");
            if (k < 0) return;
            k += "MOTOR_FRAME_MAX".length();
            while (k < h.length() && !Character.isDigit(h.charAt(k))) k++;
            int e = k;
            while (e < h.length() && Character.isDigit(h.charAt(e))) e++;
            Settings.p.setProperty("flashUsed", String.valueOf(g.get(0)));
            Settings.p.setProperty("flashMax", String.valueOf(g.get(2)));
            Settings.p.setProperty("flashFrames", h.substring(k, e));
            Settings.save();
        } catch (Throwable t) {
            // 記不起來也不影響更新
        }
    }

    static int run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), nativeCharset()));
        String line;
        while ((line = r.readLine()) != null) {
            log(line);
            noteSize(line);
        }
        return p.waitFor();
    }

    /** arduino-cli(Go 程式)輸出的是 UTF-8,不是 Windows 的 MS950 */
    static Charset nativeCharset() {
        return Charset.forName("UTF-8");
    }
}
