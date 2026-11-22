package com.mcpanel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.UserStore;
import com.mcpanel.instance.Instance;
import com.mcpanel.instance.InstanceManager;
import com.mcpanel.instance.InstanceRuntime;
import com.mcpanel.instance.InstanceRuntimeManager;
import com.mcpanel.util.LinuxUserManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class InstanceHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final InstanceRuntimeManager runtimeManager;
    private final InstanceManager instanceManager;
    private final AuthHandler auth;

    private static final long MAX_BG_SIZE = 100L * 1024 * 1024;

    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
        "png","jpg","jpeg","gif","webp","bmp"
    ));
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
        "mp4","webm","m4v","mov","ogv"
    ));

    public InstanceHandler(InstanceRuntimeManager runtimeManager, AuthHandler auth) {
        this.runtimeManager = runtimeManager;
        this.instanceManager = InstanceManager.getInstance();
        this.auth = auth;
    }

    private boolean hasPerm(String user, String perm) {
        if (UserStore.getInstance().isMaster(user)) return true;
        return UserStore.getInstance().hasPermission(user, perm);
    }

    private String sanitizeHtml(String input) {
        if (input == null) return "";
        return input.replaceAll("[<>\"'&]", "");
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        if (path.equals("/api/instances/bg")) { serveBg(exchange); return; }
        if (path.equals("/api/instances/upload-bg")) { handleUploadBg(exchange); return; }
        if (path.equals("/api/instances/list")) { handleList(exchange); return; }
        if (path.equals("/api/instances/current")) { handleCurrent(exchange); return; }

        exchange.getRequestReceiver().receiveFullBytes((ex, data) -> {
            ex.dispatch(() -> {
                try {
                    String body = (data == null || data.length == 0)
                            ? "" : new String(data, StandardCharsets.UTF_8);
                    handlePost(ex, path, body);
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"内部错误\"}");
                }
            });
        });
    }

    private void handleUploadBg(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) { exchange.dispatch(() -> handleUploadBg(exchange)); return; }

        File tmp = null;
        File dst = null;
        try {
            Deque<String> idp = exchange.getQueryParameters().get("id");
            Deque<String> nmp = exchange.getQueryParameters().get("name");
            String id = (idp == null || idp.isEmpty()) ? null : idp.peekFirst();
            String fname = (nmp == null || nmp.isEmpty()) ? null : nmp.peekFirst();
            if (id == null || fname == null) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"missing id or name\"}");
                return;
            }
            String user = auth.getCurrentUser(exchange);
            if (!hasPerm(user, "instance_edit")) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("{\"error\":\"权限不足，需要 instance_edit 权限\"}");
                return;
            }
            if (!UserStore.getInstance().canAccessInstance(user, id)) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("{\"error\":\"无权访问该实例\"}");
                return;
            }

            Instance inst = instanceManager.getInstance(id);
            if (inst == null) {
                exchange.setStatusCode(404);
                exchange.getResponseSender().send("{\"error\":\"instance not found\"}");
                return;
            }
            String lower = fname.toLowerCase();
            int dot = lower.lastIndexOf('.');
            String ext = dot > 0 ? lower.substring(dot + 1) : "";
            
            if (!IMAGE_EXT.contains(ext)) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"背景图仅支持图片格式（禁止 SVG）\"}");
                return;
            }
            if (fname.contains("/") || fname.contains("\\") || fname.contains("..")
                    || fname.equals("meta.json")) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"invalid filename\"}");
                return;
            }

            String cl = exchange.getRequestHeaders().getFirst(Headers.CONTENT_LENGTH);
            if (cl != null) {
                try {
                    long size = Long.parseLong(cl);
                    if (size > MAX_BG_SIZE) {
                        exchange.setStatusCode(413);
                        exchange.getResponseSender().send("{\"error\":\"背景文件过大（限 100MB）\"}");
                        return;
                    }
                } catch (Exception ignored) {}
            }

            File dir = inst.getDir();
            dir.mkdirs();
            dst = new File(dir, fname);
            tmp = new File(dir, fname + ".uploading");

            exchange.startBlocking();
            try (InputStream in = exchange.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BG_SIZE) {
                        throw new java.io.IOException("背景文件过大（限 100MB）");
                    }
                    out.write(buf, 0, n);
                }
            }

            String newFname = fname.substring(0, fname.lastIndexOf('.')) + ".webp";
            dst = new File(dir, newFname);
            fname = newFname;
            boolean ok;
            try (InputStream bgIn = new FileInputStream(tmp)) {
                ok = com.mcpanel.util.ImageUtil.convertToWebP(bgIn, dst);
            }
            tmp.delete();
            if (!ok) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("{\"error\":\"图片转 WebP 失败，可能格式不受支持\"}");
                return;
            }
            tmp = null;

            String oldBg = inst.bg;
            if (oldBg != null && !oldBg.isEmpty() && !oldBg.equals(fname)) {
                File oldFile = new File(dir, oldBg);
                try {
                    if (oldFile.getCanonicalPath().startsWith(dir.getCanonicalPath()) && oldFile.exists()) {
                        oldFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            if (inst.sysUser != null) {
                LinuxUserManager.chownDir(inst.getDir(), inst.sysUser);
            }

            inst.bg = fname;
            inst.saveMeta();
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send("{\"ok\":true,\"bg\":\"" + fname + "\"}");
        } catch (Exception e) {
            if (tmp != null && tmp.exists()) {
                try { tmp.delete(); } catch (Exception ignored) {}
            }
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"上传失败\"}");
        }
    }

    private String pick(Map<String, String> p, String plainKey, String b64Key) {
        String b64 = p.get(b64Key);
        if (b64 != null && !b64.isEmpty()) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        String plain = p.get(plainKey);
        return plain == null ? "" : plain;
    }

    private void handleList(HttpServerExchange exchange) {
        try {
            String user = auth.getCurrentUser(exchange);
            List<String> allowed = UserStore.getInstance().getAllowedInstances(user);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Instance inst : instanceManager.listInstances()) {
                if (allowed != null && !allowed.contains(inst.id)) continue;
                list.add(toInfo(inst));
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(ByteBuffer.wrap(MAPPER.writeValueAsBytes(list)));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"内部错误\"}");
        }
    }

    private void handleCurrent(HttpServerExchange exchange) {
        try {
            String id = readCookie(exchange, "instance");
            String user = auth.getCurrentUser(exchange);

            if (id != null && !id.isEmpty() && !UserStore.getInstance().canAccessInstance(user, id)) {
                id = null;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("id", id);
            if (id != null) {
                InstanceRuntime rt = runtimeManager.get(id);
                if (rt != null) {
                    result.put("running", rt.mcServer.isRunning());
                    result.put("starting", rt.mcServer.isStarting());
                    result.put("needsEula", rt.mcServer.needsEula);
                    result.put("state", rt.mcServer.getStateString());
                    result.put("name", rt.inst.name);
                    result.put("type", rt.inst.type);
                }
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(ByteBuffer.wrap(MAPPER.writeValueAsBytes(result)));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"内部错误\"}");
        }
    }

    private void handlePost(HttpServerExchange ex, String path, String body) throws Exception {
        Map<String, Object> result = new HashMap<>();
        String user = auth.getCurrentUser(ex);

        Map<String, String> p = new HashMap<>();
        if (body != null && !body.isEmpty()) {
            p = MAPPER.readValue(body,
                    MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        }

        if (path.equals("/api/instances/create")) {
            if (!hasPerm(user, "instance_create")) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"权限不足，需要 instance_create 权限\"}");
                return;
            }
            String name = pick(p, "name", "nameB64").trim();
            String desc = pick(p, "desc", "descB64").trim();
            String type = pick(p, "type", "typeB64").trim();
            if (name.isEmpty()) name = "新实例";
            
            name = sanitizeHtml(name);
            desc = sanitizeHtml(desc);
            
            if (!"velocity".equals(type) && !"mc".equals(type)) type = "mc";
            Instance inst = instanceManager.createInstance(name, desc, type);
            InstanceRuntime rt = runtimeManager.addRuntime(inst);
            
            if (rt != null && LinuxUserManager.isEnabled(rt)) {
                final String instId = inst.id;
                final File instDir = inst.getDir();
                com.mcpanel.util.Async.run(() -> LinuxUserManager.initInstanceUser(inst, instDir));
            }
            
            result.put("ok", true);
            result.put("id", inst.id);
            result.put("type", inst.type);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(ByteBuffer.wrap(MAPPER.writeValueAsBytes(result)));
            return;
        }

        if (path.equals("/api/instances/delete")) {
            if (!hasPerm(user, "instance_delete")) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"权限不足，需要 instance_delete 权限\"}");
                return;
            }
            String id = p.get("id");
            if (id == null || id.isEmpty()) {
                ex.setStatusCode(400);
                ex.getResponseSender().send("{\"error\":\"missing id\"}");
                return;
            }
            if (!UserStore.getInstance().canAccessInstance(user, id)) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"无权访问该实例\"}");
                return;
            }
            runtimeManager.removeRuntime(id);
            boolean ok = instanceManager.deleteInstance(id);
            if (ok) UserStore.getInstance().removeInstanceFromAllUsers(id);
            result.put("ok", ok);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(ByteBuffer.wrap(MAPPER.writeValueAsBytes(result)));
            return;
        }

        if (path.equals("/api/instances/enter")) {
            String id = p.get("id");
            if (!UserStore.getInstance().canAccessInstance(user, id)) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"no access to this instance\"}");
                return;
            }
            InstanceRuntime rt = runtimeManager.get(id);
            if (rt == null) {
                ex.setStatusCode(404);
                ex.getResponseSender().send("{\"error\":\"instance not found\"}");
                return;
            }
            try { runtimeManager.startInstance(id); } catch (Exception e) {}
            ex.getResponseHeaders().put(Headers.SET_COOKIE,
                    "instance=" + id + "; Path=/; SameSite=Lax; Max-Age=86400");
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send("{\"ok\":true}");
            return;
        }

        if (path.equals("/api/instances/exit")) {
            ex.getResponseHeaders().put(Headers.SET_COOKIE,
                    "instance=; Path=/; SameSite=Lax; Max-Age=0");
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send("{\"ok\":true}");
            return;
        }

        if (path.equals("/api/instances/update")) {
            if (!hasPerm(user, "instance_edit")) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"权限不足，需要 instance_edit 权限\"}");
                return;
            }
            String id = p.get("id");
            if (!UserStore.getInstance().canAccessInstance(user, id)) {
                ex.setStatusCode(403);
                ex.getResponseSender().send("{\"error\":\"无权访问该实例\"}");
                return;
            }
            Instance inst = instanceManager.getInstance(id);
            if (inst == null) {
                ex.setStatusCode(404);
                ex.getResponseSender().send("{\"error\":\"not found\"}");
                return;
            }
            if (p.containsKey("nameB64") || p.containsKey("name")) {
                String n = pick(p, "name", "nameB64").trim();
                inst.name = sanitizeHtml(n);
            }
            if (p.containsKey("descB64") || p.containsKey("desc")) {
                String d = pick(p, "desc", "descB64").trim();
                inst.desc = sanitizeHtml(d);
            }
            if (p.containsKey("type")) {
                String t = p.get("type");
                if ("velocity".equals(t) || "mc".equals(t)) inst.type = t;
            }
            if (p.containsKey("bg")) {
                String b = p.get("bg");
                String newBg = (b == null || b.trim().isEmpty()) ? null : b.trim();
                if (newBg != null && (newBg.contains("/") || newBg.contains("\\")
                        || newBg.contains("..") || newBg.equals("meta.json"))) {
                    ex.setStatusCode(400);
                    ex.getResponseSender().send("{\"error\":\"非法的背景文件名\"}");
                    return;
                }
                if (inst.bg != null && !inst.bg.equals(newBg)) {
                    File oldFile = new File(inst.getDir(), inst.bg);
                    try {
                        if (oldFile.getCanonicalPath().startsWith(inst.getDir().getCanonicalPath()) && oldFile.exists()) {
                            oldFile.delete();
                        }
                    } catch (Exception ignored) {}
                }
                inst.bg = newBg;
            }
            inst.saveMeta();
            result.put("ok", true);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(ByteBuffer.wrap(MAPPER.writeValueAsBytes(result)));
            return;
        }

        ex.setStatusCode(404);
        ex.getResponseSender().send("{\"error\":\"unknown\"}");
    }

    private Map<String, Object> toInfo(Instance inst) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", inst.id);
        m.put("name", inst.name == null ? inst.id : inst.name);
        m.put("desc", inst.desc == null ? "" : inst.desc);
        m.put("bg", inst.bg);
        m.put("type", inst.type == null ? "mc" : inst.type);
        m.put("createdAt", inst.createdAt);
        m.put("sysUser", inst.sysUser != null);
        
        InstanceRuntime rt = runtimeManager.get(inst.id);
        boolean running = rt != null && rt.mcServer.isRunning();
        boolean starting = rt != null && rt.mcServer.isStarting();
        boolean stopping = rt != null && rt.mcServer.isStopping();
        boolean needsEula = rt != null && rt.mcServer.needsEula;
        m.put("running", running);
        m.put("starting", starting);
        m.put("stopping", stopping);
        m.put("needsEula", needsEula);
        boolean isOldVideo = inst.bg != null && isVideoFile(inst.bg);
        m.put("hasBg", inst.bg != null && !inst.bg.isEmpty() && !isOldVideo);
        return m;
    }

    private boolean isVideoFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXT.contains(n.substring(dot + 1));
    }

    private void serveBg(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) { exchange.dispatch(() -> serveBg(exchange)); return; }
        try {
            Deque<String> idp = exchange.getQueryParameters().get("id");
            String id = (idp == null || idp.isEmpty()) ? null : idp.peekFirst();
            String user = auth.getCurrentUser(exchange);
            if (!UserStore.getInstance().canAccessInstance(user, id)) {
                exchange.setStatusCode(403);
                exchange.endExchange();
                return;
            }
            Instance inst = instanceManager.getInstance(id);
            if (inst == null || inst.bg == null || inst.bg.isEmpty()) {
                exchange.setStatusCode(404);
                exchange.endExchange();
                return;
            }
            Path root = inst.getDir().toPath().toAbsolutePath().normalize();
            Path bg = root.resolve(inst.bg).normalize();
            if (!bg.startsWith(root) || !Files.isRegularFile(bg)) {
                exchange.setStatusCode(404);
                exchange.endExchange();
                return;
            }

            long fileSize = Files.size(bg);
            String name = bg.getFileName().toString().toLowerCase();
            String mime = guessBgMime(name);

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, mime);
            exchange.getResponseHeaders().put(new HttpString("Cache-Control"),
                    "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().put(new HttpString("Pragma"), "no-cache");
            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");

            String rangeHeader = exchange.getRequestHeaders().getFirst(Headers.RANGE);
            long start = 0, end = fileSize - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    String spec = rangeHeader.substring(6).trim();
                    int dash = spec.indexOf('-');
                    if (dash >= 0) {
                        String s = spec.substring(0, dash);
                        String e = spec.substring(dash + 1);
                        if (!s.isEmpty()) start = Long.parseLong(s);
                        if (!e.isEmpty()) end = Long.parseLong(e);
                        if (end >= fileSize) end = fileSize - 1;
                        if (start > end || start < 0) throw new Exception();
                        partial = true;
                    }
                } catch (Exception ex) {
                    exchange.setStatusCode(416);
                    exchange.getResponseHeaders().put(new HttpString("Content-Range"),
                            "bytes */" + fileSize);
                    exchange.endExchange();
                    return;
                }
            }

            long length = end - start + 1;
            if (partial) {
                exchange.setStatusCode(206);
                exchange.getResponseHeaders().put(new HttpString("Content-Range"),
                        "bytes " + start + "-" + end + "/" + fileSize);
            } else {
                exchange.setStatusCode(200);
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(length));

            exchange.startBlocking();
            try (RandomAccessFile raf = new RandomAccessFile(bg.toFile(), "r");
                 OutputStream out = exchange.getOutputStream()) {
                raf.seek(start);
                byte[] buf = new byte[16384];
                long remaining = length;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = raf.read(buf, 0, toRead);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        } catch (Exception e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.endExchange();
            }
        }
    }

    private String guessBgMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".m4v")) return "video/x-m4v";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".ogv")) return "video/ogg";
        return "application/octet-stream";
    }

    private String readCookie(HttpServerExchange ex, String name) {
        String cookie = ex.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return null;
    }
}
