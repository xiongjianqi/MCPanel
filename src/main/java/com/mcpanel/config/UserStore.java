package com.mcpanel.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mcpanel.util.Crypto;

import java.io.File;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class UserStore {
    // ★ 核心修复：忽略未知字段，防止旧数据中的 "nodes" 字段导致解析失败
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            
    private static final String FILE = "users.json";
    private static UserStore instance;

    public static final List<String> ALL_PERMS = Collections.unmodifiableList(Arrays.asList(
        "terminal", "files", "files_all", "backup",
        "instance_create", "instance_edit", "instance_delete",
        "config", "users", "plugins", "share", "panel_log", "java"
    ));

    public static final String PLUGIN_USER = "__plugin__";

    private static final Set<String> RESERVED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        PLUGIN_USER, "web", "console", "admin", "root", "share", "system")));

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        public String username;
        public String passwordHash;
        public String email;
        public String apiKeyEnc;
        public Map<String, List<String>> instancePerms = new HashMap<>();
        public List<String> perms;
        public List<String> instances;
        public boolean needsPasswordChange = false;
        public String totpSecret;
        public String recoveryHash;
        public boolean needsRecoverySetup = false;
        public String twoFactorMethod = "none";

        public User() {}
        public User(String u, String pwHash) {
            this.username = u;
            this.passwordHash = pwHash;
            this.apiKeyEnc = "";
            this.perms = new ArrayList<>();
            this.instances = new ArrayList<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        public User master;
        public List<User> subUsers = new ArrayList<>();
    }

    private Data data;
    private String initialGeneratedPassword = null;
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InviteInfo {
        public String email;
        public List<String> perms;
        public List<String> instances;
        public long expireAt;
    }
    public final Map<String, InviteInfo> pendingInvites = new java.util.concurrent.ConcurrentHashMap<>();

    public static synchronized UserStore getInstance() {
        if (instance == null) instance = new UserStore();
        return instance;
    }

    public synchronized void load() {
        File f = new File(FILE);
        if (!f.exists()) { data = new Data(); return; }
        try {
            data = MAPPER.readValue(f, Data.class);
            if (data == null) data = new Data();
            data.subUsers = new CopyOnWriteArrayList<>(
                    data.subUsers == null ? Collections.<User>emptyList() : data.subUsers);
            for (User u : data.subUsers) {
                if (u.instances == null) u.instances = new ArrayList<>();
                if (u.perms == null) u.perms = new ArrayList<>();
                if (u.instancePerms == null) u.instancePerms = new HashMap<>();
            }
            if (data.master != null) {
                if (data.master.instances == null) data.master.instances = new ArrayList<>();
                if (data.master.perms == null) data.master.perms = new ArrayList<>();
                if (data.master.instancePerms == null) data.master.instancePerms = new HashMap<>();
            }
        } catch (Exception e) {
            System.err.println("[Panel] 读取 users.json 失败: " + e.getMessage());
            data = new Data();
        }
    }

    public synchronized void save() {
        File target = new File(FILE);
        File tmp = new File(target.getAbsoluteFile().getParentFile(), target.getName() + ".tmp");
        try {
            MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
            MAPPER.writeValue(tmp, data);
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException am) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                target.setReadable(false, false);
                target.setReadable(true, true);
                target.setWritable(false, false);
                target.setWritable(true, true);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("[Panel] 写入 users.json 失败: " + e.getMessage());
        }
    }

    public String getInitialGeneratedPassword() {
        return initialGeneratedPassword;
    }

    public synchronized void bootstrapFromConfig(ConfigManager config) {
        if (data == null) load();
        boolean changed = false;

        if (data.master == null) {
            String user = config.get("web.username", "admin");
            if (user.isEmpty()) user = "admin";
            String pass = config.get("web.password", "");
            
            if (pass.isEmpty() || "admin123".equals(pass)) {
                pass = generateRandomPassword();
                initialGeneratedPassword = pass;
            }
            
            User master = new User(user, Crypto.hashPassword(pass));
            master.needsPasswordChange = true;
            String oldKey = config.get("web.apikey", "");
            master.apiKeyEnc = oldKey.isEmpty() ? "" : Crypto.encrypt(oldKey);
            data.master = master;
            changed = true;
            config.remove("web.username");
            config.remove("web.password");
            config.remove("web.apikey");
            config.remove("subuser.count");
        }

        int oldCount = config.getInt("subuser.count", 0);
        if (oldCount > 0 && data.subUsers.isEmpty()) {
            for (int i = 0; i < oldCount; i++) {
                String n = config.get("subuser." + i + ".name", "");
                if (n.isEmpty()) continue;
                User u = new User(n, Crypto.hashPassword(config.get("subuser." + i + ".pass", "")));
                String pk = config.get("subuser." + i + ".apikey", "");
                u.apiKeyEnc = pk.isEmpty() ? "" : Crypto.encrypt(pk);
                String perms = config.get("subuser." + i + ".perms", "");
                if (!perms.isEmpty()) {
                    for (String s : perms.split(",")) {
                        String t = s.trim();
                        if (!t.isEmpty()) u.perms.add(t);
                    }
                }
                data.subUsers.add(u);
                changed = true;
            }
            for (int i = 0; i < oldCount; i++) {
                config.remove("subuser." + i + ".name");
                config.remove("subuser." + i + ".pass");
                config.remove("subuser." + i + ".apikey");
                config.remove("subuser." + i + ".perms");
            }
        }
        if (changed) save();
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    public synchronized User getMaster() { return data.master; }
    public synchronized boolean isMaster(String username) {
        return data.master != null && username != null && username.equals(data.master.username);
    }

    public synchronized void setMasterPassword(String pw) {
        data.master.passwordHash = Crypto.hashPassword(pw);
        data.master.needsPasswordChange = false;
        save();
    }
    
    public synchronized void setMasterApiKey(String plainKey) {
        data.master.apiKeyEnc = (plainKey == null || plainKey.isEmpty())
                ? "" : Crypto.encrypt(plainKey);
        save();
    }

    public synchronized List<User> getSubUsers() {
        return new ArrayList<>(data.subUsers);
    }

    public synchronized User findUser(String username) {
        if (data.master != null && username != null && username.equals(data.master.username))
            return data.master;
        for (User u : data.subUsers) {
            if (username != null && username.equals(u.username)) return u;
        }
        return null;
    }

    public synchronized void addSubUser(String name, String password, List<String> perms, List<String> instances) {
        if (RESERVED.contains(name)) throw new IllegalArgumentException("该用户名为系统保留字");
        if (!name.matches("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{2,32}$")) {
            throw new IllegalArgumentException("用户名只允许字母、数字、中文、下划线、连字符，2-32 位");
        }
        User u = new User(name, Crypto.hashPassword(password));
        u.perms = perms == null ? new ArrayList<>() : new ArrayList<>(perms);
        u.instances = instances == null ? new ArrayList<>() : new ArrayList<>(instances);
        data.subUsers.add(u);
        save();
    }

    public synchronized void removeSubUser(String name) {
        data.subUsers.removeIf(u -> name.equals(u.username));
        save();
    }

    public synchronized void updateSubUser(String name, String newPassword, List<String> perms) {
        updateSubUser(name, newPassword, perms, null, false);
    }

    public synchronized void updateSubUser(String name, String newPassword, List<String> perms,
                                           List<String> instances, boolean updateInstances) {
        for (User u : data.subUsers) {
            if (name.equals(u.username)) {
                if (newPassword != null && !newPassword.isEmpty()) {
                    u.passwordHash = Crypto.hashPassword(newPassword);
                }
                if (perms != null) u.perms = new ArrayList<>(perms);
                if (updateInstances) {
                    u.instances = instances == null ? new ArrayList<>() : new ArrayList<>(instances);
                }
                save();
                return;
            }
        }
    }

    public synchronized void setSubUserApiKey(String name, String plainKey) {
        for (User u : data.subUsers) {
            if (name.equals(u.username)) {
                u.apiKeyEnc = (plainKey == null || plainKey.isEmpty()) ? "" : Crypto.encrypt(plainKey);
                save();
                return;
            }
        }
    }

    public synchronized void removeInstanceFromAllUsers(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) return;
        boolean changed = false;
        for (User u : data.subUsers) {
            if (u.instances != null && u.instances.remove(instanceId)) {
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized boolean verify(String username, String password) {
        User u = findUser(username);
        if (u == null) return false;
        return Crypto.verifyPassword(password, u.passwordHash);
    }

    public synchronized String findUserByApiKey(String plainKey) {
        if (plainKey == null || plainKey.isEmpty()) return null;
        if (data.master != null) {
            String mk = Crypto.decrypt(data.master.apiKeyEnc);
            if (!mk.isEmpty() && java.security.MessageDigest.isEqual(mk.getBytes(java.nio.charset.StandardCharsets.UTF_8), plainKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                return data.master.username;
        }
        for (User u : data.subUsers) {
            String k = Crypto.decrypt(u.apiKeyEnc);
            if (!k.isEmpty() && java.security.MessageDigest.isEqual(k.getBytes(java.nio.charset.StandardCharsets.UTF_8), plainKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                return u.username;
        }
        return null;
    }

    public synchronized boolean renameUser(String oldName, String newName) {
        if (newName == null || newName.isEmpty()) return false;
        if (RESERVED.contains(newName)) return false;
        if (!newName.matches("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{2,32}$")) return false;
        if (findUser(newName) != null) return false;

        if (data.master != null && oldName.equals(data.master.username)) {
            data.master.username = newName;
            save();
            return true;
        }
        for (User u : data.subUsers) {
            if (oldName.equals(u.username)) {
                u.username = newName;
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized List<String> getPermissions(String username) {
        if (isMaster(username)) return new ArrayList<>(ALL_PERMS);
        User u = findUser(username);
        if (u == null || u.perms == null) return new ArrayList<>();
        return new ArrayList<>(u.perms);
    }

    public synchronized boolean hasPermission(String username, String perm) {
        if (username == null) return false;
        if (isMaster(username)) return true;
        User u = findUser(username);
        if (u == null || u.perms == null) return false;
        return u.perms.contains(perm);
    }

    public synchronized boolean hasInstancePermission(String username, String instanceId, String perm) {
        if (hasPermission(username, perm)) return true;
        User u = findUser(username);
        if (u != null && u.instancePerms != null) {
            List<String> perms = u.instancePerms.get(instanceId);
            return perms != null && perms.contains(perm);
        }
        return false;
    }

    public synchronized boolean canAccessInstance(String username, String instanceId) {
        if (username == null || instanceId == null) return false;
        if (isMaster(username)) return true;
        User u = findUser(username);
        if (u == null || u.instances == null || u.instances.isEmpty()) return false;
        return u.instances.contains(instanceId);
    }

    public synchronized List<String> getAllowedInstances(String username) {
        if (isMaster(username)) return null;
        User u = findUser(username);
        if (u == null || u.instances == null) return new ArrayList<>();
        return new ArrayList<>(u.instances);
    }
}