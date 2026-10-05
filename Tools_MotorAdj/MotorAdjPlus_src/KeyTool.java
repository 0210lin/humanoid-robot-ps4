import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 老師用的金鑰工具(命令列)。沒有私鑰的人用不了。
 *   java -cp MotorAdjPlus.jar KeyTool id                                              顯示這台電腦的識別碼
 *   java -cp MotorAdjPlus.jar KeyTool init <私鑰檔>                                    產生一組新的公鑰 / 私鑰(只做一次)
 *   java -cp MotorAdjPlus.jar KeyTool sign <私鑰檔> <電腦識別碼> <名字> <到期日或never> <輸出.key>
 *                                                                                      簽發一份金鑰檔,同時印出「授權碼」(一串文字,可以貼在訊息裡傳)
 *   java -cp MotorAdjPlus.jar KeyTool signmany <私鑰檔> <名單.txt> <到期日或never> <輸出資料夾>
 *                                                                                      名單一行一個:電腦識別碼 名字(用空白、逗號或 Tab 分開);一次簽發全部
 *   java -cp MotorAdjPlus.jar KeyTool code <.key 檔>                                    把金鑰檔轉成授權碼
 *   java -cp MotorAdjPlus.jar KeyTool verify <.key 檔>                                  檢查一份金鑰檔(這台電腦能不能用)
 */
public class KeyTool {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { usage(); return; }
        String cmd = a[0];
        if (cmd.equals("id")) {
            System.out.println(License.machineId());
        } else if (cmd.equals("init") && a.length == 2) {
            File f = new File(a[1]);
            if (f.exists()) throw new Exception("私鑰檔已經存在,不會蓋掉:" + f);
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            Files.write(f.toPath(), Base64.getEncoder().encode(kp.getPrivate().getEncoded()));
            System.out.println("私鑰已存到:" + f);
            System.out.println("PUBLIC_KEY=" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        } else if (cmd.equals("sign") && a.length == 6) {
            PrivateKey pk = loadKey(a[1]);
            String text = signText(pk, a[2], a[3], a[4]);
            File of = new File(a[5]);
            if (of.getParentFile() != null) of.getParentFile().mkdirs();
            Files.write(of.toPath(), text.getBytes(StandardCharsets.UTF_8));
            System.out.println("已簽發給 " + a[3] + "  電腦 " + fmtId(a[2]) + "  到期 " + a[4] + "\n存到:" + of);
            System.out.println("授權碼(可以貼在訊息裡傳,在「教師解鎖…」→「貼上授權碼…」貼上):\n" + License.toCode(text));
        } else if (cmd.equals("signmany") && a.length == 5) {
            signMany(a[1], a[2], a[3], new File(a[4]));
        } else if (cmd.equals("setpw") && (a.length == 3 || a.length == 4)) {
            // setpw <私鑰檔> <輸出 pw.dat> [密碼]  不給密碼就在視窗裡問(輸入時看不到字)
            char[] pw;
            if (a.length == 4) {
                pw = a[3].toCharArray();
            } else if (System.console() != null) {
                pw = System.console().readPassword("新的教師密碼: ");
                char[] again = System.console().readPassword("再輸入一次: ");
                if (pw == null || again == null || !java.util.Arrays.equals(pw, again)) throw new Exception("兩次輸入不一樣,沒有變更");
            } else {
                System.out.print("新的教師密碼: ");
                pw = new java.util.Scanner(System.in, "UTF-8").nextLine().toCharArray();
            }
            if (pw.length < 6) throw new Exception("密碼至少 6 個字(建議 8 個字以上)");
            File of = new File(a[2]);
            if (of.getParentFile() != null) of.getParentFile().mkdirs();
            Files.write(of.toPath(), TeacherPw.makeFile(loadKey(a[1]), pw).getBytes(StandardCharsets.UTF_8));
            System.out.println("已產生:" + of + "\n把它放到 Tools_MotorAdj 資料夾(跟 MotorAdjPlus.jar 同一層),蓋掉舊的 pw.dat,重開 MotorAdj 就生效。");
        } else if (cmd.equals("code") && a.length == 2) {
            System.out.println(License.toCode(new String(Files.readAllBytes(new File(a[1]).toPath()), StandardCharsets.UTF_8)));
        } else if (cmd.equals("verify") && a.length == 2) {
            License.Info i = License.check(new File(a[1]));
            System.out.println((i.ok ? "可以用:" : "不能用:") + i.message);
        } else {
            usage();
        }
    }

    static PrivateKey loadKey(String path) throws Exception {
        String b64 = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8).trim();
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    static String fmtId(String id) throws Exception {
        String m = License.normId(id);
        if (m.length() != 20) throw new Exception("電腦識別碼要是 20 個字(例如 A1B2-C3D4-E5F6-7890-ABCD),收到的是「" + id + "」");
        return m.substring(0, 4) + "-" + m.substring(4, 8) + "-" + m.substring(8, 12) + "-" + m.substring(12, 16) + "-" + m.substring(16, 20);
    }

    static String cleanName(String n) { return n.replace('|', ' ').replace('=', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }

    /** 簽發,回傳整份金鑰檔的文字 */
    static String signText(PrivateKey pk, String machineId, String name, String exp) throws Exception {
        String machine = fmtId(machineId);
        name = cleanName(name);
        if (name.isEmpty()) throw new Exception("名字不能是空的");
        if (!exp.equals("never")) new SimpleDateFormat("yyyy-MM-dd").parse(exp);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pk);
        s.update(License.payload(name, machine, exp).getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(s.sign());
        return License.HEADER + "\nname=" + name + "\nmachine=" + machine + "\nexpires=" + exp + "\nsig=" + sig + "\n";
    }

    static String fileSafe(String n) { return n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim(); }

    /** 名單檔:一行「電腦識別碼 名字」。空行、# 開頭的行略過。有問題的行只跳過那一行,最後列出來 */
    static void signMany(String keyPath, String listPath, String exp, File outDir) throws Exception {
        PrivateKey pk = loadKey(keyPath);
        if (!exp.equals("never")) new SimpleDateFormat("yyyy-MM-dd").parse(exp);
        outDir.mkdirs();
        List<String> lines = Files.readAllLines(new File(listPath).toPath(), StandardCharsets.UTF_8);
        Set<String> usedNames = new HashSet<String>();
        List<String> problems = new ArrayList<String>();
        StringBuilder summary = new StringBuilder();
        int ok = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("﻿")) line = line.substring(1);
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("[\\s,;，、]+", 2);
            try {
                if (parts.length < 2 || parts[1].trim().isEmpty()) throw new Exception("要有「電腦識別碼」和「名字」兩欄");
                String text = signText(pk, parts[0], parts[1], exp);
                String base = fileSafe(cleanName(parts[1]));
                if (base.isEmpty()) base = "student";
                String name = base;
                for (int k = 2; usedNames.contains(name.toLowerCase()); k++) name = base + "_" + k;
                usedNames.add(name.toLowerCase());
                Files.write(new File(outDir, name + ".key").toPath(), text.getBytes(StandardCharsets.UTF_8));
                String code = License.toCode(text);
                Files.write(new File(outDir, name + "_授權碼.txt").toPath(), (code + "\n").getBytes(StandardCharsets.UTF_8));
                summary.append(name).append("\t").append(fmtId(parts[0])).append("\t").append(code).append("\n");
                ok++;
            } catch (Exception ex) {
                problems.add("第 " + (i + 1) + " 行「" + line + "」:" + ex.getMessage());
            }
        }
        Files.write(new File(outDir, "授權碼清單.txt").toPath(), ("名字\t電腦識別碼\t授權碼\n" + summary).getBytes(StandardCharsets.UTF_8));
        System.out.println("已簽發 " + ok + " 份,存到:" + outDir + "\n  每人一個「名字.key」和「名字_授權碼.txt」,另外有一份「授權碼清單.txt」");
        if (!problems.isEmpty()) {
            System.out.println("有 " + problems.size() + " 行沒有處理:");
            for (String p : problems) System.out.println("  " + p);
        }
    }

    static void usage() {
        System.out.println("用法:\n  KeyTool id\n  KeyTool init <私鑰檔>\n  KeyTool sign <私鑰檔> <電腦識別碼> <名字> <到期日 yyyy-MM-dd 或 never> <輸出 .key>\n"
                + "  KeyTool signmany <私鑰檔> <名單.txt> <到期日或never> <輸出資料夾>\n  KeyTool code <.key 檔>\n  KeyTool verify <.key 檔>");
    }
}
