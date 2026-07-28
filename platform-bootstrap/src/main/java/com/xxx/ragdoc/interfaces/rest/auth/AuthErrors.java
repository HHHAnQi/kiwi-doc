package com.xxx.ragdoc.interfaces.rest.auth;

import com.xxx.ragdoc.common.exception.BaseException;
import com.xxx.ragdoc.common.exception.ErrorCode;

/** V1 极简鉴权异常(由 Controller 手抛)。 V4 升级到完整 RBAC 时废弃此工具。 */
public class AuthErrors {

    private AuthErrors() {}

    public static RuntimeException unauthorized(String reason) {
        return new BaseException(ErrorCode.UNAUTHORIZED, reason) {};
    }

    public static RuntimeException forbidden(String reason) {
        return new BaseException(ErrorCode.FORBIDDEN, reason) {};
    }
}
