package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.util.ImageUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Deque;

public class SystemHandler {
    private final ConfigManager config;

    public SystemHandler(ConfigManager config) {
        this.config = config;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        if (path.equals("/api/system/default-bg") && exchange.getRequestMethod().toString().equals("GET")) {
            String type = "panel";
            Deque<String> q = exchange.getQueryParameters().get("type");
            if (q != null && !q.isEmpty()) type = q.peekFirst();

            // ★ 核心修复：防止路径穿越
            if (!"panel".equals(type) && !"instance".equals(type)) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"invalid type\"}");
                return;
            }

            File bg = new File(config.getRootDir(), "data/system/" + type + "-bg.webp");
            if (bg.exists()) {
                try {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/webp");
                    exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
                    exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(Files.readAllBytes(bg.toPath())));
                } catch (Exception e) { exchange.endExchange(); }
                return;
            }

            String resBase = "/web/resources/" + ("panel".equals(type) ? "defaultpanelbg" : "defaultinstances");
            InputStream in = SystemHandler.class.getResourceAsStream(resBase + ".webp");
            String contentType = "image/webp";
            if (in == null) {
                in = SystemHandler.class.getResourceAsStream(resBase + ".png");
                contentType = "image/png";
            }
            
            if (in != null) {
                try {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
                    exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
                    exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(in.readAllBytes()));
                } catch (Exception e) { exchange.endExchange(); }
                return;
            }

            exchange.setStatusCode(404);
            exchange.endExchange();
            return;
        }

        if (path.equals("/api/system/upload-default-bg") && exchange.getRequestMethod().toString().equals("POST")) {
            if (exchange.isInIoThread()) { exchange.dispatch(() -> handle(exchange)); return; }
            try {
                long len = exchange.getRequestContentLength();
                if (len > 20 * 1024 * 1024) {
                    exchange.setStatusCode(413);
                    exchange.getResponseSender().send("{\"error\":\"file too large\"}");
                    return;
                }
                Deque<String> q = exchange.getQueryParameters().get("type");
                String type = (q != null && !q.isEmpty()) ? q.peekFirst() : "panel";
                // ★ 核心修复
                if (!"panel".equals(type) && !"instance".equals(type)) {
                    exchange.setStatusCode(400);
                    exchange.getResponseSender().send("{\"error\":\"invalid type\"}");
                    return;
                }
                File dir = new File(config.getRootDir(), "data/system");
                if (!dir.exists()) dir.mkdirs();
                File dst = new File(dir, type + "-bg.webp");

                exchange.startBlocking();
                boolean ok = ImageUtil.convertToWebP(exchange.getInputStream(), dst);
                if (ok) exchange.getResponseSender().send("{\"ok\":true}");
                else { exchange.setStatusCode(400); exchange.getResponseSender().send("{\"error\":\"图片转换失败\"}"); }
            } catch (Exception e) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("{\"error\":\"上传失败\"}");
            }
            return;
        }

        exchange.setStatusCode(404);
        exchange.endExchange();
    }
}