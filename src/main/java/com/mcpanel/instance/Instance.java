package com.mcpanel.instance;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class Instance {
    public String id;
    public String name;
    public String desc;
    public String bg;
    public String type;
    public long createdAt;

    public String sysUser;
    public String sysPass;

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    public File getDir()            { return new File("instances", id); }
    public File getMetaFile()       { return new File(getDir(), "meta.json"); }
    public File getConfigFile()     { return new File(getDir(), "config.properties"); }
    public File getServerDir()      { return new File(getDir(), "server"); }
    public File getBackupDir()      { return new File(getDir(), "backup"); }
    public File getPluginsDir()     { return new File(getDir(), "plugins"); }
    public File getPluginsDataDir() { return new File(getDir(), "plugins-data"); }
    public File getLogsDir()        { return new File(getDir(), "logs"); }
    public File getPermsFile()      { return new File(getDir(), "mcperms.json"); }
    public File getBgFile() {
        if (bg == null) return null;
        try {
            java.io.File dir = getDir().getCanonicalFile();
            java.io.File f = new java.io.File(dir, bg).getCanonicalFile();
            if (!f.toPath().startsWith(dir.toPath())) return null;
            return f;
        } catch (Exception e) { return null; }
    }

    public boolean isVelocity() { return "velocity".equalsIgnoreCase(type); }
    public boolean isMc() { return !isVelocity(); }

    public void loadMeta() {
        File f = getMetaFile();
        if (!f.exists()) {
            if (name == null) name = id;
            if (desc == null) desc = "";
            return;
        }
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            String json = new String(data, StandardCharsets.UTF_8);
            Instance loaded = MAPPER.readValue(json, Instance.class);
            if (loaded.name != null) this.name = loaded.name;
            if (loaded.desc != null) this.desc = loaded.desc;
            if (loaded.type != null) this.type = loaded.type;
            this.bg = loaded.bg;
            this.createdAt = loaded.createdAt;
            this.sysUser = loaded.sysUser;
            this.sysPass = loaded.sysPass;
        } catch (Exception e) {
            System.err.println("[Instance] 读取 " + f.getPath() + " 失败: " + e.getMessage());
            if (name == null) name = id;
            if (desc == null) desc = "";
        }
    }

    public void saveMeta() {
        try {
            MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
            getDir().mkdirs();
            String json = MAPPER.writeValueAsString(this);
            Files.write(getMetaFile().toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Instance] 写入 " + getMetaFile().getPath() + " 失败: " + e.getMessage());
        }
    }
}