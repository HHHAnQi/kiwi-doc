package com.xxx.ragdoc.application.chat.harness;

import java.util.Optional;

/**
 * PR-5: Fixture 存取端口。{@link FileFixtureStore} 实现文件系统版本; 未来可换 Redis / DB (本 PR 不引入)。
 *
 * <p>{@link #save(FixtureRecord)} 必须:
 *
 * <ul>
 *   <li>同 replayKey 内容一致视为幂等 (覆盖自我写入)
 *   <li>同 replayKey 内容不同 → 抛 FixtureConflictException
 *   <li>原子写入 (.tmp + rename) 防半写被 REPLAY 读
 * </ul>
 */
public interface FixtureStore {

    Optional<FixtureRecord> find(String replayKey);

    void save(FixtureRecord record);

    /** 同 Key 不同内容写入 → 抛。 */
    class FixtureConflictException extends RuntimeException {
        public final String replayKey;

        public FixtureConflictException(String replayKey, String message) {
            super(message);
            this.replayKey = replayKey;
        }
    }

    /** REPLAY 时找不到 / 不匹配 / 损坏的统一异常 (调用方按 cause 细分错误码)。 */
    class FixtureUnavailableException extends RuntimeException {
        public final String replayKey;
        public final Reason reason;

        public FixtureUnavailableException(String replayKey, Reason reason, String message) {
            super(message);
            this.replayKey = replayKey;
            this.reason = reason;
        }

        public enum Reason {
            NOT_FOUND,
            REQUEST_MISMATCH,
            COMPONENT_VERSION_MISMATCH,
            SCHEMA_MISMATCH,
            CORRUPTED
        }
    }
}
