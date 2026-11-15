package com.mcpanel.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PluginEventBus {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<WebSocketChannel> clients = new CopyOnWriteArrayList<>();
    private static final int MAX_CLIENTS = 100;

    private PluginEventBus() {}

    public static boolean addClient(WebSocketChannel ch) {
        if (ch == null) return false;
        if (clients.size() >= MAX_CLIENTS) return false;
        clients.add(ch);
        return true;
    }

    public static void removeClient(WebSocketChannel ch) {
        if (ch == null) return;
        clients.remove(ch);
    }

    public static int getClientCount() { return clients.size(); }

    public static void broadcast(String pluginName, String event, Object data) {
        if (event == null || event.isEmpty()) return;
        String json;
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("plugin", pluginName);
            msg.put("event", event);
            msg.put("data", data);
            msg.put("ts", System.currentTimeMillis());
            json = MAPPER.writeValueAsString(msg);
        } catch (Exception e) {
            System.err.println("[PluginEventBus] 序列化失败: " + e.getMessage());
            return;
        }
        
        // ★ 核心修复：抛弃复杂的队列转发，直接遍历使用原生 WebSockets.sendText
        for (WebSocketChannel ch : clients) {
            try {
                if (ch.isOpen()) {
                    WebSockets.sendText(json, ch, null);
                } else {
                    clients.remove(ch);
                }
            } catch (Exception ex) {
                clients.remove(ch);
            }
        }
    }
}