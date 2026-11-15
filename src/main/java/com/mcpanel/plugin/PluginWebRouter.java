package com.mcpanel.plugin;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginWebRouter {
    public static class RouteEntry {
        public final String pluginId;
        public final PluginWebHandler handler;

        public RouteEntry(String pluginId, PluginWebHandler handler) {
            this.pluginId = pluginId;
            this.handler = handler;
        }
    }

    private static final Map<String, RouteEntry> routes = new ConcurrentHashMap<>();

    public static void register(String pluginId, PluginWebHandler handler) {
        routes.put(pluginId, new RouteEntry(pluginId, handler));
    }

    public static void unregister(String pluginId) {
        routes.remove(pluginId);
    }

    public static boolean handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        if (!path.startsWith("/plugins/")) return false;

        String rest = path.substring("/plugins/".length());
        int slash = rest.indexOf('/');
        String pluginId;
        String subPath;

        if (slash < 0) {
            pluginId = rest;
            subPath = "/";
        } else {
            pluginId = rest.substring(0, slash);
            subPath = rest.substring(slash);
        }

        RouteEntry entry = routes.get(pluginId);
        if (entry == null) {
            exchange.setStatusCode(404);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Plugin not found");
            return true;
        }

        try {
            entry.handler.handle(subPath, new PluginWebRequest(exchange));
        } catch (Exception e) {
            System.err.println("[PluginWeb] 插件 " + pluginId + " 处理失败: " + e.getMessage());
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send("Plugin error");
            }
        }
        return true;
    }
}