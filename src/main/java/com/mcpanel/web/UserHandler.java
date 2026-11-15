package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.nio.ByteBuffer;
import java.util.*;

public class UserHandler {
    private final ConfigManager config;
    private final AuthHandler auth;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UserHandler(ConfigManager config, AuthHandler auth) {
        this.config = config;
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        String currentUser = auth.getCurrentUser(exchange);
        
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    UserStore store = UserStore.getInstance();
                    if (currentUser == null) {
                        ex.setStatusCode(401);
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"error\":\"unauthorized\"}");
                        return;
                    }

                    if (path.equals("/api/users/list")) {
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (UserStore.User u : store.getSubUsers()) {
                            Map<String, Object> m = new HashMap<>();
                            m.put("name", u.username);
                            m.put("email", u.email == null ? "" : u.email);
                            m.put("perms", u.perms == null ? new ArrayList<>() : u.perms);
                            m.put("instances", u.instances == null ? new ArrayList<>() : u.instances);
                            m.put("instancePerms", u.instancePerms == null ? new HashMap<>() : u.instancePerms);
                            list.add(m);
                        }
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                        ex.getResponseSender().send(ByteBuffer.wrap(MAPPER.writeValueAsBytes(list)));
                        return;
                    }

                    Map<String, String> p = parse(body);

                    if (path.equals("/api/users/add")) {
                        String email = p.getOrDefault("email", "").trim();
                        String perms = p.getOrDefault("perms", "");
                        String instances = p.getOrDefault("instances", "");
                        String instPermsStr = p.getOrDefault("instancePerms", "{}");
                    
                        List<String> pl = splitList(perms);
                        if (!store.isMaster(currentUser)) {
                            List<String> ownPerms = store.getPermissions(currentUser);
                            for (String reqPerm : pl) {
                                if (!ownPerms.contains(reqPerm)) {
                                    jsonError(ex, "权限不足：您无法分配自己没有的权限 [" + reqPerm + "]");
                                    return;
                                }
                            }
                        }
                    
                        String token = java.util.UUID.randomUUID().toString().replace("-", "");
                        UserStore.InviteInfo inv = new UserStore.InviteInfo();
                        inv.email = email;
                        inv.perms = pl;
                        inv.instances = splitList(instances);
                        inv.expireAt = System.currentTimeMillis() + 48 * 3600_000L;
                    
                        if (!store.isMaster(currentUser)) {
                            List<String> ownInst = store.getAllowedInstances(currentUser);
                            if (ownInst != null) for (String iid : inv.instances) if (!ownInst.contains(iid)) { jsonError(ex, "越权操作：您无法分配实例 " + iid); return; }
                        }

                        store.pendingInvites.put(token, inv);

                        String host = ex.getRequestHeaders().getFirst("Host");
                        String proto = "http";
                        if (ex.getRequestHeaders().getFirst("X-Forwarded-Proto") != null) {
                            proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
                        }
                        String url = proto + "://" + host + "/register?token=" + token;
                        String html = "<h3>MC Panel 邀请</h3><p>您被邀请加入面板，请点击以下链接设置您的用户名和密码：</p><p><a href='" + url + "'>" + url + "</a></p>";
                    
                        boolean emailSent = false;
                        boolean hasSmtp = !config.get("smtp.host", "").isEmpty();
                        if (!email.isEmpty() && hasSmtp) {
                            emailSent = com.mcpanel.util.SmtpManager.sendEmail(email, "MC Panel 注册邀请", html);
                        }
                    
                        Map<String, Object> res = new HashMap<>();
                        res.put("ok", true);
                        res.put("emailSent", emailSent);
                        if (!emailSent) {
                            res.put("token", token);
                        }
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(res));
                        return;
                    }

                    if (path.equals("/api/users/delete")) {
                        String name = p.getOrDefault("name", "");
                        if (!store.isMaster(currentUser)) {
                            UserStore.User target = store.findUser(name);
                            if (target == null) { jsonError(ex, "用户不存在"); return; }
                            List<String> own = store.getPermissions(currentUser);
                            List<String> targetPerms = target.perms == null ? new ArrayList<>() : new ArrayList<>(target.perms);
                            targetPerms.removeAll(own);
                            if (!targetPerms.isEmpty()) {
                                jsonError(ex, "无权删除该用户（目标用户拥有您没有的权限）");
                                return;
                            }
                        }
                        store.removeSubUser(name);
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (path.equals("/api/users/update")) {
                        String name = p.getOrDefault("name", "");
                        if (name.equals(currentUser)) { jsonError(ex, "不能修改自己的权限"); return; }
                        String pass = p.getOrDefault("pass", "");
                        String perms = p.getOrDefault("perms", "");
                        String instances = p.getOrDefault("instances", "");
                        String instPermsStr = p.getOrDefault("instancePerms", "{}");
                    
                        if (!pass.isEmpty() && pass.length() < 8) {
                            jsonError(ex, "密码至少 8 位");
                            return;
                        }
                    
                        if (!store.isMaster(currentUser)) {
                            UserStore.User target = store.findUser(name);
                            if (target == null) { jsonError(ex, "用户不存在"); return; }
                            List<String> own0 = store.getPermissions(currentUser);
                            List<String> targetPerms = target.perms == null ? new ArrayList<>() : new ArrayList<>(target.perms);
                            targetPerms.removeAll(own0);
                            if (!targetPerms.isEmpty()) {
                                jsonError(ex, "无权修改该用户（目标用户拥有您没有的权限）");
                                return;
                            }
                        }
                    
                        List<String> pl = splitList(perms);
                        if (!store.isMaster(currentUser)) {
                            List<String> ownPerms = store.getPermissions(currentUser);
                            for (String reqPerm : pl) {
                                if (!ownPerms.contains(reqPerm)) {
                                    jsonError(ex, "权限不足：您无法分配自己没有的权限 [" + reqPerm + "]");
                                    return;
                                }
                            }
                        }
                    
                        List<String> il = splitList(instances);
                        if (!store.isMaster(currentUser)) {
                            List<String> ownInst = store.getAllowedInstances(currentUser);
                            if (ownInst != null) for (String iid : il) if (!ownInst.contains(iid)) { jsonError(ex, "越权操作：您无法分配实例 " + iid); return; }
                        }
                    
                        Map<String, List<String>> parsedInstPerms;
                        try {
                            parsedInstPerms = MAPPER.readValue(instPermsStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {});
                        } catch (Exception e) {
                            jsonError(ex, "instancePerms 格式错误");
                            return;
                        }
                        if (parsedInstPerms == null) parsedInstPerms = new HashMap<>();

                        for (Map.Entry<String, List<String>> entry : parsedInstPerms.entrySet()) {
                            String iid = entry.getKey();
                            List<String> reqPerms = entry.getValue();
                            
                            // ★ 核心修复：禁止非 master 分配 remote 权限
                            if (!store.isMaster(currentUser) && reqPerms.contains("remote")) {
                                jsonError(ex, "越权操作：只有所有者可以分配远程管理权限");
                                return;
                            }

                            if (!store.isMaster(currentUser)) {
                                if (!store.canAccessInstance(currentUser, iid)) {
                                    jsonError(ex, "越权操作：您无权分配实例 " + iid + " 的权限");
                                    return;
                                }
                                for (String rp : reqPerms) {
                                    if (!store.hasInstancePermission(currentUser, iid, rp)) {
                                        jsonError(ex, "越权操作：您无法分配实例 " + iid + " 的 [" + rp + "] 权限");
                                        return;
                                    }
                                }
                            }
                        }

                        store.updateSubUser(name, pass, pl, il, true);
                        UserStore.User u = store.findUser(name);
                        if (u != null) { 
                            u.instancePerms = parsedInstPerms;
                            store.save(); 
                        }
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    ex.setStatusCode(404);
                    jsonError(ex, "unknown");
                } catch (Exception e) {
                    e.printStackTrace();
                    jsonError(ex, "内部错误");
                }
            });
        });
    }

    private List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String x : s.split(",")) {
            String t = x.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private Map<String, String> parse(String body) throws Exception {
        if (body == null || body.isEmpty()) return new HashMap<>();
        return MAPPER.readValue(body, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
    }
    
    private void jsonError(HttpServerExchange ex, String msg) {
        try {
            ex.setStatusCode(400);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send(MAPPER.writeValueAsString(Collections.singletonMap("error", msg)));
        } catch (Exception ignored) {}
    }
}
