package com.xxx.ragdoc.infrastructure.config;

import com.xxx.ragdoc.application.chat.CitationVerifierProperties;
import com.xxx.ragdoc.application.chat.ConversationProperties;
import com.xxx.ragdoc.application.chat.QueryEnhanceProperties;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.pipeline.ChatPipelineRegistry;
import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.router.RouterProperties;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.infrastructure.milvus.RetrieveProperties;
import com.xxx.ragdoc.infrastructure.trace.LangfuseProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/** 启动期能力快照与配置门禁，避免“开关已开、依赖链却不完整”静默运行。 */
@Slf4j
@Component
public class RagCapabilityRegistry implements ApplicationRunner, InfoContributor {

    public enum Status {
        ACTIVE,
        DISABLED,
        MISCONFIGURED
    }

    private final RouterProperties router;
    private final ConversationProperties conversation;
    private final QueryEnhanceProperties queryEnhance;
    private final RetrieveProperties retrieve;
    private final RerankProperties rerank;
    private final CitationVerifierProperties verifier;
    private final PlannerProperties planner;
    private final LangfuseProperties langfuse;
    private final ChatPipelineRegistry pipelines;
    private volatile Map<String, Status> snapshot = Map.of();

    public RagCapabilityRegistry(
            RouterProperties router,
            ConversationProperties conversation,
            QueryEnhanceProperties queryEnhance,
            RetrieveProperties retrieve,
            RerankProperties rerank,
            CitationVerifierProperties verifier,
            PlannerProperties planner,
            LangfuseProperties langfuse,
            ChatPipelineRegistry pipelines) {
        this.router = router;
        this.conversation = conversation;
        this.queryEnhance = queryEnhance;
        this.retrieve = retrieve;
        this.rerank = rerank;
        this.verifier = verifier;
        this.planner = planner;
        this.langfuse = langfuse;
        this.pipelines = pipelines;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
        snapshot = buildSnapshot();
        log.info("rag.capabilities {}", snapshot);
    }

    public Map<String, Status> snapshot() {
        return snapshot;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("ragCapabilities", snapshot);
    }

    void validate() {
        require(
                !planner.isModelEnabled() || planner.isEnabled(),
                "model planner 开启时必须同时开启 planner.enabled");
        require(
                !planner.isPlannedPipelineEnabled() || planner.isEnabled(),
                "planned pipeline 开启时必须同时开启 planner.enabled");
        require(
                !conversation.isCompress() || conversation.isEnabled(),
                "conversation.compress=true 要求 conversation.enabled=true");
        require(
                !conversation.isTopicShiftDetect() || conversation.isEnabled(),
                "topic-shift-detect=true 要求 conversation.enabled=true");
        require(
                queryEnhance.getMaxExpansionQueries() >= 0,
                "query-enhance.max-expansion-queries 不能小于 0");
        require(queryEnhance.getFusionRrfK() >= 1, "query-enhance.fusion-rrf-k 必须大于等于 1");
        require(retrieve.getCandidatePool() >= 1, "retrieve.candidate-pool 必须大于等于 1");
        require(retrieve.getRrf().getK() >= 1, "retrieve.rrf.k 必须大于等于 1");
        if (rerank.isEnabled()) {
            require(hasText(rerank.getBaseUrl()), "rerank 开启时 base-url 不能为空");
            require(
                    rerank.getCandidatePool() >= rerank.getTopN() && rerank.getTopN() >= 1,
                    "rerank 要求 candidate-pool >= top-n >= 1");
        }
        if (langfuse.isEnabled()) {
            require(hasText(langfuse.getBaseUrl()), "Langfuse 开启时 base-url 不能为空");
            require(
                    hasText(langfuse.getPublicKey()) && hasText(langfuse.getSecretKey()),
                    "Langfuse 开启时 public-key/secret-key 不能为空");
        }
        if (router.isEnabled()) {
            require(
                    pipelines.registeredTypes().contains(PipelineType.CLASSIC_RAG),
                    "Router 开启时 CLASSIC_RAG pipeline 必须注册");
            require(
                    pipelines.registeredTypes().contains(PipelineType.TARGETED_RAG),
                    "Router 开启时 TARGETED_RAG pipeline 必须注册");
            require(
                    pipelines.registeredTypes().contains(PipelineType.FIXED_WORKFLOW),
                    "Router 开启时 FIXED_WORKFLOW pipeline 必须注册");
        }
        if (planner.isPlannedPipelineEnabled()) {
            require(
                    pipelines.registeredTypes().contains(PipelineType.PLANNED_AGENT),
                    "Planned Agent 开启时 PLANNED_AGENT pipeline 必须注册");
        }
    }

    private Map<String, Status> buildSnapshot() {
        Map<String, Status> result = new LinkedHashMap<>();
        result.put("router", enabled(router.isEnabled()));
        result.put("conversation", enabled(conversation.isEnabled()));
        result.put(
                "queryRewrite",
                enabled(
                        queryEnhance.isEnabled()
                                && queryEnhance.getMode()
                                        != QueryEnhanceProperties.Mode.EXPANSION));
        result.put(
                "queryExpansion",
                enabled(
                        queryEnhance.isEnabled()
                                && queryEnhance.getMode() != QueryEnhanceProperties.Mode.REWRITE));
        result.put(
                "hybridRetrieval", enabled(retrieve.getMode() == RetrieveProperties.Mode.HYBRID));
        result.put("rerank", enabled(rerank.isEnabled()));
        result.put("citationVerification", enabled(verifier.isEnabled()));
        result.put(
                "agenticRag", enabled(planner.isEnabled() && planner.isPlannedPipelineEnabled()));
        result.put("distributedTrace", enabled(langfuse.isEnabled()));
        return Map.copyOf(result);
    }

    private static Status enabled(boolean value) {
        return value ? Status.ACTIVE : Status.DISABLED;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("RAG capability 配置错误: " + message);
    }
}
