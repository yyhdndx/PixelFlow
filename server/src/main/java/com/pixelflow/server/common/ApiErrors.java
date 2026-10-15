package com.pixelflow.server.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DuplicateKeyException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    public static Map<String,Object> body(int status, String code, String message, Object requestId) {
        return Map.of("status_code", status, "code", code, "message", message,
                "requestId", requestId == null ? "unknown" : requestId);
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> domain(ApiException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status).body(body(e.status,e.code,e.getMessage(),request.getAttribute("requestId")));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body(400,"INVALID_ARGUMENT","请求字段或格式不正确",request.getAttribute("requestId")));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> conflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(body(409,"CONFLICT","记录已存在",request.getAttribute("requestId")));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> oversized(HttpServletRequest request) {
        return ResponseEntity.status(413).body(body(413,"MEDIA_TOO_LARGE","单文件最多 8 MiB，请求最多 9 MiB",request.getAttribute("requestId")));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error,HttpServletRequest request) {
        int status = error instanceof org.springframework.web.ErrorResponse response ? response.getStatusCode().value() : 500;
        if (status>=500) org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed: requestId={}",request.getAttribute("requestId"));
        return ResponseEntity.status(status).body(body(status,status>=500 ? "INTERNAL_ERROR" : "HTTP_ERROR",
                status>=500 ? "服务暂时不可用" : "请求无法处理",request.getAttribute("requestId")));
    }
}
