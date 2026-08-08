package com.promptfuzzer.agent.workflow.node;

import com.promptfuzzer.agent.workflow.AgentWorkflowContext;
import com.promptfuzzer.agent.workflow.AgentWorkflowNode;
import com.promptfuzzer.agent.workflow.AgentWorkflowNodes;
import com.promptfuzzer.agent.workflow.AgentWorkflowState;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.AgentWorkflowTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratePayloadNode implements AgentWorkflowNode {

    private final AgentPlannerService plannerService;
    private final AgentWorkflowTraceRecorder traceRecorder;

    @Override
    public String name() {
        return AgentWorkflowNodes.GENERATE_PAYLOAD;
    }

    public String execute(AgentWorkflowContext context,
                          AgentWorkflowState state,
                          AgentPlannerService.StrategyAnalysis analysis,
                          String chosenTechnique,
                          String conversationJson,
                          List<Map<String, Object>> turnRecords,
                          Long sessionId) throws Exception {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = mapOf(
                "chosenTechnique", chosenTechnique,
                "historyTurns", turnRecords != null ? turnRecords.size() : 0,
                "isBrowser", context.isBrowserMode()
        );
        try {
            String message = generateDiverseMessage(state, analysis, chosenTechnique,
                    conversationJson, turnRecords, sessionId);
            traceRecorder.success(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input,
                    mapOf("messageChars", message != null ? message.length() : 0,
                            "messagePreview", preview(message, 500)),
                    null, null, startedAt);
            return message;
        } catch (Exception e) {
            traceRecorder.error(context.getTask(), context.getSession(), name(), state.getPhase(),
                    state.getRetryIndex(), state.getTurnIndex(), state.getTargetChatIndex(),
                    input, null, "UNCERTAIN", "消息生成阶段异常", e, startedAt);
            throw e;
        }
    }

    private String generateDiverseMessage(AgentWorkflowState state,
                                          AgentPlannerService.StrategyAnalysis analysis,
                                          String chosenTechnique,
                                          String conversationJson,
                                          List<Map<String, Object>> turnRecords,
                                          Long sessionId) throws Exception {
        String recentPayloads = buildRecentPayloads(turnRecords, 5);
        String message = plannerService.generateMessage(
                state.getGoal(), analysis, chosenTechnique, conversationJson, state.getAttackContext(),
                state.getTargetIntelligenceMemory(), state.getBuildMemory(), state.getAttackSignals(),
                state.getStrategyPlan(), recentPayloads);
        if (!isPayloadTooSimilar(message, turnRecords)) {
            return message;
        }

        log.info("AgentSession {} turn {} generated payload is too similar; requesting one regeneration",
                sessionId, state.getTurnIndex());
        String diversityHint = appendMemorySection(state.getAttackSignals(), "Local Diversity Retry",
                "上一版生成结果与最近payload过于相似，禁止复用相同开头、锚点、语义载体或输出结构；必须换一种结构生成。重复候选: "
                        + preview(message, 220));
        return plannerService.generateMessage(
                state.getGoal(), analysis, chosenTechnique, conversationJson, state.getAttackContext(),
                state.getTargetIntelligenceMemory(), state.getBuildMemory(), diversityHint,
                state.getStrategyPlan(), recentPayloads);
    }

    private String buildRecentPayloads(List<Map<String, Object>> turnRecords, int limit) {
        if (turnRecords == null || turnRecords.isEmpty()) {
            return "（暂无最近payload）";
        }
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, turnRecords.size() - Math.max(1, limit));
        for (int i = start; i < turnRecords.size(); i++) {
            Map<String, Object> turn = turnRecords.get(i);
            Object message = turn.get("message");
            if (message == null || message.toString().isBlank()) {
                continue;
            }
            sb.append("T").append(turn.getOrDefault("turn", i + 1))
                    .append(" [").append(turn.getOrDefault("phase", "?"))
                    .append(" / ").append(turn.getOrDefault("technique", "?"))
                    .append("]: ")
                    .append(preview(message.toString(), 220))
                    .append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "（最近记录中没有可发送payload）";
    }

    private boolean isPayloadTooSimilar(String message, List<Map<String, Object>> turnRecords) {
        if (message == null || message.isBlank() || turnRecords == null || turnRecords.isEmpty()) {
            return false;
        }
        String current = normalizePayloadForCompare(message);
        if (current.length() < 16) {
            return false;
        }
        int start = Math.max(0, turnRecords.size() - 3);
        for (int i = start; i < turnRecords.size(); i++) {
            Object previousMessage = turnRecords.get(i).get("message");
            if (previousMessage == null || previousMessage.toString().isBlank()) {
                continue;
            }
            String previous = normalizePayloadForCompare(previousMessage.toString());
            if (previous.length() < 16) {
                continue;
            }
            if (current.equals(previous)) {
                return true;
            }
            int prefix = commonPrefixLength(current, previous);
            if (prefix >= 28 && prefix >= Math.min(current.length(), previous.length()) * 0.45) {
                return true;
            }
        }
        return false;
    }

    private String normalizePayloadForCompare(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[\\p{Punct}]+", "")
                .trim();
    }

    private int commonPrefixLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        int i = 0;
        while (i < max && left.charAt(i) == right.charAt(i)) {
            i++;
        }
        return i;
    }

    private String appendMemorySection(String existing, String title, String content) {
        String base = existing != null ? existing : "";
        String section = "\n\n## " + title + "\n" + content;
        String merged = base + section;
        int maxLen = 12000;
        if (merged.length() > maxLen) {
            return merged.substring(merged.length() - maxLen);
        }
        return merged;
    }

    private String preview(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > maxLen ? oneLine.substring(0, maxLen) + "..." : oneLine;
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }
}
