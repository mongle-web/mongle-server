package com.mongle.backend.global.security;

import com.mongle.backend.global.error.ErrorCode;
import com.mongle.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class SecurityResponseWriter {
    private final ObjectMapper objectMapper;

    public void failure(HttpServletResponse response, ErrorCode code) throws IOException {
        write(response, code.getHttpStatus().value(), ApiResponse.failure(code));
    }

    public void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
