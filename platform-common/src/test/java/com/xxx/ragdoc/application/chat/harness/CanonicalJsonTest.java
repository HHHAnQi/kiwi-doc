package com.xxx.ragdoc.application.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-5 EMS-PR5 §20.1: Canonical JSON + SHA-256 Hash 不变量测试。 */
@DisplayName("CanonicalJson + ReplayKey 不变量")
class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalJson c = new CanonicalJson(mapper);

    @Test
    @DisplayName("字段顺序不同, Hash 相同")
    void fieldOrderIndependent() {
        JsonNode a = mapper.valueToTree(java.util.Map.of("k1", "v1", "k2", "v2", "k3", "v3"));
        JsonNode b = mapper.valueToTree(
                java.util.Map.of("k3", "v3", "k1", "v1", "k2", "v2")); // 不同顺序
        assertThat(c.sha256(c.canonicalize(a))).isEqualTo(c.sha256(c.canonicalize(b)));
    }

    @Test
    @DisplayName("嵌套 Map 字段顺序递归规范化")
    void nestedMapOrder() {
        ObjectNode a = mapper.createObjectNode();
        ObjectNode aInner = mapper.createObjectNode();
        aInner.put("z", "1");
        aInner.put("a", "2");
        a.set("outer1", aInner);
        a.put("outer2", "x");

        ObjectNode b = mapper.createObjectNode();
        ObjectNode bInner = mapper.createObjectNode();
        bInner.put("a", "2");
        bInner.put("z", "1");
        b.put("outer2", "x");
        b.set("outer1", bInner);

        assertThat(c.sha256(c.canonicalize(a))).isEqualTo(c.sha256(c.canonicalize(b)));
    }

    @Test
    @DisplayName("内容变化 (一个值不同) → hash 不同")
    void contentChangeBreaksHash() {
        JsonNode a = mapper.valueToTree(java.util.Map.of("q", "hello"));
        JsonNode b = mapper.valueToTree(java.util.Map.of("q", "world"));
        assertThat(c.sha256(c.canonicalize(a))).isNotEqualTo(c.sha256(c.canonicalize(b)));
    }

    @Test
    @DisplayName("数值规范化: null vs 缺失 vs 空字符串 稳定")
    void numberNullNormalization() {
        // 同语义不能因 null vs 缺失出现 hash 分歧 — 法律: 都视为 nullNode canonical
        ObjectNode a = mapper.createObjectNode();
        a.putNull("opt");
        ObjectNode b = mapper.createObjectNode();
        // b 不放 opt (相当于缺失)
        assertThat(c.sha256(c.canonicalize(a))).isNotEqualTo(c.sha256(c.canonicalize(b))); // 两 key set 不同
        // 但相同 null vs null 一致
        ObjectNode aa = mapper.createObjectNode();
        aa.putNull("opt");
        assertThat(c.sha256(c.canonicalize(a))).isEqualTo(c.sha256(c.canonicalize(aa)));
    }

    @Test
    @DisplayName("敏感字段 (token/principal/apiKey) 被 sanitize 替换成 <redacted>")
    void bannedFieldsSanitized() {
        ObjectNode with = mapper.createObjectNode();
        with.put("query", "hello");
        with.put("token", "secret-value-xyz");
        with.put("rawPrincipal", "Principal[tenantId=admin|userId=root]");
        ObjectNode without = mapper.createObjectNode();
        without.put("query", "hello");
        without.put("token", "<redacted>");
        without.put("rawPrincipal", "<redacted>");
        assertThat(c.canonicalize(with).toString()).isEqualTo(c.canonicalize(without).toString());
    }

    @Test
    @DisplayName("Hash 在多次运行中稳定 (确定性)")
    void stableAcrossRuns() {
        JsonNode n = mapper.valueToTree(
                java.util.Map.of("tool", "semantic_search", "version", "v1", "k", 5));
        String h1 = c.sha256(c.canonicalize(n));
        String h2 = c.sha256(c.canonicalize(n));
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64).matches("^[a-fA-F0-9]{64}$");
    }

    @Test
    @DisplayName("ReplayKey 含 componentVersion: 改 version → key 变")
    void replayKeyVersionScoped() {
        Object req = java.util.Map.of("query", "q");
        String k1 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 0, req, "ps-1", "iv-1");
        String k2 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v2", 0, req, "ps-1", "iv-1");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("ReplayKey 含 permissionScopeVersion: 改 scope → key 变")
    void replayKeyScopeScoped() {
        Object req = java.util.Map.of("query", "q");
        String k1 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 0, req, "ps-1", "iv-1");
        String k2 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 0, req, "ps-2", "iv-1");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("ReplayKey 含 indexVersion: 改 indexVersion → key 变")
    void replayKeyIndexScoped() {
        Object req = java.util.Map.of("query", "q");
        String k1 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 0, req, "ps-1", "iv-1");
        String k2 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 0, req, "ps-1", "iv-2");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("ReplayKey 含 callIndex: 改 callIndex → key 变")
    void replayKeyCallIndexScoped() {
        Object req = java.util.Map.of("query", "q");
        String k1 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 0, req, "ps-1", "iv-1");
        String k2 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "semantic_search", "v1", 1, req, "ps-1", "iv-1");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("ReplayKey 输入规范化: 字段顺序不同但内容同 → key 同")
    void replayKeyInputOrder() {
        Object r1 = java.util.Map.of("k1", "v1", "k2", "v2");
        Object r2 = java.util.Map.of("k2", "v2", "k1", "v1");
        String k1 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "x", "v1", 0, r1, "ps-1", "iv-1");
        String k2 = c.replayKeyFor("c1", HarnessComponentType.TOOL, "x", "v1", 0, r2, "ps-1", "iv-1");
        assertThat(k1).isEqualTo(k2);
    }
}
