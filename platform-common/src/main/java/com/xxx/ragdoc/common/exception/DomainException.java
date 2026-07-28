package com.xxx.ragdoc.common.exception;

/** 4xx 业务错误。请求合法但业务规则不满足,例如状态机违规、资源不存在等。 */
public class DomainException extends BaseException {

    public DomainException(ErrorCode errorCode) {
        super(errorCode);
    }

    public DomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
