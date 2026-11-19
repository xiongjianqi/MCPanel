package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BackupHandler {
    private final ConfigManager config;
    private final ServerProcess mcServer;
    private final TerminalHandler terminal;
    private final RollbackService rollbackService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public BackupHandler(ConfigManager config, ServerProcess mcServer,
                         TerminalHandler terminal, RollbackService rollbackService) {
        this.config = config;
        this.mcServer = mcServer;
        this.terminal = terminal;
        this.rollbackService = rollbackService;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                try {
                    Path backupRoot = config.resolve("backup").toPath().toAbsolutePath().normalize();
                    Files.createDirectories(backupRoot);

                    if (path.equals("/api/backup/list")) {
                        handleList(ex, backupRoot);
                        return;
                    }

                    if (path.equals("/api/backup/create")) {
                        Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                        String name = p.getOrDefault("name", "backup-" + System.currentTimeMillis());
                        String desc = p.getOrDefault("desc", "");

                        if (!isSafeName(name)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"备份名只能包含字母、数字、-、_\"}");
                            return;
                        }
                        Path dest = backupRoot.resolve(name);
                        if (Files.exists(dest)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"同名备份已存在\"}");
                            return;
                        }
                        Path src = config.resolve(config.get("mc.server.dir", "server") + "/world")
                                .toPath().toAbsolutePath().normalize();
                        if (!Files.exists(src)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"world 目录不存在\"}");
                            return;
                        }

                        if (mcServer.isRunning()) {
                            if (terminal != null) terminal.log("[Panel] 正在执行 save-all，等待存档写入磁盘...");
                            boolean done = waitForSaveMarker(30, () -> mcServer.sendCommand("save-all"));
                            if (done) {
                                if (terminal != null) terminal.log("[Panel] 检测到存档已保存，开始备份");
                            } else {
                                if (terminal != null) terminal.log("[Panel] 等待 save-all 超时（30 秒），继续备份");
                            }
                        } else {
                            if (terminal != null) terminal.log("[Panel] MC 未运行，直接备份 world");
                        }

                        try {
                            copyDir(src, dest);
                            Files.write(dest.resolve("backuptext.txt"),
                                    desc.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            try { deleteRecursive(dest); } catch (IOException ignored) {}
                            if (terminal != null) terminal.log("[Panel] 备份失败: " + e.getMessage());
                            ex.setStatusCode(500);
                            ex.getResponseSender().send("{\"error\":\"备份复制失败: " + esc(e.getMessage()) + "\"}");
                            return;
                        }
                        if (terminal != null) terminal.log("[Panel] 备份完成: " + name);

                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (path.equals("/api/backup/rename")) {
                        Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                        String oldName = p.getOrDefault("old", "");
                        String newName = p.getOrDefault("name", "");
                        String newDesc = p.getOrDefault("desc", "");
                        if (!isSafeName(oldName) || !isSafeName(newName)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"名称只能包含字母、数字、-、_\"}");
                            return;
                        }
                        Path oldPath = backupRoot.resolve(oldName).normalize();
                        Path newPath = backupRoot.resolve(newName).normalize();
                        if (!oldPath.startsWith(backupRoot) || !Files.exists(oldPath)) {
                            ex.setStatusCode(404);
                            ex.getResponseSender().send("{\"error\":\"备份不存在\"}");
                            return;
                        }
                        if (!newPath.startsWith(backupRoot)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"名称非法\"}");
                            return;
                        }
                        if (!oldName.equals(newName) && Files.exists(newPath)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"同名备份已存在\"}");
                            return;
                        }
                        if (!oldName.equals(newName)) {
                            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                        }
                        Files.write(newPath.resolve("backuptext.txt"),
                                newDesc.getBytes(StandardCharsets.UTF_8));

                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (path.equals("/api/backup/delete")) {
                        Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                        String name = p.getOrDefault("name", "");
                        if (!isSafeName(name)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"名称非法\"}");
                            return;
                        }
                        Path target = backupRoot.resolve(name).normalize();
                        if (!target.startsWith(backupRoot) || !Files.exists(target)) {
                            ex.setStatusCode(404);
                            ex.getResponseSender().send("{\"error\":\"备份不存在\"}");
                            return;
                        }
                        deleteRecursive(target);
                        if (terminal != null) terminal.log("[Panel] 已删除备份: " + name);

                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (path.equals("/api/backup/rollback")) {
                        Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                        String name = p.getOrDefault("name", "");
                        if (!isSafeName(name)) {
                            ex.setStatusCode(400);
                            ex.getResponseSender().send("{\"error\":\"名称非法\"}");
                            return;
                        }
                        Path src = backupRoot.resolve(name).normalize();
                        if (!src.startsWith(backupRoot) || !Files.exists(src)) {
                            ex.setStatusCode(404);
                            ex.getResponseSender().send("{\"error\":\"备份不存在\"}");
                            return;
                        }
                        if (rollbackService == null) {
                            ex.setStatusCode(500);
                            ex.getResponseSender().send("{\"error\":\"回档服务未初始化\"}");
                            return;
                        }
                        if (rollbackService.isRunning()) {
                            ex.setStatusCode(409);
                            ex.getResponseSender().send("{\"error\":\"已有回档任务在运行\"}");
                            return;
                        }
                        if (!rollbackService.startRollback(name)) {
                            ex.setStatusCode(500);
                            ex.getResponseSender().send("{\"error\":\"回档启动失败\"}");
                            return;
                        }

                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    ex.setStatusCode(404);
                    ex.getResponseSender().send("{\"error\":\"unknown\"}");
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"内部错误: " + esc(e.getMessage()) + "\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private void handleList(HttpServerExchange ex, Path backupRoot) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        if (Files.exists(backupRoot)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupRoot)) {
                for (Path p : ds) {
                    if (!Files.isDirectory(p)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getFileName().toString());
                    String desc = "";
                    Path descFile = p.resolve("backuptext.txt");
                    if (Files.exists(descFile)) {
                        desc = new String(Files.readAllBytes(descFile), StandardCharsets.UTF_8);
                    }
                    m.put("desc", desc);
                    m.put("size", dirSize(p));
                    m.put("mtime", p.toFile().lastModified());
                    list.add(m);
                }
            }
        }
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.getResponseSender().send(MAPPER.writeValueAsString(list));
    }

    private long dirSize(Path dir) {
        final long[] total = {0L};
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try { total[0] += Files.size(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return total[0];
    }

    private boolean waitForSaveMarker(int timeoutSeconds, Runnable commandSender) {
        final CountDownLatch latch = new CountDownLatch(1);
        final ServerProcess.OutputListener listener = line -> {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("saved the game") || lower.contains("saved the world")) {
                latch.countDown();
            }
        };
        mcServer.addListener(listener);
        try {
            commandSender.run();
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            mcServer.removeListener(listener);
        }
    }

    private void copyDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path p : ds) {
                Path target = dst.resolve(p.getFileName().toString());
                if (Files.isDirectory(p)) copyDir(p, target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
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

    private boolean isSafeName(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.matches("^[A-Za-z0-9_\\-]+$");
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
