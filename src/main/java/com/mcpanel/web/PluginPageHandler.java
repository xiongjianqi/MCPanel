package com.mcpanel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.UserStore;
import com.mcpanel.plugin.PanelPage;
import com.mcpanel.plugin.PluginPageRegistry;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.*;

public class PluginPageHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AuthHandler auth;

    public PluginPageHandler(AuthHandler auth) {
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        String user = auth.getCurrentUser(exchange);

        if (path.equals("/api/plugin-pages/list")) {
            listPages(exchange, user);
        } else if (path.equals("/api/plugin-pages/render")) {
            renderPage(exchange, user);
        } else {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("{\"error\":\"unknown\"}");
        }
    }

    private void listPages(HttpServerExchange exchange, String user) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (PluginPageRegistry.Entry e : PluginPageRegistry.list()) {
                PanelPage p = e.page;
                if (p.perm != null && user != null
                        && !UserStore.getInstance().hasPermission(user, p.perm)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getFullId());
                m.put("title", p.title == null ? p.id : p.title);
                m.put("icon", p.icon == null ? "" : p.icon);
                m.put("perm", p.perm);
                m.put("scope", p.scope == null ? "both" : p.scope);
                m.put("order", p.order);
                m.put("pluginName", e.pluginName);
                out.add(m);
            }
            out.sort(Comparator.comparingInt(a -> (Integer) a.get("order")));
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(out));
        } catch (Exception ex) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"" + esc(ex.getMessage()) + "\"}");
        }
    }

    private void renderPage(HttpServerExchange exchange, String user) {
        try {
            Deque<String> idq = exchange.getQueryParameters().get("id");
            if (idq == null || idq.isEmpty()) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("missing id");
                return;
            }
            String fullId = idq.peekFirst();
            PanelPage p = PluginPageRegistry.get(fullId);
            if (p == null) {
                exchange.setStatusCode(404);
                exchange.getResponseSender().send("page not found");
                return;
            }
            if (p.perm != null && !UserStore.getInstance().hasPermission(user, p.perm)) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("forbidden");
                return;
            }

            Deque<String> iq = exchange.getQueryParameters().get("instance");
            String iid = (iq != null && !iq.isEmpty()) ? iq.peekFirst() : null;
            
            // ★ 修复：渲染前校验实例访问权
            if (iid != null && !iid.isEmpty() && !UserStore.getInstance().canAccessInstance(user, iid)) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("forbidden: no access to this instance");
                return;
            }

            PanelPage.PageContext ctx = new PanelPage.PageContext();
            ctx.username = user;
            ctx.instanceId = iid;
            ctx.query = new LinkedHashMap<>();
            for (Map.Entry<String, Deque<String>> e : exchange.getQueryParameters().entrySet()) {
                if (!e.getValue().isEmpty()) ctx.query.put(e.getKey(), e.getValue().peekFirst());
            }

            String html = p.renderer != null ? p.renderer.render(ctx) : "";
            if (html == null) html = "";

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
            exchange.getResponseSender().send(html);
        } catch (Exception ex) {
            // ★ 修复：反射型 XSS 防护
            exchange.setStatusCode(500);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
            exchange.getResponseSender().send("Error rendering plugin page");
            System.err.println("[Plugin] 渲染页面异常: " + ex.getMessage());
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}