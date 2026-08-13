package com.xxx.ragdoc.application.chat.planner;

import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.2: Planner 可见的 Tool 描述。仅暴露 schema (name/version/description), 不暴露
 * Principal/Token/内部状态 (Revision §5.2 数据最小化)。
 */
public record PlannerToolDescriptor(
        String name,
        String version,
        String description,
        Map<String, Object> inputSchema /* 给 Model Planner 作为 function-call schema */) {

    public PlannerToolDescriptor {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (version == null || version.isBlank()) version = "v1";
        if (description == null) description = "";
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    /** 安全 descriptor key, 用于 Trace 与 Fixture。 */
    public String key() {
        return name + ":" + version;
    }
}
