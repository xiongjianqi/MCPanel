package com.mcpanel.plugin;

public interface MCPlugin {
    /** 加载时调用：此时可以 registerCommand / onServerOutput / 读配置 */
    void onLoad(PluginContext ctx);
    /** 启用时调用（面板启动后自动启用） */
    default void onEnable() {}
    /** 禁用时调用 */
    default void onDisable() {}
    /** 卸载前调用 */
    default void onUnload() {}

    default String getName() { return getClass().getSimpleName(); }
    default String getVersion() { return "1.0.0"; }
    default String getAuthor() { return "unknown"; }
    default String getDescription() { return ""; }

    /**
     * 插件 ID。规则：只允许小写字母和数字，长度 1~10。
     * 空字符串（默认）= 从 jar 文件名自动推导。
     * 用于：
     *   - !!p plugin load/unload/enable/disable <id>
     *   - 插件列表显示
     */
    default String getId() { return ""; }
}