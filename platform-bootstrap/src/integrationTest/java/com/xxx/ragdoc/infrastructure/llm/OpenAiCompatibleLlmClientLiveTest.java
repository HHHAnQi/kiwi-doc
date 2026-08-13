package com.xxx.ragdoc.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.ChatMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * PR-7f.2c-pre 链路测试: 用真实 GLM (BigModel) OpenAI 兼容端点验证 {@link OpenAiCompatibleLlmClient} 端到端连通。
 *
 * <p>默认禁用 — 仅当显式 set {@code LLM_API_KEY} + {@code LLM_LIVE_TEST=1} 时本地运行。 CI 不跑(无 key + 不耗费 LLM
 * 配额)。
 *
 * <p>运行方式(本地):
 *
 * <pre>
 * LLM_LIVE_TEST=1 \
 * LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4 \
 * LLM_API_KEY=<glm-key> \
 * LLM_MODEL=glm-4-flash \
 * ./gradlew :platform-bootstrap:test \
 *     --tests "com.xxx.ragdoc.infrastructure.llm.OpenAiCompatibleLlmClientLiveTest"
 * </pre>
 *
 * <p>验证目标(每一项都断言):
 *
 * <ol>
 *   <li>HTTP RTT &lt; 30s (glm-4-flash 国内 ~1-3s)
 *   <li>非空答案 + 含 RAG 关键词
 *   <li>lastUsage 包含真实 prompt/completion token (&gt; 0)
 *   <li>currentModel() == "glm-4-flash"
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "LLM_LIVE_TEST", matches = "1|true|yes")
class OpenAiCompatibleLlmClientLiveTest {

    @Test
    void glmFlashReturnsRealAnswerWithContext() throws Exception {
        LlmRouteProperties.Route route = new LlmRouteProperties.Route();
        route.setName("glm-live");
        route.setBaseUrl(System.getenv("LLM_BASE_URL"));
        route.setApiKey(System.getenv("LLM_API_KEY"));
        route.setModel(System.getenv("LLM_MODEL"));
        if (route.getModel() == null) route.setModel("glm-4-flash");
        route.setTimeoutMs(60000);
        route.setMaxTokens(128);
        route.setTemperature(0.1);

        LlmProperties globalProps = new LlmProperties();
        // ChatMessages 在 OpenAiCompatibleLlmClient 内部仅用于 litmReorder/promptRelaxRefusal flags
        // (buildOpenAiBody 内部用 baseline prompt assembles query+context)。这里用默认 empty ChatMessages
        // 即可 — 不需自定义 prompt template; baseline prompt 已能驱动 LLM 生成 RAG 答案。
        ChatMessages chatMessages = new ChatMessages();

        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(route, globalProps, chatMessages);

        long t0 = System.currentTimeMillis();
        String answer =
                client.chat(
                        "RAG 是什么的缩写?",
                        java.util.List.of("RAG（Retrieval-Augmented Generation）检索增强生成组合了检索与大语言模型。"));
        long elapsedMs = System.currentTimeMillis() - t0;

        System.out.println(
                "[LiveTest] model="
                        + client.currentModel()
                        + " elapsedMs="
                        + elapsedMs
                        + " answer="
                        + answer);

        // 1) currentModel 透传 route.model
        assertThat(client.currentModel()).isEqualTo(route.getModel());

        // 2) HTTP < 30s (glm-4-flash 国内通常 1-3s; 留 10x margin 防偶发抖动)
        assertThat(elapsedMs).isLessThan(30_000L);

        // 3) 非空且语义相关 (LLM 应答出 Retrieval-Augmented Generation 字样)
        assertThat(answer).isNotBlank();
        String lower = answer.toLowerCase();
        assertThat(lower.matches(".*retrieval.*|.*检[索搜].*") || lower.contains("rag"))
                .as("answer should mention retrieval/search, got: " + answer)
                .isTrue();

        // 4) lastUsage 真实 token 计数 > 0 (GLM 在 usage 块里返回 prompt/completion/total)
        com.xxx.ragdoc.application.chat.port.ChatClient.TokenUsage usage =
                client.lastUsage()
                        .orElseThrow(
                                () -> new AssertionError("missing usage block from GLM response"));
        assertThat(usage.promptTokens()).isGreaterThan(0);
        assertThat(usage.completionTokens()).isGreaterThan(0);
        assertThat(usage.totalTokens())
                .isGreaterThanOrEqualTo(usage.promptTokens() + usage.completionTokens());

        System.out.println("[LiveTest] usage=" + usage);
    }
}
