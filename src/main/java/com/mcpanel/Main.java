package com.mcpanel;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.SmtpConfig;
import com.mcpanel.config.UserStore;
import com.mcpanel.instance.InstanceRuntime;
import com.mcpanel.instance.InstanceRuntimeManager;
import com.mcpanel.mc.DockerManager;
import com.mcpanel.util.Crypto;
import com.mcpanel.util.SshManager;
import com.mcpanel.web.PanelLogManager;
import com.mcpanel.web.WebServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) {
        // 服务器环境没有图形界面：关闭 AWT 对显示器的依赖（图片验证码需要）
        System.setProperty("java.awt.headless", "true");
        PanelLogManager.init();
        System.out.println("=== Minecraft Server Panel ===");
        System.out.println("[Panel] 当前运行用户为：" + System.getProperty("user.name"));

        try {
            Crypto.initKey("users.key");
        } catch (Exception e) {
            System.err.println("无法初始化加密密钥: " + e.getMessage());
            System.exit(1);
        }

        ConfigManager nodeGlobalCfg = ConfigManager.getInstance();
        nodeGlobalCfg.init();
        
        // ★ 初始化并触发 SMTP 数据迁移
        SmtpConfig.getInstance().init();

        UserStore store = UserStore.getInstance();
        store.load();
        store.bootstrapFromConfig(nodeGlobalCfg);

        if (store.getInitialGeneratedPassword() != null) {
            String masterUser = store.getMaster() != null ? store.getMaster().username : "admin";
            System.out.println("\n======================================================");
            System.out.println("  🚀 首次启动，已生成初始管理员账户！");
            System.out.println("  用户名: " + masterUser);
            System.out.println("  密  码: " + store.getInitialGeneratedPassword());
            System.out.println("  ⚠️  请登录面板后，第一时间前往【账户管理】修改密码！");
            System.out.println("======================================================\n");
        }

        com.mcpanel.instance.InstanceManager.getInstance().init();
        InstanceRuntimeManager rtMgr = InstanceRuntimeManager.getInstance();

        WebServer web = new WebServer(nodeGlobalCfg, rtMgr);
        try {
            web.start();
        } catch (Exception e) {
            System.err.println("[Panel] Web 服务启动失败: " + e.getMessage());
        }

        new Thread(() -> {
            try { DockerManager.initDocker(System.out::println); } catch (Throwable t) {}
            try { SshManager.initSsh(System.out::println); } catch (Throwable t) {}
            try { rtMgr.initAll(); } catch (Throwable t) {}
        }, "bg-init-serial").start();

        Thread consoleThread = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                try {
                    String cmd = reader.readLine();
                    if (cmd == null) break;

                    if (cmd.trim().startsWith("resetpw user=")) {
                        String targetUser = cmd.trim().substring("resetpw user=".length()).trim();
                        com.mcpanel.config.UserStore us = com.mcpanel.config.UserStore.getInstance();
                        com.mcpanel.config.UserStore.User u = us.findUser(targetUser);
                        
                        if (u == null) {
                            System.out.println("[Panel] ✗ 用户不存在: " + targetUser);
                            continue;
                        }
                        
                        String newPass = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        
                        if (us.isMaster(targetUser)) {
                            us.setMasterPassword(newPass);
                        } else {
                            us.updateSubUser(targetUser, newPass, null);
                        }
                        u.totpSecret = null;
                        u.recoveryHash = null;
                        u.needsRecoverySetup = true;
                        us.save();
                        
                        System.out.println("[Panel] 密码已重置！当前密码为：" + newPass);
                        continue;
                    }
                    
                    if ("stop".equalsIgnoreCase(cmd.trim())) {
                        System.out.println("\n[Panel] 本地终端收到 stop，正在关闭所有实例...");
                        rtMgr.shutdownAll();
                        System.out.println("[Panel] 所有实例已关闭，面板退出。");
                        System.exit(0);
                        return;
                    }
                    
                    InstanceRuntime def = rtMgr.getDefault();
                    if (def != null && def.mcServer.isRunning()) {
                        def.terminalHandler.log("> " + cmd + "   (来自本地终端)");
                        def.mcServer.sendCommand(cmd);
                    } else {
                        System.out.println("[Panel] 默认实例未运行，忽略命令: " + cmd);
                    }
                } catch (Exception e) { break; }
            }
        }, "local-console");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }
}
