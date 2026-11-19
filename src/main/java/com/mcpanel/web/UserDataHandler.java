package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class UserDataHandler {
    private final ConfigManager config;
    private final AuthHandler auth;
    private final File userDir;

    public UserDataHandler(ConfigManager config, AuthHandler auth) {
        this.config = config;
        this.auth = auth;
        this.userDir = new File(config.getRootDir(), "data/userdata");
        if (!this.userDir.exists()) this.userDir.mkdirs();
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        String user = auth.getCurrentUser(exchange);
        if (user == null) {
            exchange.setStatusCode(401);
            exchange.endExchange();
            return;
        }

        // 头像读取
        if (path.equals("/api/userdata/avatar") && exchange.getRequestMethod().toString().equals("GET")) {
            sendFile(exchange, new File(userDir, user + "_avatar.png"), "image/png");
            return;
        }

        // 背景图读取
        if (path.equals("/api/userdata/bg") && exchange.getRequestMethod().toString().equals("GET")) {
            sendFile(exchange, new File(userDir, user + "_bg.webp"), "image/webp");
            return;
        }

        // 字体读取
        if (path.equals("/api/userdata/font") && exchange.getRequestMethod().toString().equals("GET")) {
            sendFile(exchange, new File(userDir, user + "_font.ttf"), "font/ttf");
            return;
        }

        // CSS 读取
        if (path.equals("/api/userdata/css") && exchange.getRequestMethod().toString().equals("GET")) {
            sendFile(exchange, new File(userDir, user + "_custom.css"), "text/css");
            return;
        }

        // 信息查询
        if (path.equals("/api/userdata/get") && exchange.getRequestMethod().toString().equals("GET")) {
            Map<String, Object> res = new HashMap<>();
            res.put("hasAvatar", new File(userDir, user + "_avatar.png").exists());
            res.put("hasBg", new File(userDir, user + "_bg.webp").exists());
            res.put("hasFont", new File(userDir, user + "_font.ttf").exists());
            res.put("hasCss", new File(userDir, user + "_custom.css").exists());
            try {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(res));
            } catch (Exception ignored) {}
            return;
        }

        if (exchange.getRequestMethod().toString().equals("POST")) {
            handleUpload(exchange, path, user);
            return;
        }

        exchange.setStatusCode(404);
        exchange.endExchange();
    }

    private void handleUpload(HttpServerExchange exchange, String path, String user) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleUpload(exchange, path, user));
            return;
        }
        try {
            if (path.equals("/api/userdata/remove")) {
                exchange.startBlocking();
                String body = new String(readAll(exchange.getInputStream()), StandardCharsets.UTF_8);
                if (body.contains("\"bg\"")) new File(userDir, user + "_bg.webp").delete();
                if (body.contains("\"font\"")) new File(userDir, user + "_font.ttf").delete();
                if (body.contains("\"css\"")) new File(userDir, user + "_custom.css").delete();
                exchange.getResponseSender().send("{\"ok\":true}");
                return;
            }

            long limit = 20 * 1024 * 1024;
            if (path.endsWith("css")) limit = 100 * 1024;
            if (path.endsWith("font")) limit = 5 * 1024 * 1024;
            
            long len = exchange.getRequestContentLength();
            if (len > limit) {
                exchange.setStatusCode(413);
                exchange.getResponseSender().send("{\"error\":\"file too large\"}");
                return;
            }

            exchange.startBlocking();
            InputStream is = exchange.getInputStream();
            byte[] data;
            
            if (path.equals("/api/userdata/upload-avatar")) {
                File tmp = new File(userDir, user + "_avatar_tmp");
                Files.copy(is, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                boolean ok = com.mcpanel.util.ImageUtil.convertToWebP(new java.io.FileInputStream(tmp), new File(userDir, user + "_avatar.png"));
                tmp.delete();
                if (!ok) { exchange.getResponseSender().send("{\"error\":\"图片转换失败\"}"); return; }
            } else if (path.equals("/api/userdata/upload-bg")) {
                File tmp = new File(userDir, user + "_bg_tmp");
                Files.copy(is, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                boolean ok = com.mcpanel.util.ImageUtil.convertToWebP(new java.io.FileInputStream(tmp), new File(userDir, user + "_bg.webp"));
                tmp.delete();
                if (!ok) { exchange.getResponseSender().send("{\"error\":\"图片转换失败\"}"); return; }
            } else if (path.equals("/api/userdata/upload-css")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n; int total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > limit) { exchange.getResponseSender().send("{\"error\":\"file too large\"}"); return; }
                    bos.write(buf, 0, n);
                }
                String css = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                css = css.replaceAll("(?i)@import", "/* @import */").replaceAll("(?i)url\\s*\\(", "/* url( */");
                Files.write(new File(userDir, user + "_custom.css").toPath(), css.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/userdata/upload-font")) {
                Files.copy(is, new File(userDir, user + "_font.ttf").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"ok\":true}");
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"upload failed\"}");
        }
    }

    private void sendFile(HttpServerExchange exchange, File f, String mime) {
        if (!f.exists()) { exchange.setStatusCode(404); exchange.endExchange(); return; }
        try {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, mime);
            exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(Files.readAllBytes(f.toPath())));
        } catch (Exception e) { exchange.setStatusCode(500); exchange.endExchange(); }
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}