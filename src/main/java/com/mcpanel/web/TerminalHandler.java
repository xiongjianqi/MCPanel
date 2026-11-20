package com.mcpanel.web;

import com.mcpanel.command.CommandRegistry;
import com.mcpanel.command.CommandSender;
import com.mcpanel.command.PlayerPerms;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.mcpanel.instance.InstanceRuntimeManager;
import com.mcpanel.mc.ServerProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Handlers;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class TerminalHandler {
    public static volatile boolean PANEL_SHUTTING_DOWN = false;

    private final ServerProcess mc;
    private final ConfigManager config;
    private final CommandRegistry registry;
    private final PlayerPerms perms;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BUFFER = 500;
    private final File logFile;

    private final Deque<Entry> buffer = new ConcurrentLinkedDeque<>();
    private final List<WebSocketChannel> channels = new CopyOnWriteArrayList<>();
    private final AtomicLong seqCounter = new AtomicLong(0);
    private static final Map<WebSocketChannel, String> wsUsers = new java.util.concurrent.ConcurrentHashMap<>();

    private final LinkedBlockingQueue<String> logWriteQueue = new LinkedBlockingQueue<>(10000);
    private static final String LOG_RESET_SENTINEL = "\u0000__MCPANEL_LOG_RESET__";
    private static final long MAX_LOG_BYTES = 5 * 1024 * 1024L;
    private Thread logWriterThread;

    private static class Entry {
        final long seq;
        final String line;
        Entry(long seq, String line) { this.seq = seq; this.line = line; }
    }

    public TerminalHandler(ServerProcess mc, ConfigManager config, CommandRegistry registry, PlayerPerms perms) {
        this.mc = mc;
        this.config = config;
        this.registry = registry;
        this.perms = perms;

        File logsDir = config.resolve("logs");
        if (!logsDir.exists()) logsDir.mkdirs();
        this.logFile = new File(logsDir, "terminal.log");

        loadHistory();
        startLogWriter();
        mc.addListener(line -> addEntry(line));
        mc.addStateListener(state -> broadcastState(state));
    }

    private void startLogWriter() {
        logWriterThread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedWriter w = null;
                long written = 0;
                try {
                    w = new BufferedWriter(new FileWriter(logFile, true));
                    written = logFile.length();
                    while (!Thread.currentThread().isInterrupted()) {
                        String line;
                        try {
                            line = logWriteQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (line == null) {
                            w.flush();
                            continue;
                        }
                        boolean reset = LOG_RESET_SENTINEL.equals(line);
                        if (reset || written > MAX_LOG_BYTES) {
                            w.close();
                            w = null;
                            try { Files.deleteIfExists(logFile.toPath()); } catch (Exception ignored) {}
                            w = new BufferedWriter(new FileWriter(logFile, true));
                            written = 0;
                            if (reset) continue;
                        }
                        w.write(line);
                        w.newLine();
                        written += line.length() + 1;
                        if (logWriteQueue.isEmpty()) w.flush();
                    }
                } catch (Exception e) {
                    System.err.println("[TerminalHandler] 终端日志写线程异常退出: " + e.getMessage());
                } finally {
                    if (w != null) {
                        try { w.flush(); } catch (Exception ignored) {}
                        try { w.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "terminal-log-writer");
        logWriterThread.setDaemon(true);
        logWriterThread.start();
    }

    public void log(String msg) { addEntry(msg); }

    public void clearHistory() {
        buffer.clear();
        logWriteQueue.clear();
        logWriteQueue.offer(LOG_RESET_SENTINEL);
    }

    private String sanitize(String line) {
        if (line == null) return "";
        return line.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");
    }

    private void addEntry(String line) {
        long seq = seqCounter.incrementAndGet();
        String safeLine = sanitize(line);
        Entry e = new Entry(seq, safeLine);
        buffer.addLast(e);
        while (buffer.size() > MAX_BUFFER) buffer.pollFirst();

        logWriteQueue.offer(seq + "|" + safeLine);
        broadcast(e);
    }

    private void broadcastState(String state) {
        String msg = "@STATUS@:" + state;
        broadcastRaw(msg);
    }

    private void loadHistory() {
        if (!logFile.exists()) return;
        try {
            List<String> lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - MAX_BUFFER);
            long maxSeq = 0;
            for (int i = start; i < lines.size(); i++) {
                String raw = lines.get(i);
                int idx = raw.indexOf('|');
                if (idx <= 0) continue;
                try {
                    long seq = Long.parseLong(raw.substring(0, idx));
                    String line = raw.substring(idx + 1);
                    buffer.addLast(new Entry(seq, line));
                    if (seq > maxSeq) maxSeq = seq;
                } catch (NumberFormatException ignored) {}
            }
            seqCounter.set(maxSeq);
        } catch (IOException e) {}
    }

    private void broadcast(Entry e) {
        String msg = e.seq + "|" + e.line;
        broadcastRaw(msg);
    }

    private void broadcastRaw(String msg) {
        for (WebSocketChannel ch : channels) {
            try {
                if (ch.isOpen()) {
                    WebSockets.sendText(msg, ch, null);
                } else {
                    channels.remove(ch);
                }
            } catch (Exception ex) {
                channels.remove(ch);
            }
        }
    }

    public void handleUpgrade(HttpServerExchange exchange, String username) {
        long since = 0;
        try {
            Deque<String> p = exchange.getQueryParameters().get("since");
            if (p != null && !p.isEmpty()) since = Long.parseLong(p.peekFirst());
        } catch (Exception ignored) {}
        final long sinceFinal = since;
        try {
            Handlers.websocket((ex, ch) -> onConnected(ex, ch, sinceFinal, username)).handleRequest(exchange);
        } catch (Exception e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("WebSocket upgrade failed");
            }
        }
    }

    private void onConnected(WebSocketHttpExchange exchange, WebSocketChannel channel, long since, String username) {
        channels.add(channel);
        if (username != null) wsUsers.put(channel, username);
        
        for (Entry e : buffer) {
            if (e.seq > since) {
                try {
                    WebSockets.sendText(e.seq + "|" + e.line, channel, null);
                } catch (Exception ex) { break; }
            }
        }
        try {
            WebSockets.sendText("@HISTORY_END@", channel, null);
            WebSockets.sendText("@STATUS@:" + mc.getStateString(), channel, null);
        } catch (Exception ignored) {}

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                String data = msg.getData();
                if (data == null || data.isEmpty()) return;
                String cmd = data.trim();

                if (cmd.contains("\n") || cmd.contains("\r")) {
                    addEntry("[Panel] ⚠ 拦截到包含换行符的非法指令"); return;
                }
                addEntry("> " + cmd);

                if (mc.needsEula) {
                    if (cmd.equalsIgnoreCase("Y")) {
                        // 不能在这个（IO 线程）回调里直接启动：启动里有 chown/su/pkill 等阻塞操作
                        agreeEulaAndStartAsync();
                        return;
                    }
                    if (cmd.equalsIgnoreCase("N")) {
                        mc.needsEula = false; mc.notifyStateChange(); addEntry("[Panel] ✗ 您已拒绝 EULA，服务器将无法启动。"); return;
                    }
                    addEntry("[Panel] 请输入 Y 或 N"); return;
                }

                if (mc.isInstalling()) { addEntry("[Panel] 正在安装服务端，暂不接受命令输入"); return; }

                String user = wsUsers.getOrDefault(ch, "web");

                if (cmd.startsWith("!!") || cmd.startsWith("!")) {
                    CommandSender sender = new CommandSender(CommandSender.Type.WEB, user, mc, TerminalHandler.this, perms);
                    registry.dispatch(sender, cmd); return;
                }
                mc.sendCommand(cmd);
            }
        });
        channel.resumeReceives();
        channel.getCloseSetter().set(c -> {
            channels.remove(channel);
            wsUsers.remove(channel);
        });
    }

    public void triggerPanelShutdown() {
        if (PANEL_SHUTTING_DOWN) return;
        PANEL_SHUTTING_DOWN = true;
        new Thread(() -> {
            try {
                addEntry("[Panel] 正在关闭所有实例...");
                InstanceRuntimeManager.getInstance().shutdownAll();
                addEntry("[Panel] 所有实例已退出");
                addEntry("[Panel] 面板即将退出，再见");
                Thread.sleep(1500);
            } catch (Exception e) {}
            System.exit(0);
        }, "panel-shutdown").start();
    }

    public void handleApi(HttpServerExchange exchange, String currentUser) {
        String path = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString();

        if ("GET".equalsIgnoreCase(method)) {
            if (exchange.isInIoThread()) exchange.dispatch(() -> handleApiGet(exchange, path));
            else handleApiGet(exchange, path);
            return;
        }

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> handleApiPost(ex, path, body, currentUser));
        });
    }

    private void handleApiGet(HttpServerExchange ex, String path) {
        try {
            if (path.equals("/api/terminal/status")) { sendStatus(ex); return; }
            if (path.equals("/api/terminal/settings/get")) { sendSettings(ex); return; }
            if (path.equals("/api/panel/status")) { sendPanelStatus(ex); return; }
            ex.setStatusCode(404); ex.getResponseSender().send("{\"error\":\"unknown\"}");
        } catch (Exception e) {
            ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"内部错误\"}");
        }
    }

    private void handleApiPost(HttpServerExchange ex, String path, String body, String currentUser) {
        try {
            if (path.equals("/api/terminal/status")) { sendStatus(ex); return; }
            if (path.equals("/api/terminal/action")) { handleAction(ex, body, currentUser); return; }
            if (path.equals("/api/terminal/settings/set")) { handleSettingsSet(ex, body, currentUser); return; }
            if (path.equals("/api/terminal/log/clear")) { handleLogClear(ex, currentUser); return; }
            if (path.equals("/api/panel/status")) { sendPanelStatus(ex); return; }
            if (path.equals("/api/panel/shutdown")) { handleShutdown(ex, currentUser); return; }
            ex.setStatusCode(404); ex.getResponseSender().send("{\"error\":\"unknown\"}");
        } catch (Exception e) {
            ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"内部错误\"}");
        }
    }

    private void sendStatus(HttpServerExchange ex) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("running", mc.isRunning());
        m.put("starting", mc.isStarting());
        m.put("installing", mc.isInstalling());
        m.put("needsEula", mc.needsEula);
        m.put("state", mc.getStateString());
        m.put("pid", mc.getPid());
        m.put("uptime", mc.getUptime());
        m.put("type", config.get("_instance.type", "mc"));
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send(MAPPER.writeValueAsString(m));
    }

    private void sendSettings(HttpServerExchange ex) throws Exception {
        String type = config.get("_instance.type", "mc");
        boolean isVel = "velocity".equals(type);
        Map<String, String> m = new HashMap<>();
        m.put("type", type);
        m.put("shutdownCommand", config.get("terminal.shutdown.command", isVel ? "shutdown" : "stop"));
        m.put("restartDelay", config.get("terminal.restart.delay", "5000"));
        m.put("autoRestart", config.get("terminal.auto.restart", "false"));
        m.put("startCommand", isVel
                ? config.get("velocity.start.command", "java -Xms512M -Xmx512M -jar velocity.jar")
                : config.get("mc.start.command", "java -Xmx2G -jar server.jar nogui"));
        if (isVel) {
            m.put("autoStart", config.get("velocity.auto.start", "true"));
            m.put("port", config.get("velocity.port", "25577"));
        } else {
            m.put("autoStart", config.get("mc.auto.start", "true"));
            m.put("port", config.get("mc.port", "25565"));
        }
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send(MAPPER.writeValueAsString(m));
    }

    private void sendPanelStatus(HttpServerExchange ex) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("shutting_down", PANEL_SHUTTING_DOWN);
        m.put("mc_running", mc.isRunning());
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send(MAPPER.writeValueAsString(m));
    }

    // 同意 EULA 并异步启动：写文件和启动都放到独立线程，避免阻塞 Undertow 的 IO/Worker 线程
    private void agreeEulaAndStartAsync() {
        String dirKey = "velocity".equals(config.get("_instance.type", "mc")) ? "velocity.server.dir" : "mc.server.dir";
        File serverDir = config.resolve(config.get(dirKey, "server"));
        try {
            if (!serverDir.exists()) serverDir.mkdirs();
            java.nio.file.Files.write(new File(serverDir, "eula.txt").toPath(),
                    "eula=true".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            addEntry("[Panel] ✗ 写入 eula.txt 失败: " + e.getMessage());
            mc.notifyStateChange();
            return;
        }
        mc.needsEula = false;
        mc.notifyStateChange();
        addEntry("[Panel] ✓ 已同意 EULA，正在启动服务端...");
        new Thread(() -> {
            try {
                mc.start();
            } catch (Throwable t) {
                addEntry("[Panel] ✗ 启动失败: " + (t.getMessage() == null ? t.toString() : t.getMessage()));
                mc.notifyStateChange();
            }
        }, "eula-start").start();
    }

    private void handleAction(HttpServerExchange ex, String body, String currentUser) throws Exception {
        if (mc.isInstalling()) {
            ex.setStatusCode(409); ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send("{\"error\":\"正在安装服务端，请稍候\",\"installing\":true}"); return;
        }
        if (currentUser == null) { ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"unauthorized\"}"); return; }

        Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        String action = p.getOrDefault("action", "");

        switch (action) {
            case "eula":
                agreeEulaAndStartAsync();
                break;
            case "start":
                if (mc.isRunning()) {
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send("{\"error\":\"实例已经在运行中\"}");
                    return;
                }
                if (mc.needsEula) {
                    if (mc.isEulaFileAgreed()) { mc.needsEula = false; mc.notifyStateChange(); }
                    else {
                        ex.setStatusCode(409); ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"error\":\"需要先同意 EULA\",\"needsEula\":true}"); return;
                    }
                }
                addEntry("[Panel] 请求启动");
                new Thread(() -> {
                    try { mc.start(); } catch (Throwable t) {
                        addEntry("[Panel] ✗ 启动失败: " + t.getMessage());
                        mc.notifyStateChange();
                    }
                }, "start").start();
                break;
            case "stop":
                addEntry("[Panel] 请求关闭");
                new Thread(() -> {
                    try { mc.shutdown(); } catch (Throwable t) {
                        addEntry("[Panel] ✗ 关闭失败: " + t.getMessage());
                        mc.notifyStateChange();
                    }
                }, "stop").start();
                break;
            case "restart":
                if (mc.needsEula) {
                    if (mc.isEulaFileAgreed()) { mc.needsEula = false; mc.notifyStateChange(); }
                    else {
                        ex.setStatusCode(409); ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"error\":\"需要先同意 EULA\",\"needsEula\":true}"); return;
                    }
                }
                addEntry("[Panel] 请求重启");
                new Thread(() -> {
                    try { mc.restart(); } catch (Throwable t) {
                        addEntry("[Panel] ✗ 重启失败: " + t.getMessage());
                        mc.notifyStateChange();
                    }
                }, "restart").start();
                break;
            case "kill":
                addEntry("[Panel] 请求强制终止");
                try { mc.kill(); } catch (Throwable t) { addEntry("[Panel] ✗ 强制终止异常: " + t.getMessage()); }
                break;
            default:
                ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"unknown action\"}"); return;
        }
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send("{\"ok\":true}");
    }

    private void handleSettingsSet(HttpServerExchange ex, String body, String currentUser) throws Exception {
        boolean canEdit = UserStore.getInstance().isMaster(currentUser) || UserStore.getInstance().hasPermission(currentUser, "config");
        if (!canEdit) {
            ex.setStatusCode(403); ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send("{\"error\":\"权限不足：需要 config 或 master 才能修改启动设置\"}"); return;
        }
        Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        String type = config.get("_instance.type", "mc");
        boolean isVel = "velocity".equals(type);
        if (p.containsKey("shutdownCommand")) config.set("terminal.shutdown.command", p.get("shutdownCommand"));
        if (p.containsKey("restartDelay")) config.set("terminal.restart.delay", p.get("restartDelay"));
        if (p.containsKey("autoRestart")) config.set("terminal.auto.restart", p.get("autoRestart"));
        if (p.containsKey("startCommand")) {
            if (isVel) config.set("velocity.start.command", p.get("startCommand"));
            else config.set("mc.start.command", p.get("startCommand"));
        }
        if (isVel) {
            if (p.containsKey("autoStart")) config.set("velocity.auto.start", p.get("autoStart"));
            if (p.containsKey("port") && p.get("port") != null && !p.get("port").isEmpty())
                config.set("velocity.port", p.get("port").trim());
        } else {
            if (p.containsKey("autoStart")) config.set("mc.auto.start", p.get("autoStart"));
            if (p.containsKey("port") && p.get("port") != null && !p.get("port").isEmpty())
                config.set("mc.port", p.get("port").trim());
            config.remove("mc.port.forward.from"); config.remove("mc.port.forward.to"); config.remove("mc.port.random.enabled");
        }
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send("{\"ok\":true}");
    }

    private void handleLogClear(HttpServerExchange ex, String currentUser) throws Exception {
        boolean canClear = UserStore.getInstance().isMaster(currentUser) || UserStore.getInstance().hasPermission(currentUser, "config");
        if (!canClear) { ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"无权限清空日志\"}"); return; }
        clearHistory();
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send("{\"ok\":true}");
    }

    private void handleShutdown(HttpServerExchange ex, String currentUser) throws Exception {
        if (currentUser == null || !UserStore.getInstance().isMaster(currentUser)) {
            ex.setStatusCode(403); ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send("{\"error\":\"需要 master 权限才能关闭面板\"}"); return;
        }
        addEntry("[Panel] 收到关闭面板请求");
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ex.getResponseSender().send("{\"ok\":true,\"already\":" + PANEL_SHUTTING_DOWN + "}");
        triggerPanelShutdown();
    }
}