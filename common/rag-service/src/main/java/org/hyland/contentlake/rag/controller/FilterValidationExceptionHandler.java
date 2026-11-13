package org.hyland.contentlake.rag.controller;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.security.UnsafeHxqlFilterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps a rejected caller filter to HTTP 400 for every search endpoint. The filter came from the
 * caller, so the failure is theirs, not the service's.
 *
 * @see org.hyland.contentlake.security.HxqlFilterGuard
 */
@Slf4j
@RestControllerAdvice
public class FilterValidationExceptionHandler {

    @ExceptionHandler(UnsafeHxqlFilterException.class)
    public ResponseEntity<Map<String, String>> onUnsafeFilter(UnsafeHxqlFilterException e) {
        log.warn("Rejected caller filter: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid filter: " + e.getMessage()));
    }
}
