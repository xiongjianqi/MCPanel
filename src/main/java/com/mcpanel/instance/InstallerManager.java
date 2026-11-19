package com.mcpanel.instance;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class InstallerManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build(); // ★ 禁止盲目重定向，防SSRF

    private static final String USER_AGENT = "MC-Panel/1.0 (+https://github.com/mcpanel)";

    private static final String[] PAPER_ALLOWED_HOST_SUFFIXES = {
            ".papermc.io", "papermc.io", ".fill-data.papermc.io"
    };

    private final ConfigManager config;
    private final ServerProcess serverProcess;

    private volatile boolean running = false;
    private volatile boolean finished = false;
    private volatile boolean success = false;
    private volatile String errorMsg = null;

    private volatile long totalBytes = 0;
    private volatile long downloadedBytes = 0;

    public InstallerManager(ConfigManager config, ServerProcess serverProcess) {
        this.config = config;
        this.serverProcess = serverProcess;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("running", running);
        m.put("finished", finished);
        m.put("success", success);
        m.put("error", errorMsg);
        m.put("totalBytes", totalBytes);
        m.put("downloadedBytes", downloadedBytes);
        return m;
    }

    public boolean isRunning() { return running; }

    public void startInstall(String core, String mcVersion, String coreVersion,
                             String instVersion, boolean keepWorld) {
        if (running) throw new IllegalStateException("已有安装任务在运行");
        running = true;
        finished = false;
        success = false;
        errorMsg = null;
        totalBytes = 0;
        downloadedBytes = 0;

        final String coreFinal = core;
        new Thread(() -> {
            try {
                doInstall(core, mcVersion, coreVersion, instVersion, keepWorld);
                success = true;
            } catch (Exception e) {
                errorMsg = e.getMessage();
                e.printStackTrace();
                if ("forge".equals(coreFinal) || "neoforge".equals(coreFinal)) {
                    try {
                        serverProcess.broadcast("[Panel] ✗ " + coreFinal + " 安装失败: " + e.getMessage());
                    } catch (Throwable ignored) {}
                }
            } finally {
                running = false;
                finished = true;
            }
        }, "installer").start();
    }

    private void doInstall(String core, String mcVersion, String coreVersion,
                           String instVersion, boolean keepWorld) throws Exception {
        File serverDir = config.resolve("server");
        if (!serverDir.exists()) serverDir.mkdirs();

        if (!java.util.Arrays.asList("vanilla", "paper", "fabric", "forge", "neoforge", "velocity").contains(core)) {
            throw new IllegalArgumentException("不支持的核心: " + core);
        }

        if (serverProcess.isRunning()) {
            serverProcess.shutdown();
            if (serverProcess.isRunning()) serverProcess.kill();
            Thread.sleep(2000);
        }

        Path worldDir = serverDir.toPath().resolve("world");
        Path worldBak = config.getRootDir().toPath().resolve("world_bak_temp");

        if (keepWorld && Files.exists(worldDir)) {
            if (Files.exists(worldBak)) deleteDirectory(worldBak);
            copyDirectory(worldDir, worldBak);
        }

        try (Stream<Path> stream = Files.list(serverDir.toPath())) {
            // 用 collect 而不是 Stream.toList()，保持对 Java 11 的兼容
            java.util.List<Path> children = stream.collect(java.util.stream.Collectors.toList());
            for (Path p : children) {
                if (keepWorld && p.getFileName().toString().equals("world")) continue;
                deleteRecursively(p);
            }
        }

        Path jarPath = serverDir.toPath().resolve("server.jar");

        if ("vanilla".equals(core)) {
            downloadVanilla(mcVersion, jarPath);
        } else if ("paper".equals(core)) {
            downloadPaper(mcVersion, coreVersion, jarPath);
        } else if ("fabric".equals(core)) {
            downloadFabric(mcVersion, coreVersion, instVersion, jarPath);
        } else if ("forge".equals(core)) {
            installForge(mcVersion, coreVersion, serverDir.toPath());
        } else if ("neoforge".equals(core)) {
            installNeoForge(mcVersion, coreVersion, serverDir.toPath());
        } else if ("velocity".equals(core)) {
            downloadVelocity(mcVersion, coreVersion, serverDir.toPath().resolve("velocity.jar"));
            jarPath = serverDir.toPath().resolve("velocity.jar");
        } else {
            throw new IllegalArgumentException("不支持的核心: " + core);
        }

        if (keepWorld && Files.exists(worldBak)) {
            if (Files.exists(worldDir)) deleteDirectory(worldDir);
            copyDirectory(worldBak, worldDir);
            deleteDirectory(worldBak);
        }

        if ("velocity".equals(core)) {
            config.set("velocity.start.command", "java -Xmx512M -jar velocity.jar");
            config.set("velocity.server.dir", "server");
            if (!config.has("terminal.shutdown.command")) {
                config.set("terminal.shutdown.command", "shutdown");
            }
            System.out.println("[Installer] Velocity 启动命令配置: java -Xmx512M -jar velocity.jar");
        } else if ("forge".equals(core) || "neoforge".equals(core)) {
            Path runSh = serverDir.toPath().resolve("run.sh");
            Path runBat = serverDir.toPath().resolve("run.bat");
            
            if (Files.exists(runSh) || Files.exists(runBat)) {
                try {
                    if (Files.exists(runSh)) {
                        Files.setPosixFilePermissions(runSh, PosixFilePermissions.fromString("rwxr-xr-x"));
                    }
                } catch (Exception ignored) {}

                Path jvmArgs = serverDir.toPath().resolve("user_jvm_args.txt");
                boolean needWrite = !Files.exists(jvmArgs) || Files.size(jvmArgs) < 10;
                if (needWrite) {
                    String content = "# JVM 参数（由面板自动生成，可修改；每行一个参数）\n-Xmx2G\n";
                    Files.write(jvmArgs, content.getBytes(StandardCharsets.UTF_8));
                }
                
                boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
                config.set("mc.start.command", isWin ? "run.bat" : "bash run.sh");
            } else if ("forge".equals(core)) {
                String forgeJarName = null;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(serverDir.toPath(), "forge-*.jar")) {
                    for (Path p : ds) {
                        String name = p.getFileName().toString();
                        if (!name.contains("installer") && !name.contains("shim") && !name.contains("client")) {
                            forgeJarName = name;
                            break;
                        }
                    }
                }
                
                if (forgeJarName != null) {
                    config.set("mc.start.command", "java -Xmx2G -jar " + forgeJarName + " nogui");
                } else {
                    throw new RuntimeException("Forge 安装完成但未找到启动 jar 或脚本");
                }
            } else {
                throw new RuntimeException("NeoForge 安装完成但未找到 run.sh/run.bat");
            }
        } else {
            config.set("mc.start.command", "java -Xmx2G -jar server.jar nogui");
        }

        if (!"velocity".equals(core)) {
            Path eula = serverDir.toPath().resolve("eula.txt");
            boolean eulaOk = false;
            if (Files.exists(eula)) {
                for (String line : Files.readAllLines(eula, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("eula=") && "true".equalsIgnoreCase(t.substring(5).trim())) {
                        eulaOk = true;
                        break;
                    }
                }
            }
            if (!eulaOk) {
                String content =
                        "#By changing the setting below to TRUE you are indicating your "
                        + "agreement to our EULA (https://aka.ms/MinecraftEULA).\n"
                        + "eula=true\n";
                Files.write(eula, content.getBytes(StandardCharsets.UTF_8));
            }
        }

        System.out.println("[Installer] 安装完成，正在启动...");
        serverProcess.start();
    }

    private void downloadVanilla(String mc, Path dest) throws Exception {
        String manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
        JsonNode root = fetchJson(manifestUrl);
        String targetUrl = null;
        for (JsonNode v : root.get("versions")) {
            if (v.get("id").asText().equals(mc)) { targetUrl = v.get("url").asText(); break; }
        }
        if (targetUrl == null) throw new RuntimeException("官方元数据中找不到该版本: " + mc);
        JsonNode versionData = fetchJson(targetUrl);
        String serverUrl = versionData.get("downloads").get("server").get("url").asText();
        downloadFile(serverUrl, dest);
    }

    private void downloadPaper(String mc, String build, Path dest) throws Exception {
        String buildsUrl = "https://fill.papermc.io/v3/projects/paper/versions/"
                + URLEncoder.encode(mc, StandardCharsets.UTF_8) + "/builds";

        JsonNode builds;
        try {
            builds = fetchJson(buildsUrl);
        } catch (Exception e) {
            throw new RuntimeException("无法获取 PaperMC 构建列表: " + e.getMessage(), e);
        }
        if (builds == null || !builds.isArray() || builds.size() == 0) {
            throw new RuntimeException("PaperMC 未返回该版本的构建列表: " + mc);
        }

        JsonNode chosen = null;
        if (build != null && !build.isEmpty() && !"latest".equalsIgnoreCase(build)) {
            for (JsonNode b : builds) {
                if (build.equals(b.path("id").asText())) { chosen = b; break; }
            }
            if (chosen == null) throw new RuntimeException("找不到 PaperMC 构建: " + mc + " build " + build);
        } else {
            for (JsonNode b : builds) {
                if ("STABLE".equalsIgnoreCase(b.path("channel").asText())) { chosen = b; break; }
            }
            if (chosen == null) chosen = builds.get(0);
        }

        String id = chosen.path("id").asText("?");
        JsonNode dl = chosen.path("downloads").path("server:default");
        String realUrl = dl.path("url").asText("");
        if (realUrl.isEmpty()) {
            JsonNode alt = chosen.path("downloads").path("server");
            if (!alt.isMissingNode()) realUrl = alt.path("url").asText("");
        }
        if (realUrl.isEmpty()) throw new RuntimeException("PaperMC 构建响应缺少下载链接");

        checkPaperDomain(realUrl);
        downloadFile(realUrl, dest);
    }

    private void downloadFabric(String mc, String loader, String installer, Path dest) throws Exception {
        String url = "https://meta.fabricmc.net/v2/versions/loader/"
                + URLEncoder.encode(mc, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(loader, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(installer, StandardCharsets.UTF_8)
                + "/server/jar";
        downloadFile(url, dest);
    }

    private void downloadVelocity(String mc, String build, Path dest) throws Exception {
        String buildsUrl = "https://fill.papermc.io/v3/projects/velocity/versions/"
                + URLEncoder.encode(mc, StandardCharsets.UTF_8) + "/builds";

        JsonNode builds = fetchJson(buildsUrl);
        if (builds == null || !builds.isArray() || builds.size() == 0) {
            throw new RuntimeException("未返回该版本的 Velocity 构建列表: " + mc);
        }

        JsonNode chosen = null;
        if (build != null && !build.isEmpty() && !"latest".equalsIgnoreCase(build)) {
            for (JsonNode b : builds) {
                if (build.equals(b.path("id").asText())) { chosen = b; break; }
            }
            if (chosen == null) throw new RuntimeException("找不到 Velocity 构建: " + mc + " build " + build);
        } else {
            for (JsonNode b : builds) {
                if ("RECOMMENDED".equalsIgnoreCase(b.path("channel").asText())) { chosen = b; break; }
            }
            if (chosen == null) chosen = builds.get(0);
        }

        JsonNode dl = chosen.path("downloads").path("server:default");
        String realUrl = dl.path("url").asText("");
        if (realUrl.isEmpty()) throw new RuntimeException("Velocity 构建响应缺少下载链接");

        checkPaperDomain(realUrl);
        downloadFile(realUrl, dest);
    }

    private void installForge(String mc, String forge, Path dir) throws Exception {
        if (!mc.matches("[0-9.]+") || !forge.matches("[0-9.]+")) {
            throw new RuntimeException("版本号非法: mc=" + mc + " forge=" + forge);
        }
        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mc + "-" + forge + "/forge-" + mc + "-" + forge + "-installer.jar";
        Path installer = dir.resolve("forge-installer.jar");

        serverProcess.broadcast("[Panel] 开始下载 Forge 安装器: " + mc + "-" + forge);
        downloadFile(url, installer);
        serverProcess.broadcast("[Panel] Forge 安装器下载完成，开始运行安装器（输出将显示在这里）");

        final CountDownLatch latch = new CountDownLatch(1);
        final int[] exitCode = {-1};

        try {
            serverProcess.runInstaller(
                    new String[]{"java", "-jar", "forge-installer.jar", "--installServer"},
                    dir.toFile(),
                    15 * 60 * 1000L,
                    code -> {
                        exitCode[0] = code;
                        latch.countDown();
                    });
        } catch (IllegalStateException ise) {
            throw new RuntimeException("无法运行 Forge 安装器: " + ise.getMessage());
        }

        if (!latch.await(16, TimeUnit.MINUTES)) {
            throw new RuntimeException("Forge 安装器超时（>16 分钟）");
        }
        if (exitCode[0] != 0) {
            throw new RuntimeException("Forge 安装器执行失败，返回码 " + exitCode[0]);
        }

        try { Files.deleteIfExists(installer); } catch (IOException ignored) {}
        serverProcess.broadcast("[Panel] Forge 安装器执行完成");
    }

    private void installNeoForge(String mc, String neoForgeVersion, Path dir) throws Exception {
        if (!neoForgeVersion.matches("[0-9.]+")) {
            throw new RuntimeException("NeoForge 版本号非法: " + neoForgeVersion);
        }
        String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + neoForgeVersion + "/neoforge-" + neoForgeVersion + "-installer.jar";
        Path installer = dir.resolve("neoforge-installer.jar");

        serverProcess.broadcast("[Panel] 开始下载 NeoForge 安装器: " + neoForgeVersion);
        downloadFile(url, installer);
        serverProcess.broadcast("[Panel] NeoForge 安装器下载完成，开始运行安装器（输出将显示在这里）");

        final CountDownLatch latch = new CountDownLatch(1);
        final int[] exitCode = {-1};

        try {
            serverProcess.runInstaller(
                    new String[]{"java", "-jar", "neoforge-installer.jar", "--install-server"},
                    dir.toFile(),
                    15 * 60 * 1000L,
                    code -> {
                        exitCode[0] = code;
                        latch.countDown();
                    });
        } catch (IllegalStateException ise) {
            throw new RuntimeException("无法运行 NeoForge 安装器: " + ise.getMessage());
        }

        if (!latch.await(16, TimeUnit.MINUTES)) {
            throw new RuntimeException("NeoForge 安装器超时（>16 分钟）");
        }
        if (exitCode[0] != 0) {
            throw new RuntimeException("NeoForge 安装器执行失败，返回码 " + exitCode[0]);
        }

        try { Files.deleteIfExists(installer); } catch (IOException ignored) {}
        serverProcess.broadcast("[Panel] NeoForge 安装器执行完成");
    }

    private void checkPaperDomain(String realUrl) {
        try {
            URI u = URI.create(realUrl);
            if (!"https".equalsIgnoreCase(u.getScheme())) throw new RuntimeException("下载链接必须为 https");
            String host = u.getHost();
            boolean ok = false;
            if (host != null) {
                String h = host.toLowerCase(Locale.ROOT);
                for (String suf : PAPER_ALLOWED_HOST_SUFFIXES) {
                    String cleanSuf = suf.startsWith(".") ? suf : "." + suf;
                    if (h.equals(suf) || h.endsWith(cleanSuf)) { ok = true; break; }
                }
            }
            if (!ok) throw new RuntimeException("下载链接域名不合法: " + host);
        } catch (IllegalArgumentException iae) {
            throw new RuntimeException("下载链接格式错误: " + realUrl);
        }
    }

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body());
    }

    private void downloadFile(String url, Path dest) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET();

        HttpResponse<InputStream> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("下载失败，HTTP " + resp.statusCode());
        }

        totalBytes = resp.headers().firstValueAsLong("Content-Length").orElse(0);
        if (totalBytes > 2L * 1024 * 1024 * 1024) throw new RuntimeException("文件过大 (>2GB)");
        downloadedBytes = 0;

        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                downloadedBytes += n;
                if (downloadedBytes > 2L * 1024 * 1024 * 1024) {
                    throw new RuntimeException("下载体积超过 2GB 限制");
                }
            }
        }
    }

    private void copyDirectory(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(source -> {
            try {
                Path target = dest.resolve(src.relativize(source));
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {}
        });
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (IOException ignored) {}
        });
    }

    private void deleteRecursively(Path path) throws IOException {
        deleteDirectory(path);
    }
}