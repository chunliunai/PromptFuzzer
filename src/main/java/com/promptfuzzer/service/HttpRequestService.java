package com.promptfuzzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class HttpRequestService {

    private static final long SSE_TOTAL_WINDOW_MS = 20000;
    private static final long SSE_IDLE_WINDOW_MS = 5000;
    private static final int SSE_MAX_CHARS = 120000;

    public String send(String rawTemplate, String payload) throws Exception {
        return sendDetailed(rawTemplate, payload).rawResponse();
    }

    public HttpTargetResponse sendDetailed(String rawTemplate, String payload) throws Exception {
        String filledTemplate = rawTemplate.replace("{{PAYLOAD}}", escapeJson(payload));

        String[] parts = filledTemplate.split("\r?\n\r?\n", 2);
        String headerSection = parts[0];
        String body = parts.length > 1 ? parts[1] : "";

        String[] headerLines = headerSection.split("\r?\n");
        String requestLine = headerLines[0];

        String[] requestLineParts = requestLine.split(" ");
        String method = requestLineParts[0];
        String path = requestLineParts[1];

        Map<String, String> headers = new LinkedHashMap<>();
        String host = null;
        for (int i = 1; i < headerLines.length; i++) {
            int colonIdx = headerLines[i].indexOf(":");
            if (colonIdx > 0) {
                String key = headerLines[i].substring(0, colonIdx).trim();
                String value = headerLines[i].substring(colonIdx + 1).trim();
                headers.put(key, value);
                if (key.equalsIgnoreCase("Host")) {
                    host = value;
                }
            }
        }

        String scheme = headers.getOrDefault("X-Target-Scheme", "https").toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            scheme = "https";
        }
        String urlStr = scheme + "://" + host + path;
        return sendDetailed(method, urlStr, headers, body);
    }

    public HttpTargetResponse sendDetailed(String method,
                                           String urlStr,
                                           Map<String, String> headers,
                                           String body) throws Exception {
        log.info("Sending {} {}", method, urlStr);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("Host") &&
                    !entry.getKey().equalsIgnoreCase("X-Target-Scheme") &&
                    !entry.getKey().equalsIgnoreCase("Content-Length")) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (!body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = conn.getResponseCode();
            String contentType = conn.getHeaderField("Content-Type");
            if (isEventStream(contentType)) {
                conn.setReadTimeout(5000);
            }
            String responseBody = readBody(
                    statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    contentType);
            StringBuilder rawResponse = new StringBuilder();
            rawResponse.append("HTTP ").append(statusCode).append("\n");
            if (!responseBody.isEmpty()) {
                rawResponse.append(responseBody);
                if (!responseBody.endsWith("\n")) {
                    rawResponse.append("\n");
                }
            }

            return new HttpTargetResponse(
                    statusCode,
                    contentType,
                    conn.getHeaderFields(),
                    responseBody,
                    rawResponse.toString());
        } finally {
            conn.disconnect();
        }
    }

    private String readBody(InputStream stream, String contentType) throws Exception {
        if (stream == null) {
            return "";
        }
        if (isEventStream(contentType)) {
            return readEventStreamBody(stream);
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }
        return response.toString();
    }

    private String readEventStreamBody(InputStream stream) throws Exception {
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[4096];
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + SSE_TOTAL_WINDOW_MS;
        long lastDataAt = startedAt;
        try (InputStream input = stream) {
            while (System.currentTimeMillis() < deadline) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (SocketTimeoutException e) {
                    long now = System.currentTimeMillis();
                    if (response.length() > 0
                            && now - lastDataAt >= SSE_IDLE_WINDOW_MS) {
                        log.info("Returning partial SSE response after idle timeout, chars={}",
                                response.length());
                        break;
                    }
                    continue;
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                lastDataAt = System.currentTimeMillis();
                response.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (response.indexOf("[DONE]") >= 0 || response.length() >= SSE_MAX_CHARS) {
                    break;
                }
            }
        }
        return response.toString();
    }

    private boolean isEventStream(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    public record HttpTargetResponse(
            int statusCode,
            String contentType,
            Map<String, List<String>> headers,
            String body,
            String rawResponse) {
    }
}
