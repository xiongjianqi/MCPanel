package com.mcpanel.mc;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.instance.Instance;
import com.mcpanel.instance.InstanceManager;
import com.mcpanel.util.LinuxUserManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerProcess {
    private final ConfigManager config;
    private final boolean isVelocity;
    private Process process;
    private Process logProcess;
    private BufferedWriter processInput;
    private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>(10000);
    private final List<OutputListener> listeners = new CopyOnWriteArrayList<>();
    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<ChatListener> chatListeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private volatile boolean starting = false;
    private volatile boolean installing = false;
    /** 已发起关闭（面板按钮 / 面板终端输入 stop / 控制台广播 / 游戏内 stop） */
    private volatile boolean stopping = false;
    private volatile Process installerProcess = null;
    private volatile long startTime;
    private volatile long actualJavaPid = -1;
    public volatile boolean needsEula = false;

    /** 连续异常退出次数，达到上限后不再自动重启 */
    private volatile int consecutiveCrashes = 0;
    /** 下一次 doStart 是自动重启触发的（不算用户主动启动，不重置崩溃计数） */
    private volatile boolean autoRestartNext = false;
    private static final int MAX_CONSECUTIVE_CRASHES = 3;
    /** 运行超过这个时长后再退出，视为"稳定过"，崩溃计数归零 */
    private static final long STABLE_UPTIME_MS = 5 * 60 * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long STARTUP_TIMEOUT_MS = 180_000;

    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^\\[[^\\]]+\\] \\[[^\\]]+/INFO\\]: (?:\\[[^\\]]+\\] )*<([^>]+)> (.*)$");
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern SECTION_PATTERN = Pattern.compile("§.");

    private static boolean isReadyLine(String line) {
        return line != null && (line.contains("Done (") || line.contains("Listening on /"));
    }

    public interface OutputListener { void onOutput(String line); }
    public interface StatusListener { void onStatusChange(boolean running); }
    public interface StateListener  { void onStateChange(String state); }
    public interface ChatListener   { void onPlayerChat(String player, String message); }

    public ServerProcess(ConfigManager config) {
        this.config = config;
        String t = config.get("_instance.type", "");
        if ("velocity".equals(t)) this.isVelocity = true;
        else if ("mc".equals(t))  this.isVelocity = false;
        else this.isVelocity = config.has("velocity.start.command") || config.has("velocity.port");
    }

    public boolean isVelocity() { return isVelocity; }
    public boolean isContainerMode() {
        return "docker".equals(config.get("_sandbox.mode", "none")) && DockerManager.isDockerAvailable();
    }

    public String getStateString() {
        if (installing) return "installing";
        if (needsEula) return "eula";
        if (stopping) return "stopping";
        if (starting) return "starting";
        if (running) return "running";
        return "stopped";
    }

    public boolean isRunning()     { return running; }
    public boolean isStarting()    { return starting; }
    public boolean isStopping()    { return stopping; }
    public boolean isInstalling()  { return installing; }

    /** 标记"正在关闭"：面板发出关闭命令、或从日志里看到服务端开始关闭时调用 */
    private void markStopping() {
        if (stopping || (!running && !starting)) return;
        stopping = true;
        notifyStateChange();
        final long myStart = startTime;
        // 兜底：关闭命令没生效（比如被插件拦截）时，3 分钟后恢复成运行中，避免一直卡在"关闭中"
        com.mcpanel.util.Async.later(180_000L, () -> {
            if (stopping && running && startTime == myStart) {
                stopping = false;
                notifyStateChange();
            }
        });
    }

    /** 判断一条命令是不是面板配置的关闭命令（默认 stop / shutdown） */
    private boolean isShutdownCommand(String cmd) {
        if (cmd == null) return false;
        String c = cmd.trim();
        if (c.isEmpty()) return false;
        String configured = config.get("terminal.shutdown.command", isVelocity ? "shutdown" : "stop");
        if (configured != null) {
            String cfg = configured.trim();
            if (!cfg.isEmpty() && c.equalsIgnoreCase(cfg)) return true;
        }
        return "stop".equalsIgnoreCase(c) || "shutdown".equalsIgnoreCase(c);
    }

    /** 服务端输出里出现这些内容，说明是正常关闭流程（包括游戏内 /stop） */
    private void noteShutdownOutput(String line) {
        if (line == null) return;
        if (line.contains("Stopping the server") || line.contains("Stopping server")
                || line.contains("Shutting down the server") || line.contains("Shutting down server")
                || line.contains("Shutting down the proxy")) {
            markStopping();
        }
    }
    public boolean isEulaFileAgreed() { return checkEulaFile(); }

    public void notifyStateChange() {
        String state = getStateString();
        for (StateListener l : stateListeners) {
            try { l.onStateChange(state); } catch (Exception ignored) {}
        }
    }

    private boolean checkEulaFile() {
        try {
            File serverDir = config.resolve(config.get("mc.server.dir", "server"));
            File eulaFile = new File(serverDir, "eula.txt");
            if (!eulaFile.exists()) return false;
            for (String line : Files.readAllLines(eulaFile.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("eula=") && "true".equalsIgnoreCase(s.substring(5).trim())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ★ 核心安全隔离：PID 文件存放在 root 权限的 data/run/ 目录下
    private File getRunDir() {
        String instId = config.get("_instance.id", "default");
        File rootDir = ConfigManager.getInstance().getRootDir();
        File runBase = new File(rootDir, "data/run");
        File runDir = new File(runBase, instId);
        try {
            if (!runDir.getCanonicalPath().startsWith(runBase.getCanonicalPath())) {
                throw new SecurityException("Invalid instance ID");
            }
        } catch (Exception e) {
            runDir = new File(runBase, "default");
        }
        if (!runDir.exists()) runDir.mkdirs();
        return runDir;
    }

    private File getPidFile() {
        return new File(getRunDir(), "shell.pid");
    }

    /** 线程名里带的实例标识，线程 dump 时能直接看出是哪个实例 */
    private String instanceKey() {
        String id = config.get("_instance.id", "default");
        if (id == null || id.isEmpty()) return "default";
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public synchronized void runInstaller(String[] command, File workDir, long timeoutMs,
                                          Consumer<Integer> onExit) {
        if (installing) throw new IllegalStateException("已有安装进程在运行");
        if (running) throw new IllegalStateException("服务器正在运行，请先停止后再安装");

        installing = true;
        notifyStateChange();
        broadcast("[Panel] 启动安装进程: " + String.join(" ", command));

        final Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            p = pb.start();
            installerProcess = p;
        } catch (IOException e) {
            installing = false;
            installerProcess = null;
            broadcast("[Panel] ✗ 安装进程启动失败: " + e.getMessage());
            notifyStateChange();
            if (onExit != null) onExit.accept(-1);
            return;
        }

        com.mcpanel.util.Async.worker("installer-log-reader", () -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String cleanLine = ANSI_PATTERN.matcher(line).replaceAll("");
                    if (cleanLine.trim().matches("^>+$")) continue;
                    broadcast(cleanLine);
                }
            } catch (IOException ignored) {}

            int code = -1;
            try {
                if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) code = p.exitValue();
                else p.destroyForcibly();
            } catch (Exception ignored) {}

            installing = false;
            installerProcess = null;
            broadcast("[Panel] 安装进程已退出，返回码: " + code);
            notifyStateChange();

            String instId = config.get("_instance.id", "");
            Instance inst = InstanceManager.getInstance().getInstance(instId);
            if (inst != null && inst.sysUser != null && workDir != null) {
                LinuxUserManager.chownDir(workDir, inst.sysUser);
            }

            if (onExit != null) onExit.accept(code);
        });
    }

    public void startIfPresent() {
        String autoKey = isVelocity ? "velocity.auto.start" : "mc.auto.start";
        if (!"true".equalsIgnoreCase(config.get(autoKey, "true"))) return;
        if (isContainerMode()) { start(); return; }

        String dirKey = isVelocity ? "velocity.server.dir" : "mc.server.dir";
        File serverDir = config.resolve(config.get(dirKey, "server"));
        if (!serverDir.exists()) { serverDir.mkdirs(); return; }
        File[] jars = serverDir.listFiles((d, n) -> n.endsWith(".jar"));
        File runSh = new File(serverDir, "run.sh");
        if ((jars == null || jars.length == 0) && !runSh.exists()) return;
        start();
    }

    // 启动过程包含 pkill / chown / su 等等待型操作，既不能把调用方线程（尤其是 Undertow IO 线程）
    // 堵住，也不能整个方法持有对象锁，否则 dashboard、终端等请求会一起排队造成页面卡死。
    private final java.util.concurrent.atomic.AtomicBoolean startInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public void start() {
        if (!startInFlight.compareAndSet(false, true)) return; // 已经在启动，直接忽略重复请求
        try {
            doStart();
        } catch (Throwable t) {
            broadcast("[Panel] ✗ 启动异常: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage()));
            setState(false, false);
            notifyStateChange();
        } finally {
            startInFlight.set(false);
        }
    }

    private void doStart() {
        if (running || starting || installing) return;

        // 用户/面板主动启动时清零崩溃计数；自动重启触发的启动要保留计数
        if (!autoRestartNext) consecutiveCrashes = 0;
        autoRestartNext = false;
        stopping = false;

        // 线程配额已满时直接拒绝启动：否则日志读取线程拿不到配额，子进程写满 stdout 管道后
        // 会卡住，表现为"点了启动但服务端不动"，比明确报错更难排查。
        if (com.mcpanel.util.Async.workerCount() >= com.mcpanel.util.Async.maxWorkers()) {
            failStart("阻塞型线程配额已满（" + com.mcpanel.util.Async.workerCount() + "/"
                    + com.mcpanel.util.Async.maxWorkers()
                    + "），请先停止不用的实例，或用 -Dpanel.max.blocking.threads 调大上限");
            return;
        }

        if (!isVelocity && needsEula) {
            if (!checkEulaFile()) {
                broadcast("[Panel] ✗ 需要先同意 EULA 才能启动");
                setState(false, false);
                return;
            }
            needsEula = false;
            notifyStateChange();
        }

        actualJavaPid = -1;
        if (isContainerMode()) startContainer();
        else startNative();
    }

    private void startContainer() {
        String image = config.get("container.image", "itzg/minecraft-server:latest");
        String containerName = "mcpanel-" + config.get("_instance.id", "default");
        int port = isVelocity
                ? config.getInt("velocity.port", 25577)
                : config.getInt("mc.port", 25565);
        File workDir = config.resolve(isVelocity ? "velocity.server.dir" : "mc.server.dir");
        if (!workDir.exists()) workDir.mkdirs();

        setState(true, true);
        startTime = System.currentTimeMillis();
        broadcast("[Panel] 正在启动容器: " + image);

        try {
            List<String> envs = new ArrayList<>();
            if (!isVelocity) envs.add("EULA=TRUE");
            envs.add("TYPE=" + (isVelocity ? "VELOCITY" : "VANILLA"));
            envs.add("MEMORY=" + config.get("container.memory", "2G"));

            String containerId = DockerManager.runContainer(containerName, image, workDir, port, envs);
            broadcast("[Panel] 容器已启动，ID: " + containerId);

            final long myStart = startTime;
            com.mcpanel.util.Async.later(STARTUP_TIMEOUT_MS, () -> {
                if (starting && running && startTime == myStart) setState(true, false);
            });

            logProcess = DockerManager.streamLogs(containerName);
            com.mcpanel.util.Async.worker("mc-log-reader-" + instanceKey(), () -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(logProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String cleanLine = ANSI_PATTERN.matcher(line).replaceAll("");
                        if (!outputQueue.offer(cleanLine)) {
                            outputQueue.poll();
                            outputQueue.offer(cleanLine);
                        }
                        for (OutputListener l : listeners) {
                            try { l.onOutput(cleanLine); } catch (Exception ignored) {}
                        }
                        noteShutdownOutput(cleanLine);
                        if (starting && isReadyLine(cleanLine)) setState(true, false);

                        Matcher m = CHAT_PATTERN.matcher(cleanLine);
                        if (m.matches()) {
                            String rawPlayer = m.group(1);
                            String msg = m.group(2);
                            String player = SECTION_PATTERN.matcher(rawPlayer).replaceAll("").trim();
                            if (!player.isEmpty()) {
                                for (ChatListener l : chatListeners) {
                                    try { l.onPlayerChat(player, msg); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {}
                onProcessExit(myStart);
            });

        } catch (Exception e) {
            broadcast("[Panel] ✗ 容器启动失败: " + e.getMessage());
            setState(false, false);
        }
    }

    private void startNative() {
        if (isVelocity) patchVelocityToml(); else patchServerProperties();

        String cmdKey = isVelocity ? "velocity.start.command" : "mc.start.command";
        String dirKey = isVelocity ? "velocity.server.dir" : "mc.server.dir";
        
        String defaultCmd = isVelocity
                ? "java -Xms512M -Xmx512M -jar velocity.jar"
                : "java -Xmx2G -jar server.jar nogui";
        
        String originalCmd = config.get(cmdKey, defaultCmd);
        
        if (originalCmd == null || originalCmd.trim().isEmpty()) {
            originalCmd = defaultCmd;
            config.set(cmdKey, defaultCmd);
            broadcast("[Panel] ⚠ 启动命令为空，已强制恢复默认命令");
        }

        originalCmd = originalCmd.replace("\\", " ").replace("\n", " ").replace("\r", " ");
        originalCmd = originalCmd.replaceAll("\\s+", " ").trim();

        if (originalCmd.contains(";") || originalCmd.contains("|") || originalCmd.contains("&")
                || originalCmd.contains("$") || originalCmd.contains("`")
                || originalCmd.contains(">") || originalCmd.contains("<")) {
            failStart("启动命令包含非法元字符，出于安全考虑已拦截");
            return;
        }

        String serverDirStr = config.get(dirKey, "server");
        if (serverDirStr.contains("..") || serverDirStr.contains(";") || serverDirStr.contains("&")
                || serverDirStr.contains("|") || serverDirStr.contains("$") || serverDirStr.contains("`")
                || serverDirStr.contains("\"") || serverDirStr.contains("'")) {
            failStart("服务端目录路径包含非法字符");
            return;
        }

        File workDir = config.resolve(serverDirStr);
        if (!workDir.exists()) {
            failStart("服务端目录不存在: " + workDir);
            return;
        }

        // 启动前先处理 pid 文件里残留的旧 java 进程（面板重启/上次强杀后可能还活着），
        // 否则每次"停不下来 -> 再启动"都会多留一个 JVM（几十条线程）和一个日志读取线程。
        if (!running && !starting) stopPidFileProcessGracefully(15);

        setState(true, true);
        startTime = System.currentTimeMillis();

        File pidFile = getPidFile();
        pidFile.delete();

        try {
            List<String> command = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:[^\\s\"]+|\"[^\"]*\")+").matcher(originalCmd);
            while (m.find()) {
                String part = m.group();
                if (part.startsWith("\"") && part.endsWith("\"")) {
                    part = part.substring(1, part.length() - 1);
                }
                command.add(part);
            }
            if (command.isEmpty()) {
                failStart("启动命令为空");
                return;
            }

            if (!command.isEmpty()) {
                String first = command.get(0);
                if ("java".equals(first) || first.endsWith("/java") || first.endsWith("\\java.exe") || first.endsWith("\\java")) {
                    String javaPath = config.get("mc.java.path", "");
                    if (javaPath == null || javaPath.isEmpty()) {
                        javaPath = ConfigManager.getInstance().get("mc.java.path", "");
                    }
                    if (javaPath != null && !javaPath.isEmpty() && new File(javaPath).exists()) {
                        command.set(0, javaPath);
                        broadcast("[Panel] 使用指定 Java: " + javaPath);
                    } else {
                        String defaultJava = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                        if (new File(defaultJava).exists()) {
                            command.set(0, defaultJava);
                            broadcast("[Panel] 使用面板内置 Java: " + defaultJava);
                        } else {
                            broadcast("[Panel] 使用系统环境变量默认的 Java");
                        }
                    }
                }
            }

            String instId = config.get("_instance.id", "");
            final Instance inst = InstanceManager.getInstance().getInstance(instId);
            boolean useSu = inst != null && LinuxUserManager.isEnabled(null)
                    && inst.sysUser != null && !inst.sysUser.isEmpty()
                    && LinuxUserManager.isUserReady(inst);

            ProcessBuilder pb = new ProcessBuilder(command);

            // su 预检：如果用户只在 /etc/passwd 里而没有 /etc/shadow 条目，su 会报
            // "Authentication service cannot retrieve authentication info" 并立刻退出，
            // 表现为点了启动但实例起不来。先尝试补条目，仍然不行就降级为直接启动。
            if (useSu && !LinuxUserManager.canSuTo(inst.sysUser)) {
                LinuxUserManager.ensureShadowEntry(inst.sysUser);
                if (!LinuxUserManager.canSuTo(inst.sysUser)) {
                    broadcast("[Panel] ⚠ 无法切换到实例用户 " + inst.sysUser
                            + "（su 认证失败，通常是 /etc/shadow 缺少该用户），本次以面板用户直接启动，用户隔离已关闭");
                    useSu = false;
                }
            }

            if (useSu) {
                // 安全保护：绝不能对 root 或面板自身所在的用户执行 pkill，
                // 否则会把面板/系统进程一起杀掉（表现为页面卡死、实例起不来）。
                boolean safeToKill = !"root".equals(inst.sysUser)
                        && !inst.sysUser.equals(System.getProperty("user.name"));
                if (safeToKill) {
                    try {
                        new ProcessBuilder("pkill", "-9", "-u", inst.sysUser)
                                .start().waitFor(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                } else {
                    broadcast("[Panel] ⚠ 实例用户与面板用户相同，已跳过启动前的进程清理");
                }

                File runDir = getRunDir();
                File runBase = runDir.getParentFile();                       // .../data/run
                File dataDir = (runBase != null) ? runBase.getParentFile() : null; // .../data
                try {
                    // 实例用户要能穿越 data 目录，才能写 shell.pid；
                    // 这里给 711（可穿越、不可列出），不破坏原有的目录隐藏效果。
                    if (dataDir != null) {
                        new ProcessBuilder("chown", "root:root", dataDir.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                        new ProcessBuilder("chmod", "711", dataDir.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                    }
                    if (runBase != null) {
                        new ProcessBuilder("chown", "root:root", runBase.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                        new ProcessBuilder("chmod", "711", runBase.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                    }
                    new ProcessBuilder("chown", "root:root", runDir.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                    new ProcessBuilder("chmod", "755", runDir.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {}

                // 预创建 PID 文件并赋权给实例用户（让其可以写入，但无法删除）
                Files.write(pidFile.toPath(), new byte[0]);
                new ProcessBuilder("chown", inst.sysUser + ":" + inst.sysUser, pidFile.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);
                new ProcessBuilder("chmod", "600", pidFile.getAbsolutePath()).start().waitFor(2, TimeUnit.SECONDS);

                StringBuilder sb = new StringBuilder();
                sb.append("cd ").append(escapeShellArg(workDir.getAbsolutePath())).append("; ");
                sb.append("echo $$ > ").append(escapeShellArg(pidFile.getAbsolutePath())).append("; ");
                sb.append("exec ");
                for (String c : command) {
                    sb.append(escapeShellArg(c)).append(" ");
                }

                List<String> newCmd = new ArrayList<>();
                newCmd.add(LinuxUserManager.getSuPath());
                newCmd.add("-");
                newCmd.add(inst.sysUser);
                newCmd.add("-c");
                newCmd.add(sb.toString());

                pb = new ProcessBuilder(newCmd);
                pb.directory(workDir);
                broadcast("[Panel] 使用系统 su 隔离启动 (安全直接执行模式): " + inst.sysUser);
            } else {
                pb.directory(workDir);
            }

            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            process = proc;
            processInput = new BufferedWriter(
                    new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));

            com.mcpanel.util.Async.worker("pid-finder", () -> {
                for (int i = 0; i < 15; i++) {
                    try {
                        Thread.sleep(1000);
                        if (!running && !starting) break;
                        
                        if (pidFile.exists()) {
                            try {
                                String pidStr = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
                                if (!pidStr.isEmpty()) {
                                    actualJavaPid = Long.parseLong(pidStr);
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                        
                        // 兜底尝试：进程本身就是 java（没走 su / bash 包装）时，
                        // descendants() 只会返回子进程，永远找不到，所以要先看自己
                        if (process != null && process.isAlive()) {
                            if (isJavaHandle(process.toHandle())) {
                                actualJavaPid = process.pid();
                                break;
                            }
                            Optional<ProcessHandle> javaChild = process.toHandle().descendants()
                                    .filter(ServerProcess::isJavaHandle)
                                    .findFirst();
                            if (javaChild.isPresent()) {
                                actualJavaPid = javaChild.get().pid();
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

            final long myStart = startTime;
            com.mcpanel.util.Async.later(STARTUP_TIMEOUT_MS, () -> {
                if (startTime != myStart || !starting) return;
                if (actualJavaPid <= 0) {
                    // 启动命令卡住（su 等密码、命令等待输入等）时日志线程会永远阻塞在 readLine 上，
                    // 这正是"每次启动都漏一条线程"的来源，所以超时必须强制收尾。
                    broadcast("[Panel] ✗ 启动超时：未检测到 Java 进程（启动命令可能卡在 su 认证或等待输入），已强制清理");
                    kill();
                    return;
                }
                if (running) setState(true, false);
            });

            com.mcpanel.util.Async.worker("mc-log-reader-" + instanceKey(), () -> {
                boolean eulaPending = false;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains("You need to agree to the EULA")) eulaPending = true;
                        String cleanLine = ANSI_PATTERN.matcher(line).replaceAll("");
                        if (cleanLine.trim().matches("^>+$")) continue;

                        if (!outputQueue.offer(cleanLine)) {
                            outputQueue.poll();
                            outputQueue.offer(cleanLine);
                        }
                        for (OutputListener l : listeners) {
                            try { l.onOutput(cleanLine); } catch (Exception ignored) {}
                        }
                        noteShutdownOutput(cleanLine);
                        if (starting && isReadyLine(cleanLine)) setState(true, false);

                        Matcher chatMatcher = CHAT_PATTERN.matcher(cleanLine);
                        if (chatMatcher.matches()) {
                            String rawPlayer = chatMatcher.group(1);
                            String msg = chatMatcher.group(2);
                            String player = SECTION_PATTERN.matcher(rawPlayer).replaceAll("").trim();
                            if (!player.isEmpty()) {
                                for (ChatListener l : chatListeners) {
                                    try { l.onPlayerChat(player, msg); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    if (process == proc) {
                        try { if (processInput != null) processInput.close(); }
                        catch (Exception ignored) {}
                        processInput = null;
                        process = null;
                    }
                }

                if (eulaPending) {
                    needsEula = true;
                    stopping = false;
                    setState(false, false);
                    broadcast("需要同意 Minecraft EULA！");
                    broadcast("请阅读 https://aka.ms/MinecraftEULA 后在终端输入 Y 同意，N 不同意（Y/N）");
                    return;
                }
                onProcessExit(myStart);
            });

        } catch (IOException e) {
            failStart("启动进程失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        } catch (Throwable t) {
            failStart("启动异常: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage()));
        }
    }

    private static String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private void failStart(String reason) {
        broadcast("[Panel] ✗ " + reason);
        stopping = false;
        setState(false, false);
        System.err.println("[ServerProcess] " + reason);
    }

    private void patchServerProperties() {
        File serverDir = config.resolve(config.get("mc.server.dir", "server"));
        if (!serverDir.exists()) serverDir.mkdirs();
        File sp = new File(serverDir, "server.properties");
        int targetPort = config.getInt("mc.port", 25566);
        String targetIp = "";

        if (!sp.exists()) {
            Properties p = new Properties();
            p.setProperty("server-ip", targetIp);
            p.setProperty("server-port", String.valueOf(targetPort));
            try (OutputStream out = new FileOutputStream(sp)) {
                p.store(out, "auto-created");
            } catch (IOException e) {}
            return;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(sp)) {
            p.load(in);
        } catch (IOException e) { return; }
        if (String.valueOf(targetPort).equals(p.getProperty("server-port", "25565"))) return;
        p.setProperty("server-ip", targetIp);
        p.setProperty("server-port", String.valueOf(targetPort));
        try (OutputStream out = new FileOutputStream(sp)) {
            p.store(out, "auto-patched");
        } catch (IOException e) {}
    }

    private void patchVelocityToml() {
        File serverDir = config.resolve(config.get("velocity.server.dir", "server"));
        if (!serverDir.exists()) serverDir.mkdirs();
        File toml = new File(serverDir, "velocity.toml");
        int targetPort = config.getInt("velocity.port", 25577);
        String newBind = "0.0.0.0:" + targetPort;
        if (!toml.exists()) return;
        try {
            List<String> lines = Files.readAllLines(toml.toPath(), StandardCharsets.UTF_8);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("bind") && trimmed.contains("=")) {
                    lines.set(i, "bind = \"" + newBind + "\"");
                    changed = true;
                    break;
                }
            }
            if (changed) Files.write(toml.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private void setState(boolean r, boolean s) {
        boolean wasR = this.running;
        boolean wasS = this.starting;
        String prevState = getStateString();
        this.running = r;
        this.starting = s;
        String newState = getStateString();
        if (wasR == r && wasS == s && prevState.equals(newState)) return;

        if (wasR != r) {
            for (StatusListener l : statusListeners) {
                try { l.onStatusChange(r); } catch (Exception ignored) {}
            }
        }
        for (StateListener l : stateListeners) {
            try { l.onStateChange(newState); } catch (Exception ignored) {}
        }
    }

    public void broadcast(String msg) {
        for (OutputListener l : listeners) {
            try { l.onOutput(msg); } catch (Exception ignored) {}
        }
    }

    public synchronized void sendCommand(String cmd) {
        if (installing || !running) return;
        if (cmd == null) return;
        String safeCmd = cmd.replace("\n", "").replace("\r", "");
        if (isShutdownCommand(safeCmd)) markStopping();

        if (isContainerMode()) {
            // ★ 修复：docker exec 以前直接在调用线程里跑（可能是 WebSocket IO 线程），
            // 命令卡住时会把 IO 线程堵住；改为串行后台队列，既保持顺序又不阻塞网络线程。
            final String containerName = "mcpanel-" + config.get("_instance.id", "default");
            com.mcpanel.util.Async.serial(() -> {
                try {
                    DockerManager.execCommand(containerName, safeCmd);
                } catch (Exception e) {
                    broadcast("[Panel] 发送容器命令失败: " + e.getMessage());
                }
            });
            return;
        }

        if (processInput == null) return;
        try {
            processInput.write(safeCmd);
            processInput.newLine();
            processInput.flush();
        } catch (IOException ignored) {}
    }

    public void shutdown() {
        if (!running && !starting) return;
        markStopping();

        if (isContainerMode()) {
            try {
                DockerManager.stopContainer(
                        "mcpanel-" + config.get("_instance.id", "default"));
            } catch (Exception ignored) {}
            setState(false, false);
            destroyTree(logProcess);
            logProcess = null;
            return;
        }

        if (process == null) {
            stopping = false;
            setState(false, false);
            return;
        }

        String stopCmd = config.get("terminal.shutdown.command", isVelocity ? "shutdown" : "stop");
        sendCommand(stopCmd);
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                kill();
            } else {
                setState(false, false);
                String instId = config.get("_instance.id", "");
                Instance inst = InstanceManager.getInstance().getInstance(instId);
                if (inst != null && inst.sysUser != null) {
                    try {
                        new ProcessBuilder("pkill", "-9", "-u", inst.sysUser).start();
                    } catch (Exception ignored) {}
                }
            }
        } catch (InterruptedException ignored) {}
    }

    public void kill() {
        markStopping();
        if (installerProcess != null && installing) {
            destroyTree(installerProcess);
            installerProcess = null;
            return;
        }

        // 面板认为"已经停止"时也兜底清理一次：旧版本只 kill su 壳，java 子进程会活下来，
        // 表现为实例已关闭但进程/线程数继续上涨。
        boolean live = running || starting;
        String instId = config.get("_instance.id", "");
        Instance inst = InstanceManager.getInstance().getInstance(instId);

        if (live && inst != null && inst.sysUser != null) {
            boolean safeToKill = !"root".equals(inst.sysUser)
                    && !inst.sysUser.equals(System.getProperty("user.name"));
            if (safeToKill) {
                try {
                    new ProcessBuilder("pkill", "-9", "-u", inst.sysUser).start();
                } catch (Exception ignored) {}
            }
        }

        if (isContainerMode()) {
            try {
                DockerManager.killContainer(
                        "mcpanel-" + config.get("_instance.id", "default"));
            } catch (Exception ignored) {}
            destroyTree(logProcess);
            logProcess = null;
            setState(false, false);
            return;
        }

        killPidFileProcess();
        if (actualJavaPid > 0) {
            try {
                ProcessHandle.of(actualJavaPid).ifPresent(h -> {
                    if (h.isAlive()) destroyTree(h);
                });
            } catch (Throwable ignored) {}
        }
        destroyTree(process);
        destroyTree(logProcess);
        logProcess = null;
        if (processInput != null) {
            try { processInput.close(); } catch (Exception ignored) {}
            processInput = null;
        }
        actualJavaPid = -1;
        setState(false, false);
    }

    /** 进程树强制终止：先杀子孙进程（java、bash 等），再杀外壳，避免只杀 su 留下孤儿 JVM */
    private static void destroyTree(Process p) {
        if (p == null) return;
        try {
            destroyTree(p.toHandle());
        } catch (Throwable ignored) {}
    }

    private static void destroyTree(ProcessHandle root) {
        if (root == null) return;
        List<ProcessHandle> children = new ArrayList<>();
        try { root.descendants().forEach(children::add); } catch (Throwable ignored) {}
        Collections.reverse(children);
        for (ProcessHandle h : children) {
            try { h.destroyForcibly(); } catch (Throwable ignored) {}
        }
        try { root.destroyForcibly(); } catch (Throwable ignored) {}
    }

    /** 读 pid 文件里的进程号（su 壳 exec 之后就是 java 的 pid），无效返回 -1 */
    private long readPidFilePid() {
        File pidFile = getPidFile();
        if (!pidFile.exists()) return -1L;
        try {
            String s = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return -1L;
            long pid = Long.parseLong(s);
            return pid > 0 ? pid : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 只在命令行确实是 java 时才动手，避免 pid 被复用后误杀无关进程 */
    private static boolean isJavaHandle(ProcessHandle h) {
        try {
            return h.info().commandLine()
                    .map(c -> c.toLowerCase(Locale.ROOT).contains("java"))
                    .orElse(false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void killPidFileProcess() {
        long pid = readPidFilePid();
        if (pid <= 0) return;
        try {
            ProcessHandle.of(pid).ifPresent(h -> {
                try {
                    if (h.isAlive() && isJavaHandle(h)) destroyTree(h);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 启动前处理上一次残留的服务端：先发 SIGTERM 给世界保存留时间，超时再杀进程树。
     * 之所以不直接 -9：残留 JVM 可能还在正常读写区域文件，硬杀有损坏世界的风险。
     */
    private void stopPidFileProcessGracefully(int waitSeconds) {
        long pid = readPidFilePid();
        if (pid <= 0) return;
        try {
            java.util.Optional<ProcessHandle> ph = ProcessHandle.of(pid);
            if (!ph.isPresent()) return;
            ProcessHandle h = ph.get();
            if (!h.isAlive() || !isJavaHandle(h)) return;
            broadcast("[Panel] 发现上次残留的服务端进程 (pid=" + pid + ")，正在尝试正常关闭（最多 "
                    + waitSeconds + " 秒）...");
            h.destroy();
            long deadline = System.currentTimeMillis() + Math.max(1, waitSeconds) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (!h.isAlive()) break;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (h.isAlive()) {
                broadcast("[Panel] 残留进程未响应正常关闭，强制终止 (pid=" + pid + ")");
                destroyTree(h);
            }
        } catch (Throwable ignored) {}
    }

    public void restart() {
        // 取消重启延迟：关闭完成后立即拉起
        shutdown();
        start();
    }

    /**
     * 进程结束后的统一处理。
     * 正常关闭（面板按钮 / 面板终端输入 stop / 控制台 / 游戏内 stop）不自动重启；
     * 其他退出视为崩溃，按 terminal.auto.restart 自动重启，连续 3 次后停止。
     */
    private void onProcessExit(long myStart) {
        // 已经有新的启动（例如重启流程已经开始），这次退出属于上一轮，不能覆盖新状态
        if (startTime != myStart) return;
        boolean expected = stopping;
        stopping = false;
        long uptime = startTime > 0 ? System.currentTimeMillis() - startTime : 0L;
        if (expected || uptime >= STABLE_UPTIME_MS) consecutiveCrashes = 0;

        setState(false, false);

        if (expected) {
            broadcast("[Panel] 实例进程已退出");
            return;
        }

        consecutiveCrashes++;
        if (!config.getBool("terminal.auto.restart", true)) {
            broadcast("[Panel] 实例异常退出（自动重启已关闭）");
            return;
        }
        if (consecutiveCrashes >= MAX_CONSECUTIVE_CRASHES) {
            broadcast("[Panel] 实例已连续崩溃 " + consecutiveCrashes + " 次，停止自动重启，请检查日志");
            return;
        }
        broadcast("[Panel] 实例异常退出（连续第 " + consecutiveCrashes + " 次），正在自动重启...");
        autoRestartNext = true;
        com.mcpanel.util.Async.run(this::start);
    }

    public long getUptime() {
        return (running || starting) ? System.currentTimeMillis() - startTime : 0;
    }

    public long getPid() {
        if (isContainerMode() && running) {
            long pid = DockerManager.getContainerPid(
                    "mcpanel-" + config.get("_instance.id", "default"));
            if (pid > 0) return pid;
        }
        
        if (actualJavaPid > 0) {
            Optional<ProcessHandle> ph = ProcessHandle.of(actualJavaPid);
            if (ph.isPresent() && ph.get().isAlive()) {
                return actualJavaPid;
            } else {
                actualJavaPid = -1;
            }
        }
        
        if (running || starting) {
            File pidFile = getPidFile();
            if (pidFile.exists()) {
                try {
                    String pidStr = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
                    if (!pidStr.isEmpty()) {
                        long p = Long.parseLong(pidStr);
                        Optional<ProcessHandle> ph = ProcessHandle.of(p);
                        if (ph.isPresent() && ph.get().isAlive()) {
                            actualJavaPid = p;
                            return actualJavaPid;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        
        if (process != null && (running || starting)) {
            try {
                ProcessHandle self = process.toHandle();
                // 直接以 java 启动时，进程自己就是 java；descendants() 不包含自己
                if (isJavaHandle(self)) {
                    actualJavaPid = process.pid();
                    return actualJavaPid;
                }
                Optional<ProcessHandle> javaChild = self.descendants()
                        .filter(ServerProcess::isJavaHandle)
                        .findFirst();
                if (javaChild.isPresent()) {
                    actualJavaPid = javaChild.get().pid();
                    return actualJavaPid;
                }
            } catch (Exception ignored) {}
        }
        
        return -1;
    }

    public void addListener(OutputListener l) { listeners.add(l); }
    public void removeListener(OutputListener l) { listeners.remove(l); }
    public void addStatusListener(StatusListener l) { statusListeners.add(l); }
    public void removeStatusListener(StatusListener l) { statusListeners.remove(l); }
    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    public void addChatListener(ChatListener l) { chatListeners.add(l); }
    public void removeChatListener(ChatListener l) { chatListeners.remove(l); }

    public void sendCommandAndCapture(String cmd, long timeoutMs,
                                      Consumer<List<String>> callback) {
        final List<String> captured = Collections.synchronizedList(new ArrayList<>());
        OutputListener listener = captured::add;
        addListener(listener);
        try {
            sendCommand(cmd);
            if (timeoutMs > 0) Thread.sleep(timeoutMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            removeListener(listener);
        }
        if (callback != null) callback.accept(new ArrayList<>(captured));
    }

    public void sendCommandAndWait(String cmd, long timeoutMs,
                                   java.util.function.Predicate<String> stopWhen,
                                   Consumer<List<String>> callback) {
        final List<String> captured = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch latch = new CountDownLatch(1);
        OutputListener listener = line -> {
            captured.add(line);
            try {
                if (stopWhen != null && stopWhen.test(line)) latch.countDown();
            } catch (Throwable ignored) {}
        };
        addListener(listener);
        try {
            sendCommand(cmd);
            if (timeoutMs > 0) latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            removeListener(listener);
        }
        if (callback != null) callback.accept(new ArrayList<>(captured));
    }
}