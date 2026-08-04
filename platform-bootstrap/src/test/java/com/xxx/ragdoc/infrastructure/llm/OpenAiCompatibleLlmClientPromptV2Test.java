package com.xxx.ragdoc.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.ChatMessages;
import com.xxx.ragdoc.infrastructure.llm.LlmProperties;
import com.xxx.ragdoc.infrastructure.llm.LlmRouteProperties;
import com.xxx.ragdoc.infrastructure.llm.LlmRouteProperties.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2.B / P2-2: V2 prompt 选择优先级单测。
 *
 * <p>不进 LLM HTTP 调用, 仅断言:
 * <ul>
 *   <li>V2 flag ON → buildSystemPrompt 走 V2 分支 (含 V2 特征词)
 *   <li>V2 OFF + relaxed ON → 走 relaxed 分支
 *   <li>全 OFF → 走 baseline 分支
 * </ul>
 *
 * <p>触发方式: 用反射调用 buildSystemPrompt (private), 验证返回值字符串特征。
 */
@DisplayName("OpenAiCompatibleLlmClient PromptV2 选择")
class OpenAiCompatibleLlmClientPromptV2Test {

    private Route sampleRoute() {
        Route r = new Route();
        r.setName("primary");
        r.setBaseUrl("http://localhost:11111");
        r.setApiKey("test");
        r.setModel("glm-4-plus-test");
        r.setTimeoutMs(5000);
        r.setMaxTokens(0);
        r.setTemperature(0.3);
        return r;
    }

    private LlmProperties sampleGlobalProps() {
        return new LlmProperties();
    }

    @Test
    @DisplayName("V2 ON → system prompt 含 V2 特征词 '严禁编造片段中未出现' ")
    void v2FlagOnSelectsV2Prompt() throws Exception {
        ChatMessages cm = new ChatMessages();
        cm.setPromptV2(true);
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(sampleRoute(), sampleGlobalProps(), cm);

        String prompt = invokeBuildSystemPrompt(client);

        assertThat(prompt).contains("严禁编造片段中未出现");
        assertThat(prompt).contains("fallback 判定");
    }

    @Test
    @DisplayName("V2 ON + relaxed ON → V2 胜出 (严格 > 宽松)")
    void v2OverridesRelaxedWhenBothOn() throws Exception {
        ChatMessages cm = new ChatMessages();
        cm.setPromptV2(true);
        cm.setPromptRelaxRefusal(true);
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(sampleRoute(), sampleGlobalProps(), cm);

        String prompt = invokeBuildSystemPrompt(client);

        // V2 特征: '严禁编造片段中未出现'; relaxed 特征: '放宽判定'。验证 V2 赢。
        assertThat(prompt).contains("严禁编造片段中未出现");
        assertThat(prompt).doesNotContain("放宽判定");
    }

    @Test
    @DisplayName("V2 OFF + relaxed ON → relaxed 分支")
    void relaxedOnlyWhenV2Off() throws Exception {
        ChatMessages cm = new ChatMessages();
        cm.setPromptRelaxRefusal(true);
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(sampleRoute(), sampleGlobalProps(), cm);

        String prompt = invokeBuildSystemPrompt(client);

        // relaxed 特征: '严格遵守, 避免误判', V2 特征: '严禁编造片段中未出现'
        assertThat(prompt).contains("避免误判");
    }

    @Test
    @DisplayName("全 OFF → baseline 分支")
    void baselineWhenAllOff() throws Exception {
        ChatMessages cm = new ChatMessages();
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(sampleRoute(), sampleGlobalProps(), cm);

        String prompt = invokeBuildSystemPrompt(client);

        // baseline body 特征: 第 5 条 单句一句话答 fallback; V2 用 'fallback 判定'; relaxed '避免误判'
        assertThat(prompt).contains("片段与问题完全无关时");
        assertThat(prompt).doesNotContain("fallback 判定");
        assertThat(prompt).doesNotContain("避免误判");
    }

    @Test
    @DisplayName("V2 citation OFF → 系统提示 '非强制' 出现")
    void v2CitationOff() throws Exception {
        ChatMessages cm = new ChatMessages();
        cm.setPromptV2(true);
        cm.setPromptV2Citation(false);
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(sampleRoute(), sampleGlobalProps(), cm);

        String prompt = invokeBuildSystemPrompt(client);

        assertThat(prompt).contains("非强制");
    }

    @Test
    @DisplayName("ChatMessages null (理论不可达, 防御) → baseline")
    void nullChatMessagesFallsBackToBaseline() throws Exception {
        OpenAiCompatibleLlmClient client =
                new OpenAiCompatibleLlmClient(sampleRoute(), sampleGlobalProps(), null);

        String prompt = invokeBuildSystemPrompt(client);

        assertThat(prompt).contains("片段与问题完全无关时");
    }

    private static String invokeBuildSystemPrompt(OpenAiCompatibleLlmClient client) throws Exception {
        var m = OpenAiCompatibleLlmClient.class.getDeclaredMethod("buildSystemPrompt");
        m.setAccessible(true);
        return (String) m.invoke(client);
    }
}
