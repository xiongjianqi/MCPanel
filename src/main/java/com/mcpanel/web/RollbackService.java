package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.mc.ServerProcess;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RollbackService {
    private final ConfigManager config;
    private final ServerProcess mcServer;
    private final TerminalHandler terminal;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RollbackService(ConfigManager config, ServerProcess mcServer, TerminalHandler terminal) {
        this.config = config;
        this.mcServer = mcServer;
        this.terminal = terminal;
    }

    public boolean isRunning() { return running.get(); }

    public boolean startRollback(String backupName) {
        if (!running.compareAndSet(false, true)) return false;
        com.mcpanel.util.Async.serial(() -> {
            try { doRollback(backupName); }
            catch (Exception e) {
                if (terminal != null) terminal.log("[Panel] 回档失败：" + e.getMessage());
                e.printStackTrace();
            } finally { running.set(false); }
        });
        return true;
    }

    private void doRollback(String backupName) throws Exception {
        Path backupRoot = config.resolve("backup").toPath().toAbsolutePath().normalize();
        Path backupDir = backupRoot.resolve(backupName).normalize();
        Path serverDir = config.resolve(config.get("mc.server.dir", "server"))
                .toPath().toAbsolutePath().normalize();
        Path worldDir = serverDir.resolve("world").normalize();
        Path tempDir = serverDir.resolve("world_rollback_temp").normalize();

        if (!backupDir.startsWith(backupRoot) || !Files.exists(backupDir)) {
            throw new IOException("备份不存在: " + backupName);
        }

        if (terminal != null) terminal.log("[Panel] 开始回档：" + backupName);

        boolean didCountdown = false;
        for (int i = 10; i >= 1; i--) {
            if (!mcServer.isRunning()) break;
            didCountdown = true;
            String msg = "[回档] " + i + " 秒后服务器将回档到备份 [" + backupName + "]";
            String json = "{\"text\":\"" + escapeJson(msg) + "\",\"color\":\"red\",\"bold\":true}";
            mcServer.sendCommand("tellraw @a " + json);
            Thread.sleep(1000);
        }
        if (!didCountdown && terminal != null) {
            terminal.log("[Panel] MC 未运行，跳过倒计时");
        }

        if (mcServer.isRunning()) {
            if (terminal != null) terminal.log("[Panel] 正在关闭 MC 服务器以进行回档...");
            mcServer.sendCommand("say [回档] 服务器即将关闭以进行回档...");
            Thread.sleep(500);
            mcServer.shutdown();
            if (mcServer.isRunning()) {
                if (terminal != null) terminal.log("[Panel] MC 未能在 60 秒内正常关闭，强制终止");
                mcServer.kill();
                Thread.sleep(2000);
            }
        }

        // ★ 核心修复：先复制到临时目录，校验后再替换
        if (terminal != null) terminal.log("[Panel] 正在提取备份到临时目录...");
        if (Files.exists(tempDir)) deleteRecursive(tempDir);
        Files.createDirectories(tempDir);
        copyDirSkipText(backupDir, tempDir);

        // 备份旧的 world 以防万一
        Path oldWorld = serverDir.resolve("world_old_backup");
        if (Files.exists(worldDir)) {
            if (Files.exists(oldWorld)) deleteRecursive(oldWorld);
            Files.move(worldDir, oldWorld, StandardCopyOption.ATOMIC_MOVE);
        }

        // 替换
        Files.move(tempDir, worldDir, StandardCopyOption.ATOMIC_MOVE);
        if (terminal != null) terminal.log("[Panel] 世界文件夹替换成功");

        // 删除旧备份
        if (Files.exists(oldWorld)) {
            deleteRecursive(oldWorld);
        }

        if (terminal != null) terminal.log("[Panel] 回档完成，2 秒后重新启动服务器...");
        Thread.sleep(2000);
        try { mcServer.start(); }
        catch (Exception e) {
            if (terminal != null) terminal.log("[Panel] 自动重启失败：" + e.getMessage());
        }
    }

    private void copyDirSkipText(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.equals("backuptext.txt") && Files.isRegularFile(p)) continue;
                Path target = dst.resolve(n);
                if (Files.isDirectory(p)) copyDirSkipText(p, target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteRecursive(c);
            }
        }
        Files.deleteIfExists(p);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}