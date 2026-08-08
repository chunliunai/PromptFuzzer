package com.promptfuzzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class HttpRequestService {

    public String send(String rawTemplate, String payload) throws Exception {
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
        log.info("Sending {} {}", method, urlStr);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
        StringBuilder response = new StringBuilder();
        response.append("HTTP ").append(statusCode).append("\n");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }

        return response.toString();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
