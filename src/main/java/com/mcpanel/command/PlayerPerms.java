package com.mcpanel.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerPerms {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** key = rootDir 的规范路径；相同 rootDir 返回同一对象 */
    private static final ConcurrentHashMap<String, PlayerPerms> instances = new ConcurrentHashMap<>();

    private final File file;
    private final Map<String, Integer> perms = new ConcurrentHashMap<>();
    // ★ 默认等级：USER(1)
    private int defaultLevel = PlayerLevel.USER.level;

    /** 按 rootDir 取实例，相同 rootDir 返回同一对象。第一次访问时自动 load */
    public static PlayerPerms forRoot(File rootDir) {
        String key;
        try {
            key = rootDir.getCanonicalPath();
        } catch (IOException e) {
            key = rootDir.getAbsolutePath();
        }
        final String fkey = key;
        return instances.computeIfAbsent(fkey, k -> {
            PlayerPerms p = new PlayerPerms(new File(rootDir, "mcperms.json"));
            p.load();
            return p;
        });
    }

    /** 兼容旧代码：指向进程 CWD 下的 mcperms.json */
    public static PlayerPerms getInstance() {
        return forRoot(new File("."));
    }

    private PlayerPerms(File file) {
        this.file = file;
    }

    public synchronized void load() {
        if (!file.exists()) return;
        try {
            Data data = MAPPER.readValue(file, Data.class);
            if (data == null) return;
            if (data.perms != null) {
                perms.clear();
                perms.putAll(data.perms);
            }
            defaultLevel = data.defaultLevel;
            System.out.println("[Panel] 已加载 " + perms.size() + " 名玩家的权限 ("
                    + file.getPath() + ")，默认等级=" + PlayerLevel.fromInt(defaultLevel).label);
        } catch (Exception e) {
            System.err.println("[Panel] 读取 " + file.getPath() + " 失败: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
            Data d = new Data();
            d.perms = new HashMap<>(perms);
            d.defaultLevel = defaultLevel;
            MAPPER.writeValue(file, d);
        } catch (Exception e) {
            System.err.println("[Panel] 写入 " + file.getPath() + " 失败: " + e.getMessage());
        }
    }

    public synchronized PlayerLevel getLevel(String player) {
        Integer n = perms.get(player);
        if (n == null) return PlayerLevel.fromInt(defaultLevel);
        return PlayerLevel.fromInt(n);
    }

    public synchronized void setLevel(String player, PlayerLevel level) {
        perms.put(player, level.level);
        save();
    }

    public synchronized void remove(String player) {
        perms.remove(player);
        save();
    }

    public synchronized Map<String, Integer> all() {
        return new HashMap<>(perms);
    }

    public synchronized int getDefaultLevel() { return defaultLevel; }
    public synchronized void setDefaultLevel(int lv) {
        defaultLevel = lv;
        save();
    }

    public static class Data {
        public Map<String, Integer> perms = new HashMap<>();
        // ★ 默认等级：USER(1)
        public int defaultLevel = PlayerLevel.USER.level;
    }
}