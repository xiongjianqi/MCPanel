package com.mcpanel.plugin;

import com.mcpanel.web.AuthHandler;
import io.undertow.Handlers;
import io.undertow.server.HttpServerExchange;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.Locale;

public class PluginEventSocketHandler {
    private final AuthHandler auth;

    // ★ 修复：补上带参构造函数，供 WebServer 调用
    public PluginEventSocketHandler(AuthHandler auth) {
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        // ★ 核心修复：校验 Origin，防止跨站 WebSocket 劫持
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!isOriginAllowed(origin, host)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Origin mismatch");
            return;
        }

        // ★ 核心修复：校验会话登录状态
        String user = auth.getCurrentUser(exchange);
        if (user == null) {
            exchange.setStatusCode(401);
            exchange.getResponseSender().send("Unauthorized");
            return;
        }

        try {
            Handlers.websocket(this::onConnected).handleRequest(exchange);
        } catch (Exception e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("WebSocket upgrade failed");
            }
        }
    }

    private void onConnected(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        // ★ 修复：注册失败（客户端数上限）直接拒绝，注册成功则由 WsBroadcaster 维护有界异步发送队列
        if (!PluginEventBus.addClient(channel)) {
            try { channel.sendClose(); } catch (Exception ignored) {}
            return;
        }

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                // 单向通道，忽略客户端发来的消息
            }
        });
        channel.resumeReceives();
        channel.getCloseSetter().set(c -> PluginEventBus.removeClient(channel));
    }

    private boolean isOriginAllowed(String origin, String host) {
        if (origin == null || host == null) return false;
        try {
            java.net.URI u = java.net.URI.create(origin);
            String originHost = u.getHost() == null ? "" : u.getHost();
            if (u.getPort() > 0) originHost += ":" + u.getPort();
            
            String h1 = host.toLowerCase(Locale.ROOT);
            String h2 = originHost.toLowerCase(Locale.ROOT);
            return h1.equals(h2) || h1.replaceAll(":\\d+$", "").equals(h2.replaceAll(":\\d+$", ""));
        } catch (Exception e) {
            return false;
        }
    }
}