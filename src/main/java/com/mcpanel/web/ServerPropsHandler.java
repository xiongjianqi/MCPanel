package com.mcpanel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ServerPropsHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConfigManager config;

    private static final Map<String, String> VELOCITY_LABELS = new HashMap<>();
    static {
        VELOCITY_LABELS.put("config-version",                     "配置版本（请勿手动修改）");
        VELOCITY_LABELS.put("bind",                                "代理监听地址（格式 IP:端口）");
        VELOCITY_LABELS.put("motd",                                "服务器列表标语 (MOTD)");
        VELOCITY_LABELS.put("show-max-players",                    "列表显示最大玩家数");
        VELOCITY_LABELS.put("online-mode",                         "正版验证（Mojang 登录）");
        VELOCITY_LABELS.put("force-key-authentication",            "强制聊天密钥验证");
        VELOCITY_LABELS.put("prevent-client-proxy-connections",    "阻止客户端代理/VPN 连接");
        VELOCITY_LABELS.put("player-info-forwarding-mode",         "玩家信息转发模式");
        VELOCITY_LABELS.put("forwarding-secret-file",              "转发密钥文件名");
        VELOCITY_LABELS.put("announce-forge",                      "广播 Forge 信息");
        VELOCITY_LABELS.put("kick-existing-players",               "重复登录时踢出旧连接");
        VELOCITY_LABELS.put("ping-passthrough",                    "Ping 透传模式");
        VELOCITY_LABELS.put("enable-player-address-logging",       "日志记录玩家 IP");
        VELOCITY_LABELS.put("sample-players-in-ping",              "Ping 响应显示示例玩家");
        VELOCITY_LABELS.put("try",                                 "尝试连接的服务器顺序（逗号分隔）");
        VELOCITY_LABELS.put("compression-threshold",               "数据包压缩阈值（字节）");
        VELOCITY_LABELS.put("compression-level",                   "压缩级别（0-9，-1 为库默认）");
        VELOCITY_LABELS.put("login-ratelimit",                     "登录速率限制（毫秒）");
        VELOCITY_LABELS.put("connection-timeout",                  "连接超时（毫秒）");
        VELOCITY_LABELS.put("read-timeout",                        "读取超时（毫秒）");
        VELOCITY_LABELS.put("haproxy-protocol",                    "启用 HAProxy PROXY protocol");
        VELOCITY_LABELS.put("tcp-fast-open",                       "启用 TCP Fast Open（仅 Linux）");
        VELOCITY_LABELS.put("bungee-plugin-message-channel",       "启用 Bungee 插件消息通道");
        VELOCITY_LABELS.put("show-ping-requests",                  "在日志中显示 Ping 请求");
        VELOCITY_LABELS.put("failover-on-unexpected-server-disconnect", "后端意外断线时故障转移");
        VELOCITY_LABELS.put("announce-proxy-commands",             "向 1.13+ 客户端广播代理命令");
        VELOCITY_LABELS.put("log-command-executions",              "记录命令执行日志");
        VELOCITY_LABELS.put("log-player-connections",              "记录玩家连接/切服/断开日志");
        VELOCITY_LABELS.put("accepts-transfers",                   "接受来自其他代理的 transfer");
        VELOCITY_LABELS.put("enable-plugin-scheduler",             "启用插件调度器");
        VELOCITY_LABELS.put("enable-reuse-port",                   "启用端口复用（SO_REUSEPORT）");
        VELOCITY_LABELS.put("command-rate-limit",                  "命令速率限制（毫秒）");
        VELOCITY_LABELS.put("forward-commands-if-rate-limited",    "速率超限时仍转发命令");
        VELOCITY_LABELS.put("kick-after-rate-limited-commands",    "速率超限命令数达到后踢出");
        VELOCITY_LABELS.put("tab-complete-rate-limit",             "Tab 补全速率限制（毫秒）");
        VELOCITY_LABELS.put("kick-after-rate-limited-tab-completes","速率超限补全数达到后踢出");
        VELOCITY_LABELS.put("interval",                            "统计间隔（秒）");
        VELOCITY_LABELS.put("packets-per-second",                  "每秒允许的数据包数（-1=不限）");
        VELOCITY_LABELS.put("bytes-per-second",                    "每秒允许的字节数（-1=不限）");
        VELOCITY_LABELS.put("decompressed-bytes-per-second",       "每秒允许的解压后字节数");
        VELOCITY_LABELS.put("version",                             "透传后端版本号");
        VELOCITY_LABELS.put("players",                             "透传在线人数");
        VELOCITY_LABELS.put("description",                         "透传服务器描述 (MOTD)");
        VELOCITY_LABELS.put("favicon",                             "透传服务器图标");
        VELOCITY_LABELS.put("modinfo",                             "透传 Mod 列表信息");
        VELOCITY_LABELS.put("enabled",                             "启用 Query 协议");
        VELOCITY_LABELS.put("port",                                "Query 端口");
        VELOCITY_LABELS.put("host",                                "Query 绑定地址（留空监听所有）");
        VELOCITY_LABELS.put("map",                                 "Query 显示的地图名");
        VELOCITY_LABELS.put("show-plugins",                        "Query 响应中显示插件列表");
    }

    private static final Map<String, String> MC_LABELS = new HashMap<>();
    static {
        MC_LABELS.put("accepts-transfers", "接受来自其他服务器的 transfer");
        MC_LABELS.put("allow-flight", "允许飞行（生存模式防作弊）");
        MC_LABELS.put("allow-nether", "允许玩家进入下界");
        MC_LABELS.put("broadcast-console-to-ops", "将控制台命令广播给 OP");
        MC_LABELS.put("broadcast-rcon-to-ops", "将 RCON 命令广播给 OP");
        MC_LABELS.put("bug-report-link", "服务器 Bug 反馈链接");
        MC_LABELS.put("chat-spam-threshold-seconds", "聊天刷屏阈值（秒）");
        MC_LABELS.put("command-spam-threshold-seconds", "指令刷屏阈值（秒）");
        MC_LABELS.put("difficulty", "游戏难度（peaceful/easy/normal/hard）");
        MC_LABELS.put("enable-code-of-conduct", "启用行为准则提示");        MC_LABELS.put("enable-command-block", "启用命令方块");
        MC_LABELS.put("log-ips", "在日志中记录玩家 IP");
        MC_LABELS.put("enable-jmx-monitoring", "启用 JMX 监控");
        MC_LABELS.put("enable-query", "启用 Query 协议（GameSpy4）");
        MC_LABELS.put("enable-rcon", "启用 RCON 远程控制");
        MC_LABELS.put("enable-status", "启用服务器状态查询");
        MC_LABELS.put("enforce-secure-profile", "强制安全签名验证");
        MC_LABELS.put("enforce-whitelist", "强制白名单（不在名单直接踢出）");
        MC_LABELS.put("entity-broadcast-range-percentage", "实体广播范围百分比");
        MC_LABELS.put("force-gamemode", "强制玩家使用默认游戏模式");
        MC_LABELS.put("function-permission-level", "函数命令权限等级（1-4）");
        MC_LABELS.put("gamemode", "默认游戏模式（survival/creative）");
        MC_LABELS.put("generate-structures", "生成结构（村庄/遗迹等）");
        MC_LABELS.put("generator-settings", "自定义世界生成设置（JSON）");
        MC_LABELS.put("hardcore", "极限模式（死亡后旁观）");
        MC_LABELS.put("hide-online-players", "隐藏在线的玩家列表");
        MC_LABELS.put("initial-disabled-packs", "初始禁用的数据包");
        MC_LABELS.put("initial-enabled-packs", "初始启用的数据包");
        MC_LABELS.put("level-name", "世界文件夹名称");
        MC_LABELS.put("level-seed", "世界种子");
        MC_LABELS.put("level-type", "世界类型（normal/flat/large_biomes）");
        MC_LABELS.put("max-chained-neighbor-updates", "最大链式邻居更新数");
        MC_LABELS.put("max-entity-collisions", "最大实体碰撞次数");
        MC_LABELS.put("max-players", "最大玩家数");
        MC_LABELS.put("max-tick-time", "最大单次 Tick 耗时（毫秒）");
        MC_LABELS.put("max-world-size", "最大世界边界半径");
        MC_LABELS.put("motd", "服务器列表标语（MOTD）");
        MC_LABELS.put("network-compression-threshold", "网络数据包压缩阈值");
        MC_LABELS.put("online-mode", "正版验证（Mojang 登录）");
        MC_LABELS.put("op-permission-level", "OP 权限等级（1-4）");
        MC_LABELS.put("pause-when-empty-seconds", "无人时暂停服务器（秒）");
        MC_LABELS.put("player-idle-timeout", "挂机踢出时间（分钟，0=禁用）");
        MC_LABELS.put("prevent-proxy-connections", "阻止代理/VPN 连接");
        MC_LABELS.put("pvp", "允许玩家对战（PVP）");
        MC_LABELS.put("query.port", "Query 协议端口");
        MC_LABELS.put("rate-limit", "全局封包速率限制（0=禁用）");
        MC_LABELS.put("rcon.password", "RCON 密码");
        MC_LABELS.put("rcon.port", "RCON 监听端口");
        MC_LABELS.put("region-file-compression", "区域文件压缩算法");
        MC_LABELS.put("require-resource-pack", "强制使用资源包");
        MC_LABELS.put("resource-pack", "资源包下载链接");
        MC_LABELS.put("resource-pack-id", "资源包 UUID");
        MC_LABELS.put("resource-pack-prompt", "资源包提示信息");
        MC_LABELS.put("resource-pack-sha1", "资源包 SHA1 校验");
        MC_LABELS.put("server-ip", "服务器绑定 IP（留空为所有）");
        MC_LABELS.put("server-port", "服务器监听端口");
        MC_LABELS.put("simulation-distance", "模拟距离（区块）");
        MC_LABELS.put("spawn-animals", "生成被动动物");
        MC_LABELS.put("spawn-monsters", "生成敌对怪物");
        MC_LABELS.put("spawn-npcs", "生成村民/NPC");
        MC_LABELS.put("spawn-protection", "出生点保护半径（0=禁用）");
        MC_LABELS.put("sync-chunk-writes", "同步区块写入");
        MC_LABELS.put("text-filtering-config", "文本过滤配置");
        MC_LABELS.put("use-native-transport", "使用 Linux 原生网络传输");
        MC_LABELS.put("view-distance", "服务器视距（区块）");
        MC_LABELS.put("white-list", "启用白名单");
        MC_LABELS.put("status-heartbeat-interval", "状态心跳间隔（秒）");
        MC_LABELS.put("text-filtering-version", "文本过滤规则版本");
        MC_LABELS.put("management-server-allowed-origins", "管理服务器允许的跨域来源");
        MC_LABELS.put("management-server-enabled", "启用管理服务器 API");
        MC_LABELS.put("management-server-host", "管理服务器绑定地址");
        MC_LABELS.put("management-server-port", "管理服务器监听端口");
        MC_LABELS.put("management-server-secret", "管理服务器访问密钥");
        MC_LABELS.put("management-server-tls-enabled", "管理服务器启用 TLS 加密");
        MC_LABELS.put("management-server-tls-keystore", "管理服务器 TLS 证书库路径");
        MC_LABELS.put("management-server-tls-keystore-password", "管理服务器 TLS 证书库密码");
        MC_LABELS.put("region-file-compression", "区域文件压缩算法");
        MC_LABELS.put("enable-code-of-conduct", "启用行为准则提示");
        MC_LABELS.put("enable-command-block", "启用命令方块");
        MC_LABELS.put("log-ips", "在日志中记录玩家 IP");
        MC_LABELS.put("pause-when-empty-seconds", "无人时暂停服务器（秒）");
        MC_LABELS.put("max-entity-collisions", "最大实体碰撞次数");
    }

    private static final Map<String, String> VELOCITY_SECTION_LABELS = new HashMap<>();
    static {
        VELOCITY_SECTION_LABELS.put("",                 "基础");
        VELOCITY_SECTION_LABELS.put("servers",          "服务器列表");
        VELOCITY_SECTION_LABELS.put("forced-hosts",     "强制主机（域名 → 服务器）");
        VELOCITY_SECTION_LABELS.put("advanced",         "高级设置");
        VELOCITY_SECTION_LABELS.put("packet-limiter",   "数据包速率限制");
        VELOCITY_SECTION_LABELS.put("ping-passthrough", "Ping 透传");
        VELOCITY_SECTION_LABELS.put("query",            "Query 协议");
    }

    public ServerPropsHandler(ConfigManager config) {
        this.config = config;
    }

    private boolean isVelocity() {
        return "velocity".equals(config.get("_instance.type", "mc"));
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        if (path.equals("/api/serverprops/type")) {
            handleType(exchange);
            return;
        }

        if (isVelocity()) {
            if (path.equals("/api/serverprops/get")) { handleVelocityGet(exchange); return; }
            if (path.equals("/api/serverprops/set")) { handleVelocitySet(exchange); return; }
        } else {
            if (path.equals("/api/serverprops/get"))  { handleMcGet(exchange); return; }
            if (path.equals("/api/serverprops/set"))  { handleMcSet(exchange); return; }
            if (path.equals("/api/serverprops/eula")) { handleEula(exchange); return; }
        }

        exchange.setStatusCode(404);
        exchange.getResponseSender().send("{\"error\":\"unknown\"}");
    }

    private void handleType(HttpServerExchange exchange) {
        try {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", isVelocity() ? "velocity" : "mc");
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(r));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private Path velocityTomlPath() {
        String dir = config.get("velocity.server.dir", "server");
        return config.resolve(dir).toPath().toAbsolutePath().normalize().resolve("velocity.toml");
    }

    private void handleVelocityGet(HttpServerExchange exchange) {
        try {
            Path f = velocityTomlPath();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", "velocity");
            r.put("path", f.toString());

            if (!Files.exists(f)) {
                r.put("exists", false);
                r.put("sections", new ArrayList<>());
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                exchange.getResponseSender().send(MAPPER.writeValueAsString(r));
                return;
            }

            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            Map<String, Map<String, Object>> sectionMap = new LinkedHashMap<>();
            sectionMap.put("", newVelocitySection("", "基础"));

            String currentSection = "";
            for (int i = 0; i < lines.size(); i++) {
                int lineNo = i + 1;
                String raw = lines.get(i);
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    String secName = trimmed.substring(1, trimmed.length() - 1).trim();
                    if ((secName.startsWith("\"") && secName.endsWith("\"")) || (secName.startsWith("'") && secName.endsWith("'"))) {
                        secName = secName.substring(1, secName.length() - 1);
                    }
                    currentSection = secName;
                    if (!sectionMap.containsKey(secName)) {
                        sectionMap.put(secName, newVelocitySection(secName, velocitySectionLabel(secName)));
                    }
                    continue;
                }

                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;

                String key = trimmed.substring(0, eq).trim();
                if ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))) {
                    key = key.substring(1, key.length() - 1);
                }
                key = key.replace("[", "").replace("]", "").trim();

                String rawValue = trimmed.substring(eq + 1).trim();
                
                if (rawValue.startsWith("[") && !rawValue.endsWith("]")) {
                    while (!rawValue.endsWith("]") && i + 1 < lines.size()) {
                        i++;
                        rawValue += " " + lines.get(i).trim();
                    }
                }

                boolean isArr = rawValue.startsWith("[");
                String displayValue = isArr ? unwrapTomlArray(rawValue) : unwrapTomlString(rawValue);
                String type = isArr ? "array" : velocityValueType(rawValue);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", key);
                item.put("value", displayValue);
                item.put("rawValue", rawValue);
                item.put("line", lineNo);
                item.put("type", type);
                item.put("label", velocityKeyLabel(key, currentSection));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) sectionMap.get(currentSection).get("items");
                items.add(item);
            }

            if (((List<?>) sectionMap.get("").get("items")).isEmpty()) sectionMap.remove("");

            r.put("exists", true);
            r.put("sections", new ArrayList<>(sectionMap.values()));

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(r));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleVelocitySet(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                try {
                    JsonNode root = MAPPER.readTree(body == null ? "{}" : body);
                    Path f = velocityTomlPath();

                    if (!Files.exists(f)) {
                        ex.setStatusCode(400);
                        ex.getResponseSender().send("{\"error\":\"velocity.toml 不存在\"}");
                        return;
                    }

                    List<String> rawLines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    List<String> lines = new ArrayList<>();
                
                    for (int i = 0; i < rawLines.size(); i++) {
                        String raw = rawLines.get(i);
                        String t = raw.trim();
                    
                        if (t.contains("=") && t.contains("[") && !t.endsWith("]")) {
                            StringBuilder sb = new StringBuilder(raw);
                            i++;
                            while (i < rawLines.size()) {
                                String next = rawLines.get(i).trim();
                                sb.append(" ").append(next);
                                if (next.endsWith("]")) break;
                                i++;
                            }
                            lines.add(sb.toString());
                        } else if (!t.isEmpty() && !t.contains("=") && !t.startsWith("[") && !t.startsWith("#")) {
                            continue; 
                        } else {
                            lines.add(raw);
                        }
                    }

                    int changed = 0;
                    JsonNode items = root.get("items");
                    if (items != null && items.isArray()) {
                        for (JsonNode it : items) {
                            if (!it.has("line") || !it.has("value")) continue;
                            int lineNo = it.get("line").asInt();
                            String newValue = it.get("value").asText();
                            if (lineNo < 1 || lineNo > lines.size()) continue;

                            String orig = lines.get(lineNo - 1);
                            int eq = orig.indexOf('=');
                            if (eq < 0) continue;

                            String oldRaw = orig.substring(eq + 1).trim();
                            String type = oldRaw.startsWith("[") ? "array" : velocityValueType(oldRaw);
                            String prefix = orig.substring(0, eq + 1);
                            String formatted = formatTomlValue(newValue, type);
                            lines.set(lineNo - 1, prefix + " " + formatted);
                            changed++;
                        }
                    }

                    JsonNode deletes = root.get("deletes");
                    if (deletes != null && deletes.isArray()) {
                        List<Integer> delLines = new ArrayList<>();
                        for (JsonNode d : deletes) {
                            if (d.has("line")) delLines.add(d.get("line").asInt());
                        }
                        delLines.sort(Collections.reverseOrder());
                        for (int lineNo : delLines) {
                            if (lineNo >= 1 && lineNo <= lines.size()) {
                                lines.remove(lineNo - 1);
                                changed++;
                            }
                        }
                    }

                    JsonNode adds = root.get("adds");
                    if (adds != null && adds.isArray()) {
                        for (JsonNode a : adds) {
                            String sec = a.path("section").asText("");
                            String key = a.path("key").asText("");
                            key = key.replace("[", "").replace("]", "").trim();
                            String val = a.path("value").asText("");
                            if (key.isEmpty()) continue;
                        
                            int secLine = -1;
                            if (!sec.isEmpty()) {
                                for (int i = 0; i < lines.size(); i++) {
                                    String t = lines.get(i).trim();
                                    if (t.startsWith("[") && t.endsWith("]")) {
                                        String sName = t.substring(1, t.length() - 1).trim();
                                        if ((sName.startsWith("\"") && sName.endsWith("\"")) || (sName.startsWith("'") && sName.endsWith("'"))) {
                                            sName = sName.substring(1, sName.length() - 1);
                                        }
                                        if (sName.equals(sec)) { secLine = i; break; }
                                    }
                                }
                            }
                        
                            String valType = "try".equals(key) ? "array" : "string";
                            String newLine = key + " = " + formatTomlValue(val, valType);
                        
                            if (secLine >= 0) {
                                int insertAt = lines.size();
                                for (int i = secLine + 1; i < lines.size(); i++) {
                                    String t = lines.get(i).trim();
                                    if (t.startsWith("[") && t.endsWith("]")) { insertAt = i; break; }
                                }
                                lines.add(insertAt, newLine);
                            } else {
                                if (lines.size() > 0 && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
                                lines.add("[" + sec + "]");
                                lines.add(newLine);
                            }
                            changed++;
                        }
                    }

                    // 终极防呆：清理无效服务器
                    java.util.Set<String> validServers = new java.util.HashSet<>();
                    boolean inServers = false;
                    for (String line : lines) {
                        String t = line.trim();
                        if (t.startsWith("[") && t.endsWith("]")) {
                            inServers = "servers".equals(t.substring(1, t.length() - 1).replace("\"", "").replace("'", "").trim());
                            continue;
                        }
                        if (inServers && !t.isEmpty() && !t.startsWith("#")) {
                            int eq = t.indexOf('=');
                            if (eq > 0) {
                                String k = t.substring(0, eq).trim().replace("\"", "").replace("'", "").replace("[", "").replace("]", "");
                                if (!"try".equals(k)) validServers.add(k);
                            }
                        }
                    }

                    boolean inForcedHosts = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String t = line.trim();
                        if (t.startsWith("[") && t.endsWith("]")) {
                            String sec = t.substring(1, t.length() - 1).replace("\"", "").replace("'", "").trim();
                            inForcedHosts = "forced-hosts".equals(sec);
                            continue;
                        }
                        int eq = t.indexOf('=');
                        if (eq > 0 && !t.startsWith("#")) {
                            String key = t.substring(0, eq).trim().replace("\"", "").replace("'", "").replace("[", "").replace("]", "");
                            boolean isTry = "try".equals(key) && !inForcedHosts;
                            if (isTry || inForcedHosts) {
                                String val = t.substring(eq + 1).trim();
                                String cleaned = val;
                                if (cleaned.startsWith("[")) cleaned = cleaned.substring(1);
                                if (cleaned.endsWith("]")) cleaned = cleaned.substring(0, cleaned.length() - 1);
                                String[] parts = cleaned.split(",");
                                List<String> kept = new ArrayList<>();
                                for (String p : parts) {
                                    String s = p.trim().replace("\"", "").replace("'", "");
                                    if (!s.isEmpty() && validServers.contains(s)) {
                                        kept.add(s);
                                    }
                                }
                                if (kept.isEmpty() && inForcedHosts) {
                                    lines.remove(i);
                                    i--;
                                } else {
                                    String joined = kept.stream().map(x -> "\"" + x + "\"").collect(java.util.stream.Collectors.joining(", "));
                                    String newVal = "[" + joined + "]";
                                    String prefix = line.substring(0, eq + 1);
                                    lines.set(i, prefix + " " + newVal);
                                }
                            }
                        }
                    }

                    Files.write(f, lines, StandardCharsets.UTF_8);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ok", true);
                    r.put("changed", changed);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(r));
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"" + esc(e.getMessage()) + "\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> newVelocitySection(String name, String label) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("label", label);
        s.put("items", new ArrayList<Map<String, Object>>());
        return s;
    }

    private static String velocitySectionLabel(String name) {
        String l = VELOCITY_SECTION_LABELS.get(name);
        return l != null ? l : name;
    }

    private static String velocityKeyLabel(String key, String section) {
        if ("query".equals(section)) {
            if ("enabled".equals(key))      return "启用 Query 协议";
            if ("port".equals(key))         return "Query 端口";
            if ("host".equals(key))         return "Query 绑定地址（留空监听所有）";
            if ("map".equals(key))          return "Query 显示的地图名";
            if ("show-plugins".equals(key)) return "Query 响应中显示插件列表";
        }
        if ("ping-passthrough".equals(section)) {
            if ("version".equals(key))     return "透传后端版本号";
            if ("players".equals(key))     return "透传在线人数";
            if ("description".equals(key)) return "透传服务器描述 (MOTD)";
            if ("favicon".equals(key))     return "透传服务器图标";
            if ("modinfo".equals(key))     return "透传 Mod 列表信息";
        }
        String l = VELOCITY_LABELS.get(key);
        if (l != null) return l;
        if ("servers".equals(section) && !"try".equals(key)) return key;
        if ("forced-hosts".equals(section)) return "域名：" + key;
        return key;
    }

    private static String unwrapTomlArray(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            String inner = raw.substring(1, raw.length() - 1).trim();
            if (inner.isEmpty()) return "";
            String[] parts = inner.split(",");
            List<String> res = new ArrayList<>();
            for (String p : parts) {
                String clean = p.trim().replaceAll("^[\"']|[\"']$", "");
                if (!clean.isEmpty()) res.add(clean);
            }
            return String.join(", ", res);
        }
        return raw;
    }

    private static String unwrapTomlString(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            char last = raw.charAt(raw.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String velocityValueType(String raw) {
        if (raw == null) return "string";
        raw = raw.trim();
        if (raw.equals("true") || raw.equals("false")) return "bool";
        if (raw.startsWith("\"") || raw.startsWith("'")) return "string";
        if (raw.startsWith("[")) return "array";
        if (raw.startsWith("{")) return "string";
        try { Long.parseLong(raw); return "int"; } catch (NumberFormatException ignored) {}
        try { Double.parseDouble(raw); return "float"; } catch (NumberFormatException ignored) {}
        return "string";
    }

    private static String formatTomlValue(String value, String type) {
        if (value == null) value = "";
        value = value.trim();

        if ("bool".equals(type)) {
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("1") || value.equalsIgnoreCase("yes")) return "true";
            return "false";
        }
        if ("int".equals(type)) {
            if (value.isEmpty()) return "0";
            if (!value.matches("-?\\d+")) return "0";
            return value;
        }
        if ("float".equals(type)) {
            if (value.isEmpty()) return "0.0";
            if (!value.matches("-?\\d+(\\.\\d+)?")) return "0.0";
            return value;
        }
        if ("array".equals(type)) {
            String cleaned = value.trim();
            if (cleaned.startsWith("[")) cleaned = cleaned.substring(1);
            if (cleaned.endsWith("]")) cleaned = cleaned.substring(0, cleaned.length() - 1);
            String[] parts = cleaned.split(",");
            List<String> formatted = new ArrayList<>();
            for (String p : parts) {
                String t = p.trim().replace("\"", "").replace("'", "");
                if (!t.isEmpty()) formatted.add(quoteTomlString(t));
            }
            return "[" + String.join(", ", formatted) + "]";
        }
        return quoteTomlString(value);
    }

    private static String quoteTomlString(String s) {
        if (s == null) s = "";
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        return "\"" + esc + "\"";
    }

    private Path serverDir() {
        String serverDirName = config.get("mc.server.dir", "server");
        return config.resolve(serverDirName).toPath().toAbsolutePath().normalize();
    }

    private Path propsPath() { return serverDir().resolve("server.properties"); }
    private Path eulaPath()  { return serverDir().resolve("eula.txt"); }

    private void handleMcGet(HttpServerExchange exchange) {
        try {
            String user = exchange.getAttachment(com.mcpanel.web.AuthHandler.USER_ATTRIBUTE);
            boolean isMaster = com.mcpanel.config.UserStore.getInstance().isMaster(user);
            Map<String, Object> r = new LinkedHashMap<>();
            Path sp = propsPath();
            List<Map<String, String>> items = new ArrayList<>();
            if (Files.exists(sp)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(sp)) { props.load(in); }
                TreeSet<String> sorted = new TreeSet<>(props.stringPropertyNames());
                for (String k : sorted) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("key", k);
                    String val = props.getProperty(k, "");
                    if (!isMaster && (k.contains("password") || k.contains("secret"))) val = "********";
                    item.put("value", val);
                    item.put("type", mcValueType(k, props.getProperty(k, "")));
                    String label = MC_LABELS.get(k);
                    if (label == null) {
                        label = k.replace("-", " ").replace("_", " ");
                        if (label.length() > 0) label = label.substring(0, 1).toUpperCase() + label.substring(1);
                    }
                    item.put("label", label);
                    items.add(item);
                }
                r.put("exists", true);
            } else {
                r.put("exists", false);
            }
            r.put("items", items);

            Path eula = eulaPath();
            Map<String, Object> eulaInfo = new LinkedHashMap<>();
            if (Files.exists(eula)) {
                eulaInfo.put("exists", true);
                eulaInfo.put("agreed", readEula());
            } else {
                eulaInfo.put("exists", false);
                eulaInfo.put("agreed", false);
            }
            r.put("eula", eulaInfo);

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(MAPPER.writeValueAsString(r));
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private static String mcValueType(String key, String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "bool";
        if (key != null && (key.indexOf("port") >= 0 || key.indexOf("distance") >= 0 || key.indexOf("max") >= 0 || key.indexOf("size") >= 0 || key.indexOf("protection") >= 0 || key.indexOf("timeout") >= 0 || key.indexOf("threshold") >= 0 || key.indexOf("rate") >= 0 || key.indexOf("percentage") >= 0 || key.indexOf("tick") >= 0 || key.indexOf("seconds") >= 0 || key.indexOf("collisions") >= 0)) {
            return "int";
        }
        return "string";
    }

    private boolean readEula() {
        try {
            Path eula = eulaPath();
            if (!Files.exists(eula)) return false;
            for (String line : Files.readAllLines(eula, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("eula=")) return "true".equalsIgnoreCase(s.substring(5).trim());
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void handleMcSet(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                try {
                    JsonNode root = MAPPER.readTree(body == null ? "{}" : body);
                    Path sp = propsPath();
                    Path dir = serverDir();
                    if (!Files.exists(dir)) {
                        ex.setStatusCode(400);
                        ex.getResponseSender().send("{\"error\":\"服务端目录不存在\"}");
                        return;
                    }

                    Properties props = new Properties();
                    if (Files.exists(sp)) {
                        try (InputStream in = Files.newInputStream(sp)) { props.load(in); }
                    }

                    int changed = 0;
                    if (root.has("items") && root.get("items").isArray()) {
                        String user = exchange.getAttachment(AuthHandler.USER_ATTRIBUTE);
                        boolean isMaster = UserStore.getInstance().isMaster(user);
                        for (JsonNode it : root.get("items")) {
                            if (!it.has("key")) continue;
                            String k = it.get("key").asText();
                            String v = it.has("value") ? it.get("value").asText() : "";
                            if (k == null || k.isEmpty()) continue;
                            if (!isMaster && (k.contains("password") || k.contains("secret") || k.contains("rcon") || k.contains("management-server"))) {
                                ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"only master can modify sensitive properties\"}"); return;
                            }
                            props.setProperty(k, v);
                            changed++;
                        }
                    } else if (root.isObject()) {
                        Iterator<String> names = root.fieldNames();
                        while (names.hasNext()) {
                            String k = names.next();
                            if ("items".equals(k)) continue;
                            JsonNode v = root.get(k);
                            String val = v.isNull() ? "" : v.asText();
                            props.setProperty(k, val);
                            changed++;
                        }
                    }

                    try (OutputStream out = Files.newOutputStream(sp)) { props.store(out, "Minecraft server properties"); }

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ok", true);
                    r.put("changed", changed);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(r));
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"" + esc(e.getMessage()) + "\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private void handleEula(HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                try {
                    Map<String, String> p = MAPPER.readValue(body, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                    String agreed = p.get("agreed");
                    boolean val = "true".equalsIgnoreCase(agreed);

                    Path dir = serverDir();
                    if (!Files.exists(dir)) Files.createDirectories(dir);

                    Path eula = eulaPath();
                    StringBuilder sb = new StringBuilder();
                    sb.append("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n");
                    sb.append("eula=").append(val).append("\n");
                    Files.write(eula, sb.toString().getBytes(StandardCharsets.UTF_8));

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ok", true);
                    r.put("agreed", val);
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(r));
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"" + esc(e.getMessage()) + "\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
