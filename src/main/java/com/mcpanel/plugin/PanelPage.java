package com.mcpanel.plugin;

import java.util.Map;

/**
 * 插件注册的侧边栏页面。
 */
public class PanelPage {

    public String id;
    public String title;
    public String icon;

    /** 需要的面板权限（如 "terminal" / "files"），null = 所有登录用户可见 */
    public String perm;

    /**
     * 显示范围：
     *   "global"   = 只在未进入实例时显示（全局视图）
     *   "instance" = 只在进入实例后显示（实例视图）
     *   "both"     = 两处都显示
     * 默认 "both"。
     */
    public String scope = "both";

    /** 排序，越小越靠前，默认 100 */
    public int order = 100;

    /** 渲染回调。返回完整的 HTML 页面内容 */
    public Renderer renderer;

    public interface Renderer {
        String render(PageContext ctx);
    }

    /** 渲染上下文 */
    public static class PageContext {
        public String username;
        public String instanceId;          // null = 未进入实例
        public Map<String, String> query;
    }

    public PanelPage() {}

    public PanelPage(String id, String title, String icon, Renderer renderer) {
        this.id = id;
        this.title = title;
        this.icon = icon;
        this.renderer = renderer;
    }

    // Fluent 设置（都返回 this）

    public PanelPage perm(String p) { this.perm = p; return this; }

    public PanelPage scope(String s) {
        if ("global".equals(s) || "instance".equals(s) || "both".equals(s)) {
            this.scope = s;
        } else {
            this.scope = "both";
        }
        return this;
    }

    public PanelPage order(int o) { this.order = o; return this; }

    /**
     * @deprecated 用 {@link #scope(String)} 代替。
     *   instanceScoped(true)  等价于 scope("instance")
     *   instanceScoped(false) 等价于 scope("both")
     */
    @Deprecated
    public PanelPage instanceScoped(boolean b) {
        this.scope = b ? "instance" : "both";
        return this;
    }
}