import java.io.File;

import javax.swing.SwingUtilities;

/**
 * 預設設定檔:咖啡杯啟動後自動載入(等於自動按「載入設定」選這個檔),不用每次手動選。
 * 存在 MotorAdjPlus.properties 的 defaultConfig(絕對路徑,或相對於 Tools_MotorAdj)。
 *   ・從來沒設定過 → 預設用 light1.2.config(檔案存在才載入)
 *   ・設成空白 → 不自動載入(維持原本 MotorAdj 的行為)
 * 在「設定」分頁的「路徑設定…」裡可以改,也可以按一個鈕把「目前載入的設定檔」設成預設。
 */
public class DefaultConfig {
    static final String KEY = "defaultConfig";
    static final String FIRST_DEFAULT = "light1.2.config";

    /** 目前設定的預設檔(沒設定 = null) */
    static File file() {
        String v = Settings.p.getProperty(KEY);
        if (v == null) v = FIRST_DEFAULT;
        v = v.trim();
        if (v.isEmpty()) return null;
        File f = new File(v);
        return f.isAbsolute() ? f : new File(Settings.dir, v);
    }

    /** 設定視窗欄位要顯示的字(沒設定過就顯示第一次的預設) */
    static String shown() {
        String v = Settings.p.getProperty(KEY);
        return v == null ? FIRST_DEFAULT : v.trim();
    }

    /** 咖啡杯目前載入的設定檔(絕對路徑;讀不到回傳空字串) */
    static String currentPath() {
        try {
            Object ms = Plus.fld(Plus.outer(), "MotorSet");
            String s = String.valueOf(Plus.fld(ms, "SettingConfigPath"));
            return new File(s).getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 啟動後呼叫:有預設檔就載入 */
    static void autoLoad() {
        final File f = file();
        if (f == null) return;
        if (!f.isFile()) {
            System.err.println("DefaultConfig:預設設定檔不存在,略過:" + f);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    loadNow(f);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    /** 做跟「載入設定」按鈕一樣的事:讀檔、改標題、重新整理畫面 */
    static void loadNow(File f) throws Exception {
        Object outer = Plus.outer();
        if (outer == null) return;
        Object ms = Plus.fld(outer, "MotorSet");
        java.lang.reflect.Field pathF = ms.getClass().getDeclaredField("SettingConfigPath");
        pathF.setAccessible(true);
        pathF.set(ms, f.getAbsolutePath());

        Class<?> ioC = Class.forName("main.ConfigSettingIO");
        java.lang.reflect.Constructor<?> ctor = ioC.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object io = ctor.newInstance();
        java.lang.reflect.Method read = ioC.getDeclaredMethod("read", String.class, ms.getClass());
        read.setAccessible(true);
        read.invoke(io, f.getAbsolutePath(), ms);

        try {
            javax.swing.JFrame fr = (javax.swing.JFrame) Plus.fld(outer, "frame");
            fr.setTitle(f.getName() + " - Motor Adjust tools [" + f.getAbsoluteFile().getParent() + "]");
        } catch (Throwable ignore) {
        }
        java.lang.reflect.Method up = outer.getClass().getDeclaredMethod("setting_upgradeFrame");
        up.setAccessible(true);
        up.invoke(outer);
        System.out.println("DefaultConfig:已載入預設設定檔 " + f);
    }
}
