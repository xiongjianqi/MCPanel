package com.mcpanel.command;

import com.mcpanel.mc.ServerProcess;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CommandRegistry {

    public interface Handler {
        void handle(CommandSender sender, String[] args);
    }

    public static class Info {
        public String name;
        public PlayerLevel minLevel;
        public String description;
        public String usage;
        public Handler handler;
        public String ownerPlugin; // null = 内置
    }

    private final Map<String, Info> commands = new ConcurrentHashMap<>();
    private final ServerProcess serverProcess;

    public CommandRegistry() {
        this(null);
    }

    public CommandRegistry(ServerProcess serverProcess) {
        this.serverProcess = serverProcess;
    }

    public synchronized void register(String name, PlayerLevel minLevel,
                                      String description, String usage, Handler handler) {
        Info info = new Info();
        info.name = name.toLowerCase(Locale.ROOT);
        info.minLevel = minLevel;
        info.description = description;
        info.usage = usage;
        info.handler = handler;
        commands.put(info.name, info);
    }

    public synchronized void registerPluginCommand(String pluginName, String name,
                                                   PlayerLevel minLevel, Handler handler) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        // ★ 核心修复：插件命令不能覆盖内置命令
        Info existing = commands.get(lowerName);
        if (existing != null && existing.ownerPlugin == null) {
            throw new IllegalArgumentException("插件命令名与内置命令冲突: " + lowerName);
        }
        
        Info info = new Info();
        info.name = lowerName;
        info.minLevel = minLevel;
        info.description = "由插件 " + pluginName + " 提供";
        info.usage = "!!" + lowerName;
        info.handler = handler;
        info.ownerPlugin = pluginName;
        commands.put(info.name, info);
    }

    public synchronized void unregister(String name) {
        commands.remove(name.toLowerCase(Locale.ROOT));
    }

    public synchronized int unregisterPluginCommands(String pluginName) {
        if (pluginName == null) return 0;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Info> e : commands.entrySet()) {
            if (pluginName.equals(e.getValue().ownerPlugin)) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) commands.remove(k);
        return toRemove.size();
    }

    public synchronized List<String> listPluginCommands(String pluginName) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Info> e : commands.entrySet()) {
            if (pluginName.equals(e.getValue().ownerPlugin)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private String stripPrefix(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("!!")) {
            return s.substring(2).trim();
        }
        if (s.startsWith("!")) {
            return s.substring(1).trim();
        }
        return null;
    }

    public boolean dispatch(CommandSender sender, String raw) {
        String body = stripPrefix(raw);
        if (body == null || body.isEmpty()) return false;

        String[] parts = body.split("\\s+");
        String cmdName = parts[0].toLowerCase(Locale.ROOT);
        Info info = commands.get(cmdName);
        if (info == null) return false;

        if (info.ownerPlugin != null && serverProcess != null && !serverProcess.isRunning()) {
            sender.reply("§cMC 服务器未运行，插件命令 §e!" + cmdName + " §c暂不可用");
            return true;
        }

        if (sender.getLevel().level < info.minLevel.level) {
            sender.reply("§c权限不足，需要 §e" + info.minLevel.label + " §c级别（当前 §e"
                    + sender.getLevel().label + "§c）");
            return true;
        }

        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
        try {
            info.handler.handle(sender, args);
        } catch (Throwable t) {
            // ★ 修复：异常信息脱敏，不再回显详细堆栈给用户
            sender.reply("§c命令执行出错，请联系管理员查看后台日志");
            t.printStackTrace();
        }
        return true;
    }

    public Collection<Info> all() { return commands.values(); }
    public Info get(String name) { return commands.get(name.toLowerCase(Locale.ROOT)); }
    public boolean has(String name) { return commands.containsKey(name.toLowerCase(Locale.ROOT)); }
}