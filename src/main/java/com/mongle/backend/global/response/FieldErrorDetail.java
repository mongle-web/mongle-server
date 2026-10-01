package com.mongle.backend.global.response;

public record FieldErrorDetail(
        String field,
        String message
) {
}