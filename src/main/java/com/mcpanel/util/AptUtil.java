package com.mcpanel.util;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class AptUtil {
    private static final ReentrantLock APT_LOCK = new ReentrantLock();

    // ★ 修复：以前每次探测镜像都 new HttpClient()，而每个 HttpClient 会拉起一条
    // SelectorManager 线程（要等对象被 GC/close 才释放）；配置项反复保存时线程会慢性增长。
    // 这里全进程复用一个客户端。
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static void lockApt() { APT_LOCK.lock(); }
    public static void unlockApt() { if (APT_LOCK.isHeldByCurrentThread()) APT_LOCK.unlock(); }
    
    private static class Mirror {
        String name;
        String mainUrl;
        String securityUrl;
        long latency = Long.MAX_VALUE;
        Mirror(String name, String mainUrl, String securityUrl) {
            this.name = name; this.mainUrl = mainUrl; this.securityUrl = securityUrl;
        }
    }

    public static void configureFastestMirror(Consumer<String> logger) {
        try {
            lockApt();
            File osRelease = new File("/etc/os-release");
            if (!osRelease.exists()) return;

            String osId = "";
            String codename = "";

            for (String line : Files.readAllLines(osRelease.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.startsWith("ID=")) {
                    osId = line.substring(3).replace("\"", "").replace("'", "").trim();
                } else if (line.startsWith("VERSION_CODENAME=")) {
                    codename = line.substring(17).replace("\"", "").replace("'", "").trim();
                }
            }

            if (!"debian".equalsIgnoreCase(osId) && !"ubuntu".equalsIgnoreCase(osId)) return;
            if (codename.isEmpty()) return;

            List<Mirror> mirrors = new ArrayList<>();
            if ("debian".equalsIgnoreCase(osId)) {
                // ★ 已经移除了阿里云
                mirrors.add(new Mirror("清华源", "https://mirrors.tuna.tsinghua.edu.cn/debian/", "https://mirrors.tuna.tsinghua.edu.cn/debian-security/"));
                mirrors.add(new Mirror("中科大", "https://mirrors.ustc.edu.cn/debian/", "https://mirrors.ustc.edu.cn/debian-security/"));
                mirrors.add(new Mirror("腾讯云", "http://mirrors.tencent.com/debian/", "http://mirrors.tencent.com/debian-security/"));
                mirrors.add(new Mirror("官方源", "http://deb.debian.org/debian/", "http://security.debian.org/debian-security/"));
            } else {
                mirrors.add(new Mirror("清华源", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/"));
                mirrors.add(new Mirror("中科大", "https://mirrors.ustc.edu.cn/ubuntu/", "https://mirrors.ustc.edu.cn/ubuntu/"));
                mirrors.add(new Mirror("腾讯云", "http://mirrors.tencent.com/ubuntu/", "http://mirrors.tencent.com/ubuntu/"));
                mirrors.add(new Mirror("官方源", "http://archive.ubuntu.com/ubuntu/", "http://security.ubuntu.com/ubuntu/"));
            }

            for (Mirror m : mirrors) {
                try {
                    String testUrl = m.mainUrl + "dists/" + codename + "/Release";
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(testUrl)).timeout(Duration.ofSeconds(3)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                    long start = System.currentTimeMillis();
                    HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
                    long cost = System.currentTimeMillis() - start;
                    if (resp.statusCode() >= 200 && resp.statusCode() < 400) { m.latency = cost; }
                } catch (Exception ignored) {}
            }

            mirrors.sort(Comparator.comparingLong(m -> m.latency));
            Mirror fastest = mirrors.get(0);
            if (fastest.latency == Long.MAX_VALUE) return;

            File sourcesFile = new File("/etc/apt/sources.list");
            if (sourcesFile.exists() && !new File("/etc/apt/sources.list.bak").exists()) {
                Files.copy(sourcesFile.toPath(), new File("/etc/apt/sources.list.bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> lines = new ArrayList<>();
            if ("debian".equalsIgnoreCase(osId)) {
                lines.add("deb " + fastest.mainUrl + " " + codename + " main contrib non-free non-free-firmware");
                lines.add("deb " + fastest.mainUrl + " " + codename + "-updates main contrib non-free non-free-firmware");
                lines.add("deb " + fastest.mainUrl + " " + codename + "-backports main contrib non-free non-free-firmware");
                lines.add("deb " + fastest.securityUrl + " " + codename + "-security main contrib non-free non-free-firmware");
            } else {
                lines.add("deb " + fastest.mainUrl + " " + codename + " main restricted universe multiverse");
                lines.add("deb " + fastest.mainUrl + " " + codename + "-updates main restricted universe multiverse");
                lines.add("deb " + fastest.mainUrl + " " + codename + "-backports main restricted universe multiverse");
                lines.add("deb " + fastest.securityUrl + " " + codename + "-security main restricted universe multiverse");
            }
            Files.write(sourcesFile.toPath(), lines, StandardCharsets.UTF_8);

            File sourcesDir = new File("/etc/apt/sources.list.d");
            if (sourcesDir.exists() && sourcesDir.isDirectory()) {
                File[] files = sourcesDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        try {
                            if (!f.getName().endsWith(".bak")) {
                                Files.move(f.toPath(), new File(f.getAbsolutePath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            if (logger != null) logger.accept("[AptUtil] 配置镜像源出错: " + e.getMessage());
        } finally {
            unlockApt();
        }
    }
}