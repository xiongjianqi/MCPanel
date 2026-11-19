package com.mcpanel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.mcpanel.instance.Instance;
import com.mcpanel.instance.InstanceRuntime;
import com.mcpanel.instance.InstanceRuntimeManager;
import com.mcpanel.util.Crypto;
import com.mcpanel.util.LinuxUserManager;
import com.mcpanel.util.TotpUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class RemoteHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConfigManager globalConfig;
    private final InstanceRuntimeManager rtMgr;
    private final AuthHandler auth;

    public RemoteHandler(ConfigManager globalConfig, InstanceRuntimeManager rtMgr, AuthHandler auth) {
        this.globalConfig = globalConfig;
        this.rtMgr = rtMgr;
        this.auth = auth;
    }

    private void checkAndRestoreRootKey() {
        File pubKeyFile = new File(globalConfig.getRootDir(), "data/ssh/root_rsa.pub");
        if (!pubKeyFile.exists()) return; 

        try {
            String pubKey = new String(Files.readAllBytes(pubKeyFile.toPath()), StandardCharsets.UTF_8).trim();
            File authFile = new File("/root/.ssh/authorized_keys");
            boolean needWrite = true;

            if (authFile.exists()) {
                String content = new String(Files.readAllBytes(authFile.toPath()), StandardCharsets.UTF_8);
                if (content.contains(pubKey)) needWrite = false;
            }

            if (needWrite) {
                File sshDir = new File("/root/.ssh");
                if (!sshDir.exists()) sshDir.mkdirs();
                try { new ProcessBuilder("chmod", "700", "/root/.ssh").start().waitFor(); } catch (Exception ignored) {}
                String content = pubKey + "\n";
                if (authFile.exists()) {
                    content = new String(Files.readAllBytes(authFile.toPath()), StandardCharsets.UTF_8) + "\n" + content;
                }
                Files.write(authFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                try { new ProcessBuilder("chmod", "600", authFile.getAbsolutePath()).start().waitFor(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {}
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

    public void handle(HttpServerExchange exchange) {
        String currentUser = auth.getCurrentUser(exchange);
        if (currentUser == null) {
            exchange.setStatusCode(401); exchange.getResponseSender().send("{\"error\":\"unauthorized\"}"); return;
        }

        String path = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString();

        if (path.equals("/api/remote/ssh-key") && method.equals("GET")) {
            Deque<String> qp = exchange.getQueryParameters().get("instance");
            String instId = (qp != null && !qp.isEmpty()) ? qp.peekFirst() : null;
            
            if (instId == null || !UserStore.getInstance().canAccessInstance(currentUser, instId)) {
                exchange.setStatusCode(403); exchange.endExchange(); return;
            }
            // ★ 核心修复：下载私钥必须校验 remote 权限
            if (!UserStore.getInstance().hasInstancePermission(currentUser, instId, "remote")) {
                exchange.setStatusCode(403); exchange.getResponseSender().send("Forbidden: No remote permission"); return;
            }
            File keyFile = new File(globalConfig.getRootDir(), "data/ssh/" + instId + "_rsa.key");
            if (!keyFile.exists()) {
                exchange.setStatusCode(404); exchange.getResponseSender().send("Key not found"); return;
            }
            try {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION, "attachment; filename=\"" + instId + "_rsa.key\"");
                exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(Files.readAllBytes(keyFile.toPath())));
            } catch (Exception e) { exchange.setStatusCode(500); exchange.endExchange(); }
            return;
        }

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            try {
                String domain = globalConfig.get("panel.domain", "未配置域名");
                if ("未配置域名".equals(domain)) domain = "请先在配置管理里设置 panel.domain";
                String sshPort = globalConfig.get("remote.ssh.port", "22");
                String sftpPort = globalConfig.get("remote.sftp.port", "22");

                if (path.equals("/api/remote/master/download-key")) {
                    if (!UserStore.getInstance().isMaster(currentUser)) {
                        ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"need master\"}"); return;
                    }
                    String totpStr = null;
                    try {
                        Map<String, String> p = MAPPER.readValue(body, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                        totpStr = p.get("totp");
                    } catch (Exception ignored) {}
                    
                    if (totpStr == null || totpStr.isEmpty()) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"missing totp\"}"); return;
                    }

                    UserStore.User u = UserStore.getInstance().findUser(currentUser);
                    try {
                        if (u == null || u.totpSecret == null || !TotpUtil.verifyCode(Crypto.decrypt(u.totpSecret), Integer.parseInt(totpStr))) {
                            ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"invalid totp\"}"); return;
                        }
                    } catch (Exception e) { ex.setStatusCode(400); ex.endExchange(); return; }

                    File keyFile = new File(globalConfig.getRootDir(), "data/ssh/root_rsa.key");
                    if (!keyFile.exists()) {
                        ex.setStatusCode(404); ex.getResponseSender().send("{\"error\":\"key not found or already downloaded\"}"); return;
                    }
                    try {
                        byte[] data = Files.readAllBytes(keyFile.toPath());
                        keyFile.delete(); 
                        
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
                        ex.getResponseHeaders().put(Headers.CONTENT_DISPOSITION, "attachment; filename=\"root_rsa.key\"");
                        ex.getResponseSender().send(java.nio.ByteBuffer.wrap(data));
                    } catch (Exception e) { ex.setStatusCode(500); ex.endExchange(); }
                    return;
                }

                if (path.equals("/api/remote/master/setup-key")) {
                    if (!UserStore.getInstance().isMaster(currentUser)) {
                        ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"need master\"}"); return;
                    }
                    UserStore.User u = UserStore.getInstance().findUser(currentUser);
                    String code = extract(body, "totp");
                    if (u == null || u.totpSecret == null || !TotpUtil.verifyCode(Crypto.decrypt(u.totpSecret), Integer.parseInt(code))) {
                        ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"2FA required\"}"); return;
                    }
                    
                    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                    kpg.initialize(4096);
                    KeyPair kp = kpg.generateKeyPair();

                    File sshDir = new File(globalConfig.getRootDir(), "data/ssh");
                    if (!sshDir.exists()) sshDir.mkdirs();
                    File keyFile = new File(sshDir, "root_rsa.key");
                    
                    String privateKeyPEM = "-----BEGIN PRIVATE KEY-----\n" +
                            Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded()) +
                            "\n-----END PRIVATE KEY-----\n";
                    Files.write(keyFile.toPath(), privateKeyPEM.getBytes(StandardCharsets.UTF_8));
                    keyFile.setReadable(false, false); keyFile.setReadable(true, true);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    byte[] sshRsa = "ssh-rsa".getBytes(StandardCharsets.UTF_8);
                    dos.writeInt(sshRsa.length); dos.write(sshRsa);
                    byte[] e = ((RSAPublicKey) kp.getPublic()).getPublicExponent().toByteArray();
                    dos.writeInt(e.length); dos.write(e);
                    byte[] m = ((RSAPublicKey) kp.getPublic()).getModulus().toByteArray();
                    dos.writeInt(m.length); dos.write(m);
                    dos.flush();
                    String pubKeyOpenSSH = "ssh-rsa " + Base64.getEncoder().encodeToString(baos.toByteArray()) + " mcpanel-root";

                    Files.write(new File(sshDir, "root_rsa.pub").toPath(), pubKeyOpenSSH.getBytes(StandardCharsets.UTF_8));

                    File rootSshDir = new File("/root/.ssh");
                    if (!rootSshDir.exists()) rootSshDir.mkdirs();
                    try { new ProcessBuilder("chmod", "700", "/root/.ssh").start().waitFor(); } catch (Exception ignored) {}
                    
                    File authFile = new File(rootSshDir, "authorized_keys");
                    String content = pubKeyOpenSSH + "\n";
                    if (authFile.exists()) {
                        content = new String(Files.readAllBytes(authFile.toPath()), StandardCharsets.UTF_8) + "\n" + content;
                    }
                    Files.write(authFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    try { new ProcessBuilder("chmod", "600", authFile.getAbsolutePath()).start().waitFor(); } catch (Exception ignored) {}

                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send("{\"ok\":true}");
                    return;
                }

                if (path.equals("/api/remote/master")) {
                    if (!UserStore.getInstance().isMaster(currentUser)) {
                        ex.setStatusCode(403); ex.getResponseSender().send("{\"error\":\"need master\"}"); return;
                    }
                    
                    UserStore.User u = UserStore.getInstance().findUser(currentUser);
                    if (u == null || u.totpSecret == null || u.totpSecret.isEmpty()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("enabled", false); map.put("need2fa", true); map.put("reason", "必须先绑定二次验证 (2FA)");
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(map)); return;
                    }

                    checkAndRestoreRootKey();

                    Map<String, Object> map = new HashMap<>();
                    File keyFile = new File(globalConfig.getRootDir(), "data/ssh/root_rsa.key");
                    
                    map.put("enabled", LinuxUserManager.isEnabled(null));
                    map.put("user", "root");
                    map.put("domain", domain);
                    map.put("sshPort", sshPort);
                    map.put("sftpPort", sftpPort);
                    map.put("needKeySetup", !keyFile.exists());
                    
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(map));
                    return;
                }

                if (path.equals("/api/remote/info")) {
                    Map<String, Object> map = new HashMap<>();
                    try {
                        String instId = null;
                        Deque<String> qp = ex.getQueryParameters().get("instance");
                        if (qp != null && !qp.isEmpty()) instId = qp.peekFirst();
                        if (instId == null || instId.isEmpty()) instId = readCookie(ex, "instance");
                        
                        if (instId == null || instId.isEmpty() || !UserStore.getInstance().canAccessInstance(currentUser, instId)) {
                            map.put("enabled", false); 
                            map.put("reason", "无权访问该实例或未进入实例");
                            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                            ex.getResponseSender().send(MAPPER.writeValueAsString(map));
                            return;
                        }

                        // ★ 核心修复：必须拥有该实例的 remote 权限
                        if (!UserStore.getInstance().hasInstancePermission(currentUser, instId, "remote")) {
                            map.put("enabled", false); 
                            map.put("reason", "无远程管理权限，请联系所有者分配");
                            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                            ex.getResponseSender().send(MAPPER.writeValueAsString(map));
                            return;
                        }

                        InstanceRuntime rt = rtMgr.get(instId);
                        if (rt != null) {
                            Instance inst = rt.inst;
                            if (inst.sysUser == null) {
                                new Thread(() -> LinuxUserManager.initInstanceUser(inst, inst.getDir())).start();
                                map.put("enabled", false);
                                map.put("reason", "正在初始化系统用户，请稍后刷新");
                                ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                ex.getResponseSender().send(MAPPER.writeValueAsString(map));
                                return;
                            }
                            
                            if (inst.sysUser != null) {
                                try {
                                    File sshDir = new File(globalConfig.getRootDir(), "data/ssh");
                                    if (!sshDir.exists()) sshDir.mkdirs();
                                    File keyFile = new File(sshDir, instId + "_rsa.key");
                                    File pubKeyFile = new File(sshDir, instId + "_rsa.pub");

                                    if (!keyFile.exists() || !pubKeyFile.exists()) {
                                        if (keyFile.exists()) keyFile.delete();
                                        if (pubKeyFile.exists()) pubKeyFile.delete();
                                        
                                        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                                        kpg.initialize(2048);
                                        KeyPair kp = kpg.generateKeyPair();
                                        
                                        String privateKeyPEM = "-----BEGIN PRIVATE KEY-----\n" +
                                                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded()) +
                                                "\n-----END PRIVATE KEY-----\n";
                                        Files.write(keyFile.toPath(), privateKeyPEM.getBytes(StandardCharsets.UTF_8));
                                        keyFile.setReadable(false, false); keyFile.setReadable(true, true);
                                        
                                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                        DataOutputStream dos = new DataOutputStream(baos);
                                        byte[] sshRsa = "ssh-rsa".getBytes(StandardCharsets.UTF_8);
                                        dos.writeInt(sshRsa.length); dos.write(sshRsa);
                                        byte[] e = ((RSAPublicKey) kp.getPublic()).getPublicExponent().toByteArray();
                                        dos.writeInt(e.length); dos.write(e);
                                        byte[] m = ((RSAPublicKey) kp.getPublic()).getModulus().toByteArray();
                                        dos.writeInt(m.length); dos.write(m);
                                        dos.flush();
                                        String pubKeyOpenSSH = "ssh-rsa " + Base64.getEncoder().encodeToString(baos.toByteArray()) + " mcpanel-" + instId;
                                        Files.write(pubKeyFile.toPath(), pubKeyOpenSSH.getBytes(StandardCharsets.UTF_8));
                                    }

                                    File userSshDir = new File(inst.getDir(), ".ssh");
                                    if (!userSshDir.exists()) userSshDir.mkdirs();
                                    File authKeys = new File(userSshDir, "authorized_keys");
                                    Files.copy(pubKeyFile.toPath(), authKeys.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    
                                    new ProcessBuilder("chown", "-R", inst.sysUser + ":" + inst.sysUser, userSshDir.getAbsolutePath()).start().waitFor();
                                    new ProcessBuilder("chmod", "700", userSshDir.getAbsolutePath()).start().waitFor();
                                    new ProcessBuilder("chmod", "600", authKeys.getAbsolutePath()).start().waitFor();

                                    map.put("hasKey", true);
                                } catch (Exception ignored) { map.put("hasKey", false); }

                                map.put("enabled", true);
                                map.put("user", inst.sysUser);
                                map.put("pass", inst.sysPass != null ? inst.sysPass : "密码未设置");
                                map.put("domain", domain);
                                map.put("sshPort", sshPort);
                                map.put("sftpPort", sftpPort);
                            } else {
                                map.put("enabled", false); map.put("reason", "系统未成功创建该实例用户（请检查日志）");
                            }
                        } else {
                            map.put("enabled", false); map.put("reason", "实例运行时不存在，请尝试重启面板");
                        }
                    } catch (Throwable t) {
                        map.put("enabled", false); 
                        map.put("reason", "后端异常: " + t.getMessage());
                    }
                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(map));
                    return;
                }

                ex.setStatusCode(404); ex.getResponseSender().send("{\"error\":\"unknown\"}");
            } catch (Exception e) {
                ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"internal\"}");
            }
        });
    }

    private String extract(String body, String key) {
        if (body == null || body.isEmpty()) return "";
        try { return MAPPER.readTree(body).path(key).asText(""); } catch (Exception ignored) { return ""; }
    }
}