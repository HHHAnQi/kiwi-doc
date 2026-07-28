package com.xxx.ragdoc.common.exception;

/**
 * 异常基类。所有业务异常必须继承它,让 {@code GlobalExceptionHandler} 统一处理。 禁止在 Controller / Service 直接抛 {@code
 * RuntimeException}。
 */
public abstract class BaseException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BaseException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    protected BaseException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
