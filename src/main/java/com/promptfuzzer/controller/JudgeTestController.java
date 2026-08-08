package com.promptfuzzer.controller;

import com.promptfuzzer.service.JudgeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class JudgeTestController {

    private final JudgeService judgeService;

    @PostMapping("/judge")
    public JudgeTestResponse judge(@RequestBody JudgeTestRequest req) {
        JudgeService.JudgeResult result = judgeService.judge(
                req.getRawResponse(),
                req.getGoal(),
                req.getAttackContext()
        );

        JudgeTestResponse resp = new JudgeTestResponse();
        resp.setGoal(req.getGoal());
        resp.setExtractedTextPreview(result.getExtractedTextPreview());
        resp.setExtractedTextLength(result.getExtractedTextLength());
        resp.setVerdict(result.getVerdict());
        resp.setEvidence(result.getEvidence());
        return resp;
    }

    @Data
    public static class JudgeTestRequest {
        private String rawResponse;
        private String goal;
        private String attackContext;
    }

    @Data
    public static class JudgeTestResponse {
        private String goal;
        private String extractedTextPreview;
        private Integer extractedTextLength;
        private String verdict;
        private String evidence;
    }
}
