package com.promptfuzzer.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptfuzzer.entity.ReconApplicationSurface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconApplicationSurfaceResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesStructuredArraysForHistoricalTextColumns() {
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setCurrentTitle("账户信息查询");
        surface.setCurrentDescription("查询当前用户权限范围内的账户基本信息。");
        surface.setSupportedActions("[\"查询\"]");
        surface.setResourceObjects("[\"account\",\"账户基本信息\"]");
        surface.setEntrypoints("[\"自然语言查询\"]");
        surface.setPermissionBoundaries("[\"user-scoped\"]");
        surface.setControlBoundaries("[\"role context\"]");
        surface.setSourceTraceIds("[\"1003\"]");

        ReconApplicationSurfaceResponse response =
                ReconApplicationSurfaceResponse.from(surface);

        assertEquals(List.of("查询"), response.getSupportedActions());
        assertEquals(List.of(1003L), response.getSourceTraceIds());
        assertEquals(List.of("account", "账户基本信息"),
                response.getResourceObjects());
        assertEquals(response.getDescription(), response.getDisplayDescription());
    }

    @Test
    void invalidHistoricalJsonDegradesToEmptyArrays() {
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setSupportedActions("invalid");
        surface.setSourceTraceIds("[broken");

        ReconApplicationSurfaceResponse response =
                ReconApplicationSurfaceResponse.from(surface);

        assertTrue(response.getSupportedActions().isEmpty());
        assertTrue(response.getSourceTraceIds().isEmpty());
        JsonNode json = objectMapper.valueToTree(response);
        assertTrue(json.path("supportedActions").isArray());
        assertTrue(json.path("sourceTraceIds").isArray());
    }
}
