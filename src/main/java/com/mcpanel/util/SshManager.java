package com.mcpanel.util;

import com.mcpanel.config.ConfigManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SshManager {
    public static synchronized void initSsh(Consumer<String> logger) {
        try {
            EnvironmentHealer.heal(logger);

            ConfigManager config = ConfigManager.getInstance();
            String port = config.get("remote.ssh.port", "22");

            File sshDir = new File("/etc/ssh");
            if (!sshDir.exists()) sshDir.mkdirs();

            File persistSshDir = new File(config.getRootDir(), "data/ssh");
            if (!persistSshDir.exists()) persistSshDir.mkdirs();

            String[] keyNames = {"ssh_host_rsa_key", "ssh_host_ecdsa_key", "ssh_host_ed25519_key"};
            boolean needGenerate = false;

            for (String keyName : keyNames) {
                File persistKey = new File(persistSshDir, keyName);
                File sysKey = new File(sshDir, keyName);

                if (persistKey.exists()) {
                    Files.copy(persistKey.toPath(), sysKey.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    new ProcessBuilder("chmod", "600", sysKey.getAbsolutePath()).start().waitFor();
                } else if (sysKey.exists()) {
                    Files.copy(sysKey.toPath(), persistKey.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    needGenerate = true;
                }
            }

            if (needGenerate) {
                new ProcessBuilder("ssh-keygen", "-A").start().waitFor();
                for (String keyName : keyNames) {
                    File sysKey = new File(sshDir, keyName);
                    if (sysKey.exists()) {
                        Files.copy(sysKey.toPath(), new File(persistSshDir, keyName).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            File rootPubKey = new File(persistSshDir, "root_rsa.pub");
            if (rootPubKey.exists()) {
                try {
                    String pubKeyContent = new String(Files.readAllBytes(rootPubKey.toPath()), StandardCharsets.UTF_8).trim();
                    File rootSshDir = new File("/root/.ssh");
                    if (!rootSshDir.exists()) rootSshDir.mkdirs();
                    
                    new ProcessBuilder("chmod", "700", "/root/.ssh").start().waitFor();
                    new ProcessBuilder("chown", "root:root", "/root/.ssh").start().waitFor();

                    File authKeys = new File(rootSshDir, "authorized_keys");
                    boolean needWrite = true;
                    if (authKeys.exists()) {
                        String existing = new String(Files.readAllBytes(authKeys.toPath()), StandardCharsets.UTF_8);
                        if (existing.contains(pubKeyContent)) needWrite = false;
                    }

                    if (needWrite) {
                        String content = pubKeyContent + "\n";
                        if (authKeys.exists()) {
                            content = new String(Files.readAllBytes(authKeys.toPath()), StandardCharsets.UTF_8) + "\n" + content;
                        }
                        Files.write(authKeys.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    }

                    new ProcessBuilder("chmod", "600", authKeys.getAbsolutePath()).start().waitFor();
                    new ProcessBuilder("chown", "root:root", authKeys.getAbsolutePath()).start().waitFor();
                    logger.accept("[SshManager] ✓ 已自动恢复 Root SSH 密钥并修复权限");
                } catch (Exception e) {}
            }

            File nologin = new File("/etc/nologin");
            if (nologin.exists()) nologin.delete();

            File sshdConfig = new File(sshDir, "sshd_config");
            List<String> newConfig = new ArrayList<>();
            newConfig.add("Port " + port);
            newConfig.add("ListenAddress 0.0.0.0");
            newConfig.add("HostKey /etc/ssh/ssh_host_rsa_key");
            newConfig.add("HostKey /etc/ssh/ssh_host_ecdsa_key");
            newConfig.add("HostKey /etc/ssh/ssh_host_ed25519_key");
            newConfig.add("PermitRootLogin yes");
            newConfig.add("PasswordAuthentication yes");
            newConfig.add("PubkeyAuthentication yes");
            newConfig.add("PermitEmptyPasswords no");
            newConfig.add("UsePAM no");
            newConfig.add("UseDNS no");
            newConfig.add("X11Forwarding no");
            newConfig.add("AllowTcpForwarding yes");
            newConfig.add("Subsystem sftp internal-sftp");
            newConfig.add("ClientAliveInterval 30");
            newConfig.add("ClientAliveCountMax 3");

            // ★ 恢复实例用户的普通 SSH 登录权限，不再限制 Chroot 或 SFTP
            // 安全防护完全依赖 LinuxUserManager 对 /workspace 的 711 权限限制

            Files.write(sshdConfig.toPath(), newConfig, StandardCharsets.UTF_8);

            File configDir = new File(sshDir, "sshd_config.d");
            if (configDir.exists() && configDir.isDirectory()) {
                File[] files = configDir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
            }

            try {
                new ProcessBuilder("pkill", "-9", "-x", "sshd").start().waitFor();
            } catch (Exception ignored) {}

            new ProcessBuilder("/usr/sbin/sshd").start().waitFor();
            
            Thread.sleep(1000);
            Process ps = new ProcessBuilder("pgrep", "-x", "sshd").start();
            boolean running = ps.waitFor() == 0 && ps.exitValue() == 0;

            if (running) {
                logger.accept("[SshManager] ✓ SSH 服务已在端口 " + port + " 成功运行");
            } else {
                logger.accept("[SshManager] ✗ SSH 服务启动异常！");
            }

        } catch (Exception e) {
            logger.accept("[SshManager] ✗ SSH 初始化失败: " + e.getMessage());
        }
    }
}