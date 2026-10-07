package com.mongle.backend.domain.auth.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.nio.charset.StandardCharsets;

/**
 * 소셜 계정 ID는 대소문자를 구분해야 한다. MySQL에서 서로 다른 ID가
 * 같은 값으로 취급되지 않도록 VARBINARY로 저장하고, 자바에서는 문자열로 조회한다.
 */
@Converter
public class ProviderUserIdConverter implements AttributeConverter<String, byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public String convertToEntityAttribute(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.US_ASCII);
    }
}
