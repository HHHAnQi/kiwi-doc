package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.common.exception.DomainException;
import lombok.RequiredArgsConstructor;

/**
 * PR-5: Router 的 {@link ObjectResultMapper}。让 Router 既能被 HarnessProvider 记录/回放,
 * 又不强依赖 Harness 的 record/replay 语义。
 *
 * <p>序列化对象 = {@link RouterDecision}; 异常 (理论上 RuleBasedTaskRouter 不抛, 模型 Router 未来可能抛
 * DomainException)。
 */
@RequiredArgsConstructor
public class RouterHarnessAdapter implements ObjectResultMapper {

    private final ObjectMapper mapper;
    private final CanonicalJson canonical;

    public RouterHarnessAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
        this.canonical = new CanonicalJson(mapper);
    }

    @Override
    public String requestHash(Object request) {
        // request 是 query 字符串 String
        return canonical.hashObject(java.util.Map.of("query", request == null ? "" : request));
    }

    @Override
    public Object fromFixtureResponse(JsonNode responseNode, FixtureError error) {
        if (error != null) {
            // Router 抛 DomainException → FixtureError 还原
            throw new DomainException(
                    com.xxx.ragdoc.common.exception.ErrorCode.SYS_INTERNAL,
                    "replay router exception: " + error.safeMessage());
        }
        if (responseNode == null || responseNode.isNull()) {
            throw new IllegalStateException("router replay response 为 null");
        }
        return mapper.convertValue(responseNode, RouterDecision.class);
    }

    @Override
    public FixtureOutcome.OutcomeResult toOutcome(Object liveResult, Throwable thrown) {
        if (thrown != null) {
            FixtureError.Category cat = mapCategory(thrown);
            String code = thrown instanceof DomainException de
                    ? de.errorCode().code() : "ROUTER_FAILED";
            FixtureError err = new FixtureError(code, safeMsg(thrown.getMessage()), false,
                    thrown.getClass().getSimpleName(), cat);
            return FixtureOutcome.OutcomeResult.error(err);
        }
        JsonNode node = canonical.canonicalize(canonical.toJsonNode(liveResult));
        return FixtureOutcome.OutcomeResult.success(node);
    }

    private static FixtureError.Category mapCategory(Throwable t) {
        if (t instanceof java.util.concurrent.TimeoutException) return FixtureError.Category.TIMEOUT;
        if (t instanceof com.xxx.ragdoc.common.exception.NotFoundException) return FixtureError.Category.PERMISSION;
        return FixtureError.Category.GENERIC;
    }

    private static String safeMsg(String m) {
        if (m == null) return "";
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
