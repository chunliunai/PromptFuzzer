package com.promptfuzzer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetResponseNormalizerServiceTest {

    @Test
    void extractsSseDataWithoutAiFallback() {
        JudgeService judgeService = mock(JudgeService.class);
        TargetResponseNormalizerService service = new TargetResponseNormalizerService(judgeService);

        TargetResponseNormalizerService.NormalizedTargetResponse response = service.normalize(
                "HTTP 200\n"
                        + "event: message\n"
                        + "data: {\"content\":\"hello\"}\n\n"
                        + "data: {\"delta\":\" world\"}\n\n"
                        + "data: [DONE]\n",
                "text/event-stream",
                "HTTP_REPLAY",
                true);

        assertEquals("SSE", response.responseProtocol());
        assertEquals("SSE_DATA_FIELDS", response.extractionMethod());
        assertEquals("hello\nworld", response.extractedText());
        assertFalse(response.aiFallbackUsed());
        verify(judgeService, never()).extractOnly(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void extractsLikelyJsonText() {
        TargetResponseNormalizerService service = new TargetResponseNormalizerService(mock(JudgeService.class));

        TargetResponseNormalizerService.NormalizedTargetResponse response = service.normalize(
                "{\"data\":{\"answer\":\"allowed response\"}}",
                "application/json",
                "HTTP_TEMPLATE",
                false);

        assertEquals("JSON", response.responseProtocol());
        assertEquals("JSON_LIKELY_FIELDS", response.extractionMethod());
        assertEquals("allowed response", response.extractedText());
    }

    @Test
    void usesAiFallbackForUnrecognizedJsonEnvelope() {
        JudgeService judgeService = mock(JudgeService.class);
        JudgeService.JudgeResult judgeResult = new JudgeService.JudgeResult();
        judgeResult.setExtractedText("model text from ai extractor");
        when(judgeService.extractOnly("{\"payload\":{\"unknown\":\"value\"}}")).thenReturn(judgeResult);
        TargetResponseNormalizerService service = new TargetResponseNormalizerService(judgeService);

        TargetResponseNormalizerService.NormalizedTargetResponse response = service.normalize(
                "{\"payload\":{\"unknown\":\"value\"}}",
                "application/json",
                "HTTP_TEMPLATE",
                true);

        assertEquals("AI_EXTRACT_ONLY", response.extractionMethod());
        assertEquals("model text from ai extractor", response.extractedText());
        assertTrue(response.aiFallbackUsed());
    }
}
