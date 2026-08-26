package com.example.invoice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * pocfile: 超限 multipart 在 DispatcherServlet.checkMultipart() 阶段就被拒绝，此时还没做
 * handler 映射（handler==null），@ExceptionHandler 只会查 @ControllerAdvice —— 写在
 * Controller 上的超限处理器永远不触发。所以这里必须是全局 Advice。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("文件超过 10MB 限制");
    }
}
