package com.mcpanel.plugin;

public interface PluginWebHandler {
    void handle(String subPath, PluginWebRequest req) throws Exception;
}