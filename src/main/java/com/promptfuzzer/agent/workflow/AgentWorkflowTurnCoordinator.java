package com.promptfuzzer.agent.workflow;

import com.promptfuzzer.agent.workflow.node.AnalyzeNode;
import com.promptfuzzer.agent.workflow.node.ExecuteTargetNode;
import com.promptfuzzer.agent.workflow.node.GeneratePayloadNode;
import com.promptfuzzer.agent.workflow.node.JudgeOrExtractNode;
import com.promptfuzzer.agent.workflow.node.MemoryDeltaNode;
import com.promptfuzzer.service.AgentPlannerService;
import com.promptfuzzer.service.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentWorkflowTurnCoordinator {

    private final AnalyzeNode analyzeNode;
    private final GeneratePayloadNode generatePayloadNode;
    private final ExecuteTargetNode executeTargetNode;
    private final JudgeOrExtractNode judgeOrExtractNode;
    private final MemoryDeltaNode memoryDeltaNode;

    public WorkflowNodeResult<AgentWorkflowTurnOutput> execute(AgentWorkflowTurnRequest request) {
        AgentWorkflowContext context = request.getContext();
        AgentWorkflowState initialState = request.getState();
        Map<String, Object> turnRecord = new LinkedHashMap<>();
        turnRecord.put("turn", initialState.getTurnIndex());
        turnRecord.put("phase", initialState.getPhase());
        turnRecord.put("targetChatIndex", initialState.getTargetChatIndex());

        AgentPlannerService.StrategyAnalysis analysis;
        try {
            analysis = analyzeNode.execute(context, initialState, request.getConversationJson(),
                    request.getIntelligenceLog(), initialState.getTurnIndex(), initialState.getMaxTurns(),
                    request.getTurnRecords().size());
        } catch (Exception e) {
            return failed(initialState, turnRecord, null, "Planner 分析阶段异常: " + e.getMessage(), e);
        }

        turnRecord.put("analysis", analysis.getAnalysis());
        turnRecord.put("technique", analysis.getChosenTechnique());
        if (analysis.isShouldStop()) {
            turnRecord.put("shouldStop", true);
            turnRecord.put("stopReason", analysis.getStopReason());
            return result(WorkflowRoute.STOP, analysis, turnRecord, initialState.getAttackSignals(),
                    value(initialState.getTargetChatIndex()), null, analysis.getStopReason(), null);
        }

        String actionType = resolveActionType(context, analysis);
        turnRecord.put("action", actionType);
        try {
            DispatchResult dispatch = context.isBrowserMode()
                    ? dispatchBrowser(request, initialState, analysis, actionType, turnRecord)
                    : dispatchHttp(request, initialState, analysis, turnRecord);
            AgentWorkflowState completedState = initialState.toBuilder()
                    .targetChatIndex(dispatch.targetChatIndex())
                    .message(stringValue(turnRecord.get("message")))
                    .rawResponse(dispatch.rawResponse())
                    .extractedText(stringValue(turnRecord.get("extractedText")))
                    .verdict(stringValue(turnRecord.get("verdict")))
                    .evidence(stringValue(turnRecord.get("evidence")))
                    .build();

            if ("SUCCESS".equals(turnRecord.get("verdict"))) {
                return result(WorkflowRoute.SUCCESS, analysis, turnRecord, initialState.getAttackSignals(),
                        dispatch.targetChatIndex(), stringValue(turnRecord.get("verdict")),
                        stringValue(turnRecord.get("evidence")), null);
            }

            String nextAttackSignals = memoryDeltaNode.execute(context, completedState);
            return result(WorkflowRoute.CONTINUE, analysis, turnRecord, nextAttackSignals,
                    dispatch.targetChatIndex(), stringValue(turnRecord.get("verdict")),
                    stringValue(turnRecord.get("evidence")), null);
        } catch (Exception e) {
            String evidence = turnRecord.get("evidence") != null
                    ? stringValue(turnRecord.get("evidence"))
                    : "单轮执行异常: " + e.getMessage();
            return failed(initialState, turnRecord, analysis, evidence, e);
        }
    }

    private DispatchResult dispatchHttp(AgentWorkflowTurnRequest request,
                                        AgentWorkflowState state,
                                        AgentPlannerService.StrategyAnalysis analysis,
                                        Map<String, Object> turnRecord) throws Exception {
        String technique = analysis.getChosenTechnique();
        String message = generatePayloadNode.execute(request.getContext(), state, analysis, technique,
                request.getConversationJson(), request.getTurnRecords(), state.getAgentSessionId());
        if (message == null || message.isBlank()) {
            throw new IllegalStateException("生成器返回空消息");
        }
        turnRecord.put("message", message);
        String rawResponse = executeTargetNode.sendHttp(
                request.getContext(), state, request.getRawRequestTemplate(), message);
        turnRecord.put("responseBody", rawResponse);
        applyJudgeResult(request.getContext(), state, rawResponse, turnRecord, false);
        return new DispatchResult(rawResponse, value(state.getTargetChatIndex()));
    }

    private DispatchResult dispatchBrowser(AgentWorkflowTurnRequest request,
                                           AgentWorkflowState state,
                                           AgentPlannerService.StrategyAnalysis analysis,
                                           String actionType,
                                           Map<String, Object> turnRecord) throws Exception {
        AgentPlannerService.ActionInfo action = analysis.getAction();
        int targetChatIndex = value(state.getTargetChatIndex());
        return switch (actionType) {
            case "SEND_MESSAGE" -> {
                String technique = action != null && action.getTechnique() != null
                        ? action.getTechnique() : "none";
                turnRecord.put("technique", technique);
                String message = generatePayloadNode.execute(request.getContext(), state, analysis, technique,
                        request.getConversationJson(), request.getTurnRecords(), state.getAgentSessionId());
                if ((message == null || message.isBlank()) && action != null) {
                    message = action.getMessage();
                }
                if (message == null || message.isBlank()) {
                    throw new IllegalStateException("生成器返回空消息");
                }
                turnRecord.put("message", message);
                String rawResponse = executeTargetNode.sendBrowserMessage(
                        request.getContext(), state, request.getBrowserSessionId(), message);
                turnRecord.put("extractedText", rawResponse);
                applyJudgeResult(request.getContext(), state, rawResponse, turnRecord, true);
                yield new DispatchResult(rawResponse, targetChatIndex);
            }
            case "NEW_CHAT" -> {
                turnRecord.put("technique", "new_chat");
                ExecuteTargetNode.BrowserActionResult actionResult = executeTargetNode.newChat(
                        request.getContext(), state, request.getBrowserSessionId(), targetChatIndex);
                turnRecord.put("message", null);
                turnRecord.put("verdict", null);
                turnRecord.put("evidence", actionResult.getEvidence());
                turnRecord.put("targetChatIndex", actionResult.getTargetChatIndex());
                yield new DispatchResult(null, actionResult.getTargetChatIndex());
            }
            case "READ_PAGE" -> {
                String selector = action != null ? action.getSelector() : null;
                turnRecord.put("technique", "read_page");
                String pageContent = executeTargetNode.readPage(
                        request.getContext(), state, request.getBrowserSessionId(), selector);
                turnRecord.put("extractedText", pageContent);
                turnRecord.put("message", null);
                turnRecord.put("verdict", null);
                turnRecord.put("evidence", "读取页面" + (selector != null ? "(" + selector + ")" : ""));
                yield new DispatchResult(pageContent, targetChatIndex);
            }
            case "CLICK" -> {
                String selector = action != null ? action.getSelector() : null;
                turnRecord.put("technique", "click");
                ExecuteTargetNode.BrowserActionResult actionResult = executeTargetNode.click(
                        request.getContext(), state, request.getBrowserSessionId(), selector);
                turnRecord.put("message", null);
                turnRecord.put("verdict", null);
                turnRecord.put("evidence", actionResult.getEvidence());
                yield new DispatchResult(null, targetChatIndex);
            }
            default -> {
                turnRecord.put("verdict", "UNCERTAIN");
                turnRecord.put("evidence", "未知行动类型: " + actionType);
                yield new DispatchResult(null, targetChatIndex);
            }
        };
    }

    private void applyJudgeResult(AgentWorkflowContext context,
                                  AgentWorkflowState state,
                                  String rawResponse,
                                  Map<String, Object> turnRecord,
                                  boolean browserMode) {
        AgentWorkflowState responseState = state.toBuilder().rawResponse(rawResponse).build();
        if ("ATTACK".equals(state.getPhase())) {
            JudgeService.JudgeResult judgeResult = judgeOrExtractNode.execute(context, responseState, "judge");
            turnRecord.put("extractedText", judgeResult.getExtractedText());
            turnRecord.put("verdict", judgeResult.getVerdict());
            turnRecord.put("evidence", judgeResult.getEvidence());
        } else if (browserMode) {
            judgeOrExtractNode.recordBrowserExtract(context, responseState);
            turnRecord.put("extractedText", rawResponse);
            turnRecord.put("verdict", null);
            turnRecord.put("evidence", null);
        } else {
            JudgeService.JudgeResult extractResult =
                    judgeOrExtractNode.execute(context, responseState, "extractOnly");
            turnRecord.put("extractedText", extractResult.getExtractedText());
            turnRecord.put("verdict", null);
            turnRecord.put("evidence", null);
        }
    }

    private WorkflowNodeResult<AgentWorkflowTurnOutput> failed(AgentWorkflowState state,
                                                               Map<String, Object> turnRecord,
                                                               AgentPlannerService.StrategyAnalysis analysis,
                                                               String evidence,
                                                               Exception error) {
        turnRecord.putIfAbsent("message", "");
        turnRecord.put("verdict", "UNCERTAIN");
        turnRecord.put("evidence", evidence);
        return result(WorkflowRoute.ERROR, analysis, turnRecord, state.getAttackSignals(),
                value(state.getTargetChatIndex()), "UNCERTAIN", evidence, error);
    }

    private WorkflowNodeResult<AgentWorkflowTurnOutput> result(WorkflowRoute route,
                                                               AgentPlannerService.StrategyAnalysis analysis,
                                                               Map<String, Object> turnRecord,
                                                               String attackSignals,
                                                               int targetChatIndex,
                                                               String verdict,
                                                               String evidence,
                                                               Exception error) {
        return WorkflowNodeResult.<AgentWorkflowTurnOutput>builder()
                .output(AgentWorkflowTurnOutput.builder()
                        .analysis(analysis)
                        .turnRecord(turnRecord)
                        .attackSignals(attackSignals)
                        .targetChatIndex(targetChatIndex)
                        .build())
                .route(route)
                .verdict(verdict)
                .evidence(evidence)
                .error(error)
                .stateUpdates(Map.of(
                        "targetChatIndex", targetChatIndex,
                        "attackSignals", attackSignals != null ? attackSignals : ""))
                .build();
    }

    private String resolveActionType(AgentWorkflowContext context,
                                     AgentPlannerService.StrategyAnalysis analysis) {
        if (!context.isBrowserMode() || analysis.getAction() == null
                || analysis.getAction().getType() == null) {
            return "SEND_MESSAGE";
        }
        return analysis.getAction().getType();
    }

    private int value(Integer value) {
        return value != null ? value : 0;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private record DispatchResult(String rawResponse, int targetChatIndex) {
    }
}
