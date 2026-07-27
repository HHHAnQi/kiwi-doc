package com.xxx.ragdoc.common.exception;

/**
 * 5xx 基础设施错误(LLM 超时、Milvus 写入失败等)。
 * 用户最终感知的是降级后的 200 业务结果,而非裸 5xx;
 * 异常详情(cause 链)仅供日志结构化记录,不进响应体。
 */
public class InfraException extends BaseException {

    public InfraException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InfraException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public InfraException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
