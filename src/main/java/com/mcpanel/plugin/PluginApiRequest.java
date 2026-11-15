package com.mcpanel.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class PluginApiRequest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String method;
    public String path;
    public Map<String, String> query = new HashMap<>();
    public Map<String, String> headers = new HashMap<>();
    public String body;
    public String username;
    public String instanceId;

    /** 把 body 解析成 JSON；失败返回 null。 */
    public JsonNode json() {
        if (body == null || body.isEmpty()) return null;
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    public String queryParam(String key, String def) {
        String v = query.get(key);
        return v == null ? def : v;
    }

    public String header(String name) {
        if (name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}