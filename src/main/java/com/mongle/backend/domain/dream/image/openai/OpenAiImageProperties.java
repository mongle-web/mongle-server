package com.mongle.backend.domain.dream.image.openai;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.image.s3.ImageS3Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.*;

@ConfigurationProperties("mongle.image.openai")
public record OpenAiImageProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String model,
        @DefaultValue("") String size,
        @DefaultValue("") String quality,
        @DefaultValue("png") String outputFormat,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("60s") Duration totalTimeout,
        @DefaultValue("2") int responseThreads,
        Map<String,String> stylePrompts,
        Map<String,String> moodPrompts) {
    private static final Set<String> MODELS = Set.of("gpt-image-1-mini", "gpt-image-1.5", "gpt-image-2",
            "gpt-image-2-2026-04-21", "gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst-2026-09-08",
            "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08", "chatgpt-image-latest");
    public OpenAiImageProperties {
        apiKey = clean(apiKey); model = clean(model); size = clean(size); quality = clean(quality);
        outputFormat = clean(outputFormat);
        stylePrompts = stylePrompts == null ? ImageStylePrompts.defaults() : Map.copyOf(stylePrompts);
        moodPrompts = moodPrompts == null ? Map.of() : Map.copyOf(moodPrompts);
        if (connectTimeout == null || totalTimeout == null
                || connectTimeout.compareTo(Duration.ofMillis(100)) < 0
                || connectTimeout.compareTo(Duration.ofSeconds(10)) > 0
                || totalTimeout.compareTo(connectTimeout) <= 0
                || totalTimeout.compareTo(Duration.ofSeconds(90)) > 0
                || responseThreads < 1 || responseThreads > 8) {
            throw new IllegalArgumentException("이미지 Provider의 시간 제한/응답 실행 설정이 잘못되었습니다.");
        }
        if (enabled) {
            var qualities = new HashSet<>(Set.of("low", "medium", "high", "auto"));
            if (model.startsWith("gpt-image-2.5-")) qualities.addAll(Set.of("xhigh", "max"));
            if (apiKey.isEmpty() || apiKey.chars().anyMatch(Character::isWhitespace)
                    || !MODELS.contains(model) || !Set.of("1024x1024", "1536x1024", "1024x1536").contains(size)
                    || !qualities.contains(quality) || !Set.of("png", "jpeg").contains(outputFormat)) {
                throw new IllegalArgumentException("활성 이미지 Provider의 인증·모델·출력 설정을 확인해주세요.");
            }
            validatePrompts(stylePrompts); validatePrompts(moodPrompts);
        }
    }

    /** 이미지 lease 2분 내의 설정된 외부 호출 예산. DB/CPU 실제 지연까지 보장하지는 않는다. */
    public void validateActivation(ImageOptions options, ImageExecutionProperties execution, ImageS3Properties storage) {
        if (!enabled) return;
        if (options.styles().isEmpty() || !stylePrompts.keySet().equals(Set.copyOf(options.styles()))
                || !moodPrompts.keySet().equals(Set.copyOf(options.moods())))
            throw new IllegalArgumentException("이미지 허용 옵션과 Provider 프롬프트 매핑이 일치해야 합니다.");
        int threads = Math.min(execution.maxConcurrentCalls(), execution.resultThreads());
        int waves = (execution.maxConcurrentCalls() + threads - 1) / threads;
        var budget = totalTimeout.plus(storage.totalTimeout().multipliedBy(2L * waves)).plusSeconds(5);
        if (budget.compareTo(Duration.ofMinutes(2)) >= 0)
            throw new IllegalArgumentException("Provider와 결과 큐·저장/보상 예산은 이미지 lease보다 짧아야 합니다. 실행 상한/시간 제한을 조정해주세요.");
    }

    private static void validatePrompts(Map<String,String> map) {
        map.forEach((key,value) -> {
            if (!key.matches("[a-z][a-z0-9-]{0,49}") || value.isBlank() || value.length() > 1000)
                throw new IllegalArgumentException("이미지 옵션 프롬프트 설정이 잘못되었습니다.");
        });
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    @Override public String toString() { return "OpenAiImageProperties[enabled="+enabled+"]"; }
}
