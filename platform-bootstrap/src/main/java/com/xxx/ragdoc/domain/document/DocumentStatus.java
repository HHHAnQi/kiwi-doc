package com.xxx.ragdoc.domain.document;

/**
 * Document 状态机。约束见 docs/architecture/state-machines.md。
 *
 * <pre>
 *   [*]      UPLOADED → PARSING → READY
 *                      ↘ FAILED ↗(仅手动重试一次,V1)
 * </pre>
 *
 * 不可逆规则:
 * <ul>
 *   <li>READY 状态不再回退到 PARSING(V3 才支持"重新解析")</li>
 *   <li>FAILED → PARSING 仅手动触发(retry_count++)</li>
 * </ul>
 */
public enum DocumentStatus {

    UPLOADED("原始文件已落 MinIO,等待解析"),
    PARSING("解析进行中"),
    READY("可问答"),
    FAILED("解析失败");

    private final String description;

    DocumentStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * 状态迁移合法性校验。任何非法迁移抛 IllegalStateException。
     */
    public DocumentStatus transitionTo(DocumentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法状态迁移: " + this + " → " + target + " 不被允许");
        }
        return target;
    }

    private boolean canTransitionTo(DocumentStatus target) {
        return switch (this) {
            // UPLOADED → PARSING(进入解析);其他迁移(V1)不允许
            case UPLOADED -> target == DocumentStatus.PARSING;
            // PARSING → READY / FAILED
            case PARSING -> target == DocumentStatus.READY
                    || target == DocumentStatus.FAILED;
            // READY/FAILED V1 不再迁移(失败重试由应用层显式触发,通过 UPLOADED? 这里只是建表语义守门)
            case READY, FAILED -> target == DocumentStatus.PARSING;
        };
    }
}
