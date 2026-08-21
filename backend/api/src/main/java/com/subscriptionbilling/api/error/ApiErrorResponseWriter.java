package com.subscriptionbilling.api.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Writes this module's structured error envelope directly to the response, for the
 * rejections Spring Security's filter chain makes before a request ever reaches
 * {@link GlobalExceptionHandler} — the entry point (401) and access-denied handler
 * (403) can't return a {@code ResponseEntity} through MVC because MVC never runs for
 * them, so they need the same JSON shape written by hand instead.
 */
@Component
public class ApiErrorResponseWriter {

    private final JsonMapper jsonMapper;

    @Autowired
    public ApiErrorResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = new ApiError(new ApiError.ErrorDetail(code, message, CorrelationIds.current()));
        jsonMapper.writeValue(response.getWriter(), body);
    }
}
