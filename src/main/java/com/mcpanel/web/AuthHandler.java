package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.*;
import java.util.concurrent.*;

public class AuthHandler {
    public static final io.undertow.util.AttachmentKey<String> USER_ATTRIBUTE = io.undertow.util.AttachmentKey.create(String.class);

    private final ConfigManager config;
    private final RateLimiter rateLimiter;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String SESSION_FILE = "sessions.json";
    /** 会话有效期默认 15 天（360 小时） */
    private static final int DEFAULT_SESSION_HOURS = 360;
    /** 活跃访问时最多每 6 小时顺延一次有效期，避免每个请求都写盘 */
    private static final long SESSION_RENEW_AFTER_MS = 6 * 3600_000L;
    
    private static final Map<String, String> emailCodes = new ConcurrentHashMap<>();
    private static final Map<String, Integer> emailCodeAttempts = new ConcurrentHashMap<>();
    private static final Map<String, Long> emailLastSent = new ConcurrentHashMap<>();

    private static final int SESSION_WRITE_QUEUE_CAPACITY = 64;
    private static final long SESSION_FLUSH_INTERVAL_MS = 2000L;

    private static final ExecutorService IO_POOL = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(SESSION_WRITE_QUEUE_CAPACITY),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "auth-io");
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "auth-scheduler");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final long EMAIL_CODE_TTL_MS = 300_000L;
    private static final int MAX_EMAIL_TRACK = 5000;
    private static final int MAX_EMAIL_CODES = 5000;
    private static final int MAX_EMAIL_LEN = 254;

    private volatile boolean sessionsDirty = false;
    private final java.util.concurrent.atomic.AtomicBoolean sessionWriteQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static class Session {
        public String username;
        public long expireAt;
    }

    public AuthHandler(ConfigManager config, RateLimiter rateLimiter) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        loadSessions();
        SCHEDULER.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    flushSessionsIfDirty();
                } catch (Throwable t) {
                    System.err.println("[AuthHandler] 定时落盘失败: " + t);
                }
            }
        }, SESSION_FLUSH_INTERVAL_MS, SESSION_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void loadSessions() {
        try {
            java.io.File f = new java.io.File(SESSION_FILE);
            if (f.exists()) {
                java.util.Map<String, Session> loaded = MAPPER.readValue(f, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Session>>() {});
                long now = System.currentTimeMillis();
                long maxExpire = now + sessionTimeoutHours() * 3600_000L;
                for (java.util.Map.Entry<String, Session> e : loaded.entrySet()) {
                    Session s = e.getValue();
                    if (s == null || s.expireAt <= now) continue;
                    // 老版本默认 30 天，升级后把还在有效期内的会话按新上限收紧
                    if (s.expireAt > maxExpire) s.expireAt = maxExpire;
                    sessions.put(e.getKey(), s);
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveSessionsAsync() {
        sessionsDirty = true;
        flushSessionsIfDirty();
    }

    private void flushSessionsIfDirty() {
        if (!sessionsDirty) return;
        if (!sessionWriteQueued.compareAndSet(false, true)) return;
        try {
            IO_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        sessionWriteQueued.set(false);
                        if (!sessionsDirty) return;
                        sessionsDirty = false;
                        writeSessionsSnapshot();
                    } catch (Throwable t) {
                        sessionsDirty = true;
                        System.err.println("[AuthHandler] 会话写盘任务异常: " + t);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            sessionWriteQueued.set(false);
            System.err.println("[AuthHandler] 会话写盘队列已满，本次落盘合并到定时任务: " + e.getMessage());
        } catch (Throwable t) {
            sessionWriteQueued.set(false);
            System.err.println("[AuthHandler] 提交会话写盘任务失败: " + t);
        }
    }

    private void writeSessionsSnapshot() {
        java.io.File f = new java.io.File(SESSION_FILE);
        java.io.File tmp = new java.io.File(f.getAbsoluteFile().getParentFile(), f.getName() + ".tmp");
        try {
            Map<String, Session> snapshot = new HashMap<>(sessions);
            MAPPER.writeValue(tmp, snapshot);
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException am) {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            sessionsDirty = true;
            System.err.println("[AuthHandler] 会话写盘失败（将重试）: " + e);
            return;
        }
        try {
            f.setReadable(false, false); f.setReadable(true, true);
            f.setWritable(false, false); f.setWritable(true, true);
        } catch (Exception e) {
            System.err.println("[AuthHandler] 会话文件权限设置失败: " + e.getMessage());
        }
    }

    private void scheduleEmailCodeExpiry(final String key, final boolean clearAttempts) {
        try {
            SCHEDULER.schedule(new Runnable() {
                @Override
                public void run() {
                    emailCodes.remove(key);
                    if (clearAttempts) emailCodeAttempts.remove(key);
                }
            }, EMAIL_CODE_TTL_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            System.err.println("[AuthHandler] 验证码过期任务提交失败: " + e.getMessage());
        }
    }

    private static void touchEmailSent(String email, long now) {
        emailLastSent.put(email, now);
        if (emailLastSent.size() <= MAX_EMAIL_TRACK) return;
        long cutoff = now - 3_600_000L;
        emailLastSent.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < cutoff);
        java.util.Iterator<String> it = emailLastSent.keySet().iterator();
        while (emailLastSent.size() > MAX_EMAIL_TRACK && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static boolean isReasonableEmail(String email) {
        if (email == null) return false;
        String e = email.trim();
        if (e.isEmpty() || e.length() > MAX_EMAIL_LEN) return false;
        int at = e.indexOf('@');
        return at > 0 && at < e.length() - 1 && e.indexOf(' ', at) < 0;
    }

    private static String maskEmail(String email) {
        if (email == null) return "";
        String e = email.trim();
        int at = e.indexOf('@');
        if (at <= 0) return e.isEmpty() ? "" : "***";
        String name = e.substring(0, at);
        String domain = e.substring(at);
        String head = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        return head + "***" + domain;
    }

    // ===== 图形验证码：发送验证码类操作前的人机校验 =====
    private static final Map<String, Captcha> captchas = new ConcurrentHashMap<>();
    private static final int MAX_CAPTCHAS = 5000;
    private static final long CAPTCHA_TTL_MS = 300_000L;
    private static class Captcha {
        final String code;
        final long expireAt;

        Captcha(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }

    private static void cleanCaptchas() {
        long now = System.currentTimeMillis();
        captchas.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expireAt < now);
    }

    // 生成一张图片验证码：使用 easy-captcha 库（自带字体，不依赖系统字体）
    public void handleCaptcha(HttpServerExchange exchange) {
        try {
            cleanCaptchas();
            if (captchas.size() >= MAX_CAPTCHAS) {
                exchange.setStatusCode(429);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                exchange.getResponseSender().send("{\"error\":\"系统繁忙，请稍后再试\"}");
                return;
            }

            com.wf.captcha.SpecCaptcha captcha = new com.wf.captcha.SpecCaptcha(120, 40, 4);
            captcha.setCharType(com.wf.captcha.base.Captcha.TYPE_NUM_AND_UPPER);

            String id = UUID.randomUUID().toString();
            captchas.put(id, new Captcha(captcha.text(), System.currentTimeMillis() + CAPTCHA_TTL_MS));

            String image = captcha.toBase64();
            if (image == null || image.isEmpty()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("{\"error\":\"验证码生成失败\"}");
                return;
            }
            if (!image.startsWith("data:")) image = "data:image/png;base64," + image;

            Map<String, String> res = new HashMap<>();
            res.put("id", id);
            res.put("image", image);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(res));
        } catch (Throwable e) {
            // AWT 在极端环境可能抛 Error，这里统一兜住，避免整条请求线程挂掉
            System.err.println("[AuthHandler] 验证码生成失败: " + e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"验证码生成失败\"}");
        }
    }

    // 一次性消费，输入不区分大小写
    private static boolean checkCaptcha(String id, String answer) {
        if (id == null || id.isEmpty() || answer == null || answer.trim().isEmpty()) return false;
        Captcha c = captchas.remove(id);
        if (c == null) return false;
        if (c.expireAt < System.currentTimeMillis()) return false;
        return c.code.equalsIgnoreCase(answer.trim());
    }

    private String clientIp(HttpServerExchange exchange) {
        if (exchange == null) return null;
        try {
            java.net.InetAddress src = null;
            if (exchange.getSourceAddress() != null) src = exchange.getSourceAddress().getAddress();
            boolean trustedProxy = src != null && (src.isLoopbackAddress() || src.isSiteLocalAddress());

            if (trustedProxy) {
                String fwd = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
                if (fwd != null && !fwd.trim().isEmpty()) {
                    int comma = fwd.indexOf(',');
                    String first = (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
                    if (!first.isEmpty()) return first;
                }
                String real = exchange.getRequestHeaders().getFirst("X-Real-IP");
                if (real != null && !real.trim().isEmpty()) return real.trim();
            }
            if (src != null) return src.getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    public void updateSessionUsername(String oldName, String newName) {
        for (Session s : sessions.values()) {
            if (s.username.equals(oldName)) s.username = newName;
        }
        saveSessionsAsync();
    }

    public void logout(HttpServerExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                part = part.trim();
                if (part.startsWith("token=")) {
                    sessions.remove(part.substring(6));
                    saveSessionsAsync();
                }
            }
        }
        exchange.getResponseHeaders().add(Headers.SET_COOKIE, "token=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        exchange.getResponseHeaders().add(Headers.SET_COOKIE, "instance=; Path=/; SameSite=Lax; Max-Age=0");
        exchange.getResponseHeaders().add(Headers.SET_COOKIE, "device_token=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    public void serveLoginPage(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getResponseSender().send(LOGIN_HTML);
    }

    public void serveForgotPage(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getResponseSender().send(FORGOT_HTML);
    }

    public void serveRegisterPage(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getResponseSender().send(REGISTER_HTML);
    }

    public void handleLoginSendEmail(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = MAPPER.readValue(body, Map.class);
                    if (!checkCaptcha(p.get("captchaId"), p.get("captchaCode"))) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"图形验证码错误或已过期\"}"); return;
                    }
                    String user = p.get("username");
                    String pass = p.get("password");
                    UserStore store = UserStore.getInstance();
                    UserStore.User u = store.findUser(user);

                    if (u == null || !store.verify(user, pass)) {
                        rateLimiter.recordFail(clientIp(ex));
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"用户名或密码错误\"}"); return;
                    }
                    if (u.email == null || u.email.isEmpty()) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"该账户未绑定安全邮箱\"}"); return;
                    }

                    String code = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
                    emailCodes.put("login:" + user, code);
                
                    boolean ok = com.mcpanel.util.SmtpManager.sendEmail(u.email, "MC Panel 登录验证", "<h3>登录验证码</h3><p>您的登录验证码是：<b>" + code + "</b>，5分钟内有效。</p>");
                    if (ok) {
                        rateLimiter.resetFail(clientIp(ex));
                        ex.getResponseSender().send("{\"ok\":true}");
                        scheduleEmailCodeExpiry("login:" + user, false);
                    } else {
                        ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"邮件发送失败\"}");
                    }
                } catch (Exception e) {
                    ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"internal\"}");
                }
            });
        });
    }

    public void handleForgotEmail(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = MAPPER.readValue(body, Map.class);
                    String path = exchange.getRequestPath();
                    UserStore store = UserStore.getInstance();

                    if (path.equals("/api/auth/forgot/send-email")) {
                        if (!checkCaptcha(p.get("captchaId"), p.get("captchaCode"))) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"图形验证码错误或已过期\"}"); return;
                        }
                        String username = p.get("username");
                        if (username == null || username.trim().isEmpty()) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"缺少用户名\"}"); return;
                        }
                        username = username.trim();
                        UserStore.User target = store.findUser(username);
                        if (target == null) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"账号不存在\"}"); return;
                        }
                        String boundEmail = (target.email == null) ? "" : target.email.trim();
                        if (boundEmail.isEmpty() || !isReasonableEmail(boundEmail)) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"该账号未绑定安全邮箱，无法通过邮箱找回\"}"); return;
                        }
                        // 只认账号自己绑定的邮箱：前端传了邮箱时也必须与绑定邮箱一致
                        String inputEmail = p.get("email");
                        if (inputEmail != null && !inputEmail.trim().isEmpty()
                                && !boundEmail.equalsIgnoreCase(inputEmail.trim())) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"该邮箱不是此账号绑定的邮箱\"}"); return;
                        }
                        if (emailCodes.size() >= MAX_EMAIL_CODES) {
                            ex.setStatusCode(429); ex.getResponseSender().send("{\"error\":\"系统繁忙，请稍后再试\"}"); return;
                        }
                        String key = "forgot:" + username;
                        long now = System.currentTimeMillis();
                        Long last = emailLastSent.get(key);
                        if (last != null && now - last < 60000) {
                            ex.setStatusCode(429); ex.getResponseSender().send("{\"error\":\"发送过于频繁，请稍后再试\"}"); return;
                        }
                        touchEmailSent(key, now);

                        String code = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
                        emailCodes.put(key, code);
                        emailCodeAttempts.remove(key);
                        scheduleEmailCodeExpiry(key, true);

                        boolean ok = com.mcpanel.util.SmtpManager.sendEmail(boundEmail, "MC Panel 找回密码", "<h3>验证码</h3><p>您的验证码是：<b>" + code + "</b>，5分钟内有效。</p>");
                        if (ok) ex.getResponseSender().send("{\"ok\":true}");
                        else { ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"邮件发送失败\"}"); }
                        return;
                    }

                    if (path.equals("/api/auth/forgot/reset-email")) {
                        String username = p.get("username");
                        if (username == null || username.trim().isEmpty()) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"缺少用户名\"}"); return;
                        }
                        username = username.trim();
                        String code = p.get("code");
                        String newPass = p.get("newPassword");
                        if (newPass == null || newPass.length() < 8) { ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"新密码至少 8 位\"}"); return; }

                        UserStore.User target = store.findUser(username);
                        if (target == null) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"账号不存在\"}"); return;
                        }
                        String boundEmail = (target.email == null) ? "" : target.email.trim();
                        if (boundEmail.isEmpty()) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"该账号未绑定安全邮箱\"}"); return;
                        }
                        String inputEmail = p.get("email");
                        if (inputEmail != null && !inputEmail.trim().isEmpty()
                                && !boundEmail.equalsIgnoreCase(inputEmail.trim())) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"该邮箱不是此账号绑定的邮箱\"}"); return;
                        }

                        String key = "forgot:" + username;
                        int attempts = emailCodeAttempts.merge(key, 1, Integer::sum);
                        if (attempts > 5) {
                            emailCodes.remove(key); emailCodeAttempts.remove(key);
                            rateLimiter.recordFail(clientIp(ex));
                            ex.setStatusCode(429); ex.getResponseSender().send("{\"error\":\"尝试次数过多，请重新获取验证码\"}"); return;
                        }

                        if (code == null || !code.equals(emailCodes.get(key))) { 
                            rateLimiter.recordFail(clientIp(ex));
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"验证码错误或已过期\"}"); return; 
                        }
                    
                        if (store.isMaster(username)) store.setMasterPassword(newPass);
                        else store.updateSubUser(username, newPass, null);
                        emailCodes.remove(key); emailCodeAttempts.remove(key);
                        rateLimiter.resetFail(clientIp(ex));
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }
                } catch (Exception e) { ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"internal\"}"); }
            });
        });
    }

    public void handleForgot(HttpServerExchange exchange, String ip) {
        String path = exchange.getRequestPath();
        if (rateLimiter.checkAndRecord(ip)) {
            exchange.setStatusCode(429);
            exchange.getResponseSender().send("{\"error\":\"请求过于频繁\"}");
            return;
        }
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = MAPPER.readValue(body, Map.class);
                    UserStore store = UserStore.getInstance();
                    String user = p.get("username");
                    UserStore.User u = store.findUser(user);
                
                    if (path.equals("/api/auth/forgot/check")) {
                        if (u == null) { ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"用户不存在\"}"); return; }
                        Map<String, Object> r = new HashMap<>();
                        r.put("hasTotp", u.totpSecret != null && !u.totpSecret.isEmpty());
                        r.put("hasRecovery", u.recoveryHash != null && !u.recoveryHash.isEmpty());
                        String boundEmail = (u.email == null) ? "" : u.email.trim();
                        r.put("hasEmail", !boundEmail.isEmpty());
                        r.put("emailMasked", maskEmail(boundEmail));
                        ex.getResponseSender().send(MAPPER.writeValueAsString(r));
                        return;
                    }
                
                    if (path.equals("/api/auth/forgot/reset")) {
                        if (u == null) { 
                            ex.setStatusCode(400); 
                            ex.getResponseSender().send("{\"error\":\"用户不存在\"}"); 
                            return; 
                        }
                        String totp = p.get("totp");
                        String recovery = p.get("recovery");
                        String newPass = p.get("newPassword");
                    
                        if (newPass == null || newPass.length() < 8) { ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"新密码至少 8 位\"}"); return; }
                    
                        boolean authOk = false;
                        if (u.totpSecret != null && !u.totpSecret.isEmpty() && totp != null && !totp.isEmpty()) {
                            try {
                                int totpCode = Integer.parseInt(totp.trim());
                                if (com.mcpanel.util.TotpUtil.verifyCode(com.mcpanel.util.Crypto.decrypt(u.totpSecret), totpCode)) authOk = true;
                            } catch (Exception e) {
                                rateLimiter.recordFail(clientIp(ex));
                                ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"动态令牌格式错误\"}"); return;
                            }
                        }
                        if (!authOk && u.recoveryHash != null && recovery != null && !recovery.isEmpty()) {
                            if (com.mcpanel.util.Crypto.verifyPassword(recovery.replace("-", "").toUpperCase(), u.recoveryHash)) authOk = true;
                        }
                    
                        if (authOk) {
                            if (store.isMaster(user)) store.setMasterPassword(newPass); else store.updateSubUser(user, newPass, null);
                            ex.getResponseSender().send("{\"ok\":true}");
                        } else {
                            rateLimiter.recordFail(clientIp(ex));
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"验证失败，请检查动态令牌或恢复码\"}");
                        }
                        return;
                    }
                } catch (Exception e) {
                    ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"服务器错误\"}");
                }
            });
        }, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void handleSetupTotp(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = MAPPER.readValue(body, Map.class);
                    String token = p.get("token");
                    UserStore store = UserStore.getInstance();
                    UserStore.InviteInfo inv = store.pendingInvites.get(token);
                    if (inv == null || inv.expireAt < System.currentTimeMillis()) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"邀请链接无效或已过期\"}"); return;
                    }

                    String secret = com.mcpanel.util.TotpUtil.generateSecret();
                    String url = "otpauth://totp/MCPanel:" + inv.email + "?secret=" + secret + "&issuer=MCPanel";
                    String qrBase64 = com.mcpanel.util.TotpUtil.generateQRBase64(url);

                    Map<String, Object> res = new HashMap<>();
                    res.put("secret", secret);
                    res.put("qrcode", qrBase64);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(res));
                } catch (Exception e) {
                    ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"internal\"}");
                }
            });
        });
    }

    public void handleRegister(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = MAPPER.readValue(body, Map.class);
                    String token = p.get("token");
                    String username = p.get("username");
                    String password = p.get("password");
                    String totpSecret = p.get("totpSecret");
                    String totpCode = p.get("totpCode");
                    String email = p.get("email");

                    UserStore store = UserStore.getInstance();
                    UserStore.InviteInfo inv = store.pendingInvites.get(token);
                
                    if (inv == null || inv.expireAt < System.currentTimeMillis()) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"邀请链接无效或已过期\"}"); return;
                    }
                    if (username == null || username.length() < 2 || password == null || password.length() < 8) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"用户名至少2位，密码至少8位\"}"); return;
                    }
                    if (store.findUser(username) != null) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"用户名已存在\"}"); return;
                    }

                    boolean hasTotp = totpSecret != null && !totpSecret.isEmpty();
                    if (hasTotp) {
                        try {
                            int code = Integer.parseInt(totpCode);
                            if (!com.mcpanel.util.TotpUtil.verifyCode(totpSecret, code)) {
                                ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"动态令牌验证码错误\"}"); return;
                            }
                        } catch (Exception e) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"验证码格式错误\"}"); return;
                        }
                    }

                    if (email != null && !email.isEmpty()) {
                        inv.email = email;
                    }

                    try {
                        store.addSubUser(username, password, inv.perms, inv.instances);
                    } catch (IllegalArgumentException iae) {
                        ex.setStatusCode(400);
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(
                                java.util.Collections.singletonMap("error", iae.getMessage())));
                        return;
                    }
                    UserStore.User u = store.findUser(username);
                    if (u != null) {
                        u.email = inv.email;
                        if (hasTotp) {
                            u.totpSecret = com.mcpanel.util.Crypto.encrypt(totpSecret);
                            u.needsRecoverySetup = true;
                        }
                        store.save();
                    }
                    store.pendingInvites.remove(token);

                    String code = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
                    String formatted = code.substring(0,4) + "-" + code.substring(4,8) + "-" + code.substring(8,12) + "-" + code.substring(12);
                    if (u != null) {
                        u.recoveryHash = com.mcpanel.util.Crypto.hashPassword(code);
                        u.needsRecoverySetup = false;
                        store.save();
                    }

                    Map<String, Object> res = new HashMap<>();
                    res.put("ok", true);
                    res.put("recoveryCode", formatted);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(res));
                } catch (Exception e) {
                    ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"internal\"}");
                }
            });
        });
    }

    public void handleLogin(HttpServerExchange exchange, String ip) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                Map<String, String> params = parseForm(body);
                String user = params.getOrDefault("username", "");
                String pass = params.getOrDefault("password", "");
                String code = params.getOrDefault("code", "");
                UserStore store = UserStore.getInstance();
                UserStore.User u = store.findUser(user);

                if (store.verify(user, pass)) {
                    if (u != null && !"none".equals(u.twoFactorMethod)) {
                        boolean deviceTrusted = false;
                        String cookie = ex.getRequestHeaders().getFirst(Headers.COOKIE);
                        String expectedHmac = com.mcpanel.util.Crypto.hashPassword(user + u.passwordHash);
                    
                        if (cookie != null) {
                            for (String part : cookie.split(";")) {
                                part = part.trim();
                                if (part.startsWith("device_token=")) {
                                    String dt = part.substring(13);
                                    if (dt.startsWith(user + ":")) {
                                        String hmac = dt.substring(user.length() + 1);
                                        if (java.security.MessageDigest.isEqual(
                                                hmac.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                expectedHmac.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                                            deviceTrusted = true;
                                        }
                                    }
                                }
                            }
                        }

                        if (!deviceTrusted) {
                            String safeUser = "";
                            try { safeUser = java.net.URLEncoder.encode(user, "UTF-8"); } catch (Exception ignored) {}

                            if (code == null || code.isEmpty()) {
                                ex.setStatusCode(302);
                                ex.getResponseHeaders().put(Headers.LOCATION, "/login?need_2fa=" + u.twoFactorMethod + "&u=" + safeUser);
                                ex.endExchange();
                                return;
                            }
                        
                            boolean isOk = false;
                            if ("totp".equals(u.twoFactorMethod)) {
                                try {
                                    int c = Integer.parseInt(code);
                                    if (com.mcpanel.util.TotpUtil.verifyCode(com.mcpanel.util.Crypto.decrypt(u.totpSecret), c)) isOk = true;
                                } catch (Exception ignored) {}
                            } else if ("email".equals(u.twoFactorMethod)) {
                                String expected = emailCodes.get("login:" + user);
                                if (expected != null && expected.equals(code)) isOk = true;
                            }

                            String attemptKey = "login2fa:" + user;
                            int attempts = isOk ? 0 : emailCodeAttempts.merge(attemptKey, 1, Integer::sum);
                            if (attempts >= 5) {
                                emailCodes.remove("login:" + user);
                                emailCodeAttempts.remove(attemptKey);
                            }

                            if (!isOk) {
                                rateLimiter.recordFail(ip);
                                ex.setStatusCode(302);
                                String errCode = attempts >= 5 ? "2fa_locked" : "2fa";
                                ex.getResponseHeaders().put(Headers.LOCATION, "/login?err=" + errCode + "&need_2fa=" + u.twoFactorMethod + "&u=" + safeUser);
                                ex.endExchange();
                                return;
                            }
                        
                            ex.getResponseHeaders().add(Headers.SET_COOKIE, "device_token=" + user + ":" + expectedHmac + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (30 * 24 * 3600));
                            emailCodes.remove("login:" + user);
                            emailCodeAttempts.remove(attemptKey);
                        }
                    }

                    rateLimiter.resetFail(ip);
                    if (u != null && u.needsRecoverySetup) {
                        ex.getResponseHeaders().add(Headers.SET_COOKIE, "force_recovery=1; Path=/; HttpOnly");
                    }
                    kickOldSessions(user);
                    issueToken(ex, user);
                    return;
                } else {
                    rateLimiter.recordFail(ip);
                }
                ex.setStatusCode(302);
                ex.getResponseHeaders().put(Headers.LOCATION, "/login?err=1");
                ex.endExchange();
            });
        });
    }

    private synchronized void kickOldSessions(String username) {
        boolean kicked = false;
        java.util.Iterator<java.util.Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Session> entry = it.next();
            if (username.equals(entry.getValue().username)) {
                it.remove(); kicked = true;
            }
        }
        if (kicked) {
            saveSessionsAsync();
        }
    }

    private void issueToken(HttpServerExchange ex, String user) {
        String token = UUID.randomUUID().toString();
        Session s = new Session();
        s.username = user;
        int timeoutHours = sessionTimeoutHours();
        s.expireAt = System.currentTimeMillis() + timeoutHours * 3600_000L;
        sessions.put(token, s);
        saveSessionsAsync();

        boolean isSsl = config.getBool("web.ssl.enabled", false);
        String secure = isSsl ? " Secure;" : "";
        
        ex.getResponseHeaders().add(Headers.SET_COOKIE,
                "token=" + token + "; Path=/; HttpOnly; SameSite=Lax;" + secure + " Max-Age=" + (timeoutHours * 3600));
        ex.getResponseHeaders().add(Headers.SET_COOKIE, "instance=; Path=/; SameSite=Lax; Max-Age=0");
        ex.setStatusCode(302);
        ex.getResponseHeaders().put(Headers.LOCATION, "/");
        ex.endExchange();
    }

    /** 会话有效期（小时）：默认 15 天；配置非法时回落到默认值，上限一年防止手滑 */
    private int sessionTimeoutHours() {
        int h = config.getInt("web.session.timeout.hours", DEFAULT_SESSION_HOURS);
        if (h < 1) h = DEFAULT_SESSION_HOURS;
        if (h > 24 * 365) h = 24 * 365;
        return h;
    }

    /** 续期时同步刷新浏览器里的 cookie，否则 cookie 会先于会话过期 */
    private void renewTokenCookie(HttpServerExchange ex, String token, int timeoutHours) {
        if (ex.isResponseStarted()) return;
        boolean isSsl = config.getBool("web.ssl.enabled", false);
        String secure = isSsl ? " Secure;" : "";
        ex.getResponseHeaders().put(Headers.SET_COOKIE,
                "token=" + token + "; Path=/; HttpOnly; SameSite=Lax;" + secure
                        + " Max-Age=" + (timeoutHours * 3600L));
    }

    public boolean isAuthenticated(HttpServerExchange exchange) {
        return getCurrentUser(exchange) != null;
    }

    public boolean hasPermission(HttpServerExchange exchange, String perm) {
        String user = getCurrentUser(exchange);
        if (user == null) return false;
        return UserStore.getInstance().hasPermission(user, perm);
    }

    public String getCurrentUser(HttpServerExchange exchange) {
        String cached = exchange.getAttachment(USER_ATTRIBUTE);
        if (cached != null) return cached;
        String user = doGetCurrentUser(exchange);
        if (user != null) exchange.putAttachment(USER_ATTRIBUTE, user);
        return user;
    }

    private String doGetCurrentUser(HttpServerExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                part = part.trim();
                if (part.startsWith("token=")) {
                    String token = part.substring(6);
                    Session s = sessions.get(token);
                    if (s != null) {
                        long now = System.currentTimeMillis();
                        if (now > s.expireAt) {
                            sessions.remove(token); saveSessionsAsync();
                        } else {
                            // ★ 核心修复：如果 users.json 里已经没有这个用户了，强制销毁这个无效的 session，防止“幽灵登录”
                            if (UserStore.getInstance().findUser(s.username) != null) {
                                // 滑动续期：只有连续 15 天没有任何请求才会真正过期
                                int hours = sessionTimeoutHours();
                                long ttlMs = hours * 3600_000L;
                                if (s.expireAt - now < ttlMs - SESSION_RENEW_AFTER_MS) {
                                    s.expireAt = now + ttlMs;
                                    saveSessionsAsync();
                                    renewTokenCookie(exchange, token, hours);
                                }
                                return s.username;
                            } else {
                                sessions.remove(token);
                                saveSessionsAsync();
                            }
                        }
                    }
                }
            }
        }

        String key = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (key != null && !key.isEmpty()) {
            try {
                String byApi = UserStore.getInstance().findUserByApiKey(key);
                if (byApi != null) return byApi;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null) return map;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<?, ?> m = MAPPER.readValue(trimmed, Map.class);
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) map.put(e.getKey().toString(), e.getValue().toString());
                }
                return map;
            } catch (Exception ignored) {}
        }
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try { map.put(java.net.URLDecoder.decode(kv[0], "UTF-8"), java.net.URLDecoder.decode(kv[1], "UTF-8")); } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private static final String REGISTER_HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>注册 - MC Panel</title><style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#eee}.box{background:#16213e;padding:40px;border-radius:12px;width:300px}input{width:100%;padding:10px;margin:8px 0;border:1px solid #444;border-radius:6px;background:#0f3460;color:#eee;box-sizing:border-box}button{width:100%;padding:10px;background:#e94560;color:#fff;border:none;border-radius:6px;cursor:pointer;margin-top:12px}</style></head><body><form class='box' id='reg-form'><h2>加入面板</h2><input id='token' type='hidden' value=''><input id='user' placeholder='用户名' required><input id='pass' type='password' placeholder='密码 (至少 8 位)' required><button type='button' onclick='doReg()'>完成注册</button></form><script>var t=new URLSearchParams(location.search).get('token');if(!t){alert('邀请链接无效');location.href='/login';}else{document.getElementById('token').value=t;}function doReg(){var u=document.getElementById('user').value,p=document.getElementById('pass').value;if(u.length<2||p.length<8){alert('格式错误');return;}fetch('/api/auth/register',{method:'POST',body:JSON.stringify({token:t,username:u,password:p})}).then(r=>r.json()).then(d=>{if(d.ok){alert('注册成功，请登录');location.href='/login';}else alert(d.error);});}</script></body></html>";

    private static final String FORGOT_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>找回密码 - MC Panel</title>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#eee}"
        + ".box{background:#16213e;padding:40px;border-radius:12px;width:340px}"
        + "input{width:100%;padding:10px;margin:10px 0;border:1px solid #444;border-radius:6px;background:#0f3460;color:#eee;box-sizing:border-box}"
        + "button{width:100%;padding:10px;background:#e94560;color:#fff;border:none;border-radius:6px;cursor:pointer;margin-top:12px}"
        + ".tab-btn{width:32%;display:inline-block;background:#0f3460;padding:8px;text-align:center;border-radius:6px;cursor:pointer}"
        + ".tab-btn.active{background:#e94560}"
        + ".err{color:#ff5c5c;font-size:13px;margin-top:8px;text-align:center}"
        + "</style></head><body>"
        + "<div class='box'>"
        + "<h2 style='margin-top:0;text-align:center;'>找回密码</h2>"
        + "<div id='step1'>"
        + "<input id='username' placeholder='请输入用户名' autocomplete='off'>"
        + "<button onclick='checkUser()'>下一步</button>"
        + "</div>"
        + "<div id='step2' style='display:none;'>"
        + "<div id='tabs' style='display:flex; justify-content:space-between; margin-bottom:10px;'>"
        + "<div class='tab-btn active' id='tab-totp' onclick='switchTab(\"totp\")'>动态令牌</div>"
        + "<div class='tab-btn' id='tab-rec' onclick='switchTab(\"rec\")'>恢复码</div>"
        + "<div class='tab-btn' id='tab-email' onclick='switchTab(\"email\")'>邮箱验证码</div>"
        + "</div>"
        + "<div id='box-totp'><input id='totp' placeholder='6 位动态验证码' maxlength='6'></div>"
        + "<div id='box-rec' style='display:none;'><input id='rec' placeholder='恢复码 (XXXX-XXXX-XXXX)'></div>"
        + "<div id='box-email' style='display:none;'>"
        + "  <div id='email-hint' style='font-size:12.5px;color:#9aa4bb;margin:4px 0 10px;'></div>"
        + "  <div style='display:flex; gap:10px; align-items:center; margin-bottom:10px;'>"
        + "    <input id='cap-code' placeholder='图片验证码' maxlength='6' style='flex:1;min-width:0;width:auto;margin:0;'>"
        + "    <img id='cap-img' onclick='loadCaptcha()' title='点击换一张' style='height:42px;width:110px;border-radius:6px;cursor:pointer;background:#0f3460;display:block;'>"
        + "  </div>"
        + "  <button type='button' id='btn-send-mail' onclick='sendEmailCode()' style='margin-top:0;'>发送验证码到绑定邮箱</button>"
        + "  <input id='email-code' placeholder='6 位邮箱验证码' maxlength='6'>"
        + "</div>"
        + "<input id='newpass' type='password' placeholder='新密码 (至少 8 位)'>"
        + "<button onclick='doReset()'>重置密码</button>"
        + "<div id='err' class='err' style='display:none;'></div>"
        + "</div>"
        + "<div style='text-align:center; margin-top:20px;'><a href='/login' style='color:#888; font-size:13px; text-decoration:none;'>返回登录</a></div>"
        + "</div>"
        + "<script>"
        + "var fgUser=''; var hasTOTP=false; var hasRec=false; var hasEmail=false; var boundEmail=''; var capId='';"
        + "function checkUser(){"
        + "  fgUser=document.getElementById('username').value.trim();"
        + "  if(!fgUser){alert('请输入用户名');return;}"
        + "  fetch('/api/auth/forgot/check',{method:'POST',body:JSON.stringify({username:fgUser})})"
        + "  .then(r=>r.json()).then(d=>{"
        + "    if(d.error){alert(d.error);return;}"
        + "    hasTOTP=d.hasTotp; hasRec=d.hasRecovery; hasEmail=!!d.hasEmail; boundEmail=d.emailMasked||'';"
        + "    document.getElementById('step1').style.display='none';"
        + "    document.getElementById('step2').style.display='block';"
        + "    if(!hasTOTP && !hasRec) switchTab('email'); else if(hasTOTP) switchTab('totp'); else switchTab('rec');"
        + "    document.getElementById('tab-totp').style.display=hasTOTP?'block':'none';"
        + "    document.getElementById('tab-rec').style.display=hasRec?'block':'none';"
        + "    document.getElementById('tab-email').style.display=hasEmail?'block':'none';"
        + "    document.getElementById('email-hint').textContent=hasEmail?('验证码只会发送到该账号绑定的邮箱：'+boundEmail):'该账号未绑定安全邮箱，无法通过邮箱找回密码';"
        + "    document.getElementById('btn-send-mail').disabled=!hasEmail;"
        + "    loadCaptcha();"
        + "  });"
        + "}"
        + "function switchTab(m){"
        + "  document.getElementById('box-totp').style.display='none';"
        + "  document.getElementById('box-rec').style.display='none';"
        + "  document.getElementById('box-email').style.display='none';"
        + "  document.getElementById('tab-totp').classList.remove('active');"
        + "  document.getElementById('tab-rec').classList.remove('active');"
        + "  document.getElementById('tab-email').classList.remove('active');"
        + "  if(m==='totp'){document.getElementById('tab-totp').classList.add('active');document.getElementById('box-totp').style.display='block';}"
        + "  else if(m==='rec'){document.getElementById('tab-rec').classList.add('active');document.getElementById('box-rec').style.display='block';}"
        + "  else{document.getElementById('tab-email').classList.add('active');document.getElementById('box-email').style.display='block';}"
        + "}"
        + "function loadCaptcha(){"
        + "  fetch('/api/auth/captcha').then(r=>r.json()).then(d=>{"
        + "    if(d && d.id){capId=d.id; var img=document.getElementById('cap-img'); if(img && d.image) img.src=d.image;}"
        + "  }).catch(function(){});"
        + "}"
        + "function sendEmailCode(){"
        + "  if(!hasEmail){alert('该账号未绑定安全邮箱');return;}"
        + "  var cc=document.getElementById('cap-code').value.trim();"
        + "  if(!capId || !cc){alert('请先输入图片验证码');loadCaptcha();return;}"
        + "  var btn=document.getElementById('btn-send-mail');"
        + "  btn.disabled=true; btn.textContent='发送中...';"
        + "  fetch('/api/auth/forgot/send-email',{method:'POST',body:JSON.stringify({username:fgUser,captchaId:capId,captchaCode:cc})})"
        + "  .then(r=>r.json()).then(d=>{"
        + "    btn.disabled=false; btn.textContent='发送验证码到绑定邮箱';"
        + "    document.getElementById('cap-code').value=''; capId=''; loadCaptcha();"
        + "    if(d.ok) alert('验证码已发送到绑定邮箱 ' + boundEmail); else alert(d.error||'发送失败');"
        + "  }).catch(function(){ btn.disabled=false; btn.textContent='发送验证码到绑定邮箱'; });"
        + "}"
        + "function doReset(){"
        + "  var t=document.getElementById('totp').value;"
        + "  var r=document.getElementById('rec').value;"
        + "  var p=document.getElementById('newpass').value;"
        + "  var err=document.getElementById('err');"
        + "  if(p.length<8){err.textContent='新密码至少8位';err.style.display='block';return;}"
        + "  var ec=document.getElementById('email-code').value;"
        + "  var emailMode=document.getElementById('box-email').style.display!=='none';"
        + "  if(emailMode && !ec){err.textContent='请输入邮箱验证码';err.style.display='block';return;}"
        + "  if(emailMode && ec) {"
        + "    fetch('/api/auth/forgot/reset-email',{method:'POST',body:JSON.stringify({username:fgUser,code:ec,newPassword:p})})"
        + "    .then(r=>r.json()).then(d=>{ if(d.ok){alert('密码重置成功');location.href='/login';} else{err.textContent=d.error;err.style.display='block';} });"
        + "    return;"
        + "  }"
        + "  fetch('/api/auth/forgot/reset',{method:'POST',body:JSON.stringify({username:fgUser,totp:t,recovery:r,newPassword:p})})"
        + "  .then(r=>r.json()).then(d=>{"
        + "    if(d.ok){alert('密码重置成功，请重新登录');location.href='/login';}"
        + "    else{err.textContent=d.error;err.style.display='block';}"
        + "  });"
        + "}"
        + "</script></body></html>";

    private static final String LOGIN_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'>"
        + "<title>MC Panel</title>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<style>body{font-family:sans-serif;display:flex;justify-content:center;"
        + "align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#eee}"
        + "form{background:#16213e;padding:40px;border-radius:12px;width:300px}"
        + "input{width:100%;padding:10px;margin:8px 0;border:1px solid #444;"
        + "border-radius:6px;background:#0f3460;color:#eee;box-sizing:border-box}"
        + "button{width:100%;padding:10px;background:#e94560;color:#fff;"
        + "border:none;border-radius:6px;cursor:pointer;margin-top:12px}"
        + ".err{color:#ff5c5c;font-size:13px;margin-top:8px;text-align:center}"
        + "</style></head><body>"
        + "<form method='post' action='/api/login' autocomplete='off' id='login-form'>"
        + "<h2>MC Panel</h2>"
        + "<input id='l-user' name='username' placeholder='用户名' required autocomplete='off'>"
        + "<input id='l-pass' name='password' type='password' placeholder='密码' required autocomplete='current-password'>"
        
        + "<div id='box-2fa' style='display:none; margin-top:8px;'>"
        + "  <input id='l-code' name='code' placeholder='6 位验证码' maxlength='6' style='text-align:center;'>"
        + "  <div style='display:flex; justify-content:space-between; align-items:center; margin-top:4px;'>"
        + "    <div style='font-size:11px; color:#f0c040;'>新设备需要验证码</div>"
        + "    <button type='button' id='btn-send-email' style='display:none; width:auto; padding:4px 8px; margin:0; font-size:11px;' onclick='sendLoginEmail()'>发送邮箱验证码</button>"
        + "  </div>"
        + "  <div id='cap-box' style='display:none; margin-top:6px;'>"
        + "    <img id='l-cap-img' onclick='loadCaptcha()' title='点击换一张' style='width:100%;height:44px;border-radius:6px;cursor:pointer;background:#0f3460;display:block;margin:6px 0;'>"
        + "    <input id='l-cap' placeholder='输入图片验证码' maxlength='6' style='text-align:center;'>"
        + "  </div>"
        + "</div>"

        + "<div style='text-align:right; margin-bottom:12px; margin-top:8px;'><a href='/forgot' style='color:#7aa2f7; font-size:12px; text-decoration:none;'>忘记密码？</a></div>"
        + "<button type='submit'>登录</button>"
        + "<div id='err' class='err' style='display:none;'>账号或密码错误</div>"
        + "</form>"
        + "<script>"
        + "var urlParams = new URLSearchParams(location.search);"
        + "var need2fa = urlParams.get('need_2fa');"
        + "var uName = urlParams.get('u');"
        + "if(need2fa){"
        + "  document.getElementById('box-2fa').style.display='block';"
        + "  document.getElementById('l-user').value = uName;"
        + "  if(need2fa === 'email'){"
        + "    document.getElementById('btn-send-email').style.display='block';"
        + "    document.getElementById('cap-box').style.display='block';"
        + "    document.getElementById('l-code').placeholder='请输入邮箱验证码';"
        + "    loadCaptcha();"
        + "  } else {"
        + "    document.getElementById('l-code').placeholder='请输入动态令牌验证码';"
        + "  }"
        + "}"
        + "if(urlParams.get('err') === '1'){document.getElementById('err').style.display='block';}"
        + "if(urlParams.get('err') === '2fa'){"
        + "  document.getElementById('box-2fa').style.display='block';"
        + "  document.getElementById('err').style.display='block';"
        + "  document.getElementById('err').textContent='验证码错误，请重试';"
        + "}"
        + "var capId = '';"
        + "function loadCaptcha(){"
        + "  fetch('/api/auth/captcha').then(r=>r.json()).then(d=>{"
        + "    if(d && d.id){ capId=d.id; var img=document.getElementById('l-cap-img'); if(img && d.image) img.src=d.image; }"
        + "  }).catch(function(){});"
        + "}"
        + "function sendLoginEmail(){"
        + "  var u = document.getElementById('l-user').value;"
        + "  var p = document.getElementById('l-pass').value;"
        + "  if(!u || !p) { alert('请先输入用户名和密码'); return; }"
        + "  var cc = document.getElementById('l-cap').value.trim();"
        + "  if(!capId || !cc) { document.getElementById('cap-box').style.display='block'; if(!capId) loadCaptcha(); alert('请先输入图片验证码'); return; }"
        + "  var btn = document.getElementById('btn-send-email');"
        + "  btn.disabled = true; btn.textContent = '发送中...';"
        + "  fetch('/api/auth/login/send-email',{method:'POST',body:JSON.stringify({username:u,password:p,captchaId:capId,captchaCode:cc})})"
        + "  .then(r=>r.json()).then(d=>{"
        + "    document.getElementById('l-cap').value=''; capId=''; loadCaptcha();"
        + "    if(d.ok){ alert('验证码已发送至您的邮箱'); btn.textContent = '重新发送'; btn.disabled = false; }"
        + "    else{ alert(d.error); btn.textContent = '发送邮箱验证码'; btn.disabled = false; }"
        + "  }).catch(()=>{ btn.textContent = '发送邮箱验证码'; btn.disabled = false; });"
        + "}"
        + "</script>"
        + "</body></html>";
}