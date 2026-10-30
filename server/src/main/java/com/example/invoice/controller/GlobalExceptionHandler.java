package com.example.invoice.controller;

import com.example.invoice.service.DuplicateInvoiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常 → HTTP 状态码的统一出口。
 * <p>pocfile: 超限 multipart 在 DispatcherServlet.checkMultipart() 阶段就被拒绝，此时还没做
 * handler 映射（handler==null），@ExceptionHandler 只会查 @ControllerAdvice —— 写在
 * Controller 上的超限处理器永远不触发。所以这里必须是全局 Advice。
 * 业务异常（IllegalArgumentException / DuplicateInvoiceException）原先在
 * Invoice/Export 两个 Controller 各写一份，行为完全一致，上移到这里统一。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("文件超过 10MB 限制");
    }

    /** 业务校验失败（非法参数、状态冲突的前置检查等）→ 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** 重复上传（发票号码 UNIQUE 冲突）→ 409。 */
    @ExceptionHandler(DuplicateInvoiceException.class)
    public ResponseEntity<String> duplicate(DuplicateInvoiceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /** 兜底 500：统一为简短响应体（不回传异常信息，避免路径/内部细节泄漏），完整堆栈只进日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> internal(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("服务器内部错误");
    }
}
