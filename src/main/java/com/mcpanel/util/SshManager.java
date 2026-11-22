package com.mcpanel.util;

import com.mcpanel.config.ConfigManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SshManager {
    /**
     * ★ 线程治理：所有一次性系统命令统一走这里（输出丢弃 + 超时强杀）。
     * 旧写法 .start().waitFor() 有两个隐患：命令输出写满管道会让子进程永久阻塞；
     * 没有超时的 waitFor 会把调用线程一起挂死，进而让上游线程池被占满。
     */
    private static int runQuietCode(long timeoutSec, String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (p != null && p.isAlive()) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
    }

    private static void runQuiet(long timeoutSec, String... cmd) {
        runQuietCode(timeoutSec, cmd);
    }

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
                    runQuiet(10, "chmod", "600", sysKey.getAbsolutePath());
                } else if (sysKey.exists()) {
                    Files.copy(sysKey.toPath(), persistKey.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    needGenerate = true;
                }
            }

            if (needGenerate) {
                runQuiet(60, "ssh-keygen", "-A");
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
                    
                    runQuiet(10, "chmod", "700", "/root/.ssh");
                    runQuiet(10, "chown", "root:root", "/root/.ssh");

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

                    runQuiet(10, "chmod", "600", authKeys.getAbsolutePath());
                    runQuiet(10, "chown", "root:root", authKeys.getAbsolutePath());
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
            // 并发/握手限制：公网上的 SSH 扫描非常多，没有这几项时每个连接都会 fork 一个
            // sshd，连接堆积或握手卡住时进程数（ps -eLf 里的 sshd 行）会持续上涨。
            newConfig.add("LoginGraceTime 20");
            newConfig.add("MaxStartups 10:30:20");
            newConfig.add("MaxSessions 5");
            newConfig.add("MaxAuthTries 3");

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

            // ★ 不再无条件 pkill sshd：
            //   1) 那会直接杀掉用户正在用的 SSH 会话（表现为 Connection closed by remote host）；
            //   2) 守护进程被 -9 杀掉后，正在连接的 sshd 子进程会被挂到 PID 1（面板 JVM）下，
            //      Java 不会 wait 非自己创建的进程，它们退出后变成 ps -eLf 里越积越多的 sshd 僵尸。
            boolean alreadyRunning = runQuietCode(10, "pgrep", "-x", "sshd") == 0;
            if (alreadyRunning) {
                logger.accept("[SshManager] SSH 服务已在运行，跳过重启（新配置下次启动生效）");
            } else {
                runQuiet(30, "/usr/sbin/sshd");
                Thread.sleep(600);
            }

            boolean running = runQuietCode(10, "pgrep", "-x", "sshd") == 0;
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