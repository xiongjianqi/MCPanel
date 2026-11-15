package com.mcpanel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.instance.Instance;
import com.mcpanel.instance.InstanceManager;
import com.mcpanel.util.LinuxUserManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.*;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FileHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_EDIT_SIZE = 500 * 1024;
    private static final long MAX_UPLOAD_SIZE = 20L * 1024 * 1024 * 1024;
    private static final long MAX_EXTRACT_SIZE = 50L * 1024 * 1024 * 1024;
    private static final int MAX_EXTRACT_FILES = 200000;

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList("txt","log","json","yml","yaml","properties","cfg","conf","ini","md","html","htm","css","js","java","py","sh","xml","toml","csv"));
    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList("png","jpg","jpeg","gif","webp","bmp"));
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList("mp4","webm","mkv","mov","m4v","avi","flv","wmv"));
    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList("mp3","wav","ogg","m4a","aac","flac"));

    private static final Set<String> FORBIDDEN_FILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "users.json", "users.key", "node.json", "nodes.json", "plugin-auth.json", "config.properties",
            "mcperms.json", "shares.json", "share-logs.json", ".security_hashes.json", "sessions.json")));
    
    private static final Map<String, String> SENSITIVE = new HashMap<>();
    static {
        SENSITIVE.put("backup", "backup");
        SENSITIVE.put("plugins", "plugins");
        SENSITIVE.put("plugins-data", "plugins");
        SENSITIVE.put("logs", "terminal");
        SENSITIVE.put("ssl", "config");
    }

    private com.mcpanel.config.ConfigManager config;
    private final Path sandboxRoot;
    private final List<String> allowedSubdirs;
    private final Set<String> userPerms;

    public FileHandler(com.mcpanel.config.ConfigManager config) {
        this.config = config;
        this.sandboxRoot = Paths.get(".").toAbsolutePath().normalize();
        this.allowedSubdirs = null;
        this.userPerms = Collections.emptySet();
    }

    public FileHandler(com.mcpanel.config.ConfigManager config, Path sandboxRoot, List<String> allowedSubdirs, Set<String> userPerms) {
        this.config = config;
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
        this.allowedSubdirs = allowedSubdirs;
        this.userPerms = userPerms == null ? Collections.emptySet() : userPerms;
    }

    private void syncPerm(Path path) {
        if (sandboxRoot == null || path == null) return;
        String absPath = path.toAbsolutePath().normalize().toString();
        String instId = config.get("_instance.id", "");
        if (instId.isEmpty() || absPath.contains("/instances/")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[/\\\\]instances[/\\\\]([^/\\\\]+)").matcher(absPath);
            if (m.find()) instId = m.group(1);
        }
        if (!instId.isEmpty()) {
            Instance inst = InstanceManager.getInstance().getInstance(instId);
            if (inst != null && inst.sysUser != null) {
                LinuxUserManager.chownDir(path.toFile(), inst.sysUser);
            }
        }
    }

    private void checkPerm(Path p) {
        Path fileNamePath = p.getFileName();
        if (fileNamePath == null) return;
        String fileName = fileNamePath.toString().toLowerCase();
        
        if (FORBIDDEN_FILES.contains(fileName)) throw new SecurityException("系统核心配置文件受保护");
        String absPath = p.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (absPath.contains("/instances/") && absPath.endsWith("/meta.json")) {
            throw new SecurityException("实例元数据 (meta.json) 受保护");
        }

        if (userPerms.contains("files_all")) return;
        Path relToRoot;
        try {
            relToRoot = sandboxRoot.relativize(p.toAbsolutePath().normalize());
        } catch (Exception e) {
            throw new SecurityException("path outside sandbox");
        }
        if (relToRoot.getNameCount() > 0 && "..".equals(relToRoot.getName(0).toString())) {
            throw new SecurityException("path outside sandbox");
        }
        for (int i = 0; i < relToRoot.getNameCount(); i++) {
            String seg = relToRoot.getName(i).toString();
            String needPerm = SENSITIVE.get(seg);
            if (needPerm != null && !userPerms.contains(needPerm)) {
                throw new SecurityException("需要 " + needPerm + " 权限");
            }
        }
    }

    public void serveStatic(HttpServerExchange exchange, String path) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> serveStatic(exchange, path));
            return;
        }
        try {
            String actualPath = path.equals("/") ? "/index.html" : path;
            Path webRoot = Paths.get("web").toAbsolutePath().normalize();
            Path file = webRoot.resolve(actualPath.substring(1)).normalize();
            if (file.startsWith(webRoot) && Files.isRegularFile(file)) {
                byte[] data = Files.readAllBytes(file);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, guessMime(actualPath));
                exchange.getResponseHeaders().put(new HttpString("Cache-Control"), "no-cache, must-revalidate");
                exchange.getResponseSender().send(ByteBuffer.wrap(data));
                return;
            }
            String cpPath = "/web" + actualPath;
            try (InputStream in = FileHandler.class.getResourceAsStream(cpPath)) {
                if (in != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, guessMime(actualPath));
                    exchange.getResponseHeaders().put(new HttpString("Cache-Control"), "no-cache, must-revalidate");
                    exchange.getResponseSender().send(ByteBuffer.wrap(bos.toByteArray()));
                    return;
                }
            } catch (Exception ignored) {}

            // ★ 核心修复：SPA 路由兜底。如果没找到静态文件，且路径没有扩展名，返回 index.html
            if (!actualPath.contains(".")) {
                try (InputStream in = FileHandler.class.getResourceAsStream("/web/index.html")) {
                    if (in != null) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
                        exchange.getResponseHeaders().put(new HttpString("Cache-Control"), "no-cache, must-revalidate");
                        exchange.getResponseSender().send(ByteBuffer.wrap(bos.toByteArray()));
                        return;
                    }
                }
            }

            exchange.setStatusCode(404);
            exchange.getResponseSender().send("Not Found");
        } catch (Exception e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("Error");
            }
        }
    }

    public void serveDownload(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> serveDownload(exchange));
            return;
        }
        try {
            Deque<String> tp = exchange.getQueryParameters().get("token");
            if (tp == null || tp.isEmpty()) {
                exchange.setStatusCode(400); exchange.getResponseSender().send("缺少 token"); return;
            }
            String ip = getClientIp(exchange);
            DownloadLicenseManager.CheckResult ck = DownloadLicenseManager.check(tp.peekFirst(), ip);

            if (ck.result != DownloadLicenseManager.Result.OK) {
                exchange.setStatusCode(403); exchange.getResponseSender().send("许可证无效或已过期"); return;
            }
            Path file = Paths.get(ck.license.filePath).toAbsolutePath().normalize();
            if (!file.startsWith(sandboxRoot) || !Files.isRegularFile(file)) {
                exchange.setStatusCode(403); exchange.getResponseSender().send("文件不存在或无权访问"); return;
            }

            long fileSize = Files.size(file);
            String fname = file.getFileName().toString();
            String mime = guessMime(fname);
            
            boolean isInline = "inline".equals(ck.license.mode);
            if (isInline && (mime.contains("html") || mime.contains("svg") || mime.contains("xml") || mime.contains("javascript"))) {
                isInline = false; 
            }

            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, mime);
            exchange.getResponseHeaders().put(new HttpString("Content-Disposition"),
                    (isInline ? "inline" : "attachment") + "; filename=\"" + toAsciiFallback(fname) + "\"; filename*=UTF-8''" + URLEncoder.encode(fname, "UTF-8").replace("+", "%20"));
            
            exchange.getResponseHeaders().put(new HttpString("Content-Security-Policy"), "sandbox; default-src 'none';");

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
                    exchange.setStatusCode(416); exchange.endExchange(); return;
                }
            }

            long length = end - start + 1;
            if (partial) {
                exchange.setStatusCode(206);
                exchange.getResponseHeaders().put(new HttpString("Content-Range"), "bytes " + start + "-" + end + "/" + fileSize);
            } else {
                exchange.setStatusCode(200);
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(length));

            exchange.startBlocking();
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
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
                exchange.setStatusCode(500); exchange.getResponseSender().send("下载出错");
            }
        }
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString();

        if (path.equals("/api/files/upload") && method.equals("POST")) {
            handleUpload(exchange);
            return;
        }

        if (path.equals("/api/files/list")) {
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                ex.dispatch(() -> handleListRequest(ex, body));
            }, StandardCharsets.UTF_8);
            return;
        }

        if ("GET".equalsIgnoreCase(method)) {
            handleGet(exchange, path);
        } else {
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                ex.dispatch(() -> handlePost(ex, path, body));
            }, StandardCharsets.UTF_8);
        }
    }

    private void handleListRequest(HttpServerExchange exchange, String body) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleListRequest(exchange, body));
            return;
        }
        try {
            Path root = sandboxRoot;
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> list = new ArrayList<>();
            
            String reqPath = null;
            Deque<String> qp = exchange.getQueryParameters().get("path");
            if (qp != null && !qp.isEmpty()) {
                reqPath = qp.peekFirst();
            } else {
                reqPath = extract(body, "path", "/");
            }

            Path target = safeResolve(root, reqPath);
            final boolean atSandboxRoot = target.equals(root);

            if (Files.isDirectory(target)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                    for (Path p : stream) {
                        if (allowedSubdirs != null && atSandboxRoot) {
                            String fn = p.getFileName().toString();
                            if (!allowedSubdirs.contains(fn)) continue;
                        }
                        String name = p.getFileName().toString();
                        if (name.equals(".security_hashes.json")) continue;
                        try { checkPerm(p); } catch (SecurityException se) { continue; }
                        
                        Map<String, Object> f = new HashMap<>();
                        f.put("name", name);
                        f.put("isDir", Files.isDirectory(p));
                        try { f.put("size", Files.size(p)); } catch (IOException ignored) {}
                        f.put("mtime", p.toFile().lastModified());
                        String lower = name.toLowerCase();
                        boolean isArchive = lower.endsWith(".zip") || lower.endsWith(".jar")
                                || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
                        f.put("isArchive", isArchive && !Files.isDirectory(p));
                        int dot = name.lastIndexOf('.');
                        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
                        boolean isFile = !Files.isDirectory(p);
                        f.put("editable", isFile && TEXT_EXT.contains(ext));
                        f.put("isImage", isFile && IMAGE_EXT.contains(ext));
                        f.put("isVideo", isFile && VIDEO_EXT.contains(ext));
                        f.put("isAudio", isFile && AUDIO_EXT.contains(ext));
                        list.add(f);
                    }
                } catch (Exception e) {
                    // 防止目录遍历中某个文件读不了导致整个列表崩溃
                }
            }
            result.put("files", list);

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(result));
        } catch (SecurityException se) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("{\"error\":\"access denied: " + se.getMessage() + "\"}");
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleGet(HttpServerExchange exchange, String path) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleGet(exchange, path));
            return;
        }
        try {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("{\"error\":\"unknown get api\"}");
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handlePost(HttpServerExchange ex, String path, String body) {
        try {
            Path root = sandboxRoot;
            Map<String, Object> result = new HashMap<>();

            String reqPath = extract(body, "path", null);
            if (reqPath == null) reqPath = "/";

            if (path.equals("/api/files/prepare-download")) {
                String mode = extract(body, "mode", "download");
                Path target = safeResolve(root, reqPath);
                if (!Files.isRegularFile(target)) throw new RuntimeException("not a file");
                String token = DownloadLicenseManager.create(target.toString(), mode);
                result.put("token", token);
                result.put("url", "/api/files/dl?token=" + token);

            } else if (path.equals("/api/files/newfile")) {
                Path file = safeResolve(root, reqPath);
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.write(file, new byte[0], StandardOpenOption.CREATE_NEW);
                syncPerm(file);
                result.put("ok", true);

            } else if (path.equals("/api/files/copy") || path.equals("/api/files/move")) {
                List<String> paths = extractArray(body, "paths");
                String targetDir = extract(body, "target", "");
                Path dstDir = safeResolve(root, targetDir);
                boolean isMove = path.equals("/api/files/move");
                for (String p : paths) {
                    Path src = safeResolve(root, p);
                    if (src.equals(root)) throw new SecurityException("禁止复制/移动根目录");
                    Path dst = dstDir.resolve(src.getFileName().toString());
                    if (Files.exists(dst)) throw new RuntimeException("目标已存在同名文件");
                    if (isMove) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    else copyRecursive(src, dst);
                    syncPerm(dst);
                }
                result.put("ok", true);

            } else if (path.equals("/api/files/delete")) {
                List<String> paths = extractArray(body, "paths");
                if (paths.isEmpty()) {
                    String one = extract(body, "path", "");
                    if (!one.isEmpty()) paths.add(one);
                }
                for (String p : paths) {
                    Path target = safeResolve(root, p);
                    if (target.equals(root)) throw new SecurityException("禁止删除根目录");
                    if (Files.exists(target)) deleteRecursive(target);
                }
                result.put("ok", true);

            } else if (path.equals("/api/files/rename")) {
                String from = extract(body, "from", "");
                String to = extract(body, "to", "");
                if (from.matches("^instances/[^/]+/?$") || to.matches("^instances/[^/]+/?$")) {
                    throw new SecurityException("实例根目录禁止重命名");
                }
                Path src = safeResolve(root, from);
                Path dst = safeResolve(root, to);
                if (src.equals(root)) throw new SecurityException("禁止重命名根目录");
                if (dst.equals(root)) throw new SecurityException("禁止把文件移动到根目录本身");
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                syncPerm(dst);
                result.put("ok", true);

            } else if (path.equals("/api/files/mkdir")) {
                Files.createDirectories(safeResolve(root, reqPath));
                syncPerm(root);
                result.put("ok", true);

            } else if (path.equals("/api/files/read")) {
                Path file = safeResolve(root, reqPath);
                if (!Files.isRegularFile(file)) throw new RuntimeException("不是文件");
                if (Files.size(file) > MAX_EDIT_SIZE) throw new RuntimeException("文件过大无法编辑");
                result.put("content", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

            } else if (path.equals("/api/files/write")) {
                Path file = safeResolve(root, reqPath);
                String content = extract(body, "content", "");
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_EDIT_SIZE * 4) throw new RuntimeException("文件过大");
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                syncPerm(file);
                result.put("ok", true);

            } else if (path.equals("/api/files/extract")) {
                Path archive = safeResolve(root, reqPath);
                Path destDir = archive.getParent();
                String name = archive.getFileName().toString().toLowerCase();
                if (name.endsWith(".zip") || name.endsWith(".jar")) extractZip(archive, destDir);
                else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) extractTarGz(archive, destDir);
                else if (name.endsWith(".tar")) extractTar(archive, destDir);
                else throw new RuntimeException("不支持的格式");
                syncPerm(destDir);
                result.put("ok", true);

            } else if (path.equals("/api/files/compress")) {
                List<String> paths = extractArray(body, "paths");
                String target = extract(body, "target", "");
                Path dstFile = safeResolve(root, target);
                
                if (paths.isEmpty()) throw new RuntimeException("没有选择要压缩的文件");
                if (dstFile.getParent() != null) Files.createDirectories(dstFile.getParent());
                
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dstFile))) {
                    for (String p : paths) {
                        Path src = safeResolve(root, p);
                        if (!Files.exists(src)) continue;
                        String baseName = src.getFileName().toString();
                        if (Files.isDirectory(src)) {
                            addDirToZip(zos, src, baseName);
                        } else {
                            addFileToZip(zos, src, baseName);
                        }
                    }
                }
                syncPerm(dstFile);
                result.put("ok", true);
            } else {
                throw new RuntimeException("unknown api");
            }

            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            ex.getResponseSender().send(MAPPER.writeValueAsString(result));
        } catch (SecurityException se) {
            ex.setStatusCode(403);
            ex.getResponseSender().send("{\"error\":\"access denied: " + se.getMessage() + "\"}");
        } catch (Exception e) {
            ex.setStatusCode(500);
            ex.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void addDirToZip(ZipOutputStream zos, Path srcDir, String baseName) throws IOException {
        Files.walk(srcDir).forEach(p -> {
            try {
                String entryName = baseName + "/" + srcDir.relativize(p).toString().replace("\\", "/");
                if (Files.isDirectory(p)) {
                    if (!entryName.endsWith("/")) entryName += "/";
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            } catch (IOException e) {}
        });
    }

    private void addFileToZip(ZipOutputStream zos, Path file, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private void handleUpload(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleUpload(exchange));
            return;
        }
        try {
            Deque<String> pp = exchange.getQueryParameters().get("path");
            Path file = safeResolve(sandboxRoot, pp.peekFirst());
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            
            exchange.startBlocking();
            long total = 0;
            try (InputStream in = exchange.getInputStream();
                 OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_UPLOAD_SIZE) throw new IOException("文件过大 (限 20GB)");
                    out.write(buf, 0, n);
                }
            }
            syncPerm(file);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"ok\":true}");
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"上传失败\"}");
        }
    }

    private void copyRecursive(Path src, Path dst) throws IOException {
        if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dst);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(src)) {
                for (Path c : stream) copyRecursive(c, dst.resolve(c.getFileName().toString()));
            }
        } else {
            if (dst.getParent() != null) Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractZip(Path zipPath, Path destDir) throws IOException {
        int fileCount = 0;
        long totalBytes = 0;
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("__MACOSX") || entry.getName().contains("/._")) continue;
                
                fileCount++;
                if (fileCount > MAX_EXTRACT_FILES) throw new IOException("文件数量超限");
                
                Path target = safeResolveUnder(destDir, entry.getName());
                checkPerm(target);

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) {
                            totalBytes += n;
                            if (totalBytes > MAX_EXTRACT_SIZE) throw new IOException("解压体积超限");
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    private void extractTarGz(Path tarGz, Path destDir) throws IOException {
        try (InputStream fin = Files.newInputStream(tarGz); GZIPInputStream gz = new GZIPInputStream(fin)) {
            extractTarStream(gz, destDir);
        }
    }

    private void extractTar(Path tar, Path destDir) throws IOException {
        try (InputStream fin = Files.newInputStream(tar)) {
            extractTarStream(fin, destDir);
        }
    }

    private void extractTarStream(InputStream in, Path destDir) throws IOException {
        byte[] header = new byte[512];
        int fileCount = 0;
        long totalBytes = 0;
        while (true) {
            int got = 0;
            while (got < header.length) {
                int n = in.read(header, got, header.length - got);
                if (n < 0) break;
                got += n;
            }
            if (got < header.length) break;
            boolean allZero = true;
            for (byte b : header) if (b != 0) { allZero = false; break; }
            if (allZero) break;
            String name = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
            long size;
            try {
                size = Long.parseLong(new String(header, 124, 12, StandardCharsets.US_ASCII).trim(), 8);
            } catch (NumberFormatException nfe) {
                throw new IOException("损坏的 tar 头（size 字段非法）");
            }
            if (size < 0) throw new IOException("损坏的 tar 头（size 为负）");
            int typeFlag = header[156] & 0xFF;
            Path target = safeResolveUnder(destDir, name);
            checkPerm(target);
            
            if (typeFlag == '5') {
                Files.createDirectories(target);
                skipPadding(in, size);
            } else if (typeFlag == '0' || typeFlag == 0 || typeFlag == ' ') {
                fileCount++;
                if (fileCount > MAX_EXTRACT_FILES) throw new IOException("文件数量超限");
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    long remaining = size;
                    byte[] buf = new byte[8192];
                    while (remaining > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) break;
                        totalBytes += n;
                        if (totalBytes > MAX_EXTRACT_SIZE) throw new IOException("解压体积超限");
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                skipPadding(in, size);
            } else {
                skipData(in, size);
            }
        }
    }
    
    private void skipData(InputStream in, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
        skipPadding(in, size);
    }
    
    private void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        while (pad > 0) {
            long skipped = in.skip(pad);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                pad--;
            } else {
                pad -= skipped;
            }
        }
    }

    private Path safeResolve(Path root, String rel) {
        String norm = (rel == null) ? "" : rel.trim();
        if (norm.startsWith("/")) norm = norm.substring(1);
        while (norm.startsWith("./")) norm = norm.substring(2);
        Path p = root.resolve(norm).normalize();
        if (!p.startsWith(root)) throw new SecurityException("path escape");
        
        if (allowedSubdirs != null) {
            Path relToRoot = root.relativize(p);
            if (relToRoot.getNameCount() > 0) {
                String first = relToRoot.getName(0).toString();
                if (!first.isEmpty() && !allowedSubdirs.contains(first)) {
                    throw new SecurityException("access denied: instance out of bounds");
                }
            }
        }

        checkPerm(p);
        try {
            Path current = root;
            for (Path part : root.relativize(p)) {
                current = current.resolve(part);
                if (Files.exists(current) && Files.isSymbolicLink(current)) {
                    throw new SecurityException("符号链接穿越已拦截");
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception ignored) {}
        return p;
    }

    private Path safeResolveUnder(Path dir, String rel) {
        Path p = dir.resolve(rel).normalize();
        if (!p.startsWith(dir)) throw new SecurityException("archive entry escape");
        return p;
    }

    private void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for (Path c : stream) deleteRecursive(c);
            }
        }
        Files.deleteIfExists(p);
    }

    private String extract(String body, String key, String def) {
        if (body == null || body.isEmpty()) return def;
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.has(key)) return node.get(key).asText();
        } catch (Exception ignored) {}
        return def;
    }
    
    private List<String> extractArray(String body, String key) {
        List<String> list = new ArrayList<>();
        if (body == null || body.isEmpty()) return list;
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode arr = node.get(key);
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) list.add(n.asText());
            }
        } catch (Exception ignored) {}
        return list;
    }

    private String getClientIp(HttpServerExchange exchange) {
        boolean isTrusted = false;
        try {
            if (exchange.getSourceAddress() != null && exchange.getSourceAddress().getAddress() != null) {
                isTrusted = exchange.getSourceAddress().getAddress().isLoopbackAddress()
                         || exchange.getSourceAddress().getAddress().isSiteLocalAddress();
            }
        } catch (Exception ignored) {}

        if (isTrusted) {
            String h = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (h != null && !h.isEmpty()) {
                int comma = h.indexOf(',');
                return comma > 0 ? h.substring(0, comma).trim() : h.trim();
            }
            String real = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (real != null && !real.isEmpty()) return real;
        }

        if (exchange.getSourceAddress() != null && exchange.getSourceAddress().getAddress() != null) {
            return exchange.getSourceAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private String guessMime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
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
}