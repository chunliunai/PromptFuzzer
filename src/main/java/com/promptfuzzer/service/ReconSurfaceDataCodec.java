package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReconSurfaceDataCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ReconSurfaceDataCodec() {
    }

    public static String writeStringArray(JsonNode node) {
        return writeJson(normalizeStrings(node));
    }

    public static String writeLongArray(JsonNode node) {
        return writeJson(normalizeLongs(node));
    }

    public static String writeRequestStringArray(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "[]";
        }
        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (value.isBlank()) {
                return "[]";
            }
            List<String> parsed = readStringArray(value);
            return writeJson(parsed.isEmpty() && !looksLikeJson(value)
                    ? List.of(value) : parsed);
        }
        return writeStringArray(node);
    }

    public static List<String> readStringArray(String raw) {
        JsonNode node = readArray(raw);
        return node == null ? List.of() : normalizeStrings(node);
    }

    public static List<Long> readLongArray(String raw) {
        JsonNode node = readArray(raw);
        return node == null ? List.of() : normalizeLongs(node);
    }

    private static JsonNode readArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            return node.isArray() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> normalizeStrings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isValueNode() || item.isNull()) {
                continue;
            }
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static List<Long> normalizeLongs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<Long> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            try {
                if (item.isIntegralNumber()) {
                    values.add(item.longValue());
                } else if (item.isTextual()) {
                    String value = item.asText("").trim();
                    if (!value.isBlank()) {
                        values.add(Long.parseLong(value));
                    }
                }
            } catch (NumberFormatException ignored) {
                // Invalid legacy elements are omitted without failing the response.
            }
        }
        return new ArrayList<>(values);
    }

    private static boolean looksLikeJson(String value) {
        return value.startsWith("[") || value.startsWith("{");
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Surface data", e);
        }
    }
}
