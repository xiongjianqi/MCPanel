package com.mcpanel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.UserStore;
import com.mcpanel.plugin.PluginApiRegistry;
import com.mcpanel.plugin.PluginApiRequest;
import com.mcpanel.plugin.PluginApiResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;

public class PluginApiHandler {

    private static final String PREFIX = "/api/plugin-api/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthHandler auth;

    public PluginApiHandler(AuthHandler auth) {
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        String sub = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
        String method = exchange.getRequestMethod().toString();

        PluginApiRegistry.Entry entry = PluginApiRegistry.find(method, sub);
        if (entry == null) {
            exchange.setStatusCode(404);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"no such plugin api\"}");
            return;
        }

        String user = auth.getCurrentUser(exchange);
        if (user == null) {
            exchange.setStatusCode(401);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"unauthorized\"}");
            return;
        }

        // ★ 修复：获取实例 ID 并校验当前用户是否有权访问
        String iid = readInstanceId(exchange);
        if (iid != null && !iid.isEmpty() && !UserStore.getInstance().canAccessInstance(user, iid)) {
            exchange.setStatusCode(403);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"no access to this instance\"}");
            return;
        }

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                PluginApiRequest req = new PluginApiRequest();
                req.method = method;
                req.path = sub;
                req.body = body == null ? "" : body;
                req.username = user;
                req.instanceId = iid;

                for (Map.Entry<String, Deque<String>> e : ex.getQueryParameters().entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        req.query.put(e.getKey(), e.getValue().peekFirst());
                    }
                }
                ex.getRequestHeaders().forEach(h ->
                        req.headers.put(h.getHeaderName().toString(), h.getFirst()));

                PluginApiResponse resp = new ExchangeResponse(ex);
                try {
                    entry.handler.handle(req, resp);
                } catch (Throwable t) {
                    try {
                        resp.error(500, t.getMessage() == null
                                ? t.getClass().getSimpleName() : t.getMessage());
                    } catch (Exception ignored) {}
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private String readInstanceId(HttpServerExchange ex) {
        Deque<String> qp = ex.getQueryParameters().get("instance");
        if (qp != null && !qp.isEmpty()) {
            String id = qp.peekFirst();
            if (id != null && !id.isEmpty()) return id;
        }
        String cookie = ex.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith("instance=")) return part.substring(9);
        }
        return null;
    }

    private static class ExchangeResponse implements PluginApiResponse {
        private final HttpServerExchange ex;
        private boolean started = false;

        ExchangeResponse(HttpServerExchange ex) { this.ex = ex; }

        @Override
        public void status(int code) {
            if (!started) ex.setStatusCode(code);
        }

        @Override
        public void header(String name, String value) {
            if (started) return;
            ex.getResponseHeaders().put(new HttpString(name), value);
        }

        @Override
        public void json(Object data) {
            if (started) return;
            started = true;
            try {
                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                ex.getResponseSender().send(MAPPER.writeValueAsString(data));
            } catch (Exception e) {
                ex.getResponseSender().send("{\"error\":\"serialization failed\"}");
            }
        }

        @Override
        public void text(String s) {
            if (started) return;
            started = true;
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
            ex.getResponseSender().send(s == null ? "" : s);
        }

        @Override
        public void ok() {
            if (started) return;
            started = true;
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"ok\":true}");
        }

        @Override
        public void error(int code, String message) {
            if (started) return;
            started = true;
            ex.setStatusCode(code);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            try {
                ex.getResponseSender().send(
                        MAPPER.writeValueAsString(java.util.Collections.singletonMap("error",
                                message == null ? "" : message)));
            } catch (Exception ignored) {
                ex.getResponseSender().send("{\"error\":\"internal\"}");
            }
        }
    }
}
