package com.mcpanel.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.command.CommandRegistry;
import com.mcpanel.command.CommandSender;
import com.mcpanel.command.PlayerLevel;
import com.mcpanel.command.PlayerPerms;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;
import com.mcpanel.web.TerminalHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

public class PluginManager {
    private final ServerProcess mcServer;
    private final TerminalHandler terminal;
    private final ConfigManager config;
    private final CommandRegistry registry;
    private final PlayerPerms perms;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();
    private final Map<String, String> byId = new ConcurrentHashMap<>();

    public static class LoadedPlugin {
        public String jarName;
        public String id;
        public File jarFile;
        public MCPlugin plugin;
        public URLClassLoader classLoader;
        public PluginContext context;
        public boolean enabled;
        public JarMeta jarMeta;
    }

    public static class JarMeta {
        public String id;
        public String name;
        public String version;
        public String author;
        public String description;
        public String mainClass;
    }

    public PluginManager(ServerProcess mcServer, TerminalHandler terminal,
                         ConfigManager config, CommandRegistry registry) {
        this.mcServer = mcServer;
        this.terminal = terminal;
        this.config = config;
        this.registry = registry;
        this.perms = PlayerPerms.forRoot(config.getRootDir());

        if (mcServer != null) {
            mcServer.addChatListener((player, msg) -> {
                if (msg != null && (msg.startsWith("!!") || msg.startsWith("!"))) {
                    CommandSender sender = new CommandSender(
                            CommandSender.Type.PLAYER, player, mcServer, terminal, perms);
                    registry.dispatch(sender, msg);
                }
            });
        }
    }

    // ===================== 工具 =====================

    private static boolean isJar(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String sanitizeId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String lower = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            if (sb.length() >= 10) break;
        }
        return sb.toString();
    }

    private static String idFromJarName(String jarName) {
        if (jarName == null) return "";
        String base = jarName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        return sanitizeId(base);
    }

    private static String stripJarExt(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) return name.substring(0, name.length() - 4);
        return name;
    }

    private static boolean isZipError(Throwable t) {
        if (t == null) return false;
        if (t instanceof ZipException) return true;
        String cn = t.getClass().getName();
        if (cn.contains("ZipException")) return true;
        String msg = t.getMessage();
        if (msg != null) {
            String m = msg.toLowerCase(Locale.ROOT);
            if (m.contains("zip") || m.contains("invalid entry")
                    || m.contains("central directory")) return true;
        }
        return false;
    }

    private static boolean isJsonError(Throwable t) {
        if (t == null) return false;
        String cn = t.getClass().getName();
        if (cn.contains("JsonParse") || cn.contains("JsonMapping")
                || cn.contains("JsonProcessing")) return true;
        Throwable c = t.getCause();
        if (c != null && c != t) return isJsonError(c);
        return false;
    }

    // ===================== 读 plugin.json =====================

    public static JarMeta readMetaFromJar(File jarFile) {
        JarMeta meta = new JarMeta();
        if (jarFile == null || !jarFile.isFile()) return meta;
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.json");
            if (entry == null) return meta;
            try (InputStream is = jar.getInputStream(entry)) {
                ObjectMapper m = new ObjectMapper();
                Map<?, ?> map = m.readValue(is, Map.class);
                Object ido = map.get("id");
                if (ido != null) {
                    String s = sanitizeId(ido.toString());
                    if (!s.isEmpty()) meta.id = s;
                }
                Object nameo = map.get("name");
                if (nameo != null) meta.name = nameo.toString();
                Object vero = map.get("version");
                if (vero != null) meta.version = vero.toString();
                Object authoro = map.get("author");
                if (authoro != null) meta.author = authoro.toString();
                Object desco = map.get("description");
                if (desco != null) meta.description = desco.toString();
                Object mco = map.get("mainClass");
                if (mco != null) meta.mainClass = mco.toString();
            }
        } catch (Exception ignored) {}
        return meta;
    }

    public static String readIdFromJar(File jarFile) {
        return readMetaFromJar(jarFile).id;
    }

    public static String computeId(File jarFile) {
        String id = readMetaFromJar(jarFile).id;
        if (id == null || id.isEmpty()) id = idFromJarName(jarFile.getName());
        return id;
    }

    private String assignId(String jarName, String declaredFromJson) {
        String id = "";
        if (declaredFromJson != null && !declaredFromJson.isEmpty()) {
            id = sanitizeId(declaredFromJson);
        }
        if (id.isEmpty()) id = idFromJarName(jarName);
        if (id.isEmpty()) id = "plugin";

        String candidate = id;
        int n = 1;
        while (byId.containsKey(candidate)) {
            candidate = id + (++n);
            if (candidate.length() > 10) candidate = candidate.substring(0, 10);
        }
        return candidate;
    }

    private String resolveJarName(String key) {
        if (key == null || key.isEmpty()) return null;
        if (loaded.containsKey(key)) return key;
        String jn = byId.get(key.toLowerCase(Locale.ROOT));
        if (jn != null && loaded.containsKey(jn)) return jn;
        for (String k : loaded.keySet()) {
            if (k.equalsIgnoreCase(key)) return k;
        }
        File dir = config.resolve("plugins");
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles((d, n) -> isJar(new File(n)));
        if (files == null || files.length == 0) return null;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(key)) return f.getName();
        }
        String keyWithJar = key.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? key : key + ".jar";
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(keyWithJar)) return f.getName();
        }
        for (File f : files) {
            if (stripJarExt(f.getName()).equalsIgnoreCase(key)) return f.getName();
        }
        for (File f : files) {
            String id = computeId(f);
            if (id.equalsIgnoreCase(key)) return f.getName();
        }
        return null;
    }

    private static String pickName(JarMeta meta, MCPlugin plugin, String jarName) {
        if (meta != null && meta.name != null && !meta.name.isEmpty()) return meta.name;
        if (plugin != null) {
            try { return plugin.getName(); } catch (Throwable ignored) {}
        }
        return stripJarExt(jarName);
    }
    private static String pickVersion(JarMeta meta, MCPlugin plugin) {
        if (meta != null && meta.version != null && !meta.version.isEmpty()) return meta.version;
        if (plugin != null) {
            try { return plugin.getVersion(); } catch (Throwable ignored) {}
        }
        return "";
    }
    private static String pickAuthor(JarMeta meta, MCPlugin plugin) {
        if (meta != null && meta.author != null && !meta.author.isEmpty()) return meta.author;
        if (plugin != null) {
            try { return plugin.getAuthor(); } catch (Throwable ignored) {}
        }
        return "-";
    }
    private static String pickDescription(JarMeta meta, MCPlugin plugin) {
        if (meta != null && meta.description != null && !meta.description.isEmpty()) return meta.description;
        if (plugin != null) {
            try { return plugin.getDescription(); } catch (Throwable ignored) {}
        }
        return "";
    }

    // ===================== 加载 / 卸载 =====================

    public void loadAll() {
        Path dir = config.resolve("plugins").toPath();
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                File f = p.toFile();
                if (isJar(f)) loadPlugin(f, true);
            }
        } catch (IOException e) {
            terminal.log("[Plugin] 扫描失败: " + e.getMessage());
        }
    }

    public synchronized boolean loadPlugin(File jarFile, boolean autoEnable) {
        if (jarFile == null || !jarFile.exists()) return false;
        String jarName = jarFile.getName();
        if (loaded.containsKey(jarName)) return false;

        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(
                    new URL[]{jarFile.toURI().toURL()},
                    PluginManager.class.getClassLoader());

            String mainClass;
            JarMeta jarMeta = new JarMeta();
            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry meta = jar.getJarEntry("plugin.json");
                if (meta == null) {
                    terminal.log("[Plugin] " + jarName + " 缺少 plugin.json");
                    return false;
                }
                try (InputStream is = jar.getInputStream(meta)) {
                    Map<?, ?> map = mapper.readValue(is, Map.class);
                    Object mc = map.get("mainClass");
                    mainClass = mc == null ? null : mc.toString();
                    Object ido = map.get("id");
                    if (ido != null) {
                        String s = sanitizeId(ido.toString());
                        if (!s.isEmpty()) jarMeta.id = s;
                    }
                    Object nameo = map.get("name");
                    if (nameo != null) jarMeta.name = nameo.toString();
                    Object vero = map.get("version");
                    if (vero != null) jarMeta.version = vero.toString();
                    Object authoro = map.get("author");
                    if (authoro != null) jarMeta.author = authoro.toString();
                    Object desco = map.get("description");
                    if (desco != null) jarMeta.description = desco.toString();
                    jarMeta.mainClass = mainClass;
                }
            }
            if (mainClass == null || mainClass.isEmpty()) {
                terminal.log("[Plugin] " + jarName + " plugin.json 缺少 mainClass");
                return false;
            }
            Class<?> cls = Class.forName(mainClass, true, loader);
            if (!MCPlugin.class.isAssignableFrom(cls)) {
                terminal.log("[Plugin] " + mainClass + " 未实现 MCPlugin");
                return false;
            }
            MCPlugin plugin = (MCPlugin) cls.getDeclaredConstructor().newInstance();

            LoadedPlugin lp = new LoadedPlugin();
            lp.jarName = jarName;
            lp.jarFile = jarFile;
            lp.plugin = plugin;
            lp.classLoader = loader;
            lp.jarMeta = jarMeta;
            lp.id = assignId(jarName, jarMeta.id);

            String safeName = plugin.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            File dataFolder = config.resolve("plugins-data/" + safeName);
            dataFolder.mkdirs();
            lp.context = new SimpleContext(this, plugin, dataFolder, registry, perms);

            plugin.onLoad(lp.context);
            if (autoEnable) {
                try {
                    plugin.onEnable();
                    lp.enabled = true;
                } catch (Throwable t) {
                    terminal.log("[Plugin] " + plugin.getName() + " onEnable 失败: " + t.getMessage());
                }
            }
            loaded.put(jarName, lp);
            byId.put(lp.id, jarName);

            List<String> cmds = registry.listPluginCommands(plugin.getName());
            String dispName    = pickName(jarMeta, plugin, jarName);
            String dispVersion = pickVersion(jarMeta, plugin);
            String dispAuthor  = pickAuthor(jarMeta, plugin);
            // 仅在后台控制台输出，不再往 Web 终端里刷屏
            System.out.println("[Plugin] 已加载 " + dispName + " v" + dispVersion
                    + " by " + dispAuthor
                    + (lp.enabled ? " [启用]" : " [未启用]")
                    + "（id=" + lp.id + "，命令=" + cmds + "）");
            return true;
        } catch (Throwable t) {
            if (loader != null) {
                try { loader.close(); } catch (IOException ignored) {}
            }
            if (isJsonError(t)) {
                terminal.log("[Plugin] " + jarName
                        + " 的 plugin.json 格式错误（通常是缺少逗号、括号不匹配，或多余的逗号）");
                terminal.log("[Plugin] 具体信息: " + t.getMessage());
            } else if (isZipError(t)) {
                terminal.log("[Plugin] " + jarName
                        + " 不是有效的 JAR 包（可能上传不完整、损坏，或不是 ZIP 格式）");
            } else {
                terminal.log("[Plugin] 加载 " + jarName + " 失败: " + t.getMessage());
            }
            return false;
        }
    }

    public synchronized void enablePlugin(String key) {
        String jarName = resolveJarName(key);
        if (jarName == null) { terminal.log("[Plugin] 未找到插件 " + key); return; }
        LoadedPlugin lp = loaded.get(jarName);
        if (lp == null || lp.enabled) return;
        try {
            lp.plugin.onEnable();
            lp.enabled = true;
            terminal.log("[Plugin] 已启用 " + pickName(lp.jarMeta, lp.plugin, lp.jarName)
                    + " (" + lp.id + ")");
        } catch (Throwable t) {
            terminal.log("[Plugin] 启用 " + key + " 失败: " + t.getMessage());
        }
    }

    public synchronized void disablePlugin(String key) {
        String jarName = resolveJarName(key);
        if (jarName == null) { terminal.log("[Plugin] 未找到插件 " + key); return; }
        LoadedPlugin lp = loaded.get(jarName);
        if (lp == null || !lp.enabled) return;
        try { lp.plugin.onDisable(); } catch (Throwable t) {
            terminal.log("[Plugin] onDisable 异常: " + t.getMessage());
        }
        lp.enabled = false;
        terminal.log("[Plugin] 已禁用 " + pickName(lp.jarMeta, lp.plugin, lp.jarName)
                + " (" + lp.id + ")");
    }

    public synchronized void unloadPlugin(String key) {
        String jarName = resolveJarName(key);
        if (jarName == null) {
            terminal.log("[Plugin] 未找到插件 " + key + "（既不是 jar 名也不是 id）");
            return;
        }
        LoadedPlugin lp = loaded.remove(jarName);
        if (lp == null) return;
        byId.remove(lp.id);

        String pluginName = lp.plugin.getName();
        List<String> beforeCmds = registry.listPluginCommands(pluginName);
        int totalCmds = beforeCmds.size();
        int beforeOutListeners = 0, beforeStatusListeners = 0;
        SimpleContext sc = null;
        if (lp.context instanceof SimpleContext) {
            sc = (SimpleContext) lp.context;
            beforeOutListeners = sc.outputListeners.size();
            beforeStatusListeners = sc.statusListeners.size();
        }

        if (lp.enabled) {
            try { lp.plugin.onDisable(); }
            catch (Throwable t) { terminal.log("[Plugin] onDisable 异常: " + t.getMessage()); }
        }
        try { lp.plugin.onUnload(); }
        catch (Throwable t) { terminal.log("[Plugin] onUnload 异常: " + t.getMessage()); }

        int panelClearedCmds = registry.unregisterPluginCommands(pluginName);
        int pluginClearedCmds = totalCmds - panelClearedCmds;

        int panelClearedOut = 0, panelClearedStatus = 0;
        if (sc != null) {
            panelClearedOut = sc.outputListeners.size();
            panelClearedStatus = sc.statusListeners.size();
            sc.cleanup();
        }
        int pluginClearedOut = beforeOutListeners - panelClearedOut;
        int pluginClearedStatus = beforeStatusListeners - panelClearedStatus;

        // ★ 清空这个插件的侧边栏页面 + 自定义 API
        PluginPageRegistry.unregisterAll(pluginName);
        PluginApiRegistry.unregisterAll(pluginName);
        PluginWebRouter.unregister(lp.id);

        try { lp.classLoader.close(); } catch (IOException ignored) {}

        terminal.log("[Plugin] 已卸载 " + pickName(lp.jarMeta, lp.plugin, lp.jarName)
                + " (" + lp.id + ")"
                + "，清理 " + totalCmds + " 条命令"
                + "（插件自清 " + pluginClearedCmds + "，面板兜底 " + panelClearedCmds + "）"
                + "，输出监听 " + beforeOutListeners
                + "（自清 " + pluginClearedOut + "，兜底 " + panelClearedOut + "）"
                + "，状态监听 " + beforeStatusListeners
                + "（自清 " + pluginClearedStatus + "，兜底 " + panelClearedStatus + "）");
    }

    public synchronized boolean deletePlugin(String key) {
        if (key == null || key.isEmpty() || !key.matches("^[a-zA-Z0-9_\\-.]+$")) return false;
        String jarName = resolveJarName(key);
        if (jarName != null) {
            LoadedPlugin lp = loaded.get(jarName);
            if (lp != null) unloadPlugin(jarName);
            File f = config.resolve("plugins/" + jarName);
            if (f.exists()) {
                boolean ok = f.delete();
                if (!ok) terminal.log("[Plugin] 删除文件失败（可能仍被占用）: " + f.getPath());
                return ok;
            }
        }
        File dir = config.resolve("plugins");
        if (!dir.isDirectory()) return false;
        String nameWithJar = key.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? key : key + ".jar";
        File f = new File(dir, nameWithJar);
        if (f.isFile()) return f.delete();
        f = new File(dir, key);
        if (f.isFile()) return f.delete();
        terminal.log("[Plugin] 找不到插件文件: " + key);
        return false;
    }

    public File findPluginJar(String key) {
        if (key == null || key.isEmpty() || !key.matches("^[a-zA-Z0-9_\\-.]+$")) return null;
        File dir = config.resolve("plugins");
        if (!dir.isDirectory()) return null;
        File f1 = new File(dir, key);
        if (f1.isFile()) return f1;
        if (!key.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            File f2 = new File(dir, key + ".jar");
            if (f2.isFile()) return f2;
        }
        File[] files = dir.listFiles((d, n) -> isJar(new File(n)));
        if (files != null) {
            for (File f : files) {
                String id = computeId(f);
                if (id.equalsIgnoreCase(key)) return f;
                String nameNoExt = stripJarExt(f.getName());
                if (nameNoExt.equalsIgnoreCase(key)) return f;
            }
        }
        return null;
    }

    public List<Map<String, Object>> listPlugins() {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LoadedPlugin lp : loaded.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jarName", lp.jarName);
            m.put("id", lp.id);
            m.put("name", pickName(lp.jarMeta, lp.plugin, lp.jarName));
            m.put("version", pickVersion(lp.jarMeta, lp.plugin));
            m.put("author", pickAuthor(lp.jarMeta, lp.plugin));
            m.put("description", pickDescription(lp.jarMeta, lp.plugin));
            m.put("enabled", lp.enabled);
            m.put("loaded", true);
            list.add(m);
            seen.add(lp.jarName);
        }
        File dir = config.resolve("plugins");
        if (dir.isDirectory()) {
            File[] files = dir.listFiles((d, n) -> isJar(new File(n)));
            if (files != null) {
                for (File f : files) {
                    if (seen.contains(f.getName())) continue;
                    JarMeta meta = readMetaFromJar(f);
                    String name = (meta.name != null && !meta.name.isEmpty())
                            ? meta.name : stripJarExt(f.getName());
                    String version = (meta.version != null && !meta.version.isEmpty())
                            ? meta.version : "";
                    String author = (meta.author != null && !meta.author.isEmpty())
                            ? meta.author : "-";
                    String description = (meta.description != null && !meta.description.isEmpty())
                            ? meta.description : "";
                    String id = (meta.id != null && !meta.id.isEmpty())
                            ? meta.id : idFromJarName(f.getName());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("jarName", f.getName());
                    m.put("id", id);
                    m.put("name", name);
                    m.put("version", version);
                    m.put("author", author);
                    m.put("description", description);
                    m.put("enabled", false);
                    m.put("loaded", false);
                    list.add(m);
                }
            }
        }
        list.sort((a, b) -> String.valueOf(a.get("name"))
                .compareToIgnoreCase(String.valueOf(b.get("name"))));
        return list;
    }

    public PlayerPerms getPerms() { return perms; }

    // ===================== 路由内存 =====================

    public static class RouteMemory {
        private static final Map<String, String> pending = new ConcurrentHashMap<>();
        private static final Map<String, String> persistent = new ConcurrentHashMap<>();
        private static final int MAX_PENDING = 20000;

        public static void setPending(String player, String instance, int ttlSeconds) {
            if (player == null || instance == null) return;
            // ★ 修复：以前每次调用都 new java.util.Timer()，一个 Timer 就是一条线程，
            // 而且要等对象被 GC/finalize 才会退出；插件按事件高频调用时线程会慢性堆积。
            // 现在统一交给共享的调度器，一个进程只有一条定时线程。
            if (pending.size() >= MAX_PENDING) pending.clear();
            pending.put(player, instance);
            if (ttlSeconds > 0) {
                com.mcpanel.util.Async.later(ttlSeconds * 1000L, () -> pending.remove(player, instance));
            }
        }

        public static String consumePending(String player) {
            if (player == null) return null;
            return pending.remove(player);
        }

        public static void setPersistent(String player, String instance) {
            if (player == null || instance == null) return;
            persistent.put(player, instance);
        }

        public static void clearPersistent(String player) {
            if (player == null) return;
            persistent.remove(player);
        }

        public static String getPersistent(String player) {
            if (player == null) return null;
            return persistent.get(player);
        }
    }

    // ===================== 内部：SimpleContext =====================

    private static class SimpleContext implements PluginContext {
        private final PluginManager mgr;
        private final MCPlugin plugin;
        private final File dataFolder;
        private final CommandRegistry registry;
        private final PlayerPerms perms;

        public final List<ServerProcess.OutputListener> outputListeners = new ArrayList<>();
        public final List<ServerProcess.StatusListener> statusListeners = new ArrayList<>();

        SimpleContext(PluginManager mgr, MCPlugin plugin, File dataFolder,
                      CommandRegistry registry, PlayerPerms perms) {
            this.mgr = mgr; this.plugin = plugin; this.dataFolder = dataFolder;
            this.registry = registry; this.perms = perms;
        }

        @Override public String getPluginName() { return plugin.getName(); }
        @Override public File getDataFolder() { return dataFolder; }

        @Override
        public File getSharedDataFolder() {
            try {
                File shared = ConfigManager.getInstance().resolve(
                        "plugin-shared-data/" + plugin.getName());
                if (!shared.exists()) shared.mkdirs();
                return shared;
            } catch (Throwable e) {
                return dataFolder;
            }
        }

        @Override public void sendCommand(String cmd) { mgr.mcServer.sendCommand(cmd); }
        @Override public void log(String msg) { 
            // ★ 核心修复：如果服务端还没启动，插件日志只进后台，不污染面板终端
            if (mgr.mcServer == null || !mgr.mcServer.isRunning() || mgr.terminal == null) {
                System.out.println("[" + plugin.getName() + "] " + msg);
            } else {
                mgr.terminal.log("[" + plugin.getName() + "] " + msg); 
            }
        }

        @Override public void onServerOutput(Consumer<String> listener) {
            if (mgr.mcServer == null) return;
            ServerProcess.OutputListener ol = line -> {
                try { listener.accept(line); } catch (Throwable ignored) {}
            };
            outputListeners.add(ol);
            mgr.mcServer.addListener(ol);
        }

        @Override public void onServerStatus(Consumer<Boolean> listener) {
            if (mgr.mcServer == null) return;
            ServerProcess.StatusListener sl = running -> {
                try { listener.accept(running); } catch (Throwable ignored) {}
            };
            statusListeners.add(sl);
            mgr.mcServer.addStatusListener(sl);
        }

        @Override public void sendCommandAndCapture(String cmd, long timeoutMs, Consumer<List<String>> callback) {
            if (mgr.mcServer != null) mgr.mcServer.sendCommandAndCapture(cmd, timeoutMs, callback);
        }

        @Override public void sendCommandAndWait(String cmd, long timeoutMs,
                                                java.util.function.Predicate<String> stopWhen,
                                                Consumer<List<String>> callback) {
            if (mgr.mcServer != null) mgr.mcServer.sendCommandAndWait(cmd, timeoutMs, stopWhen, callback);
        }

        @Override public void registerCommand(String name, CommandHandler handler) {
            registerCommand(name, PlayerLevel.GUEST, handler);
        }

        @Override public void registerCommand(String name, PlayerLevel minLevel, CommandHandler handler) {
            registry.registerPluginCommand(
                    plugin.getName(), name, minLevel,
                    (sender, args) -> handler.handle(sender.name, args));
        }

        @Override public void unregisterCommand(String name) {
            registry.unregister(name);
        }

        @Override
        public void registerWebHandler(PluginWebHandler handler) {
            String pid = plugin.getId() != null && !plugin.getId().isEmpty() ? plugin.getId() : plugin.getName();
            PluginWebRouter.register(pid, handler);
        }

        @Override public PlayerLevel getSenderLevel(String sender) {
            if (sender == null) return PlayerLevel.GUEST;
            if ("web".equals(sender) || "console".equals(sender)) return PlayerLevel.OWNER;
            return perms.getLevel(sender);
        }

        // ==================== 面板页面 ====================

        @Override
        public void registerPanelPage(PanelPage page) {
            PluginPageRegistry.register(plugin.getName(), page);
        }

        @Override
        public void unregisterPanelPage(String pageId) {
            PluginPageRegistry.unregister(plugin.getName(), pageId);
        }

        // ==================== 自定义 API ====================

        @Override
        public void registerApi(String method, String path, PluginApiRegistry.Handler handler) {
            PluginApiRegistry.register(plugin.getName(), method, path, handler);
        }

        @Override
        public void unregisterApi(String method, String path) {
            PluginApiRegistry.unregister(plugin.getName(), method, path);
        }

        // ==================== 事件推送 ====================

        @Override
        public void pushEvent(String event, Object data) {
            PluginEventBus.broadcast(plugin.getName(), event, data);
        }

        // ==================== 路由 API ====================

        @Override
        public void setPendingRoute(String playerName, String instanceId, int ttlSeconds) {
            RouteMemory.setPending(playerName, instanceId, ttlSeconds);
        }

        @Override
        public void setPersistentRoute(String playerName, String instanceId) {
            RouteMemory.setPersistent(playerName, instanceId);
        }

        @Override
        public void clearPersistentRoute(String playerName) {
            RouteMemory.clearPersistent(playerName);
        }

        @Override
        public String getPersistentRoute(String playerName) {
            return RouteMemory.getPersistent(playerName);
        }

        @Override
        public String getCurrentInstanceId() {
            if (mgr.mcServer == null) return null;
            try {
                File root = mgr.config.getRootDir();
                if (root != null && root.getName() != null && !root.getName().isEmpty()) {
                    return root.getName();
                }
            } catch (Throwable ignored) {}
            return null;
        }

        @Override
        public List<InstanceInfo> listInstances() {
            List<InstanceInfo> out = new ArrayList<>();
            try {
                com.mcpanel.instance.InstanceRuntimeManager mgr2 =
                        com.mcpanel.instance.InstanceRuntimeManager.getInstance();
                for (com.mcpanel.instance.InstanceRuntime rt : mgr2.all()) {
                    InstanceInfo info = new InstanceInfo();
                    info.id = rt.inst.id;
                    info.name = (rt.inst.name != null && !rt.inst.name.isEmpty())
                            ? rt.inst.name : rt.inst.id;
                    info.desc = rt.inst.desc == null ? "" : rt.inst.desc;
                    info.running = rt.mcServer.isRunning();
                    info.whitelistEnabled = isWhitelistEnabled(rt);
                    info.whitelistNames = readWhitelistNames(rt);
                    out.add(info);
                }
                out.sort((a, b) -> a.id.compareTo(b.id));
            } catch (Throwable e) {
                mgr_log("listInstances 失败: " + e.getMessage());
            }
            return out;
        }

        private boolean isWhitelistEnabled(com.mcpanel.instance.InstanceRuntime rt) {
            try {
                File sp = rt.config.resolve(
                        rt.config.get("mc.server.dir", "server") + "/server.properties");
                if (!sp.exists()) return false;
                java.util.Properties p = new java.util.Properties();
                try (InputStream in = new java.io.FileInputStream(sp)) {
                    p.load(in);
                }
                return "true".equalsIgnoreCase(p.getProperty("white-list", "false"));
            } catch (Throwable e) {
                return false;
            }
        }

        private List<String> readWhitelistNames(com.mcpanel.instance.InstanceRuntime rt) {
            List<String> names = new ArrayList<>();
            try {
                File wl = rt.config.resolve(
                        rt.config.get("mc.server.dir", "server") + "/whitelist.json");
                if (!wl.exists()) return names;
                List<Map<String, Object>> entries = mgr.mapper.readValue(
                        wl, new TypeReference<ArrayList<Map<String, Object>>>() {});
                if (entries != null) {
                    for (Map<String, Object> e : entries) {
                        if (e == null) continue;
                        Object n = e.get("name");
                        if (n != null) names.add(n.toString());
                    }
                }
            } catch (Throwable e) {}
            return names;
        }

        private void mgr_log(String msg) {
            try { mgr.terminal.log("[Plugin] " + msg); } catch (Throwable ignored) {}
        }

        public void cleanup() {
            for (ServerProcess.OutputListener l : outputListeners) {
                try { mgr.mcServer.removeListener(l); } catch (Throwable ignored) {}
            }
            for (ServerProcess.StatusListener l : statusListeners) {
                try { mgr.mcServer.removeStatusListener(l); } catch (Throwable ignored) {}
            }
            outputListeners.clear();
            statusListeners.clear();
        }
    }
}