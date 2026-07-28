package com.xxx.ragdoc.interfaces.rest.error;

import com.xxx.ragdoc.common.exception.BaseException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.web.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器。
 *
 * <ul>
 *   <li>任何业务异常(BaseException 子类)统一转 ErrorResponse
 *   <li>参数校验失败 → SYS_INVALID_ARGUMENT
 *   <li>未捕获异常 → SYS_INTERNAL,不向用户泄漏堆栈
 * </ul>
 *
 * 见 docs/architecture/error-model.md §1。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex) {
        ErrorCode code = ex.errorCode();
        log.warn(
                "biz_error code={}, message={}, trace_id={}",
                code.code(),
                ex.getMessage(),
                MDC.get("trace_id"));
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code.code(), ex.getMessage(), MDC.get("trace_id")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg =
                ex.getBindingResult().getAllErrors().stream()
                        .findFirst()
                        .map(e -> e.getDefaultMessage())
                        .orElse("参数校验失败");
        return build(ErrorCode.SYS_INVALID_ARGUMENT, msg);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.SYS_INVALID_ARGUMENT, "参数类型不匹配: " + ex.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        return build(ErrorCode.SYS_INVALID_ARGUMENT, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        log.error("unhandled_error trace_id={}", MDC.get("trace_id"), ex);
        return build(ErrorCode.SYS_INTERNAL, "系统内部错误,请联系管理员并提供 trace_id");
    }

    private static ResponseEntity<ErrorResponse> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code.code(), message, MDC.get("trace_id")));
    }
}
