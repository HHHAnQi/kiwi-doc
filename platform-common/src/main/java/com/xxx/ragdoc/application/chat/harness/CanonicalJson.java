package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR-5 / EMS-PR5: Canonical JSON 序列化 + SHA-256 ReplayKey 生成。
 *
 * <h2>Canonical 规则 (让任意等价 JSON 产出相同 Hash)</h2>
 *
 * <ol>
 *   <li>Object 字段按字典序排序 (递归)
 *   <li>数值规范化: null/0/false→"false"; 字符串 trim 后保留
 *   <li>数组按原顺序 (不重排, 因 element 语义常常插入顺序敏感)
 *   <li>空白符标准化 (单 space)
 *   <li>敏感字段在做 canonical 前已删除/替换 (caller 责任 — replayKeyFor 自动 sanitize)
 * </ol>
 *
 * <p>调用约定: 用同一 ObjectMapper 实例注入, 避免每次 new。
 */
public final class CanonicalJson {

    /** 敏感字段名 (小写), canonical 前必须删除/用占位符替换。 */
    public static final java.util.Set<String> BANNED_FIELD_NAMES =
            java.util.Set.of(
                    "rawtoken", "raw_token", "token", "authorization", "authorizationheader",
                    "apikey", "api_key", "cookie", "connectionstring", "password", "secret",
                    "principal", "rawprincipal", "raw_principal");

    private final ObjectMapper mapper;

    public CanonicalJson(ObjectMapper mapper) {
        // 拿一个 canonical-only copy, 不污染 caller 的 mapper
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** 序列化 → JsonNode; 调用方应传入普通 POJO / Map / record (Jackson 默认序列化)。 */
    public JsonNode toJsonNode(Object value) {
        return mapper.valueToTree(value);
    }

    /** 去敏感 + 字段字典序 → 标准 JsonNode (供 fixture normalizedRequest 储存)。 */
    public JsonNode canonicalize(JsonNode node) {
        return canonicalizeInternal(sanitize(node));
    }

    /** Canonical JSON 字符串; 用于 sha256 hash。 */
    public String canonicalString(JsonNode node) {
        return canonicalize(node).toString();
    }

    /** Hash: SHA-256 hex of canonical UTF-8 bytes (固定 64 字符)。 */
    public String sha256(JsonNode canonicalNode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonicalNode.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Convenience: 把对象 → sanitize+canonical+hash 一气呵成。 */
    public String hashObject(Object value) {
        return sha256(canonicalize(toJsonNode(value)));
    }

    /**
     * PR-5.1 / EMS-PR6 §2.2: tenantScopeFingerprint = SHA-256(normalizedTenantId + ":" +
     * permissionScopeVersion)。让 Fixture Key 间接绑定租户范围，但不暴露明文 tenantId。
     *
     * 不同 tenant 即使 permissionScopeVersion 相同（两条 DB 不同的 admin/user），也产出不同 fingerprint，
     * 杜绝跨租户 Fixture 误命中。
     */
    public static String tenantScopeFingerprint(String tenantId, String permissionScopeVersion) {
        String normalizedTenant = tenantId == null ? "" : tenantId.trim().toLowerCase();
        String scope = permissionScopeVersion == null ? "" : permissionScopeVersion;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            md.digest((normalizedTenant + ":" + scope).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * PR-5/PR-5.1: ReplayKey = SHA-256 canonical(
     *   caseId | componentType | componentName | componentVersion | callIndex
     *   | normalizedRequest | tenantScopeFingerprint(tenantId, permissionScopeVersion) | indexVersion
     * )
     *
     * <p>tenantId 不直接进 Hash；用 {@link #tenantScopeFingerprint} 绑定租户范围。
     */
    public String replayKeyFor(
            String caseId,
            HarnessComponentType componentType,
            String componentName,
            String componentVersion,
            int callIndex,
            Object request,
            String tenantId,
            String permissionScopeVersion,
            String indexVersion) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("caseId", nullSafe(caseId));
        root.put("componentType", componentType.name());
        root.put("componentName", nullSafe(componentName));
        root.put("componentVersion", nullSafe(componentVersion));
        root.put("callIndex", callIndex);
        root.set("normalizedRequest", canonicalize(toJsonNode(request)));
        root.put("tenantScopeFingerprint", tenantScopeFingerprint(tenantId, permissionScopeVersion));
        root.put("indexVersion", nullSafe(indexVersion));
        return sha256(canonicalize(root));
    }

    // ─── 内部 ────────────────────────────────────────────

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 删除敏感字段 (递归)。 */
    static JsonNode sanitize(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String lc = e.getKey().toLowerCase();
                if (BANNED_FIELD_NAMES.contains(lc)) {
                    out.put(e.getKey(), "<redacted>");
                } else {
                    out.set(e.getKey(), sanitize(e.getValue()));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode el : node) out.add(sanitize(el));
            return out;
        }
        return node;
    }

    /** 字段字典序排序 + trim。 */
    private static JsonNode canonicalizeInternal(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            // TreeMap 按 key 字典序遍历
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), canonicalizeInternal(e.getValue()));
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) out.set(e.getKey(), e.getValue());
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode el : node) out.add(canonicalizeInternal(el));
            return out;
        }
        if (node.isTextual()) {
            String s = node.textValue();
            return JsonNodeFactory.instance.textNode(s == null ? "" : s.trim());
        }
        return node;
    }
}
