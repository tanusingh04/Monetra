package com.payflow.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.dto.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        ErrorResponse errorResponse = new ErrorResponse(
                Instant.now(),
                HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized",
                authException.getMessage() != null ? authException.getMessage() : "Full authentication is required to access this resource",
                request.getRequestURI()
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
