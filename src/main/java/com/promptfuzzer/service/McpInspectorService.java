package com.promptfuzzer.service;

import com.promptfuzzer.config.McpInspectorProperties;
import com.promptfuzzer.dto.McpInspectorSessionResponse;
import com.promptfuzzer.dto.McpInspectorStartRequest;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Inspector 进程生命周期管理（CVE-2026-119025 本地实验台）。
 *
 * <p>按所选版本变体启动钉版本安装的官方 MCP Inspector Web UI，
 * 负责分配端口、捕获启动日志中的一次性访问 token、停止进程。
 * 仅用于授权本地实验，不参与 PT 原有业务链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpInspectorService {

    private static final String LAUNCHER_RELATIVE =
            "node_modules/@modelcontextprotocol/inspector/clients/launcher/build/index.js";
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("MCP_INSPECTOR_API_TOKEN=([0-9a-fA-F]+)");
    private static final int MAX_LOG_LINES = 300;

    private static final Map<String, VariantSpec> VARIANTS = Map.of(
            "vulnerable", new VariantSpec("inspector-vulnerable", "2.11.0"),
            "fixed", new VariantSpec("inspector-fixed", "2.12.0")
    );
    private static final Set<String> SUPPORTED_TRANSPORTS = Set.of("http", "sse");

    private final McpInspectorProperties properties;

    private Session current;

    public synchronized McpInspectorSessionResponse start(McpInspectorStartRequest request) {
        VariantSpec spec = VARIANTS.get(Optional.ofNullable(request.getVariant())
                .orElse("vulnerable").trim().toLowerCase());
        if (spec == null) {
            throw new IllegalArgumentException("variant 必须是 vulnerable 或 fixed");
        }
        String transport = Optional.ofNullable(request.getTransport()).orElse("http").trim().toLowerCase();
        if (!SUPPORTED_TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("transport 仅支持 http / sse");
        }
        String serverUrl = request.getServerUrl() == null ? "" : request.getServerUrl().trim();
        if (serverUrl.isEmpty()) {
            throw new IllegalArgumentException("serverUrl 不能为空");
        }

        // 复用同一进程前先停止旧会话
        stopInternal();

        Path launcher = resolveLauncher(spec);
        int port = findFreePort();

        List<String> command = new ArrayList<>(List.of(
                properties.getNodeCommand(),
                launcher.toAbsolutePath().toString(),
                "--web",
                "--transport", transport,
                "--server-url", serverUrl
        ));
        String launchCommand = String.join(" ", command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("CLIENT_PORT", String.valueOf(port));
        pb.redirectErrorStream(true);

        Session session = new Session(spec, transport, serverUrl, port, launchCommand);
        try {
            Process process = pb.start();
            session.attach(process);
        } catch (Exception e) {
            throw new IllegalStateException("启动 MCP Inspector 失败：" + e.getMessage()
                    + "（请确认 node 已安装且在 PATH 中）", e);
        }
        this.current = session;

        long deadline = System.currentTimeMillis()
                + Duration.ofSeconds(properties.getStartTimeoutSeconds()).toMillis();
        synchronized (session) {
            while (session.token == null && session.process.isAlive()
                    && System.currentTimeMillis() < deadline) {
                try {
                    session.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (session.token == null) {
            String reason = session.process.isAlive()
                    ? "等待 Inspector UI 启动超时"
                    : "Inspector 进程提前退出（exit=" + safeExitCode(session.process) + "）";
            stopInternal();
            throw new IllegalStateException(reason + "，请查看日志。");
        }
        return status();
    }

    public synchronized McpInspectorSessionResponse stop() {
        stopInternal();
        return status();
    }

    public synchronized McpInspectorSessionResponse status() {
        Session s = this.current;
        if (s == null) {
            return McpInspectorSessionResponse.builder()
                    .running(false)
                    .logTail(List.of())
                    .build();
        }
        boolean alive = s.process != null && s.process.isAlive();
        String uiUrl = s.token == null ? null
                : String.format("http://127.0.0.1:%d/?MCP_INSPECTOR_API_TOKEN=%s", s.port, s.token);
        return McpInspectorSessionResponse.builder()
                .running(alive)
                .variant(s.spec.dir.equals("inspector-vulnerable") ? "vulnerable" : "fixed")
                .version(s.spec.version)
                .transport(s.transport)
                .serverUrl(s.serverUrl)
                .port(s.port)
                .uiUrl(uiUrl)
                .token(s.token)
                .launchCommand(s.launchCommand)
                .logTail(s.tail())
                .error(alive || s.token != null ? null : "Inspector 进程已退出")
                .build();
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopInternal();
    }

    // ------------------------------------------------------------------ //

    private void stopInternal() {
        Session s = this.current;
        if (s == null || s.process == null) {
            return;
        }
        Process p = s.process;
        if (p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        this.current = null;
    }

    private Path resolveLauncher(VariantSpec spec) {
        Path root = Paths.get(properties.getLabsRoot());
        if (!root.isAbsolute()) {
            root = Paths.get(System.getProperty("user.dir")).resolve(root);
        }
        Path launcher = root.resolve(spec.dir).resolve(LAUNCHER_RELATIVE).normalize();
        if (!Files.isRegularFile(launcher)) {
            throw new IllegalStateException(
                    "未找到钉版本 Inspector：" + launcher + "，请先在该目录执行 npm install。");
        }
        return launcher;
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("无法分配本地端口：" + e.getMessage(), e);
        }
    }

    private int safeExitCode(Process p) {
        try {
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private record VariantSpec(String dir, String version) {
    }

    /** 单次 Inspector 会话及其输出采集。 */
    private static final class Session {
        final VariantSpec spec;
        final String transport;
        final String serverUrl;
        final int port;
        final String launchCommand;
        final ArrayDeque<String> lines = new ArrayDeque<>();
        Process process;
        volatile String token;

        Session(VariantSpec spec, String transport, String serverUrl, int port, String launchCommand) {
            this.spec = spec;
            this.transport = transport;
            this.serverUrl = serverUrl;
            this.port = port;
            this.launchCommand = launchCommand;
        }

        void attach(Process p) {
            this.process = p;
            Thread reader = new Thread(this::readOutput, "mcp-inspector-log");
            reader.setDaemon(true);
            reader.start();
        }

        private void readOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(line);
                    Matcher m = TOKEN_PATTERN.matcher(line);
                    if (m.find() && token == null) {
                        token = m.group(1);
                        synchronized (this) {
                            notifyAll();
                        }
                    }
                }
            } catch (Exception e) {
                append("[日志读取异常] " + e.getMessage());
            }
        }

        private synchronized void append(String line) {
            lines.addLast(line);
            while (lines.size() > MAX_LOG_LINES) {
                lines.removeFirst();
            }
        }

        private synchronized List<String> tail() {
            return new ArrayList<>(lines);
        }
    }
}
