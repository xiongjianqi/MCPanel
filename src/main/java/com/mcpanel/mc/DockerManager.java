package com.mcpanel.mc;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class DockerManager {
    private static volatile boolean dockerAvailable = false;
    private static volatile boolean checkedDocker = false;

    private static String workDirPath = null;
    private static File podmanHome = null;
    private static String podmanTool = null;

    // ==================== 1. Docker 检测 ====================

    public static synchronized void initDocker(Consumer<String> logger) {
        if (checkedDocker) return;
        checkedDocker = true;
        workDirPath = System.getProperty("user.dir");

        if (testDocker()) {
            dockerAvailable = true;
            logger.accept("[Runtime] ✓ 检测到 Docker 引擎可用");
            return;
        }

        dockerAvailable = false;
        
        // 检查是否在 Docker 容器内运行
        boolean inDocker = new File("/.dockerenv").exists();
        if (!inDocker) {
            try {
                String cgroup = new String(Files.readAllBytes(Path.of("/proc/self/cgroup")), java.nio.charset.StandardCharsets.UTF_8);
                if (cgroup.contains("docker") || cgroup.contains("kubepods")) {
                    inDocker = true;
                }
            } catch (Exception ignored) {}
        }

        logger.accept("[Runtime] 未检测到 Docker 引擎，已降级为无沙箱模式运行"
                + (inDocker
                ? "（当前面板本身运行在容器内，如需实例级隔离请在宿主机运行面板）"
                : "（如需隔离可安装 Docker 后重启面板）"));
    }

    public static boolean isDockerAvailable() {
        return dockerAvailable;
    }

    private static boolean testDocker() {
        return runQuietCode(5, "docker", "info") == 0;
    }

    /**
     * ★ 线程治理：所有「一次性」docker 命令都走这里。
     *
     * 旧写法 new ProcessBuilder(...).start() 之后既不读输出也不关闭流：
     *  - 输出超过管道缓冲（64KB）时子进程会永久阻塞；
     *  - 子进程不退出，JVM 的 process reaper 线程就一直挂着；
     *  - 没有超时的 waitFor 会把调用线程永久堵死。
     * 统一用 DISCARD + 超时，既不产生读线程，也不会留下孤儿进程。
     */
    private static int runQuietCode(long timeoutSec, String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (p != null && p.isAlive()) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
    }

    private static void runQuiet(long timeoutSec, String... cmd) {
        runQuietCode(timeoutSec, cmd);
    }

    // ==================== 公共工具 ====================

    public static String getDockerCmd() {
        if (podmanTool != null) return podmanTool;
        return "docker";
    }

    // ==================== Docker 操作 ====================

    public static void ensureImage(String image, File localTar, Consumer<String> logger) throws Exception {
        if (!dockerAvailable) throw new RuntimeException("Docker 不可用");
        if (runQuietCode(5, getDockerCmd(), "image", "inspect", image) == 0) {
            if (logger != null) logger.accept("[Docker] 镜像已存在: " + image);
            return;
        }
        if (localTar != null && localTar.exists()) {
            if (logger != null) logger.accept("[Docker] 发现离线镜像包，正在导入...");
            Process load = new ProcessBuilder(getDockerCmd(), "load", "-i", localTar.getAbsolutePath()).redirectErrorStream(true).start();
            if (load.waitFor(15, TimeUnit.MINUTES) && load.exitValue() == 0) return;
        }
        if (logger != null) logger.accept("[Docker] 正在拉取镜像: " + image);
        Process pull = new ProcessBuilder(getDockerCmd(), "pull", image).redirectErrorStream(true).start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(pull.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (logger != null) logger.accept("[Docker Pull] " + line.trim());
            }
        }
        if (!pull.waitFor(30, TimeUnit.MINUTES) || pull.exitValue() != 0) throw new RuntimeException("镜像拉取失败");
        if (logger != null) logger.accept("[Docker] ✓ 镜像拉取完成");
    }

    public static String runContainer(String name, String image, File dataDir, int port, List<String> envs) throws Exception {
        if (!dockerAvailable) throw new RuntimeException("Docker 不可用");
        runQuiet(10, getDockerCmd(), "rm", "-f", name);

        List<String> cmd = new ArrayList<>();
        cmd.add(getDockerCmd()); cmd.add("run"); cmd.add("-d");
        cmd.add("--name"); cmd.add(name);
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("-v"); cmd.add(dataDir.getAbsolutePath() + ":/data");
        if (port > 0) { cmd.add("-p"); cmd.add(port + ":" + port); }
        if (envs != null) {
            for (String e : envs) { cmd.add("-e"); cmd.add(e); }
        }
        cmd.add(image);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (podmanHome != null) {
            pb.environment().put("HOME", workDirPath);
            pb.environment().put("PATH", podmanHome.getAbsolutePath() + ":" + System.getenv("PATH"));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line = br.readLine();
            if (p.waitFor(30, TimeUnit.SECONDS)) {
                if (p.exitValue() != 0) throw new RuntimeException("容器启动失败: " + line);
                return line != null ? line.trim() : name;
            }
        }
        throw new RuntimeException("容器启动超时");
    }

    public static Process streamLogs(String name) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(getDockerCmd(), "logs", "-f", name);
        if (podmanHome != null) pb.environment().put("PATH", podmanHome.getAbsolutePath() + ":" + System.getenv("PATH"));
        return pb.redirectErrorStream(true).start();
    }

    public static void execCommand(String name, String command) {
        runQuiet(20, getDockerCmd(), "exec", name, "rcon-cli", command);
    }

    public static void stopContainer(String name) {
        runQuiet(20, getDockerCmd(), "stop", "-t", "10", name);
    }

    public static void killContainer(String name) {
        runQuiet(30, getDockerCmd(), "rm", "-f", name);
    }

    public static long getContainerPid(String name) {
        try {
            Process p = new ProcessBuilder(getDockerCmd(), "inspect", "-f", "{{.State.Pid}}", name)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String line = null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = br.readLine();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            if (line != null && !line.isEmpty()) return Long.parseLong(line.trim());
        } catch (Exception ignored) {}
        return -1;
    }
}