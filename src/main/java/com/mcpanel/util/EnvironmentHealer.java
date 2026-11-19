package com.mcpanel.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class EnvironmentHealer {
    public static void heal(Consumer<String> logger) {
        logger.accept("[Healer] 正在将精简容器修补为完整 Linux 环境 ...");

        boolean isDebian = new File("/usr/bin/apt-get").exists();
        
        if (isDebian) {
            AptUtil.configureFastestMirror(logger);
            try {
                AptUtil.lockApt();
                ProcessBuilder pb = new ProcessBuilder("apt-get", "update");
                pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
                pb.inheritIO().start().waitFor();

                // ★ 核心修改：加入了 sudo
                ProcessBuilder install = new ProcessBuilder(
                    "apt-get", "install", "-y", "--no-install-recommends", 
                    "bash", "sudo", "util-linux", "passwd", "procps", "coreutils", 
                    "openssh-server", "mount", "openssl",
                    "iproute2", "iputils-ping", "curl", "wget", "nano", "net-tools"
                );
                install.environment().put("DEBIAN_FRONTEND", "noninteractive");
                install.inheritIO().start().waitFor();
            } catch (Exception e) {
                logger.accept("[Healer] apt 安装依赖失败: " + e.getMessage());
            } finally {
                AptUtil.unlockApt();
            }
        }

        try {
            new File("/run/sshd").mkdirs();
            new File("/dev/pts").mkdirs();
            new File("/var/run").mkdirs();
            new File("/tmp").mkdirs();
            new ProcessBuilder("chmod", "1777", "/tmp").start().waitFor();
            new ProcessBuilder("chmod", "755", "/run/sshd").start().waitFor();

            File pts = new File("/dev/pts");
            if (pts.list() == null || pts.list().length == 0) {
                new ProcessBuilder("mount", "-t", "devpts", "devpts", "/dev/pts").inheritIO().start().waitFor();
            }
            if (!new File("/dev/ptmx").exists() && new File("/dev/pts/ptmx").exists()) {
                new ProcessBuilder("ln", "-sf", "/dev/pts/ptmx", "/dev/ptmx").start().waitFor();
            }
        } catch (Exception e) {}

        try {
            File shellsFile = new File("/etc/shells");
            List<String> shells = Arrays.asList("/bin/sh", "/bin/bash", "/usr/bin/bash");
            Files.write(shellsFile.toPath(), shells, StandardCharsets.UTF_8);

            File pamDir = new File("/etc/pam.d");
            if (!pamDir.exists()) pamDir.mkdirs();

            writePam(pamDir, "sshd", Arrays.asList(
                    "auth       required     pam_unix.so",
                    "account    required     pam_unix.so",
                    "session    required     pam_unix.so"
            ));
            writePam(pamDir, "common-auth", Arrays.asList("auth    required    pam_unix.so nullok"));
            writePam(pamDir, "common-account", Arrays.asList("account required    pam_unix.so"));
            writePam(pamDir, "common-session", Arrays.asList("session required    pam_unix.so"));
            
            File shadow = new File("/etc/shadow");
            if (shadow.exists()) {
                new ProcessBuilder("chmod", "640", "/etc/shadow").start().waitFor();
                new ProcessBuilder("chown", "root:shadow", "/etc/shadow").start().waitFor();
            }
        } catch (Exception e) {}
        
        try {
            File bashrc = new File("/root/.bashrc");
            StringBuilder bashrcContent = new StringBuilder();
            bashrcContent.append("export PATH=$PATH:/usr/games\n");
            bashrcContent.append("cd /workspace 2>/dev/null || true\n");

            File runtimeDir = new File("/workspace/runtime");
            if (runtimeDir.exists() && runtimeDir.isDirectory()) {
                File[] javaDirs = runtimeDir.listFiles();
                if (javaDirs != null) {
                    for (File dir : javaDirs) {
                        if (dir.isDirectory() && (dir.getName().startsWith("jdk") || dir.getName().startsWith("jre"))) {
                            File bin = new File(dir, "bin");
                            if (bin.exists()) {
                                bashrcContent.append("export PATH=").append(bin.getAbsolutePath()).append(":$PATH\n");
                                logger.accept("[Healer] 已自动挂载 Java 环境: " + dir.getName());
                                break; 
                            }
                        }
                    }
                }
            }
            
            if (!bashrc.exists()) {
                Files.write(bashrc.toPath(), bashrcContent.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                String existing = new String(Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8);
                if (!existing.contains("/workspace")) {
                    Files.write(bashrc.toPath(), (existing + "\n" + bashrcContent.toString()).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {}

        logger.accept("[Healer] ✓ 容器已修补为标准 Linux 环境！");
    }

    private static void writePam(File dir, String name, List<String> lines) throws Exception {
        Files.write(new File(dir, name).toPath(), lines, StandardCharsets.UTF_8);
    }
}