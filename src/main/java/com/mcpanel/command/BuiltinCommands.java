package com.mcpanel.command;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;
import com.mcpanel.plugin.PluginManager;
import com.mcpanel.web.RollbackService;
import com.mcpanel.web.TerminalHandler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class BuiltinCommands {
    private final ServerProcess mcServer;
    private final ConfigManager config;
    private final TerminalHandler terminal;
    private final PluginManager pluginManager;
    private final RollbackService rollbackService;
    private final CommandRegistry registry;
    private final PlayerPerms perms;

    public BuiltinCommands(ServerProcess mcServer, ConfigManager config,
                           TerminalHandler terminal, PluginManager pluginManager,
                           RollbackService rollbackService, CommandRegistry registry,
                           PlayerPerms perms) {
        this.mcServer = mcServer;
        this.config = config;
        this.terminal = terminal;
        this.pluginManager = pluginManager;
        this.rollbackService = rollbackService;
        this.registry = registry;
        this.perms = perms;
    }

    public void registerAll() {
        registry.register("panel", PlayerLevel.GUEST, "面板命令", "!panel help", this::handle);
        registry.register("p", PlayerLevel.GUEST, "面板命令简写", "!p help", this::handle);
    }

    private void handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.reply("§a[Panel] 面板插件系统已成功加载");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (sub) {
            case "help":   cmdHelp(sender); break;
            case "status": cmdStatus(sender); break;
            case "version": cmdVersion(sender); break;
            case "reload": cmdReload(sender, rest); break;
            case "echo":   cmdEcho(sender, rest); break;
            case "say":    cmdSay(sender, rest); break;
            case "server": cmdServer(sender, rest); break;
            case "plugin": cmdPlugin(sender, rest); break;
            case "backup": cmdBackup(sender, rest); break;
            case "perm":   cmdPerm(sender, rest); break;
            default:
                sender.reply("§c未知子命令 §e" + sub + "§c，输入 §e!panel help §c查看帮助");
        }
    }

    private void cmdHelp(CommandSender s) {
        s.reply("§6========== §e!Panel 帮助 §6==========");
        s.reply("§7命令前缀可以是 §e! §7或 §e!!");
        s.reply("§a!panel §7- 显示加载状态");
        s.reply("§a!panel status §7- 面板状态");
        s.reply("§a!panel version §7- 版本信息");
        s.reply("§a!panel help §7- 显示帮助");
        s.reply("§a!panel echo <内容> §7- 回显内容");
        s.reply("§a!panel say <内容> §7- 向全服广播");
        s.reply("§6--- 服务器 ---");
        s.reply("§a!panel server status §7- MC 服务器状态");
        s.reply("§a!panel server start|stop|restart|kill §7- 启停控制");
        s.reply("§6--- 插件 ---");
        s.reply("§a!panel plugin list §7- 插件列表");
        s.reply("§a!panel plugin load|unload|enable|disable|reload|delete <id> §7- 插件控制");
        s.reply("§6--- 备份 ---");
        s.reply("§a!panel backup list §7- 备份列表");
        s.reply("§a!panel backup create <名字> [描述] §7- 创建备份");
        s.reply("§a!panel backup rollback <名字> §7- 回档（10 秒倒计时 + 关服 + 替换 world + 重启）");
        s.reply("§a!panel backup delete <名字> §7- 删除备份");
        s.reply("§6--- 权限 ---");
        s.reply("§a!panel perm list [玩家] §7- 列出权限");
        s.reply("§a!panel perm set <玩家> <等级> §7- 设置权限");
        s.reply("§a!panel perm remove <玩家> §7- 移除权限");
        s.reply("§a!panel perm setdefault <等级> §7- 设置默认权限");
        s.reply("§6--- 其他 ---");
        s.reply("§a!panel reload §7- 重载配置");
        s.reply("§7简写: §e!p §7（即 §e!!p §7也支持）");
        s.reply("§7等级: §eGUEST §7< §eUSER §7< §eHELPER §7< §eADMIN §7< §eOWNER");
    }

    private void cmdStatus(CommandSender s) {
        s.reply("§6========== 面板状态 ==========");
        s.reply("§a插件系统: §e运行中");
        s.reply("§aMC 状态: §e" + (mcServer.isRunning() ? "运行中" : "已停止"));
        if (mcServer.isRunning()) {
            s.reply("§aMC PID: §e" + mcServer.getPid());
            long up = mcServer.getUptime() / 1000;
            s.reply("§a运行时长: §e" + (up / 3600) + "h " + (up % 3600 / 60) + "m " + (up % 60) + "s");
        }
        s.reply("§a已加载插件: §e" + pluginManager.listPlugins().stream()
                .filter(m -> Boolean.TRUE.equals(m.get("loaded"))).count() + " §7个");
    }

    private void cmdVersion(CommandSender s) {
        s.reply("§6MC Panel §e1.0.0");
        s.reply("§7插件系统: v1.0");
    }

    private void cmdReload(CommandSender s, String[] args) {
        if (s.getLevel().level < PlayerLevel.ADMIN.level) {
            s.reply("§c权限不足，需要 ADMIN");
            return;
        }
        config.init();
        s.reply("§a配置已重载");
        s.log("[Panel] 配置已重载");
    }

    private void cmdEcho(CommandSender s, String[] args) {
        if (args.length == 0) { s.reply("§c用法: !panel echo <内容>"); return; }
        s.reply("§e" + String.join(" ", args));
    }

    private void cmdSay(CommandSender s, String[] args) {
        if (s.getLevel().level < PlayerLevel.HELPER.level) {
            s.reply("§c权限不足，需要 HELPER");
            return;
        }
        if (args.length == 0) { s.reply("§c用法: !panel say <内容>"); return; }
        String msg = String.join(" ", args).replaceAll("§.", "").replace("&", "");
        if (msg.length() > 200) msg = msg.substring(0, 200);
        s.broadcast("[Panel] " + msg);
        s.reply("§a已广播");
    }

    private void cmdServer(CommandSender s, String[] args) {
        if (args.length == 0) {
            s.reply("§c用法: !panel server <status|start|stop|restart|kill>");
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        if ("status".equals(op)) {
            if (mcServer.isRunning()) {
                s.reply("§aMC 运行中，PID §e" + mcServer.getPid());
            } else {
                s.reply("§7MC 已停止");
            }
            return;
        }
        if (s.getLevel().level < PlayerLevel.ADMIN.level) {
            s.reply("§c权限不足，需要 ADMIN");
            return;
        }
        switch (op) {
            case "start":
                if (mcServer.isRunning()) { s.reply("§cMC 已在运行"); return; }
                new Thread(mcServer::start, "mc-start").start();
                s.reply("§a正在启动 MC...");
                break;
            case "stop":
                if (!mcServer.isRunning()) { s.reply("§cMC 未运行"); return; }
                new Thread(mcServer::shutdown, "mc-stop").start();
                s.reply("§a正在关闭 MC...");
                break;
            case "restart":
                new Thread(mcServer::restart, "mc-restart").start();
                s.reply("§a正在重启 MC...");
                break;
            case "kill":
                mcServer.kill();
                s.reply("§c已强制终止");
                break;
            default:
                s.reply("§c未知操作: " + op);
        }
    }

    private static String stripJar(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) return name.substring(0, name.length() - 4);
        return name;
    }

    private void cmdPlugin(CommandSender s, String[] args) {
        if (args.length == 0) {
            s.reply("§c用法: !panel plugin <list|load|unload|enable|disable|reload|delete> [id]");
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        if ("list".equals(op)) {
            if (s.getLevel().level < PlayerLevel.USER.level) {
                s.reply("§c权限不足，需要 USER"); return;
            }
            s.reply("§6========== 插件列表 ==========");
            List<Map<String, Object>> list = pluginManager.listPlugins();
            if (list.isEmpty()) { s.reply("§7（无）"); return; }
            for (Map<String, Object> m : list) {
                String status = Boolean.TRUE.equals(m.get("enabled")) ? "§a[启用]"
                        : Boolean.TRUE.equals(m.get("loaded")) ? "§e[禁用]" : "§7[未加载]";
                String id = String.valueOf(m.get("id"));
                String name = String.valueOf(m.get("name"));
                String ver = String.valueOf(m.get("version"));
                String verPart = (ver == null || ver.isEmpty() || "-".equals(ver))
                        ? "" : " §7v" + ver;
                s.reply(status + " §e" + id + " §7- §f" + name + verPart);
            }
            return;
        }
        if (s.getLevel().level < PlayerLevel.ADMIN.level) {
            s.reply("§c权限不足，需要 ADMIN");
            return;
        }
        if (args.length < 2) { s.reply("§c缺少插件 id"); return; }
        
        String key = args[1];
        // ★ 核心修复：严格校验插件文件名，防止路径穿越
        if (!key.matches("^[a-zA-Z0-9_\\-]+$")) {
            s.reply("§c非法插件标识，只能包含字母、数字、下划线、连字符");
            return;
        }

        try {
            switch (op) {
                case "load": {
                    File f = pluginManager.findPluginJar(key);
                    if (f == null) {
                        s.reply("§c找不到插件: " + key);
                        break;
                    }
                    boolean ok = pluginManager.loadPlugin(f, true);
                    s.reply(ok ? "§a已加载 " + stripJar(f.getName())
                               : "§c加载失败");
                    break;
                }
                case "unload":
                    pluginManager.unloadPlugin(key);
                    s.reply("§a已卸载 " + key);
                    break;
                case "enable":
                    pluginManager.enablePlugin(key);
                    s.reply("§a已启用 " + key);
                    break;
                case "disable":
                    pluginManager.disablePlugin(key);
                    s.reply("§a已禁用 " + key);
                    break;
                case "delete":
                    boolean okd = pluginManager.deletePlugin(key);
                    s.reply(okd ? "§a已删除 " + key : "§c删除失败");
                    break;
                case "reload":
                    pluginManager.unloadPlugin(key);
                    Thread.sleep(300);
                    File rf = pluginManager.findPluginJar(key);
                    if (rf == null) {
                        s.reply("§c重载失败：找不到 jar");
                        break;
                    }
                    pluginManager.loadPlugin(rf, true);
                    s.reply("§a已重载 " + stripJar(rf.getName()));
                    break;
                default:
                    s.reply("§c未知操作: " + op);
            }
        } catch (Exception e) {
            s.reply("§c操作失败: " + e.getMessage());
        }
    }

    private void cmdBackup(CommandSender s, String[] args) {
        if (args.length == 0) {
            s.reply("§c用法: !panel backup <list|create|rollback|delete> [参数]");
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        Path backupRoot = config.resolve("backup").toPath().toAbsolutePath().normalize();

        if ("list".equals(op)) {
            if (s.getLevel().level < PlayerLevel.USER.level) {
                s.reply("§c权限不足，需要 USER"); return;
            }
            s.reply("§6========== 备份列表 ==========");
            if (!Files.exists(backupRoot)) { s.reply("§7（无）"); return; }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupRoot)) {
                int n = 0;
                for (Path p : ds) {
                    if (!Files.isDirectory(p)) continue;
                    String desc = "";
                    Path d = p.resolve("backuptext.txt");
                    if (Files.exists(d)) desc = new String(Files.readAllBytes(d), StandardCharsets.UTF_8);
                    String safeDesc = desc.replaceAll("§.", "");
                    s.reply("§a" + p.getFileName() + (safeDesc.isEmpty() ? "" : " §7- " + safeDesc));
                    n++;
                }
                if (n == 0) s.reply("§7（无）");
            } catch (IOException e) {
                s.reply("§c读取失败: " + e.getMessage());
            }
            return;
        }

        if (s.getLevel().level < PlayerLevel.ADMIN.level) {
            s.reply("§c权限不足，需要 ADMIN");
            return;
        }

        if ("create".equals(op)) {
            if (args.length < 2) { s.reply("§c用法: !panel backup create <名字> [描述]"); return; }
            String name = args[1];
            if (!name.matches("^[A-Za-z0-9_\\-]+$")) {
                s.reply("§c名字只能包含字母、数字、-、_"); return;
            }
            String desc = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
            s.reply("§e正在备份，请稍候...");
            new Thread(() -> {
                try {
                    Path dest = backupRoot.resolve(name);
                    if (Files.exists(dest)) { s.reply("§c同名备份已存在"); return; }
                    Path src = config.resolve(config.get("mc.server.dir", "server") + "/world")
                            .toPath().toAbsolutePath().normalize();
                    if (mcServer.isRunning()) {
                        mcServer.sendCommand("save-all");
                        Thread.sleep(3000);
                    }
                    copyDir(src, dest);
                    Files.write(dest.resolve("backuptext.txt"), desc.getBytes(StandardCharsets.UTF_8));
                    s.reply("§a备份完成: " + name);
                } catch (Exception e) {
                    s.reply("§c备份失败: " + e.getMessage());
                }
            }, "cmd-backup").start();
            return;
        }

        if ("delete".equals(op)) {
            if (args.length < 2) { s.reply("§c用法: !panel backup delete <名字>"); return; }
            String name = args[1];
            if (!name.matches("^[A-Za-z0-9_\\-]+$")) {
                s.reply("§c名字只能包含字母、数字、-、_"); return;
            }
            Path target = backupRoot.resolve(name).normalize();
            if (!target.startsWith(backupRoot) || !Files.exists(target)) {
                s.reply("§c备份不存在: " + name);
                return;
            }
            try {
                deleteRecursive(target);
                s.reply("§a已删除备份: " + name);
                s.log("[Panel] " + s.name + " 删除了备份：" + name);
            } catch (IOException e) {
                s.reply("§c删除失败: " + e.getMessage());
            }
            return;
        }

        if ("rollback".equals(op)) {
            if (args.length < 2) { s.reply("§c用法: !panel backup rollback <名字>"); return; }
            String name = args[1];
            if (!name.matches("^[A-Za-z0-9_\\-]+$")) {
                s.reply("§c名字只能包含字母、数字、-、_"); return;
            }
            Path src = backupRoot.resolve(name).normalize();
            if (!src.startsWith(backupRoot) || !Files.exists(src)) {
                s.reply("§c备份不存在: " + name);
                return;
            }
            if (rollbackService == null) {
                s.reply("§c回档服务未初始化");
                return;
            }
            if (rollbackService.isRunning()) {
                s.reply("§c已有回档任务在运行，请稍候");
                return;
            }
            if (!rollbackService.startRollback(name)) {
                s.reply("§c回档启动失败");
                return;
            }
            s.reply("§e回档已启动：§6" + name);
            s.reply("§7MC 运行中时会先倒计时 10 秒，然后关服、替换 world、重启");
            s.log("[Panel] " + s.name + " 触发了回档：" + name);
            return;
        }

        s.reply("§c未知操作: " + op);
    }

    private void copyDir(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) throw new IOException("world 目录不存在");
        Files.createDirectories(dst);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path p : ds) {
                Path t = dst.resolve(p.getFileName().toString());
                if (Files.isDirectory(p)) copyDir(p, t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteRecursive(c);
            }
        }
        Files.deleteIfExists(p);
    }

    private void cmdPerm(CommandSender s, String[] args) {
        if (args.length == 0) {
            s.reply("§c用法: !panel perm <list|set|remove|setdefault> [参数]");
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        PlayerPerms p = (perms != null) ? perms : PlayerPerms.getInstance();

        if ("list".equals(op)) {
            if (s.getLevel().level < PlayerLevel.HELPER.level) {
                s.reply("§c权限不足，需要 HELPER"); return;
            }
            if (args.length >= 2) {
                String player = args[1];
                PlayerLevel lv = p.getLevel(player);
                s.reply("§a" + player + " §7-> §e" + lv.label + " §7(" + lv.level + ")");
                return;
            }
            Map<String, Integer> all = p.all();
            s.reply("§6========== 玩家权限 ==========");
            if (all.isEmpty()) s.reply("§7（无自定义）");
            else for (Map.Entry<String, Integer> e : all.entrySet()) {
                PlayerLevel lv = PlayerLevel.fromInt(e.getValue());
                s.reply("§a" + e.getKey() + " §7- §e" + lv.label);
            }
            s.reply("§7默认等级: §e" + PlayerLevel.fromInt(p.getDefaultLevel()).label);
            return;
        }

        if (s.getLevel().level < PlayerLevel.ADMIN.level) {
            s.reply("§c权限不足，需要 ADMIN");
            return;
        }

        if ("set".equals(op)) {
            if (args.length < 3) { s.reply("§c用法: !panel perm set <玩家> <等级>"); return; }
            PlayerLevel lv = PlayerLevel.fromName(args[2]);
            if (lv == null) { s.reply("§c等级必须是 GUEST/USER/HELPER/ADMIN/OWNER"); return; }
            
            // ★ 核心修复：禁止分配高于自身等级的权限，且禁止修改自己的权限
            if (s.getLevel().level < lv.level) {
                s.reply("§c权限不足：您无法分配比自己更高的等级");
                return;
            }
            if (s.name.equals(args[1])) {
                s.reply("§c禁止修改自己的权限");
                return;
            }
            
            p.setLevel(args[1], lv);
            s.reply("§a已设置 " + args[1] + " -> " + lv.label);
            s.log("[Panel] " + s.name + " 设置 " + args[1] + " 权限为 " + lv.label);
            return;
        }
        if ("remove".equals(op)) {
            if (args.length < 2) { s.reply("§c用法: !panel perm remove <玩家>"); return; }
            p.remove(args[1]);
            s.reply("§a已移除 " + args[1]);
            return;
        }
        if ("setdefault".equals(op)) {
            if (args.length < 2) { s.reply("§c用法: !panel perm setdefault <等级>"); return; }
            PlayerLevel lv = PlayerLevel.fromName(args[1]);
            if (lv == null) { s.reply("§c等级无效"); return; }
            // ★ 核心修复：禁止设置高于自身的默认等级
            if (s.getLevel().level < lv.level) {
                s.reply("§c权限不足：您无法设置比自己更高的默认等级");
                return;
            }
            p.setDefaultLevel(lv.level);
            s.reply("§a默认等级已设为 " + lv.label);
            return;
        }
        s.reply("§c未知操作: " + op);
    }
}