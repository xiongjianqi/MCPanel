package com.mcpanel.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mcpanel.config.UserStore;
import com.mcpanel.util.Crypto;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ShareHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE = "shares.json";
    private static final String LOG_FILE = "share-logs.json";
    private static final SecureRandom RNG = new SecureRandom();
    private static final Map<String, Share> shares = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentLinkedDeque<AccessLog> accessLogs =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int MAX_LOGS = 5000;
    private static boolean loaded = false;

    private static final java.util.concurrent.ScheduledExecutorService SAVE_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "share-saver");
                    t.setDaemon(true);
                    return t;
                }
            });
    private static final java.util.concurrent.atomic.AtomicBoolean SAVE_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long SAVE_DELAY_MS = 2000L;

    private static final Set<String> FORBIDDEN_FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "users.json", "users.key", "config.properties", "mcperms.json",
        "shares.json", "share-logs.json", ".security_hashes.json",
        "cert.pem", "key.pem", "sessions.json"
    )));
    private static final Set<String> FORBIDDEN_DIRS = Collections.unmodifiableSet(new HashSet<>(Collections.singletonList("ssl")));

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Share {
        public String token;
        public String path;
        public String name;
        public long createdAt;
        public String createdBy;
        public String mime;
        public String passwordHash;
        public long expireAt;
        public long rateLimitBps;
        public long usedBytes;
        public long accessCount;
        public long lastAccessAt;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccessLog {
        public String token;
        public String name;
        public String ip;
        public long time;
        public long bytes;
        public String ua;
        public String status;
    }

    private final com.mcpanel.config.ConfigManager config;
    private final AuthHandler auth;

    public ShareHandler(com.mcpanel.config.ConfigManager config, AuthHandler auth) {
        this.config = config; this.auth = auth;
    }

    private static synchronized void ensureLoadedStatic() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = Paths.get(FILE);
            if (Files.exists(p)) {
                Share[] arr = MAPPER.readValue(p.toFile(), Share[].class);
                if (arr != null) for (Share s : arr) if (s.token != null) shares.put(s.token, s);
            }
            Path lp = Paths.get(LOG_FILE);
            if (Files.exists(lp)) {
                AccessLog[] larr = MAPPER.readValue(lp.toFile(), AccessLog[].class);
                if (larr != null) for (AccessLog l : larr) accessLogs.addLast(l);
            }
        } catch (Exception e) {
            System.err.println("[Panel] 读取分享数据失败: " + e.getMessage());
        }
    }

    private void ensureLoaded() { ensureLoadedStatic(); }

    private static synchronized void saveStatic() {
        try {
            MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
            MAPPER.writeValue(new File(FILE), new ArrayList<>(shares.values()));
            while (accessLogs.size() > MAX_LOGS) accessLogs.pollFirst();
            MAPPER.writeValue(new File(LOG_FILE), new ArrayList<>(accessLogs));
        } catch (Exception e) {
            System.err.println("[Panel] 写入分享数据失败: " + e.getMessage());
        }
    }

    private static void scheduleSave() {
        if (!SAVE_PENDING.compareAndSet(false, true)) return;
        try {
            SAVE_SCHEDULER.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        saveStatic();
                    } catch (Throwable t) {
                        System.err.println("[Panel] 分享数据合并落盘失败: " + t);
                    } finally {
                        SAVE_PENDING.set(false);
                    }
                }
            }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            SAVE_PENDING.set(false);
        }
    }

    private void save() { saveStatic(); }

    private static String newToken() {
        byte[] buf = new byte[18];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String validateCustomToken(String token, String forPath) {
        if (token == null || token.isEmpty()) return null;
        if (token.length() < 6) return "Token 至少 6 个字符";
        if (token.length() > 64) return "Token 太长（最多 64 字符）";
        if (!token.matches("^[A-Za-z0-9_-]+$")) {
            return "Token 只能包含字母、数字、下划线、连字符";
        }
        Share exist = shares.get(token);
        if (exist != null && !exist.path.equals(forPath)) {
            return "Token 已被其他分享占用";
        }
        return null;
    }

    public void handle(HttpServerExchange exchange) {
        ensureLoaded();
        String path = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString();

        if (path.startsWith("/share/")) {
            String token = path.substring("/share/".length());
            if (token.endsWith(".html")) token = token.substring(0, token.length() - 5);
            while (token.endsWith("/")) token = token.substring(0, token.length() - 1);
            if (method.equals("POST")) handlePasswordPost(exchange, token);
            else serveShare(exchange, token);
            return;
        }

        if (path.equals("/api/share/port")) {
            try {
                Map<String, Object> r = new HashMap<>();
                int port = (config != null) ? config.getInt("share.port", 33333) : 33333;
                r.put("port", port);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                exchange.getResponseSender().send(MAPPER.writeValueAsString(r));
            } catch (Exception e) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("{\"error\":\"内部错误\"}");
            }
            return;
        }

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = parseJson(body);
                    String currentUser = auth.getCurrentUser(ex);

                    if (currentUser == null) {
                        ex.setStatusCode(401);
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                        ex.getResponseSender().send("{\"error\":\"unauthorized\"}");
                        return;
                    }

                    if (path.equals("/api/share/list")) {
                        cleanupInvalid();
                        List<Share> list = new ArrayList<>(shares.values());
                        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (Share s : list) {
                            if (!UserStore.getInstance().isMaster(currentUser) && 
                                !currentUser.equals(s.createdBy)) {
                                continue;
                            }
                            out.add(toPublicShare(s));
                        }
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(out));
                        return;
                    }

                    if (path.equals("/api/share/logs")) {
                        String tok = p.getOrDefault("token", "");
                        int limit = 200;
                        try { limit = Integer.parseInt(p.getOrDefault("limit", "200")); } catch (Exception ignored) {}
                        if (limit <= 0) limit = 200;
                        if (limit > 1000) limit = 1000;
                        boolean isMaster = UserStore.getInstance().isMaster(currentUser);
                        List<AccessLog> snapshot = new ArrayList<>(accessLogs);
                        Collections.reverse(snapshot);
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (AccessLog l : snapshot) {
                            if (!tok.isEmpty() && !tok.equals(l.token)) continue;
                            if (!isMaster) {
                                Share owner = shares.get(l.token);
                                if (owner == null || !currentUser.equals(owner.createdBy)) continue;
                            }
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("token", l.token);
                            m.put("name", l.name);
                            m.put("ip", l.ip);
                            m.put("time", l.time);
                            m.put("bytes", l.bytes);
                            m.put("ua", l.ua);
                            m.put("status", l.status);
                            out.add(m);
                            if (out.size() >= limit) break;
                        }
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(out));
                        return;
                    }

                    if (path.equals("/api/share/create")) { handleCreate(ex, p, currentUser); return; }
                    if (path.equals("/api/share/rotate")) { handleRotate(ex, p, currentUser); return; }
                    if (path.equals("/api/share/update")) { handleUpdate(ex, p, currentUser); return; }

                    if (path.equals("/api/share/delete")) {
                        String token = p.getOrDefault("token", "");
                        if (!token.isEmpty()) { 
                            Share s = shares.get(token);
                            if (s != null && !UserStore.getInstance().isMaster(currentUser) && !currentUser.equals(s.createdBy)) {
                                ex.setStatusCode(403);
                                ex.getResponseSender().send("{\"error\":\"无权删除该分享\"}");
                                return;
                            }
                            shares.remove(token); save(); 
                        }
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }
                    ex.setStatusCode(404);
                    ex.getResponseSender().send("{\"error\":\"unknown\"}");
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"内部错误\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private void handleCreate(HttpServerExchange ex, Map<String, String> p, String user) throws Exception {
        String rel = p.getOrDefault("path", "");
        String customToken = p.getOrDefault("token", "").trim();
        String password = p.getOrDefault("password", "");
        String durationStr = p.getOrDefault("durationSeconds", "");
        String rateStr = p.getOrDefault("rateLimitBps", "");

        if (rel.isEmpty()) {
            ex.setStatusCode(400);
            ex.getResponseSender().send("{\"error\":\"missing path\"}");
            return;
        }

        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path file = root.resolve(rel.startsWith("/") ? rel.substring(1) : rel).normalize();
        if (!file.startsWith(root)) {
            ex.setStatusCode(403);
            ex.getResponseSender().send("{\"error\":\"路径越界\"}");
            return;
        }
        String fileName = file.getFileName().toString().toLowerCase();
        if (FORBIDDEN_FILES.contains(fileName)) {
            ex.setStatusCode(403);
            ex.getResponseSender().send("{\"error\":\"系统核心配置文件受保护，禁止分享\"}");
            return;
        }
        if (fileName.endsWith(".key") || fileName.contains("root_rsa") || fileName.equals("users.key")) {
            ex.setStatusCode(403);
            ex.getResponseSender().send("{\"error\":\"私钥/密钥文件受保护，禁止分享\"}");
            return;
        }
        for (Path part : root.relativize(file)) {
            if (FORBIDDEN_DIRS.contains(part.toString().toLowerCase()) || part.toString().toLowerCase().equals("ssh")) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"系统安全目录受保护，禁止分享\"}");
                return;
            }
        }

        if (!UserStore.getInstance().isMaster(user) && !UserStore.getInstance().hasPermission(user, "files_all")) {
            Path relative = root.relativize(file);
            if (relative.getNameCount() > 1 && relative.getName(0).toString().equals("instances")) {
                String instId = relative.getName(1).toString();
                if (!UserStore.getInstance().canAccessInstance(user, instId)) {
                    ex.setStatusCode(403);
                    ex.getResponseSender().send("{\"error\":\"无权分享该实例的文件\"}");
                    return;
                }
            }
        }

        if (!customToken.isEmpty()) {
            String err = validateCustomToken(customToken, rel);
            if (err != null) {
                ex.setStatusCode(400);
                ex.getResponseSender().send("{\"error\":\"" + err + "\"}");
                return;
            }
        }

        Share s = new Share();
        s.token = customToken.isEmpty() ? newToken() : customToken;
        s.path = rel;
        Path relPath = Paths.get(rel.replace('\\', '/'));
        Path relName = relPath.getFileName();
        if (relName == null) {
            ex.setStatusCode(400);
            ex.getResponseSender().send("{\"error\":\"不能分享目录本身\"}");
            return;
        }
        s.name = relName.toString();
        s.createdAt = System.currentTimeMillis();
        s.createdBy = user;
        s.mime = guessMime(s.name);
        
        if (!password.isEmpty()) s.passwordHash = Crypto.hashPassword(password);
        long durationSec = parseLongOrZero(durationStr);
        s.expireAt = durationSec > 0 ? System.currentTimeMillis() + durationSec * 1000L : 0;
        s.rateLimitBps = parseLongOrZero(rateStr);

        shares.put(s.token, s);
        save();
        
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("token", s.token);
        r.put("url", "/share/" + s.token);
        r.put("sharePort", (config != null) ? config.getInt("share.port", 33333) : 33333);
        r.put("hasPassword", s.passwordHash != null && !s.passwordHash.isEmpty());
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.getResponseSender().send(MAPPER.writeValueAsString(r));
    }

    private void handleRotate(HttpServerExchange ex, Map<String, String> p, String user) throws Exception {
        String oldToken = p.getOrDefault("oldToken", "").trim();
        String newToken = p.getOrDefault("token", "").trim();
        if (oldToken.isEmpty()) {
            ex.setStatusCode(400);
            ex.getResponseSender().send("{\"error\":\"missing oldToken\"}");
            return;
        }
        Share s = shares.get(oldToken);
        if (s == null) {
            ex.setStatusCode(404);
            ex.getResponseSender().send("{\"error\":\"分享不存在\"}");
            return;
        }
        if (!UserStore.getInstance().isMaster(user) && !user.equals(s.createdBy)) {
            ex.setStatusCode(403);
            ex.getResponseSender().send("{\"error\":\"无权修改此分享\"}");
            return;
        }
        if (newToken.isEmpty()) newToken = newToken();
        if (!oldToken.equals(newToken)) {
            String err = validateCustomToken(newToken, s.path);
            if (err != null) {
                ex.setStatusCode(400);
                ex.getResponseSender().send("{\"error\":\"" + err + "\"}");
                return;
            }
            shares.remove(oldToken);
            s.token = newToken;
            shares.put(newToken, s);
            save();
        }
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("token", newToken);
        r.put("url", "/share/" + newToken);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.getResponseSender().send(MAPPER.writeValueAsString(r));
    }

    private void handleUpdate(HttpServerExchange ex, Map<String, String> p, String user) throws Exception {
        String token = p.getOrDefault("token", "").trim();
        Share s = shares.get(token);
        if (s == null) {
            ex.setStatusCode(404);
            ex.getResponseSender().send("{\"error\":\"分享不存在\"}");
            return;
        }
        if (!UserStore.getInstance().isMaster(user) && !user.equals(s.createdBy)) {
            ex.setStatusCode(403);
            ex.getResponseSender().send("{\"error\":\"无权修改此分享\"}");
            return;
        }
        if (p.containsKey("password")) {
            String pw = p.get("password");
            s.passwordHash = (pw == null || pw.isEmpty()) ? null : Crypto.hashPassword(pw);
        }
        if (p.containsKey("durationSeconds")) {
            long sec = parseLongOrZero(p.get("durationSeconds"));
            s.expireAt = sec > 0 ? System.currentTimeMillis() + sec * 1000L : 0;
        }
        if (p.containsKey("rateLimitBps")) {
            s.rateLimitBps = parseLongOrZero(p.get("rateLimitBps"));
        }
        if (p.containsKey("resetUsed")) s.usedBytes = 0;
        save();
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("hasPassword", s.passwordHash != null && !s.passwordHash.isEmpty());
        r.put("expireAt", s.expireAt);
        r.put("rateLimitBps", s.rateLimitBps);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        ex.getResponseSender().send(MAPPER.writeValueAsString(r));
    }

    private static String computePwdCookie(String token, String passwordHash) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(passwordHash.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void handlePasswordPost(HttpServerExchange exchange, String token) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handlePasswordPost(exchange, token));
            return;
        }
        Share s = shares.get(token);
        if (s == null) {
            sendHtml(exchange, 404, simplePage("分享不存在", "该分享链接无效或已被取消"));
            return;
        }
        try {
            exchange.startBlocking();
            String body = new String(readAll(exchange.getInputStream()), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String pwd = form.getOrDefault("pwd", "");
            if (s.passwordHash == null || s.passwordHash.isEmpty()) {
                redirect(exchange, "/share/" + token);
                return;
            }
            if (!Crypto.verifyPassword(pwd, s.passwordHash)) {
                logAccess(exchange, s, 0, "denied-pwd");
                sendHtml(exchange, 401, passwordPage(token, "提取码错误，请重试"));
                return;
            }
            exchange.getResponseHeaders().add(Headers.SET_COOKIE,
                    "share_pwd_" + token + "=" + computePwdCookie(token, s.passwordHash) + "; Path=/share/; HttpOnly; SameSite=Lax; Max-Age=86400");
            redirect(exchange, "/share/" + token);
        } catch (Exception e) {
            sendHtml(exchange, 500, simplePage("错误", "请求处理失败"));
        }
    }

    private void serveShare(HttpServerExchange exchange, String token) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> serveShare(exchange, token));
            return;
        }
        try {
            Share s = shares.get(token);
            if (s == null) {
                logByToken(exchange, token, "not-found");
                sendHtml(exchange, 404, simplePage("分享不存在", "该分享链接无效或已被取消"));
                return;
            }
            if (s.expireAt > 0 && System.currentTimeMillis() > s.expireAt) {
                logAccess(exchange, s, 0, "expired");
                sendHtml(exchange, 410, simplePage("分享已过期", "该链接已超过有效期"));
                return;
            }

            if (s.passwordHash != null && !s.passwordHash.isEmpty()) {
                String passcode = queryParam(exchange, "passcode");
                if (passcode != null && !passcode.isEmpty()) {
                    if (Crypto.verifyPassword(passcode, s.passwordHash)) {
                        exchange.getResponseHeaders().add(Headers.SET_COOKIE,
                                "share_pwd_" + token + "=" + computePwdCookie(token, s.passwordHash)
                                + "; Path=/share/; HttpOnly; SameSite=Lax; Max-Age=86400");
                        redirect(exchange, "/share/" + token);
                        return;
                    } else {
                        logAccess(exchange, s, 0, "denied-pwd");
                        sendHtml(exchange, 401, passwordPage(token, "提取码错误，请重试"));
                        return;
                    }
                }
                String cookieOk = readCookie(exchange, "share_pwd_" + token);
                if (cookieOk == null || !cookieOk.equals(computePwdCookie(token, s.passwordHash))) {
                    sendHtml(exchange, 200, passwordPage(token, ""));
                    return;
                }
            }

            Path root = Paths.get(".").toAbsolutePath().normalize();
            Path file = root.resolve(s.path.startsWith("/") ? s.path.substring(1) : s.path).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                shares.remove(token);
                save();
                sendHtml(exchange, 404, simplePage("文件已删除", "该分享已自动取消"));
                return;
            }

            String fileName = file.getFileName().toString().toLowerCase();
            if (FORBIDDEN_FILES.contains(fileName)) {
                logAccess(exchange, s, 0, "node-error");
                sendHtml(exchange, 403, simplePage("禁止访问", "系统核心配置文件已被禁止分享"));
                return;
            }
            for (Path part : root.relativize(file)) {
                if (FORBIDDEN_DIRS.contains(part.toString().toLowerCase()) || part.toString().toLowerCase().equals("ssh")) {
                    logAccess(exchange, s, 0, "node-error");
                    sendHtml(exchange, 403, simplePage("禁止访问", "系统安全目录受保护"));
                    return;
                }
            }

            serveFile(exchange, s, file);
        } catch (Exception e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("Error: " + e.getMessage());
            }
        }
    }

    private void serveFile(HttpServerExchange exchange, Share s, Path file) throws IOException {
        long fileSize = Files.size(file);
        String fname = file.getFileName().toString();
        String mime = s.mime != null ? s.mime : guessMime(fname);

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, mime);
        exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
        boolean inline = mime.startsWith("image/")
                || mime.startsWith("video/")
                || mime.startsWith("text/html")
                || mime.startsWith("application/pdf")
                || mime.startsWith("text/plain")
                || mime.equals("audio/mpeg")
                || mime.equals("audio/wav")
                || mime.equals("audio/ogg")
                || mime.equals("audio/mp4")
                || mime.equals("audio/aac");
        exchange.getResponseHeaders().put(new HttpString("Content-Disposition"),
                buildContentDisposition(inline, fname));
        exchange.getResponseHeaders().put(new HttpString("Cache-Control"),
                "no-cache, no-store, must-revalidate");

        String rangeHeader = exchange.getRequestHeaders().getFirst(Headers.RANGE);
        long start = 0, end = fileSize - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String sStart = spec.substring(0, dash);
                    String sEnd = spec.substring(dash + 1);
                    if (!sStart.isEmpty()) start = Long.parseLong(sStart);
                    if (!sEnd.isEmpty()) end = Long.parseLong(sEnd);
                    if (end >= fileSize) end = fileSize - 1;
                    if (start > end || start < 0) throw new Exception();
                    partial = true;
                }
            } catch (Exception ex) {
                exchange.setStatusCode(416);
                exchange.getResponseHeaders().put(new HttpString("Content-Range"), "bytes */" + fileSize);
                exchange.endExchange();
                return;
            }
        }
        long length = end - start + 1;

        if (partial) {
            exchange.setStatusCode(206);
            exchange.getResponseHeaders().put(new HttpString("Content-Range"),
                    "bytes " + start + "-" + (start + length - 1) + "/" + fileSize);
        } else {
            exchange.setStatusCode(200);
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(length));

        exchange.startBlocking();
        long written = 0;
        long rate = s.rateLimitBps;

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             OutputStream out = exchange.getOutputStream()) {
            raf.seek(start);
            byte[] buf = new byte[16384];
            long remaining = length;
            long t0 = System.currentTimeMillis();
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = raf.read(buf, 0, toRead);
                if (n < 0) break;
                out.write(buf, 0, n);
                out.flush();
                remaining -= n;
                written += n;

                if (rate > 0) {
                    long expectMs = written * 1000L / rate;
                    long actualMs = System.currentTimeMillis() - t0;
                    if (expectMs > actualMs) {
                        try {
                            Thread.sleep(expectMs - actualMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        } finally {
            if (written > 0) {
                s.usedBytes += written;
                s.accessCount++;
                s.lastAccessAt = System.currentTimeMillis();
                logAccess(exchange, s, written, "ok");
            }
        }
    }

    private void logAccess(HttpServerExchange ex, Share s, long bytes, String status) {
        AccessLog l = new AccessLog();
        l.token = s.token;
        l.name = s.name;
        l.ip = getClientIp(ex);
        l.time = System.currentTimeMillis();
        l.bytes = bytes;
        String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
        if (ua != null && ua.length() > 200) ua = ua.substring(0, 200);
        l.ua = ua;
        l.status = status;
        accessLogs.addLast(l);
        if (accessLogs.size() > MAX_LOGS + 200) {
            while (accessLogs.size() > MAX_LOGS) accessLogs.pollFirst();
        }
        scheduleSave();
    }

    private void logByToken(HttpServerExchange ex, String token, String status) {
        AccessLog l = new AccessLog();
        l.token = token;
        l.name = "";
        l.ip = getClientIp(ex);
        l.time = System.currentTimeMillis();
        l.bytes = 0;
        String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
        if (ua != null && ua.length() > 200) ua = ua.substring(0, 200);
        l.ua = ua;
        l.status = status;
        accessLogs.addLast(l);
        if (accessLogs.size() > MAX_LOGS + 200) {
            while (accessLogs.size() > MAX_LOGS) accessLogs.pollFirst();
        }
        scheduleSave();
    }

    private Map<String, Object> toPublicShare(Share s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", s.token);
        m.put("path", s.path);
        m.put("name", s.name);
        m.put("createdAt", s.createdAt);
        m.put("createdBy", s.createdBy);
        m.put("hasPassword", s.passwordHash != null && !s.passwordHash.isEmpty());
        m.put("expireAt", s.expireAt);
        m.put("rateLimitBps", s.rateLimitBps);
        m.put("usedBytes", s.usedBytes);
        m.put("accessCount", s.accessCount);
        m.put("lastAccessAt", s.lastAccessAt);
        m.put("sharePort", (config != null) ? config.getInt("share.port", 33333) : 33333);
        return m;
    }

    private void cleanupInvalid() {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        boolean changed = false;
        Iterator<Map.Entry<String, Share>> it = shares.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Share> e = it.next();
            Share s = e.getValue();
            if (s == null || s.path == null || s.path.isEmpty()) {
                it.remove(); changed = true; continue;
            }
            try {
                String rel = s.path.startsWith("/") ? s.path.substring(1) : s.path;
                Path file = root.resolve(rel).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    it.remove(); changed = true;
                }
            } catch (Exception ex) { it.remove(); changed = true; }
        }
        if (changed) save();
    }

    private void sendHtml(HttpServerExchange ex, int status, String html) {
        ex.setStatusCode(status);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        ex.getResponseSender().send(html);
    }

    private String simplePage(String title, String msg) {
        String safeTitle = escapeHtml(title);
        String safeMsg = escapeHtml(msg);
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + safeTitle + "</title>"
                + "<style>body{font-family:sans-serif;display:flex;justify-content:center;"
                + "align-items:center;height:100vh;margin:0;background:#0d1017;color:#eee}"
                + ".box{text-align:center;padding:40px;border:1px solid #28303f;border-radius:12px;"
                + "background:#151a24;max-width:400px}h2{margin:0 0 12px;color:#fff}"
                + "p{color:#8b94ab;margin:0}</style></head><body>"
                + "<div class='box'><h2>" + safeTitle + "</h2><p>" + safeMsg + "</p></div>"
                + "</body></html>";
    }

    private String passwordPage(String token, String error) {
        String safeToken = escapeHtml(token);
        String err = (error == null || error.isEmpty()) ? "" :
                "<p style='color:#ff5c5c;margin:0 0 12px;'>" + escapeHtml(error) + "</p>";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>需要提取码</title>"
                + "<style>body{font-family:sans-serif;display:flex;justify-content:center;"
                + "align-items:center;height:100vh;margin:0;background:#0d1017;color:#eee}"
                + ".box{padding:40px;border:1px solid #28303f;border-radius:12px;background:#151a24;"
                + "text-align:center;width:320px}"
                + "h2{margin:0 0 20px;color:#fff}"
                + "input{width:100%;padding:12px;background:#0a0d14;border:1px solid #28303f;"
                + "color:#fff;border-radius:8px;font-size:14px;box-sizing:border-box;margin-bottom:14px}"
                + "input:focus{outline:none;border-color:#e94560}"
                + "button{width:100%;padding:12px;background:#e94560;color:#fff;border:none;"
                + "border-radius:8px;cursor:pointer;font-size:14px;font-weight:600}"
                + "button:hover{background:#ff6b86}"
                + "</style></head><body>"
                + "<form class='box' method='post' action='/share/" + safeToken + "'>"
                + "<h2>🔒 需要提取码</h2>"
                + err
                + "<input name='pwd' type='password' placeholder='请输入提取码' required autofocus>"
                + "<button type='submit'>确认</button>"
                + "</form></body></html>";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private Map<String, String> parseJson(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) return out;
        try {
            Map<?, ?> m = MAPPER.readValue(body, Map.class);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                String k = e.getKey().toString();
                Object v = e.getValue();
                out.put(k, v == null ? "" : v.toString());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], "UTF-8"),
                            URLDecoder.decode(kv[1], "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private String queryParam(HttpServerExchange ex, String name) {
        Deque<String> q = ex.getQueryParameters().get(name);
        return (q == null || q.isEmpty()) ? null : q.peekFirst();
    }

    private void redirect(HttpServerExchange ex, String location) {
        ex.setStatusCode(302);
        ex.getResponseHeaders().put(Headers.LOCATION, location);
        ex.endExchange();
    }

    private String readCookie(HttpServerExchange ex, String name) {
        String cookie = ex.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        return null;
    }

    private String getClientIp(HttpServerExchange ex) {
        boolean isTrusted = false;
        try {
            if (ex.getSourceAddress() != null && ex.getSourceAddress().getAddress() != null) {
                isTrusted = ex.getSourceAddress().getAddress().isLoopbackAddress()
                         || ex.getSourceAddress().getAddress().isSiteLocalAddress();
            }
        } catch (Exception ignored) {}
        if (isTrusted) {
            String h = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (h != null && !h.isEmpty()) {
                int comma = h.indexOf(',');
                return comma > 0 ? h.substring(0, comma).trim() : h.trim();
            }
            String real = ex.getRequestHeaders().getFirst("X-Real-IP");
            if (real != null && !real.isEmpty()) return real;
        }
        if (ex.getSourceAddress() != null && ex.getSourceAddress().getAddress() != null) {
            return ex.getSourceAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private long parseLongOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String buildContentDisposition(boolean inline, String fileName) {
        String type = inline ? "inline" : "attachment";
        String ascii = toAsciiFallback(fileName);
        String encoded;
        try { encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { encoded = ascii; }
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private String toAsciiFallback(String name) {
        if (name == null || name.isEmpty()) return "file";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x80 && c != '"' && c != '\\' && c != '\r' && c != '\n') sb.append(c);
            else sb.append('_');
        }
        if (sb.length() == 0) sb.append("file");
        return sb.toString();
    }

    private String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".m4v")) return "video/x-m4v";
        if (n.endsWith(".ogv")) return "video/ogg";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".oga")) return "audio/ogg";
        if (n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) return "text/plain; charset=utf-8";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "application/octet-stream";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}