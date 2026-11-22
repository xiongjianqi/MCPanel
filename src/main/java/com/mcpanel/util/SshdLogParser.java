package com.mcpanel.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SshdLogParser {
    private static final int MAX_LOGS = 500;
    private static final Deque<Map<String, String>> logs = new ConcurrentLinkedDeque<>();
    private static Thread readerThread;
    private static Process sshdProcess;

    private static final Pattern ACCEPTED_PATTERN = Pattern.compile(
            "^\\w{3}\\s+\\d+\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+sshd\\[\\d+\\]:\\s+Accepted\\s+(\\S+)\\s+for\\s+(\\S+)\\s+from\\s+(\\S+)\\s+port\\s+(\\d+).*");
    private static final Pattern FAILED_PATTERN = Pattern.compile(
            "^\\w{3}\\s+\\d+\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+sshd\\[\\d+\\]:\\s+Failed\\s+(\\S+)\\s+for\\s+(?:invalid user\\s+)?(\\S+)\\s+from\\s+(\\S+)\\s+port\\s+(\\d+).*");

    public static synchronized void start() {
        if (readerThread != null && readerThread.isAlive()) {
            stop();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/usr/sbin/sshd", "-D", "-e");
            pb.redirectErrorStream(true); 
            sshdProcess = pb.start();
            
            readerThread = com.mcpanel.util.Async.worker("sshd-log-reader", () -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(sshdProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        parseLine(line);
                    }
                } catch (Exception ignored) {}
            });
            if (readerThread == null) {
                sshdProcess.destroyForcibly();
                sshdProcess = null;
                System.err.println("[SshdLogParser] 阻塞型线程已达上限，已放弃启动 sshd 日志采集");
            }

        } catch (Exception e) {
            System.err.println("[SshdLogParser] 启动 sshd 失败: " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (sshdProcess != null) {
            sshdProcess.destroyForcibly();
            sshdProcess = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    private static void parseLine(String line) {
        String timeStr = "";
        if (line.length() > 15) {
            timeStr = line.substring(0, 15);
        }

        Matcher ma = ACCEPTED_PATTERN.matcher(line);
        if (ma.find()) {
            addLog(timeStr, ma.group(3), ma.group(2), ma.group(1), "成功");
            return;
        }

        Matcher mf = FAILED_PATTERN.matcher(line);
        if (mf.find()) {
            addLog(timeStr, mf.group(3), mf.group(2), mf.group(1), "失败");
        }
    }

    private static void addLog(String time, String ip, String user, String method, String status) {
        Map<String, String> log = new LinkedHashMap<>();
        log.put("time", time);
        log.put("ip", ip);
        log.put("user", user);
        
        String methodName = "密码";
        if ("publickey".equalsIgnoreCase(method)) methodName = "密钥";
        log.put("method", methodName);
        log.put("status", status);

        logs.addFirst(log); 
        while (logs.size() > MAX_LOGS) {
            logs.pollLast();
        }
    }

    public static List<Map<String, String>> getLogs() {
        return new ArrayList<>(logs);
    }
}