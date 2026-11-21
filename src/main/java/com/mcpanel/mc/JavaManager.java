package com.mcpanel.mc;

import com.mcpanel.config.ConfigManager;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JavaManager {
    private static final ConfigManager globalConfig = ConfigManager.getInstance();
    private static final Set<String> downloading = ConcurrentHashMap.newKeySet();

    public static class DownloadStatus {
        public double progress = 0.0;
        public long totalBytes = 0;
        public long downloadedBytes = 0;
        public String state = "downloading"; // downloading, extracting, error, success
    }

    private static final Map<String, DownloadStatus> statusMap = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static final int[] JAVA_VERSIONS = {8, 17, 21, 25};
    public static final String[] JAVA_TYPES = {"jre", "jdk"};

    private static final String TUNA_BASE = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/";
    private static final String USTC_BASE = "https://mirrors.ustc.edu.cn/adoptium/releases/";
    private static final String OFFICIAL_BASE = "https://api.adoptium.net/v3/binary/latest/";

    public static File getRuntimeDir() {
        // ★ 核心修复：Java 环境是全局的，必须存在全局根目录下，不能存到实例文件夹
        File dir = new File(globalConfig.getRootDir(), "runtime");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getJavaHome(String type, int version) {
        return new File(getRuntimeDir(), type + "-" + version);
    }

    public static File getJavaBin(String type, int version) {
        String os = System.getProperty("os.name").toLowerCase();
        String exe = os.contains("win") ? "java.exe" : "java";
        return new File(getJavaHome(type, version), "bin/" + exe);
    }

    public static boolean isInstalled(String type, int version) {
        return getJavaBin(type, version).exists();
    }

    public static boolean isDownloading(String type, int version) {
        return downloading.contains(type + "-" + version);
    }

    public static DownloadStatus getStatus(String type, int version) {
        String key = type + "-" + version;
        if (isInstalled(type, version)) {
            DownloadStatus st = new DownloadStatus();
            st.state = "success";
            st.progress = 1.0;
            return st;
        }
        return statusMap.getOrDefault(key, null);
    }

    public static void download(String type, int version) {
        String key = type + "-" + version;
        if (downloading.contains(key)) return;
        downloading.add(key);
        
        DownloadStatus st = new DownloadStatus();
        st.state = "downloading";
        statusMap.put(key, st);

        com.mcpanel.util.Async.worker("java-downloader", () -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                String arch = System.getProperty("os.arch").toLowerCase();
                String archStr = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x64";
                String osStr = os.contains("win") ? "windows" : "linux";
                String ext = os.contains("win") ? "zip" : "tar.gz";

                String downloadUrl = resolveDownloadUrl(type, version, osStr, archStr, ext);
                if (downloadUrl == null) throw new IOException("所有镜像源均不可用");

                File tmpFile = new File(getRuntimeDir(), key + "." + ext);
                
                long totalBytes = -1;
                boolean supportsRange = false;
                try {
                    HttpRequest rangeReq = HttpRequest.newBuilder()
                            .uri(URI.create(downloadUrl))
                            .header("Range", "bytes=0-0")
                            .timeout(Duration.ofSeconds(10))
                            .build();
                    HttpResponse<InputStream> rangeResp = HTTP.send(rangeReq, HttpResponse.BodyHandlers.ofInputStream());
                    if (rangeResp.statusCode() == 206) {
                        supportsRange = true;
                        String cr = rangeResp.headers().firstValue("Content-Range").orElse("");
                        if (cr.contains("/")) totalBytes = Long.parseLong(cr.split("/")[1]);
                    } else if (rangeResp.statusCode() == 200) {
                        totalBytes = rangeResp.headers().firstValueAsLong("Content-Length").orElse(-1);
                    }
                    try { rangeResp.body().close(); } catch (Exception ignored) {}
                } catch (Exception ignored) {}

                if (supportsRange && totalBytes > 0) {
                    final long finalTotalBytes = totalBytes;
                    st.totalBytes = finalTotalBytes;
                    int threads = 4; // 分段下载固定 4 路，避免一次拉起过多线程
                    long chunkSize = finalTotalBytes / threads;
                    RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
                    raf.setLength(finalTotalBytes);
                    java.util.concurrent.atomic.AtomicLong totalRead = new java.util.concurrent.atomic.AtomicLong(0);
                    List<Thread> threadList = new ArrayList<>();

                    for (int i = 0; i < threads; i++) {
                        long start = i * chunkSize;
                        long end = (i == threads - 1) ? finalTotalBytes - 1 : (start + chunkSize - 1);

                        Thread t = com.mcpanel.util.Async.worker("java-chunk", () -> {
                            try {
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(downloadUrl))
                                        .header("Range", "bytes=" + start + "-" + end)
                                        .build();
                                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                                if (resp.statusCode() == 206) {
                                    try (InputStream in = resp.body()) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        long pos = start;
                                        while ((n = in.read(buf)) != -1) {
                                            synchronized (raf) {
                                                raf.seek(pos);
                                                raf.write(buf, 0, n);
                                            }
                                            pos += n;
                                            long current = totalRead.addAndGet(n);
                                            synchronized(st) {
                                                st.downloadedBytes = current;
                                                st.progress = (double) current / finalTotalBytes;
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
                        threadList.add(t);
                    }
                    for (Thread t : threadList) t.join();
                    raf.close();
                } else {
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).timeout(Duration.ofSeconds(30)).build();
                    HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                    long total = resp.headers().firstValueAsLong("Content-Length").orElse(0);
                    st.totalBytes = total;
                    try (InputStream in = resp.body(); OutputStream out = new FileOutputStream(tmpFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        long read = 0;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            read += n;
                            st.downloadedBytes = read;
                            if (total > 0) st.progress = (double) read / total;
                        }
                    }
                }

                File finalDir = getJavaHome(type, version);
                if (finalDir.exists()) deleteDir(finalDir);
                finalDir.mkdirs();

                st.state = "extracting";
                if (ext.equals("zip")) extractZip(tmpFile, finalDir);
                else extractTarGz(tmpFile, finalDir);
                tmpFile.delete();

                File[] files = finalDir.listFiles();
                if (files != null && files.length == 1 && files[0].isDirectory()) {
                    File inner = files[0];
                    for (File f : inner.listFiles()) f.renameTo(new File(finalDir, f.getName()));
                    inner.delete();
                }
                getJavaBin(type, version).setExecutable(true);
                st.state = "success";
                st.progress = 1.0;

            } catch (Exception e) {
                e.printStackTrace();
                st.state = "error";
                File tmp = new File(getRuntimeDir(), key + ".tar.gz");
                if (tmp.exists()) tmp.delete();
            } finally {
                downloading.remove(key);
            }
        });
    }

    private static String resolveDownloadUrl(String type, int version, String os, String arch, String ext) {
        String versionStr = String.valueOf(version);
        String prefix = "OpenJDK" + versionStr + "U-" + type + "_" + arch + "_" + os + "_hotspot_";
        String suffix = "." + ext;

        String tunaUrl = TUNA_BASE + versionStr + "/" + type + "/" + arch + "/" + os + "/";
        String fileName = fetchLatestFileName(tunaUrl, prefix, suffix);
        if (fileName != null) return tunaUrl + fileName;

        String ustcUrl = USTC_BASE + "temurin" + versionStr + "-binaries/LatestRelease/";
        String ustcFileName = fetchLatestFileName(ustcUrl, prefix, suffix);
        if (ustcFileName != null) return ustcUrl + ustcFileName;

        return OFFICIAL_BASE + versionStr + "/ga/" + os + "/" + arch + "/" + type + "/hotspot/normal/eclipse";
    }

    private static String fetchLatestFileName(String dirUrl, String prefix, String suffix) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(dirUrl)).timeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String html = resp.body();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(prefix + "([\\w\\.]+)" + java.util.regex.Pattern.quote(suffix));
            java.util.regex.Matcher m = p.matcher(html);
            if (m.find()) return m.group(0);
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean delete(String type, int version) {
        File dir = getJavaHome(type, version);
        if (dir.exists()) {
            try {
                deleteDir(dir);
                statusMap.remove(type + "-" + version);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static void extractTarGz(File tarGz, File dest) throws Exception {
        try (InputStream fin = new FileInputStream(tarGz);
             GzipCompressorInputStream gz = new GzipCompressorInputStream(fin);
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                File f = new File(dest, entry.getName()).getCanonicalFile();
                if (!f.toPath().startsWith(dest.getCanonicalFile().toPath())) continue;
                if (entry.isDirectory()) f.mkdirs();
                else {
                    f.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = tar.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void extractZip(File zip, File dest) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File f = new File(dest, entry.getName()).getCanonicalFile();
                if (!f.toPath().startsWith(dest.getCanonicalFile().toPath())) continue;
                if (entry.isDirectory()) f.mkdirs();
                else {
                    f.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }
}
