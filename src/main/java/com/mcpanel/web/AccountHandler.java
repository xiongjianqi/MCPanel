package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.SmtpConfig;
import com.mcpanel.config.UserStore;
import com.mcpanel.util.Crypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AccountHandler {
    private final ConfigManager config;
    private final AuthHandler auth;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private static final Map<String, String> emailCodes = new ConcurrentHashMap<>();
    private static final Map<String, Integer> emailAttempts = new ConcurrentHashMap<>();
    private static final Map<String, Long> emailLastSent = new ConcurrentHashMap<>();

    public AccountHandler(ConfigManager config, AuthHandler auth) {
        this.config = config;
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        
        if (path.equals("/api/account/logout")) {
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                ex.dispatch(() -> {
                    auth.logout(ex);
                    json(ex, Collections.singletonMap("ok", true));
                });
            });
            return;
        }

        String currentUser = auth.getCurrentUser(exchange);
        if (currentUser == null) {
            exchange.setStatusCode(401);
            exchange.getResponseSender().send("{\"error\":\"unauthorized\"}");
            return;
        }
        
        if ("GET".equals(exchange.getRequestMethod().toString()) && path.equals("/api/account/info")) {
            try {
                json(exchange, buildAccountInfo(currentUser));
            } catch (Exception e) {
                exchange.setStatusCode(500);
                jsonError(exchange, "内部错误");
            }
            return;
        }
        
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = parse(body);
                    UserStore store = UserStore.getInstance();

                    if (path.equals("/api/account/bind-email/send-code")) {
                        String email = p.getOrDefault("email", "").trim();
                        if (email.isEmpty()) {
                            ex.setStatusCode(400); jsonError(ex, "邮箱不能为空"); return;
                        }
                    
                        long now = System.currentTimeMillis();
                        Long last = emailLastSent.get(email);
                        if (last != null && now - last < 60000) {
                            ex.setStatusCode(429); jsonError(ex, "发送过于频繁，请稍后再试"); return;
                        }
                        emailLastSent.put(email, now);
                        emailAttempts.remove(email);
                    
                        String code = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
                        emailCodes.put(email, code);
                    
                        boolean ok = com.mcpanel.util.SmtpManager.sendEmail(email, "MC Panel 绑定邮箱", "<h3>绑定验证码</h3><p>您的验证码是：<b>" + code + "</b>，5分钟内有效。</p>");
                        if (ok) {
                            ok(ex);
                            new java.util.Timer().schedule(new java.util.TimerTask() {
                                @Override public void run() { emailCodes.remove(email); emailAttempts.remove(email); emailLastSent.remove(email); }
                            }, 300_000);
                        } else {
                            jsonError(ex, "邮件发送失败，请检查 SMTP 配置");
                        }
                        return;
                    }

                    if (path.equals("/api/account/bind-email")) {
                        String email = p.getOrDefault("email", "").trim();
                        String code = p.getOrDefault("code", "").trim();
                        if (email.isEmpty() || code.isEmpty()) {
                            ex.setStatusCode(400); jsonError(ex, "邮箱和验证码不能为空"); return;
                        }
                    
                        int att = emailAttempts.merge(email, 1, Integer::sum);
                        if (att > 5) {
                            emailCodes.remove(email); emailAttempts.remove(email);
                            ex.setStatusCode(429); jsonError(ex, "尝试次数过多，请重新获取验证码"); return;
                        }

                        String expected = emailCodes.get(email);
                        if (expected == null || !expected.equals(code)) {
                            ex.setStatusCode(400); jsonError(ex, "验证码错误或已过期"); return;
                        }
                        UserStore.User u = store.findUser(currentUser);
                        if (u != null) { 
                            u.email = email; 
                            store.save(); 
                            emailCodes.remove(email);
                        }
                        ok(ex);
                        return;
                    }

                    if (path.equals("/api/account/unbind-email")) {
                        // ★ 核心修复：解绑安全邮箱必须验证当前密码
                        String pass = p.getOrDefault("password", "");
                        if (!store.verify(currentUser, pass)) {
                            ex.setStatusCode(400); jsonError(ex, "密码错误，无法解绑邮箱"); return;
                        }
                        
                        UserStore.User u = store.findUser(currentUser);
                        if (u != null) { 
                            u.email = null; 
                            if ("email".equals(u.twoFactorMethod)) u.twoFactorMethod = "none";
                            store.save(); 
                        }
                        ok(ex);
                        return;
                    }

                    if (path.equals("/api/account/setting/2fa_method")) {
                        String method = p.getOrDefault("method", "none");
                        UserStore.User u = store.findUser(currentUser);
                        if (u != null) { 
                            if ("email".equals(method) && (u.email == null || u.email.isEmpty())) {
                                jsonError(ex, "请先绑定安全邮箱"); return;
                            }
                            if ("totp".equals(method) && (u.totpSecret == null || u.totpSecret.isEmpty())) {
                                jsonError(ex, "请先绑定动态令牌"); return;
                            }
                            u.twoFactorMethod = method; 
                            store.save(); 
                        }
                        ok(ex);
                        return;
                    }

                    if (path.equals("/api/account/totp/setup")) {
                        String secret = com.mcpanel.util.TotpUtil.generateSecret();
                        Map<String, Object> r = new HashMap<>();
                        r.put("secret", secret);
                        String url = "otpauth://totp/MCPanel:" + currentUser + "?secret=" + secret + "&issuer=MCPanel";
                        r.put("url", url);
                        r.put("qrcode", com.mcpanel.util.TotpUtil.generateQRBase64(url));
                        json(ex, r);
                        return;
                    }

                    if (path.equals("/api/account/totp/enable")) {
                        if (!store.verify(currentUser, p.getOrDefault("password", ""))) {
                            ex.setStatusCode(400); jsonError(ex, "密码错误，无法绑定"); return;
                        }
                        String secret = p.getOrDefault("secret", "");
                        String codeStr = p.getOrDefault("code", "");
                        try {
                            int code = Integer.parseInt(codeStr);
                            if (com.mcpanel.util.TotpUtil.verifyCode(secret, code)) {
                                UserStore.User u = store.findUser(currentUser);
                                if (u != null) { u.totpSecret = Crypto.encrypt(secret); store.save(); }
                                ok(ex);
                            } else { jsonError(ex, "验证码错误"); }
                        } catch (Exception e) { jsonError(ex, "验证码格式错误"); }
                        return;
                    }

                    if (path.equals("/api/account/totp/disable")) {
                        if (!store.verify(currentUser, p.getOrDefault("password", ""))) {
                            ex.setStatusCode(400); jsonError(ex, "密码错误，无法解绑"); return;
                        }
                        UserStore.User u = store.findUser(currentUser);
                        if (u != null) { 
                            u.totpSecret = null; 
                            if ("totp".equals(u.twoFactorMethod)) u.twoFactorMethod = "none";
                            store.save(); 
                        }
                        ok(ex);
                        return;
                    }

                    if (path.equals("/api/account/recovery/generate")) {
                        if (!store.verify(currentUser, p.getOrDefault("password", ""))) {
                            ex.setStatusCode(400); jsonError(ex, "密码错误，无法生成恢复码"); return;
                        }
                        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
                        String formatted = code.substring(0,4) + "-" + code.substring(4,8) + "-" + code.substring(8,12) + "-" + code.substring(12);
                        UserStore.User u = store.findUser(currentUser);
                        if (u != null) { u.recoveryHash = Crypto.hashPassword(code); u.needsRecoverySetup = false; store.save(); }
                        Map<String, Object> r = new HashMap<>();
                        r.put("code", formatted);
                        json(ex, r);
                        return;
                    }

                    if (path.equals("/api/account/info")) {
                        json(ex, buildAccountInfo(currentUser));
                        return;
                    }

                    if (path.equals("/api/account/password")) {
                        String oldPass = p.getOrDefault("oldPassword", "");
                        String newPass = p.getOrDefault("newPassword", "");
                        if (!store.verify(currentUser, oldPass)) {
                            ex.setStatusCode(400); jsonError(ex, "旧密码错误"); return;
                        }
                        if (newPass.length() < 8) {
                            ex.setStatusCode(400); jsonError(ex, "新密码至少 8 位"); return;
                        }
                        if (store.isMaster(currentUser)) store.setMasterPassword(newPass);
                        else store.updateSubUser(currentUser, newPass, null);
                        
                        UserStore.User u = store.findUser(currentUser);
                        if (u != null) { u.needsPasswordChange = false; store.save(); }
                        
                        ok(ex);
                        return;
                    }

                    if (path.equals("/api/account/rename")) {
                        String newName = p.getOrDefault("newUsername", "").trim();
                        if (newName.length() < 2) {
                            ex.setStatusCode(400); jsonError(ex, "用户名至少 2 位"); return;
                        }
                        if (newName.equals(currentUser)) { ok(ex); return; }
                        if (store.findUser(newName) != null) {
                            ex.setStatusCode(400); jsonError(ex, "用户名已存在"); return;
                        }
                        String oldName = currentUser;
                        if (store.renameUser(oldName, newName)) {
                            auth.updateSessionUsername(oldName, newName);
                            Map<String, Object> r = new HashMap<>();
                            r.put("ok", true);
                            r.put("username", newName);
                            json(ex, r);
                        } else {
                            ex.setStatusCode(400); jsonError(ex, "重命名失败");
                        }
                        return;
                    }

                    if (path.equals("/api/account/apikey/generate")) {
                        String key = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                        if (store.isMaster(currentUser)) store.setMasterApiKey(key);
                        else store.setSubUserApiKey(currentUser, key);
                        Map<String, Object> r = new HashMap<>();
                        r.put("apiKey", key);
                        json(ex, r);
                        return;
                    }

                    if (path.equals("/api/account/apikey/revoke")) {
                        if (store.isMaster(currentUser)) store.setMasterApiKey("");
                        else store.setSubUserApiKey(currentUser, "");
                        ok(ex);
                        return;
                    }

                    ex.setStatusCode(404);
                    jsonError(ex, "unknown");
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    jsonError(ex, "内部错误");
                }
            });
        });
    }

    private Map<String, Object> buildAccountInfo(String currentUser) {
        UserStore store = UserStore.getInstance();
        UserStore.User u = store.findUser(currentUser);
        Map<String, Object> info = new HashMap<>();
        info.put("username", currentUser);
        info.put("isMain", store.isMaster(currentUser));
        info.put("email", u != null && u.email != null ? u.email : "");
        
        SmtpConfig.Data smtp = SmtpConfig.getInstance().get();
        info.put("hasSmtp", smtp.host != null && !smtp.host.isEmpty());
        
        info.put("perms", store.getPermissions(currentUser));
        String key = (u == null) ? "" : Crypto.decrypt(u.apiKeyEnc);
        info.put("apiKey", key);
        info.put("needsRecoverySetup", u != null && u.needsRecoverySetup);
        info.put("hasTotp", u != null && u.totpSecret != null && !u.totpSecret.isEmpty());
        info.put("twoFactorMethod", u != null ? u.twoFactorMethod : "none");
        info.put("needsPasswordChange", u != null && u.needsPasswordChange);
        return info;
    }

    private Map<String, String> parse(String body) throws Exception {
        if (body == null || body.isEmpty()) return new HashMap<>();
        return MAPPER.readValue(body, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
    }
    private void ok(HttpServerExchange ex) { json(ex, Collections.singletonMap("ok", true)); }
    private void json(HttpServerExchange ex, Object obj) {
        try {
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(MAPPER.writeValueAsString(obj));
        } catch (Exception ignored) {}
    }
    private void jsonError(HttpServerExchange ex, String msg) {
        try {
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(MAPPER.writeValueAsString(Collections.singletonMap("error", msg)));
        } catch (Exception ignored) {}
    }
}