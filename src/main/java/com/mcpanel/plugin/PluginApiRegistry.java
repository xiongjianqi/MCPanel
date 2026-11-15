package com.mcpanel.plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PluginApiRegistry {

    public interface Handler {
        void handle(PluginApiRequest req, PluginApiResponse resp) throws Exception;
    }

    public static class Entry {
        public final String pluginName;
        public final String method;
        public final String path;
        public final Handler handler;

        public Entry(String pluginName, String method, String path, Handler handler) {
            this.pluginName = pluginName;
            this.method = method;
            this.path = path;
            this.handler = handler;
        }
    }

    private static final Map<String, Entry> routes = new ConcurrentHashMap<>();

    private PluginApiRegistry() {}

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + normalize(path);
    }

    private static String normalize(String path) {
        if (path == null) return "";
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    public static synchronized void register(String pluginName, String method, String path, Handler handler) {
        if (method == null || path == null || handler == null) return;
        String k = key(method, path);
        
        // ★ 核心修复：如果路由已存在且属于其他插件，拒绝覆盖
        Entry existing = routes.get(k);
        if (existing != null && !existing.pluginName.equals(pluginName)) {
            throw new IllegalArgumentException("路由冲突: 插件 " + pluginName + " 试图覆盖 " + existing.pluginName + " 的接口 " + method + " " + path);
        }

        Entry e = new Entry(pluginName, method.toUpperCase(), normalize(path), handler);
        routes.put(k, e);
    }

    public static synchronized void unregister(String pluginName, String method, String path) {
        Entry e = routes.get(key(method, path));
        if (e != null && pluginName.equals(e.pluginName)) {
            routes.remove(key(method, path));
        }
    }

    public static synchronized void unregisterAll(String pluginName) {
        if (pluginName == null) return;
        routes.entrySet().removeIf(e -> pluginName.equals(e.getValue().pluginName));
    }

    public static Entry find(String method, String path) {
        return routes.get(key(method, path));
    }

    public static Map<String, Entry> all() {
        return new ConcurrentHashMap<>(routes);
    }
}