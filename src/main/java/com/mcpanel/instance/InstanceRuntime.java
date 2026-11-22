package com.mcpanel.instance;

import com.mcpanel.command.BuiltinCommands;
import com.mcpanel.command.CommandRegistry;
import com.mcpanel.command.PlayerPerms;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;
import com.mcpanel.plugin.PluginManager;
import com.mcpanel.util.LinuxUserManager;
import com.mcpanel.web.RollbackService;
import com.mcpanel.web.TerminalHandler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InstanceRuntime {
    public final Instance inst;
    public final ConfigManager config;
    public final PlayerPerms perms;
    public final CommandRegistry commandRegistry;
    public final ServerProcess mcServer;
    public final TerminalHandler terminalHandler;
    public final PluginManager pluginManager;
    public final RollbackService rollbackService;
    public final InstallerManager installerManager;

    private volatile boolean started = false;

    public InstanceRuntime(Instance inst) {
        this.inst = inst;

        if (inst.type == null || inst.type.isEmpty()) {
            inst.type = InstanceManager.detectType(inst.getServerDir());
            inst.saveMeta();
        }

        this.config = new ConfigManager(inst.getDir());
        this.config.init();
        this.config.setRuntime("_instance.id", inst.id);
        this.config.setRuntime("_instance.type", inst.type);
        ConfigManager globalCfg = ConfigManager.getInstance();
        this.config.setRuntime("_sandbox.mode", globalCfg.get("_sandbox.mode", "none"));

        if (inst.isVelocity()) {
            ensureVelocityDefaults();
        } else {
            ensureMcDefaults();
            migratePortConfig();
        }

        // ★ 核心修复：构造函数只创建对象，不做任何可能阻塞的操作（initInstanceUser/ssh-keygen 等）
        this.perms = PlayerPerms.forRoot(inst.getDir());
        this.mcServer = new ServerProcess(config);
        this.commandRegistry = new CommandRegistry(mcServer);
        this.terminalHandler = new TerminalHandler(mcServer, config, commandRegistry, perms);
        this.rollbackService = new RollbackService(config, mcServer, terminalHandler);
        this.pluginManager = new PluginManager(mcServer, terminalHandler, config, commandRegistry);
        this.installerManager = new InstallerManager(config, mcServer);
    }

    private void ensureMcDefaults() {
        ensure("mc.start.command", "java -Xmx2G -jar server.jar nogui");
        ensure("mc.server.dir", "server");
        ensure("mc.auto.start", "true");
        ensure("mc.port", "25565");
    }

    private void ensureVelocityDefaults() {
        ensure("velocity.start.command", "java -Xms512M -Xmx512M -jar velocity.jar");
        ensure("velocity.server.dir", "server");
        ensure("velocity.auto.start", "true");
        ensure("velocity.port", "25577");
        ensure("terminal.shutdown.command", "shutdown");
    }

    private void ensure(String key, String def) {
        if (!config.has(key)) config.set(key, def);
    }

    private void migratePortConfig() {
        if (!config.get("mc.port", "").isEmpty()) return;
        String oldFrom = config.get("mc.port.forward.from", "");
        String oldTo = config.get("mc.port.forward.to", "");
        String port = !oldFrom.isEmpty() ? oldFrom : (!oldTo.isEmpty() ? oldTo : "25565");
        config.set("mc.port", port);
        config.remove("mc.port.forward.from");
        config.remove("mc.port.forward.to");
    }

    public synchronized void start() {
        if (started) return;
        started = true;

        // ★ 核心修复：系统用户初始化挪到这里，最多等 5 秒；超时就跳过（不用 su 启动）
        if (LinuxUserManager.isEnabled(this) && !LinuxUserManager.isUserReady(inst)) {
            final CountDownLatch latch = new CountDownLatch(1);
            com.mcpanel.util.Async.run(() -> {
                try {
                    LinuxUserManager.initInstanceUser(inst, inst.getDir());
                } catch (Throwable t) {
                    System.err.println("[Instance] 初始化系统用户失败: " + t.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(8, TimeUnit.SECONDS)) {
                    System.err.println("[Instance] " + inst.id + " 初始化系统用户超时，降级为普通启动");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        new BuiltinCommands(mcServer, config, terminalHandler, pluginManager,
                rollbackService, commandRegistry, perms).registerAll();

        mcServer.startIfPresent();
        pluginManager.loadAll();
    }

    public boolean isStarted() { return started; }

    /**
     * 释放运行时资源：杀进程树、摘监听器、停常驻写盘线程。
     * 实例被删除 / 移除运行时的时候必须调用，否则每次新建实例都会多留一条线程和几个监听器。
     */
    public void close() {
        try { mcServer.kill(); } catch (Throwable ignored) {}
        try { terminalHandler.close(); } catch (Throwable ignored) {}
    }
}