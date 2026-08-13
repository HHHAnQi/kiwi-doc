package com.xxx.ragdoc.application.chat.comparison;

import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * PR-6c / EMS-PR6c §8: <b>单次</b> LLM 答案生成 — 复用统一左/右 Evidence Context。
 *
 * <p>硬约束 (Revision §1.6 + §8.4):
 *
 * <ol>
 *   <li><b>只调用一次</b> {@link ChatClient#chat} (单测 verify); 不允许 PR-3 旧版的两次独立 ChatService.chat
 *   <li>Prompt 显式 left / right 分块; 同步与流式共享同一 Prompt 构造
 *   <li>Prompt <b>不</b>含 Agent Transcript / 预算 / 错误 / 历史; 只含原始 question + 左右 Evidence
 *   <li>{@link ComparisonAnswer#usedEvidenceIds()} 让调用方完成 Citation / Snapshot 对齐
 * </ol>
 *
 * <p>回答规约 (§8.2):
 *
 * <ul>
 *   <li>系统指令: 只能使用提供的 Evidence; 左右必须分别描述; 不允许跨侧推断; 未覆盖维度标 "文档未提供"; 不可虚构共同点或差异; 关键结论附 Evidence 引用
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonAnswerComposer {

    public static final String SYSTEM_PROMPT =
            "你是文档对比回答助手。只能使用提供的 Evidence。请遵守:\n"
                    + "1. 左右对象必须分别描述, 严禁把一侧信息推断到另一侧。\n"
                    + "2. Evidence 未覆盖的维度, 须明确标注 \"文档未提供\"。\n"
                    + "3. 不允许虚构共同点或差异。\n"
                    + "4. 每个关键结论附 [Evidence:ID] 引用。\n"
                    + "5. 回答结构: 结论摘要 / 共同点 / 主要差异 (表格) / 证据不足或不确定项。";

    private final ChatClient chatClient;

    /** 同步生成。 */
    public ComparisonAnswer compose(
            String originalQuery, ComparisonEvidencePartitioner.ComparisonEvidenceSet evidenceSet)
            throws Exception {
        if (evidenceSet == null) {
            throw new IllegalArgumentException("evidenceSet 必填");
        }
        List<String> context = buildPromptContext(originalQuery, evidenceSet);
        String answer = chatClient.chat(SYSTEM_PROMPT + "\n\n用户问题: " + originalQuery, context);
        List<String> usedIds = collectUsedEvidenceIds(evidenceSet);
        return new ComparisonAnswer(answer, usedIds);
    }

    /** 流式生成。 */
    public Flux<ChatStreamEvent> stream(
            String originalQuery, ComparisonEvidencePartitioner.ComparisonEvidenceSet evidenceSet) {
        if (evidenceSet == null) {
            return Flux.error(new IllegalArgumentException("evidenceSet 必填"));
        }
        List<String> context = buildPromptContext(originalQuery, evidenceSet);
        String fullQuery = SYSTEM_PROMPT + "\n\n用户问题: " + originalQuery;
        return chatClient
                .chatStream(fullQuery, context)
                .map(token -> (ChatStreamEvent) new ChatStreamEvent.DeltaEvent(token));
    }

    /**
     * 结构化的 Context entries, 不包含 Agent Transcript / 预算 / 错误。
     *
     * <p>每条 entry: 文本片段 (LLM context list)。Evidence ID 显式 chunk-prefix 让模型可 在 answer 中以
     * "[Evidence:ID]" 引用, 供后续 Citation 校验。
     */
    static List<String> buildPromptContext(
            String originalQuery, ComparisonEvidencePartitioner.ComparisonEvidenceSet set) {
        List<String> out = new ArrayList<>();
        out.add("====== LEFT TARGET: " + set.leftTarget().label() + " ======");
        int i = 0;
        for (Evidence e : set.leftEvidence()) {
            out.add("[Evidence:" + shortId(e.evidenceId()) + "] " + safeContent(e.content()));
            i++;
            if (i >= 10) break; // 安全上限, 不超 budget
        }
        out.add("====== RIGHT TARGET: " + set.rightTarget().label() + " ======");
        i = 0;
        for (Evidence e : set.rightEvidence()) {
            out.add("[Evidence:" + shortId(e.evidenceId()) + "] " + safeContent(e.content()));
            i++;
            if (i >= 10) break;
        }
        return out;
    }

    static List<String> collectUsedEvidenceIds(
            ComparisonEvidencePartitioner.ComparisonEvidenceSet set) {
        List<String> ids = new ArrayList<>();
        for (Evidence e : set.leftEvidence()) ids.add(e.evidenceId());
        for (Evidence e : set.rightEvidence()) ids.add(e.evidenceId());
        return ids;
    }

    /** 12 字符短 ID 让模型在引用中更易识别 (full 64 char 仍可后续 mapping)。 */
    private static String shortId(String id) {
        return id == null ? "UNKNOWN" : id.substring(0, Math.min(12, id.length()));
    }

    private static String safeContent(String content) {
        if (content == null) return "";
        return content.length() > 800 ? content.substring(0, 800) + "..." : content;
    }

    /** Composer 输出 (text + 用到的 evidence IDs)。 */
    public record ComparisonAnswer(String text, List<String> usedEvidenceIds) {
        public ComparisonAnswer {
            if (text == null) text = "";
            usedEvidenceIds = usedEvidenceIds == null ? List.of() : List.copyOf(usedEvidenceIds);
        }

        /** Trace metadata (脱敏, 不含 Evidence 正文)。 */
        public Map<String, Object> describeTrace() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("usedEvidenceCount", usedEvidenceIds.size());
            return m;
        }
    }
}
