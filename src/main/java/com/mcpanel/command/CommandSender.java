package com.mcpanel.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.UserStore;
import com.mcpanel.mc.ServerProcess;
import com.mcpanel.web.TerminalHandler;

import java.util.*;

public class CommandSender {
    public enum Type { PLAYER, CONSOLE, WEB }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final Type type;
    public final String name;
    private final ServerProcess mcServer;
    private final TerminalHandler terminal;
    private final PlayerPerms perms;

    public CommandSender(Type type, String name, ServerProcess mcServer,
                         TerminalHandler terminal, PlayerPerms perms) {
        this.type = type;
        this.name = name;
        this.mcServer = mcServer;
        this.terminal = terminal;
        this.perms = perms;
    }

    public PlayerLevel getLevel() {
        if (type == Type.PLAYER) {
            PlayerPerms p = (perms != null) ? perms : PlayerPerms.getInstance();
            return p.getLevel(name);
        }
        if (type == Type.WEB) {
            // ★ 安全修复：不再直接返回 OWNER，动态查询面板权限
            if (UserStore.PLUGIN_USER.equals(name)) return PlayerLevel.GUEST;
            if (UserStore.getInstance().isMaster(name)) return PlayerLevel.OWNER;
            
            // 映射面板权限到游戏内权限
            UserStore store = UserStore.getInstance();
            if (store.hasPermission(name, "config")) return PlayerLevel.ADMIN;
            if (store.hasPermission(name, "terminal")) return PlayerLevel.HELPER;
            return PlayerLevel.USER;
        }
        return PlayerLevel.OWNER; // CONSOLE
    }

    public void reply(String message) {
        if (type == Type.PLAYER) {
            if (name == null || !name.matches("^[a-zA-Z0-9_]{3,16}$")) return; // ★ 校验玩家名
            String json = toTellrawJson(message);
            mcServer.sendCommand("tellraw " + name + " " + json);
        } else {
            if (terminal != null) terminal.log(message);
            else System.out.println("[Console] " + message);
        }
    }

    public void log(String message) {
        if (terminal != null) terminal.log(message);
    }

    public void broadcast(String message) {
        if (mcServer != null && mcServer.isRunning()) {
            // ★ 核心修复：剥离所有的 MC 颜色格式化符，防伪造
            String safeMsg = message.replace("\n", " ").replace("\r", " ").replaceAll("§.", "").replace("&", "&");
            mcServer.sendCommand("say " + safeMsg);
        }
    }

    private String toTellrawJson(String message) {
        List<Map<String, Object>> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String curColor = null;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\u00A7' && i + 1 < message.length()) {
                char code = Character.toLowerCase(message.charAt(i + 1));
                if (code == 'r') {
                    if (cur.length() > 0) {
                        parts.add(part(cur.toString(), curColor));
                        cur = new StringBuilder();
                    }
                    curColor = null;
                    i++;
                    continue;
                }
                String colorName = mcColorName(code);
                if (colorName != null) {
                    if (cur.length() > 0) {
                        parts.add(part(cur.toString(), curColor));
                        cur = new StringBuilder();
                    }
                    curColor = colorName;
                    i++;
                    continue;
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) parts.add(part(cur.toString(), curColor));
        if (parts.isEmpty()) parts.add(part(message, null));
        
        try { return MAPPER.writeValueAsString(parts); }
        catch (Exception e) { 
            try {
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("text", message);
                return MAPPER.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"text\":\"\"}";
            }
        }
    }

    private Map<String, Object> part(String text, String color) {
        Map<String, Object> p = new HashMap<>();
        p.put("text", text);
        if (color != null) p.put("color", color);
        return p;
    }

    private String mcColorName(char code) {
        switch (code) {
            case '0': return "black";
            case '1': return "dark_blue";
            case '2': return "dark_green";
            case '3': return "dark_aqua";
            case '4': return "dark_red";
            case '5': return "dark_purple";
            case '6': return "gold";
            case '7': return "gray";
            case '8': return "dark_gray";
            case '9': return "blue";
            case 'a': return "green";
            case 'b': return "aqua";
            case 'c': return "red";
            case 'd': return "light_purple";
            case 'e': return "yellow";
            case 'f': return "white";
            default: return null;
        }
    }
}