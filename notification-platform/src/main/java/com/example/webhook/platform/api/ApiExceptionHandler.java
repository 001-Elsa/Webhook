package com.example.webhook.platform.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;
import java.util.Map;
import com.example.webhook.platform.service.TenantQuotaService;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(Exception ex) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "error", "BAD_REQUEST",
                "message", ex.getMessage()
        );
    }

    @ExceptionHandler(TenantQuotaService.QuotaExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, Object> quotaExceeded(TenantQuotaService.QuotaExceededException ex) {
        return Map.of("timestamp", Instant.now().toString(), "error", "QUOTA_EXCEEDED",
                "message", ex.getMessage());
    }
}
