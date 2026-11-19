package com.mcpanel.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadLicenseManager {
    private static final long TTL_MS = 3 * 60 * 60 * 1000L;
    private static final SecureRandom RNG = new SecureRandom();

    public static class License {
        public String filePath;
        public String boundIp;
        public long expiresAt;
        public String mode;   // "download" / "inline"
    }

    public enum Result { OK, NOT_FOUND, EXPIRED, IP_MISMATCH }

    public static class CheckResult {
        public Result result;
        public License license;
        public String normalizedIp;
    }

    private static final Map<String, License> licenses = new ConcurrentHashMap<>();

    public static synchronized String create(String filePath) {
        return create(filePath, "download");
    }

    public static synchronized String create(String filePath, String mode) {
        if (licenses.size() > 1000) { cleanup(); if (licenses.size() > 1000) licenses.clear(); }
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        License lic = new License();
        lic.filePath = filePath;
        lic.boundIp = null;
        lic.expiresAt = System.currentTimeMillis() + TTL_MS;
        lic.mode = (mode == null || mode.isEmpty()) ? "download" : mode;
        licenses.put(token, lic);
        cleanup();
        return token;
    }

    public static synchronized CheckResult check(String token, String ip) {
        CheckResult r = new CheckResult();
        if (token == null || token.isEmpty()) { r.result = Result.NOT_FOUND; return r; }
        License lic = licenses.get(token);
        if (lic == null) { r.result = Result.NOT_FOUND; return r; }
        if (System.currentTimeMillis() > lic.expiresAt) {
            licenses.remove(token);
            r.result = Result.EXPIRED;
            return r;
        }
        String n = normalizeIp(ip);
        r.normalizedIp = n;
        if (lic.boundIp == null) {
            lic.boundIp = n;
            System.out.println("[Panel] License bound: " + shortToken(token) + " -> " + n);
        } else if (!lic.boundIp.equals(n)) {
            r.result = Result.IP_MISMATCH;
            r.license = lic;
            return r;
        }
        r.result = Result.OK;
        r.license = lic;
        return r;
    }

    public static String normalizeIp(String ip) {
        if (ip == null) return "";
        ip = ip.trim();
        if (ip.startsWith("::ffff:")) ip = ip.substring(7);
        if (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.equals("0000:0000:0000:0000:0000:0000:0000:0001")) {
            return "127.0.0.1";
        }
        return ip;
    }

    public static String shortToken(String t) {
        if (t == null) return "";
        return t.length() > 8 ? t.substring(0, 8) + "..." : t;
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        licenses.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }
}