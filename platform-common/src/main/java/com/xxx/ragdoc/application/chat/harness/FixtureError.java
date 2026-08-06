package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5 / EMS-PR5: Fixture 中保存的错误信息。
 *
 * <p>硬约束 (EMS-PR5 §8.2): 禁止保存完整堆栈/token/connection string/API Key/未脱敏依赖响应/无权文档名。
 */
public record FixtureError(
        String errorCode,
        String safeMessage,
        boolean retryable,
        /** 异常类型的短别名 (避免暴露完整 package); e.g. "NotFoundException" / "TimeoutException"。 */
        String exceptionTypeAlias,
        /** 错误类别让 OutcomeResult 映射到 EMPTY/TIMEOUT/PERMISSION/CANCELLED/ERROR。 */
        Category category) {

    public enum Category {
        EMPTY,
        TIMEOUT,
        PERMISSION,
        CANCELLED,
        DEPENDENCY,
        INVALID_ARGUMENT,
        GENERIC
    }

    public FixtureError {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("FixtureError.errorCode 必填");
        }
        if (safeMessage == null) {
            safeMessage = "";
        }
        if (exceptionTypeAlias == null) {
            exceptionTypeAlias = "";
        }
        if (category == null) {
            category = Category.GENERIC;
        }
    }

    public static FixtureError of(String code, String msg, Category cat) {
        return new FixtureError(code, msg, false, "", cat);
    }
}
