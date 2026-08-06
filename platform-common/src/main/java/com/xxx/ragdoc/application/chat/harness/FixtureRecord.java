package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * PR-5 / EMS-PR5: 持久化的 Fixture (一调用一文件)。
 *
 * <p>结构稳定, schema_version 写入顶层让 REPLAY 校验兼容性 ({@link #fixtureSchemaVersion})。
 */
public record FixtureRecord(
        String fixtureSchemaVersion,
        String replayKey,
        HarnessComponentType componentType,
        String componentName,
        String componentVersion,
        int callIndex,
        String requestHash,
        JsonNode normalizedRequest,
        FixtureOutcome.OutcomeResult outcome,
        JsonNode normalizedResponse,
        FixtureError error,
        FixtureMetadata metadata) {

    public FixtureRecord {
        if (fixtureSchemaVersion == null || fixtureVersionSchemaBlank(fixtureSchemaVersion)) {
            fixtureSchemaVersion = "v1";
        }
        if (replayKey == null || replayKey.isBlank()) {
            throw new IllegalArgumentException("FixtureRecord.replayKey 必填");
        }
        if (componentType == null) {
            throw new IllegalArgumentException("FixtureRecord.componentType 必填");
        }
        if (componentName == null || componentName.isBlank()) {
            throw new IllegalArgumentException("FixtureRecord.componentName 必填");
        }
        if (componentVersion == null || componentVersion.isBlank()) {
            throw new IllegalArgumentException("FixtureRecord.componentVersion 必填");
        }
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("FixtureRecord.requestHash 必填");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("FixtureRecord.outcome 必填");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("FixtureRecord.metadata 必填");
        }
    }

    private static boolean fixtureVersionSchemaBlank(String s) {
        return s.isBlank();
    }
}
