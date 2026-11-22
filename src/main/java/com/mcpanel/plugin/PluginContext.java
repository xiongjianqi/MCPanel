package com.mcpanel.plugin;

import com.mcpanel.command.PlayerLevel;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface PluginContext {
    String getPluginName();
    File getDataFolder();
    File getSharedDataFolder();

    void sendCommand(String cmd);
    void log(String msg);
    void onServerOutput(Consumer<String> listener);
    void onServerStatus(Consumer<Boolean> listener);

    void sendCommandAndCapture(String cmd, long timeoutMs, Consumer<List<String>> callback);

    void sendCommandAndWait(String cmd, long timeoutMs,
                            Predicate<String> stopWhen,
                            Consumer<List<String>> callback);

    void registerWebHandler(PluginWebHandler handler);
    void registerCommand(String name, CommandHandler handler);
    void registerCommand(String name, PlayerLevel minLevel, CommandHandler handler);
    void unregisterCommand(String name);

    PlayerLevel getSenderLevel(String sender);

    // ==================== 面板页面 ====================

    void registerPanelPage(PanelPage page);
    void unregisterPanelPage(String pageId);

    // ==================== 自定义 API ====================
    // 前端通过 fetch('/api/plugin-api/<path>', {method}) 打到你的 handler

    void registerApi(String method, String path, PluginApiRegistry.Handler handler);
    void unregisterApi(String method, String path);

    // ==================== 主动推送事件 ====================
    // 前端通过 onPluginEvent(eventName, fn) 接收

    void pushEvent(String event, Object data);

    // ==================== 实例信息 ====================

    String getCurrentInstanceId();
    List<InstanceInfo> listInstances();

    // ==================== 实例操作（启停 / 文件） ====================
    // instanceId 可用 ctx.listInstances() 里的 id；文件路径相对该实例目录（instances/<id>/），
    // 不能写出实例目录之外，越界路径一律按失败处理。

    boolean isInstanceRunning(String instanceId);
    boolean startInstance(String instanceId);
    boolean stopInstance(String instanceId);
    boolean restartInstance(String instanceId);

    /** 列目录：目录名以 "/" 结尾，目录在前；失败返回空列表 */
    List<String> listInstanceFiles(String instanceId, String path);

    /** 读文本文件（UTF-8，最多 maxBytes 字节，<=0 时按 1MB）；失败或超限返回 null */
    String readInstanceFile(String instanceId, String path, int maxBytes);

    /** 写文本文件（UTF-8，自动创建父目录）；成功返回 true */
    boolean writeInstanceFile(String instanceId, String path, String content);

    /** 删除文件或目录（目录递归删除）；成功返回 true */
    boolean deleteInstanceFile(String instanceId, String path);

    /** 创建目录（含父目录）；成功返回 true */
    boolean makeInstanceDir(String instanceId, String path);

    // ==================== 路由 API ====================

    void setPendingRoute(String playerName, String instanceId, int ttlSeconds);
    void setPersistentRoute(String playerName, String instanceId);
    void clearPersistentRoute(String playerName);
    String getPersistentRoute(String playerName);

    interface CommandHandler {
        void handle(String sender, String[] args);
    }

    class InstanceInfo {
        public String id;
        public String name;
        public String desc;
        public boolean running;
        public boolean whitelistEnabled;
        public List<String> whitelistNames;
    }
}