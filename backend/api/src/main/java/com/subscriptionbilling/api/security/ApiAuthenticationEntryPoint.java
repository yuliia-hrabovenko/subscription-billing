package com.subscriptionbilling.api.security;

import com.subscriptionbilling.api.error.ApiErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The 401 for a missing/invalid bearer token on a non-public endpoint.
 * Spring Security's default entry point would return an empty body with a
 * {@code WWW-Authenticate} header; this module's contract requires the same structured
 * error envelope on every rejection, so this replaces it.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorResponseWriter responseWriter;

    @Autowired
    public ApiAuthenticationEntryPoint(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        responseWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "A valid bearer token is required to access this resource.");
    }
}
