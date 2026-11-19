package com.mcpanel.web;

import com.mcpanel.instance.InstallerManager;
import com.mcpanel.instance.InstanceRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class InstallerHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static final String USER_AGENT = "MC-Panel/1.0 (+https://github.com/mcpanel)";

    private static final Set<String> ALLOWED_CORES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "vanilla", "paper", "fabric", "forge", "neoforge", "velocity")));

    private final InstallerManager manager;

    public InstallerHandler(InstanceRuntime rt) {
        this.manager = rt.installerManager;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString();

        // ★ 修复：无副作用的查询接口 GET/POST 都同步处理，不再经过 receiveFullString。
        // Undertow 对无 body 的 GET 上 receiveFullString 的回调触发行为不稳定，之前会导致请求挂死。
        if (path.equals("/api/installer/status")) {
            handleStatus(exchange);
            return;
        }

        if (path.equals("/api/installer/versions")) {
            handleVersions(exchange);
            return;
        }

        if (path.equals("/api/installer/core-versions")) {
            handleCoreVersions(exchange);
            return;
        }

        if (path.equals("/api/installer/fabric-installer-versions")) {
            handleFabricInstallerVersions(exchange);
            return;
        }

        // /api/installer/install 必须 POST，且需要读取 body
        if (path.equals("/api/installer/install")) {
            if (!"POST".equalsIgnoreCase(method)) {
                exchange.setStatusCode(405);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                exchange.getResponseSender().send("{\"error\":\"method not allowed\"}");
                return;
            }
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                ex.dispatch(() -> handleInstall(ex, body));
            }, StandardCharsets.UTF_8);
            return;
        }

        exchange.setStatusCode(404);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send("{\"error\":\"unknown\"}");
    }

    // ===================== 各接口实现 =====================

    private void handleStatus(HttpServerExchange ex) {
        try {
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(MAPPER.writeValueAsString(manager.getStatus()));
        } catch (Exception e) {
            ex.setStatusCode(500);
            ex.getResponseSender().send("{\"error\":\"状态读取失败\"}");
        }
    }

    private void handleVersions(HttpServerExchange ex) {
        String core = getQuery(ex, "core", "paper");
        if (!ALLOWED_CORES.contains(core)) {
            ex.setStatusCode(400);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"不支持的安装核心: " + esc(core) + "\"}");
            return;
        }
        try {
            List<String> versions = getVersions(core);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(MAPPER.writeValueAsString(versions));
        } catch (Exception e) {
            ex.setStatusCode(500);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"获取版本失败: " + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleCoreVersions(HttpServerExchange ex) {
        String core = getQuery(ex, "core", "paper");
        String mc = getQuery(ex, "mc", "");
        if (!ALLOWED_CORES.contains(core)) {
            ex.setStatusCode(400);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"不支持的安装核心: " + esc(core) + "\"}");
            return;
        }
        if (mc == null || mc.trim().isEmpty()) {
            ex.setStatusCode(400);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"缺少 mc 参数\"}");
            return;
        }
        try {
            List<String> coreVersions = getCoreVersions(core, mc);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(MAPPER.writeValueAsString(coreVersions));
        } catch (Exception e) {
            ex.setStatusCode(500);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"获取核心版本失败: " + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFabricInstallerVersions(HttpServerExchange ex) {
        try {
            List<String> versions = getFabricInstallerVersions();
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send(MAPPER.writeValueAsString(versions));
        } catch (Exception e) {
            ex.setStatusCode(500);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"获取安装器版本失败: " + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleInstall(HttpServerExchange ex, String body) {
        try {
            Map<String, String> p = parse(body);

            String core = p.getOrDefault("core", "").trim();
            if (!ALLOWED_CORES.contains(core)) {
                ex.setStatusCode(400);
                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                ex.getResponseSender().send("{\"error\":\"不支持的安装核心\"}");
                return;
            }

            String mc = p.getOrDefault("mc", "").trim();
            if (mc.isEmpty() && !"velocity".equals(core)) {
                // velocity 的 mc 字段在 UI 里承载的是 velocity 构建前缀，理论也不能为空
                ex.setStatusCode(400);
                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                ex.getResponseSender().send("{\"error\":\"缺少 MC 版本\"}");
                return;
            }

            // ★ 修复：isRunning 检查和 startInstall 之间有时间窗，第二个请求会抛 IllegalStateException，
            // 老代码把它包成 500。这里提前返回友好错误。
            if (manager.isRunning()) {
                ex.setStatusCode(400);
                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                ex.getResponseSender().send("{\"error\":\"已有安装任务在运行\"}");
                return;
            }

            String coreVer = p.getOrDefault("coreVer", "");
            String instVer = p.getOrDefault("instVer", "");
            boolean keepWorld = "true".equals(p.getOrDefault("keepWorld", "false"));

            try {
                manager.startInstall(core, mc, coreVer, instVer, keepWorld);
            } catch (IllegalStateException ise) {
                // 兜底：并发时 startInstall 内部可能抛，按 400 返回
                ex.setStatusCode(400);
                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                ex.getResponseSender().send("{\"error\":\"" + esc(ise.getMessage()) + "\"}");
                return;
            }

            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"ok\":true}");
        } catch (Exception e) {
            ex.setStatusCode(500);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            ex.getResponseSender().send("{\"error\":\"内部错误: " + esc(e.getMessage()) + "\"}");
        }
    }

    // ===================== 工具方法 =====================

    private String getQuery(HttpServerExchange ex, String key, String def) {
        Deque<String> q = ex.getQueryParameters().get(key);
        return (q == null || q.isEmpty()) ? def : q.peekFirst();
    }

    private Map<String, String> parse(String body) throws Exception {
        if (body == null || body.isEmpty()) return new HashMap<>();
        return MAPPER.readValue(body,
                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
    }

    private List<String> getVersions(String core) throws Exception {
        if ("paper".equals(core) || "velocity".equals(core)) {
            return getV3Versions(core);
        } else if ("vanilla".equals(core)) {
            String url = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
            JsonNode root = fetchJson(url);
            List<String> list = new ArrayList<>();
            for (JsonNode v : root.get("versions")) {
                if ("release".equals(v.get("type").asText())) {
                    list.add(v.get("id").asText());
                }
            }
            return list;
        } else if ("fabric".equals(core)) {
            String url = "https://meta.fabricmc.net/v2/versions/game";
            JsonNode root = fetchJson(url);
            List<String> list = new ArrayList<>();
            for (JsonNode v : root) {
                if (v.get("stable").asBoolean()) {
                    list.add(v.get("version").asText());
                }
            }
            return list;
        } else if ("forge".equals(core)) {
            return new ArrayList<>(Arrays.asList("1.20.4", "1.20.1", "1.19.2", "1.18.2", "1.16.5", "1.12.2"));
        } else if ("neoforge".equals(core)) {
            return new ArrayList<>(Arrays.asList("1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1"));
        }
        return new ArrayList<>();
    }

    private List<String> getV3Versions(String project) throws Exception {
        String url = "https://fill.papermc.io/v3/projects/" + project;
        JsonNode root = fetchJson(url);
        JsonNode versions = root.path("versions");

        if (versions.isObject()) {
            List<String> majors = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = versions.fields();
            while (it.hasNext()) majors.add(it.next().getKey());
            majors.sort(InstallerHandler::compareMajorDesc);

            List<String> out = new ArrayList<>();
            for (String m : majors) {
                JsonNode arr = versions.get(m);
                if (arr != null && arr.isArray()) {
                    for (JsonNode n : arr) out.add(n.asText());
                }
            }
            if (!out.isEmpty()) return out;
        }

        if (versions.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode n : versions) out.add(n.asText());
            if (!out.isEmpty()) return out;
        }
        throw new RuntimeException("未找到项目 " + project + " 的版本信息");
    }

    private List<String> getCoreVersions(String core, String mc) throws Exception {
        if ("paper".equals(core) || "velocity".equals(core)) {
            return getV3CoreVersions(core, mc);
        } else if ("fabric".equals(core)) {
            String url = "https://meta.fabricmc.net/v2/versions/loader";
            JsonNode root = fetchJson(url);
            List<String> loaders = new ArrayList<>();
            for (JsonNode l : root) {
                loaders.add(l.get("version").asText());
            }
            return loaders;
        } else if ("forge".equals(core)) {
            String url = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
            JsonNode root = fetchJson(url);
            List<String> list = new ArrayList<>();
            if (root.has("promos") && root.get("promos").has(mc + "-recommended")) {
                list.add(root.get("promos").get(mc + "-recommended").asText());
            }
            if (root.has("promos") && root.get("promos").has(mc + "-latest")) {
                list.add(root.get("promos").get(mc + "-latest").asText());
            }
            if (list.isEmpty()) list.add("latest");
            return list;
        } else if ("neoforge".equals(core)) {
            return getNeoForgeVersions(mc);
        } else if ("vanilla".equals(core)) {
            return new ArrayList<>(Collections.singletonList(mc));
        }
        return new ArrayList<>();
    }

    private List<String> getV3CoreVersions(String project, String mc) throws Exception {
        String url = "https://fill.papermc.io/v3/projects/" + project + "/versions/"
                + URLEncoder.encode(mc, StandardCharsets.UTF_8) + "/builds";
        JsonNode root = fetchJson(url);
        if (root.isArray()) {
            List<String> stable = new ArrayList<>();
            List<String> all = new ArrayList<>();
            for (JsonNode b : root) {
                String id = b.path("id").asText("");
                if (id.isEmpty()) continue;
                all.add(id);
                String channel = b.path("channel").asText();
                if ("STABLE".equalsIgnoreCase(channel) || "RECOMMENDED".equalsIgnoreCase(channel)) {
                    stable.add(id);
                }
            }
            if (!stable.isEmpty()) return stable;
            if (!all.isEmpty()) return all;
        }
        throw new RuntimeException("未找到构建信息");
    }

    private List<String> getNeoForgeVersions(String mc) throws Exception {
        String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("获取 NeoForge 版本失败，HTTP " + resp.statusCode());

        String xml = resp.body();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<version>([^<]+)</version>");
        java.util.regex.Matcher m = p.matcher(xml);
        List<String> all = new ArrayList<>();
        while (m.find()) {
            all.add(m.group(1));
        }

        String prefix = mcToNeoForgePrefix(mc);
        List<String> result = new ArrayList<>();
        for (String v : all) {
            if (v.startsWith(prefix)) result.add(v);
        }
        Collections.reverse(result);
        return result;
    }

    private String mcToNeoForgePrefix(String mc) {
        if (mc.equals("1.20.1")) return "47.";
        if (mc.startsWith("1.20.")) return mc.substring(2) + ".";
        if (mc.equals("1.21")) return "21.0.";
        if (mc.startsWith("1.21.")) return mc.substring(2) + ".";
        return mc.replace("1.", "") + ".";
    }

    private List<String> getFabricInstallerVersions() throws Exception {
        String url = "https://meta.fabricmc.net/v2/versions/installer";
        JsonNode root = fetchJson(url);
        List<String> list = new ArrayList<>();
        for (JsonNode v : root) {
            list.add(v.get("version").asText());
        }
        return list;
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

    private static int compareMajorDesc(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? safeInt(pa[i]) : 0;
            int vb = i < pb.length ? safeInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(vb, va);
        }
        return 0;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}