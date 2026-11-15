package com.mcpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.util.Crypto;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class SmtpConfig {
    private static SmtpConfig instance;
    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class Data {
        public String host = "";
        public String port = "465";
        public String user = "";
        public String passEnc = ""; // 仅存密文
        public String from = "";
        public String ssl = "ssl";
    }

    private Data data = new Data();

    public static synchronized SmtpConfig getInstance() {
        if (instance == null) {
            instance = new SmtpConfig();
        }
        return instance;
    }

    private SmtpConfig() {
        this.file = new File(ConfigManager.getInstance().getRootDir(), "data/smtp.json");
    }

    public synchronized void init() {
        // 1. 优先从独立文件读取
        if (file.exists()) {
            try {
                data = mapper.readValue(file, Data.class);
            } catch (Exception ignored) {}
        }
        
        // 2. 自动迁移：检查 config 中是否有旧的明文 SMTP 配置
        ConfigManager cm = ConfigManager.getInstance();
        if (cm.has("smtp.host")) {
            data.host = cm.get("smtp.host", "");
            data.port = cm.get("smtp.port", "465");
            data.ssl = cm.get("smtp.ssl", "ssl");
            data.from = cm.get("smtp.from", "");
            data.user = cm.get("smtp.user", "");
            
            String oldPass = cm.get("smtp.pass", "");
            if (!oldPass.isEmpty()) {
                // 尝试判断是否已经加密（如果 base64 解开后长度合理就认为加密过）
                boolean isEncrypted = false;
                try {
                    String dec = Crypto.decrypt(oldPass);
                    if (dec != null && !dec.isEmpty()) isEncrypted = true;
                } catch (Exception ignored) {}
                
                if (isEncrypted) {
                    data.passEnc = oldPass;
                } else {
                    data.passEnc = Crypto.encrypt(oldPass);
                }
            }
            
            save();
            
            // ★ 核心：从 config 中彻底删除相关敏感配置
            cm.remove("smtp.host");
            cm.remove("smtp.port");
            cm.remove("smtp.ssl");
            cm.remove("smtp.from");
            cm.remove("smtp.user");
            cm.remove("smtp.pass");
            System.out.println("[SMTP] 已将明文配置迁移至独立加密文件 smtp.json 并从 config 中移除");
        }
    }

    public synchronized void save() {
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            mapper.writeValue(tmp, data);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                file.setReadable(false, false);
                file.setReadable(true, true);
                file.setWritable(false, false);
                file.setWritable(true, true);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("[SMTP] 写入 smtp.json 失败: " + e.getMessage());
        }
    }

    public synchronized Data get() {
        return data;
    }

    public synchronized String getDecryptedPass() {
        if (data.passEnc == null || data.passEnc.isEmpty()) return "";
        try {
            return Crypto.decrypt(data.passEnc);
        } catch (Exception e) {
            return data.passEnc; // 兼容历史明文
        }
    }

    public synchronized void update(String host, String port, String ssl, String from, String user, String plainPass) {
        data.host = host;
        data.port = port;
        data.ssl = ssl;
        data.from = from;
        data.user = user;
        if (plainPass != null && !plainPass.isEmpty()) {
            data.passEnc = Crypto.encrypt(plainPass);
        }
        save();
    }
}