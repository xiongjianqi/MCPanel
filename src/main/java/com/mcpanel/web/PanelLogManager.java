package com.mcpanel.web;

import io.undertow.Handlers;
import io.undertow.server.HttpServerExchange;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class PanelLogManager {
    private static final int MAX_LOGS = 500;
    private static final ConcurrentLinkedDeque<String> logs = new ConcurrentLinkedDeque<>();
    private static final CopyOnWriteArrayList<WebSocketChannel> clients = new CopyOnWriteArrayList<>();

    // 绝对安全的递归保护
    private static final ThreadLocal<Boolean> isAppending = ThreadLocal.withInitial(() -> false);

    private static final Pattern[] SENSITIVE_PATTERNS = {
        Pattern.compile("(?i)token[=:：][a-zA-Z0-9_\\-]{16,}"),
        Pattern.compile("(?i)password[=:：][^\\s]+"),
        Pattern.compile("密\\s*码[:：]\\s*[^\\s]+"),
        Pattern.compile("(?i)passwd[=:：][^\\s]+"),
        Pattern.compile("口令[:：]\\s*[^\\s]+"),
        Pattern.compile("(?i)key[=:：][a-zA-Z0-9_\\-]{16,}"),
        Pattern.compile("当前指纹：.*"),
        Pattern.compile("指纹：.*"),
        Pattern.compile("(?i)api[_-]?key[=:：\\s][a-zA-Z0-9_\\-]+")
    };

    public static void init() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        System.setOut(new PrintStream(originalOut, true, StandardCharsets.UTF_8) {
            @Override
            public void println(String x) {
                super.println(x);
                appendLog(x);
            }
        });

        System.setErr(new PrintStream(originalErr, true, StandardCharsets.UTF_8) {
            @Override
            public void println(String x) {
                super.println(x);
                appendLog("[ERR] " + x);
            }
        });
    }

    private static void appendLog(String line) {
        if (line == null || isAppending.get()) return;
        isAppending.set(true);
        try {
            for (Pattern p : SENSITIVE_PATTERNS) {
                if (p.matcher(line).find()) { 
                    line = "[Panel] [已过滤包含敏感信息的日志]"; 
                    break; 
                }
            }
            logs.addLast(line);
            while (logs.size() > MAX_LOGS) logs.pollFirst();
            
            for (WebSocketChannel ch : clients) {
                try { if (ch.isOpen()) WebSockets.sendText(line, ch, null); } catch (Exception ignored) {}
            }
        } finally {
            isAppending.set(false);
        }
    }

    public static void handleUpgrade(HttpServerExchange exchange) {
        try {
            Handlers.websocket(PanelLogManager::onConnected).handleRequest(exchange);
        } catch (Exception e) {
            if (!exchange.isResponseStarted()) { exchange.setStatusCode(500); exchange.getResponseSender().send("WebSocket upgrade failed"); }
        }
    }

    private static void onConnected(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        if (clients.size() >= 100) { try { channel.sendClose(); } catch (Exception ignored) {} return; }
        clients.add(channel);
        // 连上时立刻推送历史日志
        for (String line : logs) { 
            try { WebSockets.sendText(line, channel, null); } catch (Exception ignored) {} 
        }
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {}
        });
        channel.resumeReceives();
        channel.getCloseSetter().set(c -> clients.remove(channel));
    }
}