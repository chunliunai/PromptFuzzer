package com.promptfuzzer.dto;

import com.promptfuzzer.entity.ReconTrace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconTraceResponseTest {

    @Test
    void exposesNullableExecutionChannelWithoutChangingExistingFields() {
        ReconTrace trace = new ReconTrace();
        trace.setId(3L);
        trace.setReconTaskId(7L);
        trace.setActionType(ReconTrace.ActionType.CAPABILITY_DISCOVERY);
        trace.setStatus(ReconTrace.Status.SUCCESS);
        trace.setObservation("observed");
        trace.setExecutionChannel(
                ReconTrace.ExecutionChannel.HTTP_REPLAY);

        ReconTraceResponse response = ReconTraceResponse.from(trace);

        assertEquals(3L, response.getId());
        assertEquals("CAPABILITY_DISCOVERY", response.getActionType());
        assertEquals("observed", response.getObservation());
        assertEquals("HTTP_REPLAY", response.getExecutionChannel());

        trace.setExecutionChannel(null);
        assertNull(ReconTraceResponse.from(trace).getExecutionChannel());
    }
}
