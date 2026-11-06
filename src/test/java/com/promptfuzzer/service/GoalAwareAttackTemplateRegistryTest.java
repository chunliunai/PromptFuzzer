package com.promptfuzzer.service;

import com.promptfuzzer.entity.ReconApplicationSurface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GoalAwareAttackTemplateRegistryTest {

    private final GoalAwareAttackTemplateRegistry registry =
            new GoalAwareAttackTemplateRegistry();

    @Test
    void mapsOwnerScopedQueryToUnauthorizedReadTemplate() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "DATA_QUERY.ORDER",
                        "订单查询",
                        "支持按 merchant_id 查询当前商家的订单",
                        "DATA_QUERY",
                        "[\"query\", \"filter\"]",
                        "merchant_id、订单"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("UNAUTHORIZED_BUSINESS_READ", match.keyPrefix());
        assertEquals("UNAUTHORIZED_BUSINESS_READ", match.riskPattern());
    }

    @Test
    void mapsInjectionProneQueryBeforeGenericUnauthorizedRead() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "DATA_QUERY.TRANSACTION",
                        "交易记录查询",
                        "支持 account_id 查询，存在 SQL 注入和拼接风险",
                        "DATA_QUERY",
                        "[\"query\"]",
                        "account_id、A1001 only"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("QUERY_INJECTION", match.keyPrefix());
        assertEquals("QUERY_INJECTION", match.riskPattern());
        assertEquals("查询注入", match.title());
        assertEquals("交易记录查询", match.resourceBoundary());
    }

    @Test
    void mapsKnowledgeBaseSurfaceToRagKnowledgeLeak() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "KNOWLEDGE_BASE.SEARCH",
                        "企业知识库检索",
                        "支持 RAG、向量检索、source citation 和文档召回",
                        "KNOWLEDGE_BASE",
                        "[\"search\", \"retrieve\"]",
                        "知识空间、collection、chunk"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("RAG_KNOWLEDGE_LEAK", match.keyPrefix());
        assertEquals("RAG_KNOWLEDGE_LEAK", match.riskPattern());
        assertEquals("KNOWLEDGE_BASE_ACCESS_CONTROL:RAG_KNOWLEDGE_LEAK",
                feature.templateSignature());
    }

    @Test
    void mapsInternalToolingToDedicatedDisclosureSignature() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "TOOL_ORCHESTRATION.MCP",
                        "MCP 工具注册表",
                        "包含 MCP tool、Skill 文件、函数 schema 和插件配置",
                        "TOOL_ORCHESTRATION",
                        "[\"list_tools\"]",
                        "内部工具定义"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("INTERNAL_TOOLING_DISCLOSURE", match.keyPrefix());
        assertEquals("INTERNAL_TOOLING_DISCLOSURE", match.riskPattern());
        assertEquals("INTERNAL_TOOLING_DISCLOSURE:INTERNAL_TOOLING_DISCLOSURE",
                feature.templateSignature());
    }

    @Test
    void mapsStateMutationToUnauthorizedBusinessMutation() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "STATE_MUTATION.TRANSFER",
                        "资金转移",
                        "执行授权范围内业务变更，权限边界：当前账户",
                        "STATE_MUTATION",
                        "[\"变更\"]",
                        "业务状态变更"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("UNAUTHORIZED_BUSINESS_MUTATION", match.keyPrefix());
        assertEquals("UNAUTHORIZED_BUSINESS_MUTATION", match.riskPattern());
    }

    @Test
    void expandsCodeExecutionIntoDerivedAttackUnits() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "CODE_EXECUTION.PYTHON",
                        "沙箱代码执行",
                        "支持 Python 和 shell 命令执行",
                        "CODE_EXECUTION",
                        "[\"execute\"]",
                        "临时执行环境"));

        List<GoalAwareAttackTemplateRegistry.TemplateMatch> matches =
                registry.templateMatches(feature);

        assertEquals(List.of(
                "COMMAND_EXECUTION",
                "SANDBOX_BOUNDARY_BYPASS",
                "NETWORK_ACCESS_ABUSE",
                "CREDENTIAL_DISCLOSURE"),
                matches.stream()
                        .map(GoalAwareAttackTemplateRegistry.TemplateMatch
                                ::keyPrefix)
                        .toList());
    }

    @Test
    void mapsQueryInterpretationWithoutExplicitInjectionWord() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "QUERY_INTERPRETATION.GENERATE.SQL",
                        "指标 SQL 生成",
                        "支持根据自然语言生成 SQL、where 条件和报表筛选参数",
                        "QUERY_INTERPRETATION",
                        "[\"generate_sql\", \"filter\"]",
                        "数据集权限、指标范围"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("QUERY_INJECTION", match.keyPrefix());
        assertEquals("查询注入", match.title());
    }

    @Test
    void derivesCredentialDisclosureFromCodeExecution() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "CODE_EXECUTION.PYTHON",
                        "Python 沙箱",
                        "支持 Python 代码执行",
                        "CODE_EXECUTION",
                        "[\"execute\"]",
                        "临时执行环境"));

        List<GoalAwareAttackTemplateRegistry.TemplateMatch> matches =
                registry.templateMatches(feature);

        assertEquals(List.of(
                "COMMAND_EXECUTION",
                "SANDBOX_BOUNDARY_BYPASS",
                "NETWORK_ACCESS_ABUSE",
                "CREDENTIAL_DISCLOSURE"),
                matches.stream()
                        .map(GoalAwareAttackTemplateRegistry.TemplateMatch
                                ::keyPrefix)
                        .toList());
    }

    @Test
    void mapsImageGenerationToProhibitedContentTemplate() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "IMAGE_GENERATION.GENERATE.IMAGE",
                        "图片生成",
                        "支持文生图和多模态图像输出",
                        "IMAGE_GENERATION",
                        "[\"generate_image\"]",
                        "视觉内容"));

        GoalAwareAttackTemplateRegistry.TemplateMatch match =
                registry.firstTemplateMatch(feature);

        assertNotNull(match);
        assertEquals("PROHIBITED_CONTENT_GENERATION", match.keyPrefix());
        assertEquals("违禁内容生成", match.title());
    }

    @Test
    void doesNotTreatPlainProductFilterAsOwnerBoundary() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "DATA_QUERY.PRODUCT",
                        "商品查询",
                        "支持按 product_id 查询商品公开信息",
                        "DATA_QUERY",
                        "[\"query\"]",
                        "product_id"));

        assertFalse(feature.objectBoundary());
    }

    @Test
    void keepsHtmlAndCsvAsDifferentTemplateSignatures() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature html =
                registry.featureOf(surface(
                        "REPORT_RENDERING.HTML",
                        "HTML报告生成",
                        "生成 HTML report",
                        "REPORT_RENDERING",
                        "[\"generate\", \"render\"]",
                        "html"));
        GoalAwareAttackTemplateRegistry.SurfaceFeature csv =
                registry.featureOf(surface(
                        "REPORT_RENDERING.CSV",
                        "订单CSV导出",
                        "导出订单 CSV",
                        "REPORT_RENDERING",
                        "[\"export\"]",
                        "csv"));

        assertEquals("CONTEXT_LEAK:UNSAFE_RENDERING_OUTPUT",
                html.templateSignature());
        assertEquals("USER_DATA_LEAK:CSV_FORMULA_EXPORT",
                csv.templateSignature());
    }

    @Test
    void doesNotTreatFileSurfaceWithShellBoundaryAsCodeExecution() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "FILE_PROCESSING.FILE.FILESYSTEM_SCOPE.C1",
                        "文件读取",
                        "读取 ../secret/flag.txt",
                        "FILE_PROCESSING",
                        "[\"读取\"]",
                        "文档；权限边界：shell_execution_restricted"));

        assertFalse(feature.codeExecution());
        assertEquals("AUTHORIZATION:PATH_TRAVERSAL",
                feature.templateSignature());
    }

    @Test
    void doesNotTreatToolRegistryWithReadDocAsFileParsing() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "TOOL_ORCHESTRATION.BUSINESS_TOOL.TOOL_SCOPE.C1",
                        "业务工具调用",
                        "可调用 get_transactions、read_doc、transfer、run_command",
                        "TOOL_ORCHESTRATION",
                        "[\"invoke\"]",
                        "工具注册表"));

        assertFalse(feature.fileParsing());
        assertEquals("", feature.templateSignature());
    }

    @Test
    void doesNotTreatToolLevelAuthorizationAsCredentialDisclosure() {
        GoalAwareAttackTemplateRegistry.SurfaceFeature feature =
                registry.featureOf(surface(
                        "STATE_MUTATION.TRANSFER.OWNER_SCOPE.C1",
                        "资金转移",
                        "控制边界：tool-level authorization bypass",
                        "STATE_MUTATION",
                        "[\"变更\"]",
                        "权限边界：Cross-account possible"));

        assertFalse(feature.credentialDisclosure());
        assertEquals("AUTHORIZATION:UNAUTHORIZED_BUSINESS_MUTATION",
                feature.templateSignature());
    }

    private ReconApplicationSurface surface(
            String key,
            String title,
            String description,
            String type,
            String actions,
            String scope) {
        ReconApplicationSurface surface = new ReconApplicationSurface();
        surface.setId(1L);
        surface.setCapabilityKey(key);
        surface.setCurrentTitle(title);
        surface.setCurrentDescription(description);
        surface.setSurfaceType(type);
        surface.setSupportedActions(actions);
        surface.setResourceScope(scope);
        return surface;
    }
}
