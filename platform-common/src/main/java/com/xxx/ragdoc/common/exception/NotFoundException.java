package com.xxx.ragdoc.common.exception;

/** 资源不存在类错误。HTTP 404。 */
public class NotFoundException extends DomainException {

    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
