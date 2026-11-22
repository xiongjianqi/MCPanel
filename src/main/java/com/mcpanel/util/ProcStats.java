package com.mcpanel.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 只读扫描 /proc，统计「本进程之外」的 java 进程及其线程数。
 *
 * `ps -eLf | wc -l` 统计的是整台机器/容器里所有进程的线程总和，面板自己的
 * ThreadMXBean 只能看到自己的线程；两边对不上时，通常就是面板已经"关闭"但
 * 实际还活着的残留 JVM（旧版本 kill 只杀 su/bash 外壳会留下孤儿 java）。
 * 这里把这种情况直接列出来，方便一眼分辨"面板在漏线程"还是"进程没被真正杀掉"。
 */
public final class ProcStats {

    public static final class JavaProc {
        public long pid;
        public int threads;
        public String cmd;
    }

    public static List<JavaProc> javaProcesses() {
        List<JavaProc> out = new ArrayList<>();
        File proc = new File("/proc");
        File[] dirs = proc.listFiles((d, n) -> !n.isEmpty() && n.chars().allMatch(Character::isDigit));
        if (dirs == null) return out;
        long self = ProcessHandle.current().pid();
        for (File d : dirs) {
            long pid;
            try {
                pid = Long.parseLong(d.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self) continue;
            try {
                String cmdline = new String(Files.readAllBytes(new File(d, "cmdline").toPath()),
                        StandardCharsets.UTF_8).replace('\0', ' ').trim();
                if (!cmdline.toLowerCase(Locale.ROOT).contains("java")) continue;
                File[] tasks = new File(d, "task").listFiles();
                JavaProc p = new JavaProc();
                p.pid = pid;
                p.threads = tasks == null ? 0 : tasks.length;
                p.cmd = cmdline.length() > 160 ? cmdline.substring(0, 160) : cmdline;
                out.add(p);
            } catch (Throwable ignored) {
                // 进程在扫描过程中退出属于正常现象
            }
        }
        out.sort((a, b) -> Integer.compare(b.threads, a.threads));
        return out;
    }

    /** 读取某个进程当前的线程数；进程不存在返回 0 */
    public static int threadsOf(long pid) {
        if (pid <= 0) return 0;
        String[] names = new File("/proc/" + pid + "/task").list();
        return names == null ? 0 : names.length;
    }

    public static String summary() {
        List<JavaProc> list = javaProcesses();
        if (list.isEmpty()) return "无其他 java 进程";
        long total = 0;
        for (JavaProc p : list) total += p.threads;
        StringBuilder sb = new StringBuilder();
        sb.append("其他 java 进程 ").append(list.size()).append(" 个，线程合计 ").append(total).append(" 条 [");
        int shown = 0;
        for (JavaProc p : list) {
            if (shown >= 8) {
                sb.append(", ...");
                break;
            }
            if (shown > 0) sb.append(", ");
            sb.append("pid=").append(p.pid).append('×').append(p.threads);
            shown++;
        }
        sb.append(']');
        return sb.toString();
    }

    private ProcStats() {
    }
}
