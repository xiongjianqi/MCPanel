package com.mcpanel.plugin;

import com.mcpanel.command.CommandRegistry;
import com.mcpanel.command.PlayerPerms;
import com.mcpanel.config.ConfigManager;

public class PanelPluginManager {
    private static PanelPluginManager instance;
    private final PluginManager mgr;

    public static synchronized PanelPluginManager getInstance() {
        if (instance == null) {
            instance = new PanelPluginManager();
        }
        return instance;
    }

    private PanelPluginManager() {
        ConfigManager config = ConfigManager.getInstance();
        CommandRegistry reg = new CommandRegistry();
        PlayerPerms perms = PlayerPerms.forRoot(config.getRootDir());
        mgr = new PluginManager(null, null, config, reg);
        mgr.loadAll();
    }

    public PluginManager getManager() { return mgr; }
}