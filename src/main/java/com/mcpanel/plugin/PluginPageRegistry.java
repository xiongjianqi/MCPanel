package com.mcpanel.plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局插件页面注册表。跨实例、跨插件。
 */
public final class PluginPageRegistry {

    private static final Map<String, Entry> pages = new ConcurrentHashMap<>();

    public static class Entry {
        public final String pluginName;
        public final PanelPage page;
        public Entry(String pluginName, PanelPage page) {
            this.pluginName = pluginName;
            this.page = page;
        }
        public String getFullId() { return pluginName + ":" + page.id; }
    }

    private PluginPageRegistry() {}

    public static void register(String pluginName, PanelPage page) {
        if (pluginName == null || page == null) return;
        if (page.id == null || !page.id.matches("^[a-zA-Z0-9_-]{1,32}$")) return;
        pages.put(pluginName + ":" + page.id, new Entry(pluginName, page));
    }

    public static void unregister(String pluginName, String pageId) {
        if (pluginName == null || pageId == null) return;
        pages.remove(pluginName + ":" + pageId);
    }

    public static void unregisterAll(String pluginName) {
        if (pluginName == null) return;
        pages.entrySet().removeIf(e -> pluginName.equals(e.getValue().pluginName));
    }

    public static List<Entry> list() {
        return new ArrayList<>(pages.values());
    }

    public static PanelPage get(String fullId) {
        Entry e = pages.get(fullId);
        return e == null ? null : e.page;
    }
}