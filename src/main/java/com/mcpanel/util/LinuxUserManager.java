package com.mcpanel.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.instance.Instance;
import com.mcpanel.instance.InstanceRuntime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LinuxUserManager {
    private static boolean isSupported = false;
    private static boolean checked = false;
    private static String suPath = null;

    private static final String[] REQUIRED_CMDS = {
            "su", "useradd", "chown", "pkill", "userdel", "id", "openssl", "ps"
    };

    private static final File PWD_FILE =
            new File(ConfigManager.getInstance().getRootDir(), "data/sys_passwords.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Map<String, String> sysPasswords = new HashMap<>();

    private static final Set<String> initInProgress =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static int runCmd(long timeoutSec, String... cmd) {
        if (cmd == null || cmd.length == 0) return -2;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread drain = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (br.readLine() != null) { }
                } catch (Exception ignored) {}
            }, "runCmd-drain");
            drain.setDaemon(true);
            drain.start();

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1; 
            }
            return p.exitValue();
        } catch (Exception e) {
            return -2;
        }
    }

    private static String runCmdCapture(long timeoutSec, String... cmd) {
        if (cmd == null || cmd.length == 0) return null;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            StringBuilder out = new StringBuilder();
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (out.length() < 4096) out.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "runCmdCapture-out");
            t.setDaemon(true);
            t.start();
            
            Thread e = new Thread(() -> {
                try (InputStream is = p.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    while (is.read(buf) != -1) {}
                } catch (Exception ignored) {}
            }, "runCmdCapture-err");
            e.setDaemon(true);
            e.start();

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            t.join(1000);
            return out.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    public static synchronized void init() {
        if (checked) return;
        checked = true;

        loadPasswords();

        if (!"root".equals(System.getProperty("user.name"))) return;
        if (System.getProperty("os.name").toLowerCase().contains("win")) return;

        boolean missing = false;
        for (String cmd : REQUIRED_CMDS) {
            if (!checkCmd(cmd)) missing = true;
        }
        if (missing && !tryInstallDependencies()) return;

        suPath = findSu();
        if (suPath == null) return;

        isSupported = true;
        System.out.println("[LinuxUserManager] 初始化完成，已启用 711 防窥视隔离，并加固核心文件权限");

        try {
            File ws = new File("/workspace");
            if (ws.exists()) {
                // ★ 核心修改：根目录设为 711，只允许穿越和进入自己目录，绝对不允许 ls 列出别人的文件
                runCmd(3, "chmod", "711", ws.getAbsolutePath());
                
                // 核心敏感文件全部锁死为 600 (仅 root 可读写)
                runCmd(3, "chmod", "600", "/workspace/users.json");
                runCmd(3, "chmod", "600", "/workspace/users.key");
                runCmd(3, "chmod", "600", "/workspace/config.properties");
                runCmd(3, "chmod", "600", "/workspace/sessions.json");
                runCmd(3, "chmod", "600", "/workspace/node.json");
                runCmd(3, "chmod", "600", "/workspace/nodes.json");
                runCmd(3, "chmod", "600", "/workspace/plugin-auth.json");
                runCmd(3, "chmod", "600", "/workspace/shares.json");
                runCmd(3, "chmod", "600", "/workspace/share-logs.json");
                runCmd(3, "chmod", "600", "/workspace/spigot.yml");
                
                // ★ 核心修改：面板主程序设为 600，普通用户无权读取、无权修改、无权删除
                runCmd(3, "chmod", "600", "/workspace/mc-panel.jar");
                
                // 数据目录
                runCmd(3, "chmod", "700", "/workspace/data");
                runCmd(3, "chmod", "700", "/workspace/runtime");
            }
            
            File instDir = new File("/workspace/instances");
            if (instDir.exists()) {
                // ★ 核心修改：实例父目录同样设为 711。用户可以直接 cd 进自己的 ID 文件夹，但不能 ls 看到别人的 ID
                runCmd(3, "chown", "root:root", instDir.getAbsolutePath());
                runCmd(3, "chmod", "711", instDir.getAbsolutePath());
            }
            
            File runtimeDir = new File("/workspace/runtime");
            if (runtimeDir.exists()) {
                // 开放 Java 执行权限，否则实例无法启动
                runCmd(10, "chown", "-R", "root:root", runtimeDir.getAbsolutePath());
                runCmd(10, "chmod", "-R", "755", runtimeDir.getAbsolutePath());
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void loadPasswords() {
        if (!PWD_FILE.exists()) {
            if (PWD_FILE.getParentFile() != null) PWD_FILE.getParentFile().mkdirs();
            return;
        }
        try {
            sysPasswords = MAPPER.readValue(PWD_FILE, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            sysPasswords = new HashMap<>();
        }
    }

    private static synchronized void savePasswords() {
        try {
            if (PWD_FILE.getParentFile() != null) PWD_FILE.getParentFile().mkdirs();
            MAPPER.writeValue(PWD_FILE, sysPasswords);
            PWD_FILE.setReadable(false, false);
            PWD_FILE.setReadable(true, true);
            PWD_FILE.setWritable(false, false);
            PWD_FILE.setWritable(true, true);
        } catch (Exception ignored) {}
    }

    private static boolean checkCmd(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[256];
                while (is.read(buf) != -1) {}
            }
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryInstallDependencies() {
        boolean isDebian = new File("/usr/bin/apt-get").exists();
        try {
            if (isDebian) {
                AptUtil.configureFastestMirror(System.out::println);
                runCmd(60, "apt-get", "update");
                runCmd(120, "apt-get", "install", "-y",
                        "util-linux", "passwd", "procps", "coreutils", "openssl");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String findSu() {
        Set<String> results = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir != null && !dir.isEmpty()) collect(new File(dir, "su"), results);
            }
        }
        String[] common = {"/bin/su", "/usr/bin/su", "/sbin/su", "/usr/sbin/su"};
        for (String p : common) collect(new File(p), results);
        return results.isEmpty() ? null : results.iterator().next();
    }

    private static void collect(File f, Set<String> out) {
        if (f.exists() && f.isFile() && f.canExecute()) {
            try { out.add(f.getCanonicalPath()); }
            catch (IOException e) { out.add(f.getAbsolutePath()); }
        }
    }

    public static boolean isEnabled(InstanceRuntime rt) {
        if (!isSupported) return false;
        if (rt == null) return true;
        String sandbox = rt.config.get("_sandbox.mode", "none");
        return "none".equals(sandbox) || "bwrap".equals(sandbox); 
    }

    public static String getSysUsername(Instance inst) {
        String safeId = inst.id.replaceAll("[^a-zA-Z0-9]", "_");
        if (safeId.length() > 20) safeId = safeId.substring(0, 20);
        return "mc_" + safeId;
    }

    // su 预检：用非登录 shell 试一次，避免 .bashrc 里的 trap 影响退出码。
    // 返回 false 通常意味着 /etc/shadow 里没有这个用户（su 会报
    // "Authentication service cannot retrieve authentication info"）。
    public static boolean canSuTo(String username) {
        if (username == null || username.isEmpty()) return false;
        String su = suPath != null ? suPath : findSu();
        if (su == null) return false;
        try {
            Process p = new ProcessBuilder(su, "-s", "/bin/sh", username, "-c", "true")
                    .redirectErrorStream(true).start();
            final StringBuilder out = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (out.length() < 512) out.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "su-check");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            if (p.exitValue() != 0) {
                System.err.println("[LinuxUserManager] su 到 " + username + " 失败: " + out.toString().trim());
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[LinuxUserManager] su 预检异常: " + e.getMessage());
            return false;
        }
    }

    // 用户可能只写进了 /etc/passwd 而没有 /etc/shadow 条目，导致 su 无法获取认证信息。
    // 这里补一条锁定密码的 shadow 记录，root 执行 su 不需要密码，能正常切换。
    public static void ensureShadowEntry(String username) {
        if (username == null || username.isEmpty()) return;
        try {
            File shadow = new File("/etc/shadow");
            if (!shadow.exists()) return;
            List<String> lines = Files.readAllLines(shadow.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith(username + ":")) return;
            }
            long days = System.currentTimeMillis() / 86400000L;
            lines.add(username + ":!:" + days + ":0:99999:7:::");
            Files.write(shadow.toPath(), lines, StandardCharsets.UTF_8);
            System.err.println("[LinuxUserManager] 已补写 /etc/shadow 条目: " + username);
        } catch (Exception e) {
            System.err.println("[LinuxUserManager] 补写 shadow 失败: " + e.getMessage());
        }
    }

    public static boolean isUserReady(Instance inst) {
        if (!isSupported || inst == null) return false;
        if (inst.sysUser == null || inst.sysUser.isEmpty()) return false;
        int rc = runCmd(2, "id", "-u", inst.sysUser);
        return rc == 0;
    }

    public static void initInstanceUser(Instance inst, File instanceDir) {
        if (!isSupported || inst == null || instanceDir == null) return;
        if (!initInProgress.add(inst.id)) return;

        try {
            String username = getSysUsername(inst);
            String plainPassword = null;

            if (inst.sysPass != null && !inst.sysPass.isEmpty()) {
                plainPassword = inst.sysPass;
            }
            if (plainPassword == null) {
                String encPwd = sysPasswords.get(inst.id);
                if (encPwd != null) {
                    try { plainPassword = Crypto.decrypt(encPwd); } catch (Exception ignored) {}
                }
            }
            if (plainPassword == null) {
                plainPassword = generateRandomPassword();
            }

            String existingEnc = sysPasswords.get(inst.id);
            boolean needSavePasswords = false;
            if (existingEnc == null) {
                needSavePasswords = true;
            } else {
                try {
                    String existingPlain = Crypto.decrypt(existingEnc);
                    if (!plainPassword.equals(existingPlain)) needSavePasswords = true;
                } catch (Exception e) {
                    needSavePasswords = true;
                }
            }
            if (needSavePasswords) {
                sysPasswords.put(inst.id, Crypto.encrypt(plainPassword));
                savePasswords();
            }

            boolean needSaveMeta = false;
            if (!username.equals(inst.sysUser)) { inst.sysUser = username; needSaveMeta = true; }
            if (!plainPassword.equals(inst.sysPass)) { inst.sysPass = plainPassword; needSaveMeta = true; }
            if (needSaveMeta) inst.saveMeta();

            String shell = "/bin/bash";
            if (!new File("/bin/bash").exists()) shell = "/bin/sh";

            int rc = runCmd(2, "id", username);
            boolean exists = (rc == 0);

            if (!exists) {
                runCmd(5, "useradd", "-m", "-d", instanceDir.getAbsolutePath(),
                        "-s", shell, username);
            } else {
                runCmd(2, "usermod", "-s", shell, username);
            }
            runCmd(2, "usermod", "-U", username);
            // 确保 /etc/shadow 里有这个用户，否则 su 会报
            // "Authentication service cannot retrieve authentication info"
            ensureShadowEntry(username);

            String hash = null;
            try {
                String out = runCmdCapture(5, "openssl", "passwd", "-6", plainPassword);
                if (out != null) {
                    String[] lines = out.split("\n");
                    if (lines.length > 0 && lines[0].startsWith("$")) hash = lines[0].trim();
                }
            } catch (Exception ignored) {}

            if (hash != null && !hash.isEmpty()) {
                File shadow = new File("/etc/shadow");
                if (shadow.exists()) {
                    try {
                        List<String> lines = Files.readAllLines(shadow.toPath(), StandardCharsets.UTF_8);
                        boolean found = false;
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).startsWith(username + ":")) {
                                String[] parts = lines.get(i).split(":", -1);
                                parts[1] = hash;
                                if (parts.length > 7) parts[7] = "";
                                lines.set(i, String.join(":", parts));
                                found = true;
                                break;
                            }
                        }
                        if (found) Files.write(shadow.toPath(), lines, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                }
            }

            runCmd(2, "chmod", "640", "/etc/shadow");
            runCmd(2, "chown", "root:shadow", "/etc/shadow");

            runCmd(10, "chown", "-R", username + ":" + username, instanceDir.getAbsolutePath());
            runCmd(2, "chmod", "700", instanceDir.getAbsolutePath());

            File bashrc = new File(instanceDir, ".bashrc");
            StringBuilder bashrcBuilder = new StringBuilder();
            bashrcBuilder.append("export PATH=$PATH:/usr/games\n");
            
            File runtimeDir = new File(ConfigManager.getInstance().getRootDir(), "runtime");
            if (runtimeDir.exists() && runtimeDir.isDirectory()) {
                File[] dirs = runtimeDir.listFiles();
                if (dirs != null) {
                    for (File d : dirs) {
                        if (d.isDirectory() && (d.getName().startsWith("jdk") || d.getName().startsWith("jre"))) {
                            bashrcBuilder.append("export PATH=").append(d.getAbsolutePath()).append("/bin:$PATH\n");
                        }
                    }
                }
            }
            
            bashrcBuilder.append("cd ").append(instanceDir.getAbsolutePath()).append("/server 2>/dev/null || true\n");
            bashrcBuilder.append("export MCPANEL_SID=$(ps -o sid= -p $$ 2>/dev/null | tr -d ' ')\n");
            bashrcBuilder.append("trap 'if [[ -n \"$MCPANEL_SID\" ]]; then pkill -9 -s $MCPANEL_SID 2>/dev/null; fi' EXIT HUP\n");
            bashrcBuilder.append("echo -e \"\\e[32m[MC Panel] 欢迎登录。终端同生共死保护已开启。\\e[0m\"\n");

            String bashrcContent = bashrcBuilder.toString();

            if (!bashrc.exists()) {
                Files.write(bashrc.toPath(), bashrcContent.getBytes(StandardCharsets.UTF_8));
                runCmd(2, "chown", username + ":" + username, bashrc.getAbsolutePath());
            } else {
                String old = new String(Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8);
                if (!old.contains("MC Panel SSH 终端同生共死机制")) {
                    Files.write(bashrc.toPath(),
                            (old + "\n" + bashrcContent).getBytes(StandardCharsets.UTF_8));
                    runCmd(2, "chown", username + ":" + username, bashrc.getAbsolutePath());
                }
            }

            System.out.println("[LinuxUserManager] 已初始化实例用户并恢复 SSH 权限: " + username);
        } catch (Exception e) {
            System.err.println("[UserManager] 初始化用户异常: " + e.getMessage());
        } finally {
            initInProgress.remove(inst.id);
        }
    }

    public static void deleteInstanceUser(Instance inst) {
        if (!isSupported || inst == null) return;
        if (inst.sysUser == null || inst.sysUser.isEmpty()) return;
        try {
            runCmd(2, "pkill", "-9", "-u", inst.sysUser);
            runCmd(5, "userdel", "-r", inst.sysUser);
            sysPasswords.remove(inst.id);
            savePasswords();
        } catch (Exception ignored) {}
    }

    public static void chownDir(File dir, String username) {
        if (!isSupported || dir == null || username == null) return;
        try {
            runCmd(30, "chown", "-R", username + ":" + username, dir.getAbsolutePath());
        } catch (Exception ignored) {}
    }

    public static String getSuPath() { return suPath; }

    private static String generateRandomPassword() {
        String chars = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }
}