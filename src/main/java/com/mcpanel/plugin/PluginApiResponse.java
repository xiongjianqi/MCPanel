package com.mcpanel.plugin;

public interface PluginApiResponse {
    void status(int code);
    void header(String name, String value);
    void json(Object data);
    void text(String s);
    void ok();
    void error(int code, String message);
}