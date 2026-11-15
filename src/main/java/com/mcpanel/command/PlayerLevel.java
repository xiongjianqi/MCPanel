package com.mcpanel.command;

public enum PlayerLevel {
    GUEST(0, "访客"),
    USER(1, "用户"),
    HELPER(2, "协助"),
    ADMIN(3, "管理"),
    OWNER(4, "所有者");

    public final int level;
    public final String label;

    PlayerLevel(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public static PlayerLevel fromInt(int n) {
        for (PlayerLevel p : values()) if (p.level == n) return p;
        return GUEST;
    }

    public static PlayerLevel fromName(String s) {
        if (s == null) return null;
        try { return PlayerLevel.valueOf(s.toUpperCase()); }
        catch (Exception e) { return null; }
    }
}