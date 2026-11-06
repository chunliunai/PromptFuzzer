package com.promptfuzzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconSurfaceDataCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsHistoricalStringArraysAndDeduplicatesValues() {
        assertEquals(List.of("查询"),
                ReconSurfaceDataCodec.readStringArray("[\" 查询 \",\"查询\",\"\"]"));
        assertEquals(List.of(),
                ReconSurfaceDataCodec.readStringArray(""));
        assertEquals(List.of(),
                ReconSurfaceDataCodec.readStringArray("invalid json"));
    }

    @Test
    void readsNumericAndNumericStringTraceIds() {
        assertEquals(List.of(1003L),
                ReconSurfaceDataCodec.readLongArray("[1003]"));
        assertEquals(List.of(1003L),
                ReconSurfaceDataCodec.readLongArray("[\"1003\"]"));
        assertEquals(List.of(1003L, 1004L),
                ReconSurfaceDataCodec.readLongArray(
                        "[1003,\"1003\",\"invalid\",1004]"));
        assertEquals(List.of(),
                ReconSurfaceDataCodec.readLongArray("not-json"));
    }

    @Test
    void acceptsLegacyTextAndArrayEditPayloads() throws Exception {
        assertEquals("[\"query\"]",
                ReconSurfaceDataCodec.writeRequestStringArray(
                        objectMapper.readTree("\"query\"")));
        assertEquals("[\"query\",\"export\"]",
                ReconSurfaceDataCodec.writeRequestStringArray(
                        objectMapper.readTree("[\" query \",\"export\",\"query\"]")));
        assertEquals("[]",
                ReconSurfaceDataCodec.writeRequestStringArray(
                        objectMapper.readTree("\"[broken\"")));
    }
}
