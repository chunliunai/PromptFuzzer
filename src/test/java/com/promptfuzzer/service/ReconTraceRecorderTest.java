package com.promptfuzzer.service;

import com.promptfuzzer.entity.ReconTrace;
import com.promptfuzzer.repository.ReconTraceRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconTraceRecorderTest {

    @Test
    void recordsExecutionChannelForTargetCallErrors() {
        ReconTraceRepository repository = mock(ReconTraceRepository.class);
        when(repository.save(any(ReconTrace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ReconTraceRecorder recorder = new ReconTraceRecorder(repository);

        ReconTrace trace = recorder.error(
                12L,
                ReconTrace.ActionType.CAPABILITY_DISCOVERY,
                0,
                2,
                "probe",
                new IllegalStateException("target failed"),
                ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
                System.currentTimeMillis());

        assertEquals(ReconTrace.Status.ERROR, trace.getStatus());
        assertEquals(
                ReconTrace.ExecutionChannel.HTTP_TEMPLATE,
                trace.getExecutionChannel());
    }
}
