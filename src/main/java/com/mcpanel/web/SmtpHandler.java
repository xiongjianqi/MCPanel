package com.mcpanel.web;

import com.mcpanel.config.SmtpConfig;
import com.mcpanel.config.UserStore;
import com.mcpanel.util.SmtpManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;

import java.util.HashMap;
import java.util.Map;

public class SmtpHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AuthHandler auth;

    public SmtpHandler(com.mcpanel.config.ConfigManager config, AuthHandler auth) {
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        String currentUser = auth.getCurrentUser(exchange);
        if (currentUser == null || !UserStore.getInstance().isMaster(currentUser)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("{\"error\":\"need master\"}");
            return;
        }

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    String path = exchange.getRequestPath();
                    SmtpConfig smtpCfg = SmtpConfig.getInstance();

                    if (path.equals("/api/smtp/get")) {
                        SmtpConfig.Data d = smtpCfg.get();
                        Map<String, String> res = new HashMap<>();
                        res.put("host", d.host);
                        res.put("port", d.port);
                        res.put("user", d.user);
                        res.put("pass", ""); // 永远不返回明文密码
                        res.put("from", d.from);
                        res.put("ssl", d.ssl);
                        ex.getResponseSender().send(MAPPER.writeValueAsString(res));
                        return;
                    }

                    Map<String, String> p = MAPPER.readValue(body, Map.class);
                    if (path.equals("/api/smtp/save")) {
                        smtpCfg.update(
                            p.getOrDefault("host", ""),
                            p.getOrDefault("port", "465"),
                            p.getOrDefault("ssl", "ssl"),
                            p.getOrDefault("from", ""),
                            p.getOrDefault("user", ""),
                            p.get("pass")
                        );
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (path.equals("/api/smtp/test")) {
                        String target = p.get("email");
                        boolean ok = SmtpManager.sendEmail(target, "MC Panel 测试邮件", "<h3>连接成功</h3><p>您的 SMTP 配置正确！</p>");
                        ex.getResponseSender().send("{\"ok\":" + ok + "}");
                        return;
                    }

                    ex.setStatusCode(404);
                    ex.getResponseSender().send("{\"error\":\"unknown\"}");
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"internal\"}");
                }
            });
        });
    }
}