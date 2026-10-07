package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.error.BusinessException;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** PM 확정 전에는 옵션을 제공하지 않는다. 운영 환경에서 확정한 키만 설정한다. */
@ConfigurationProperties(prefix = "mongle.image")
public record ImageOptions(List<String> styles, List<String> moods) {
    public ImageOptions {
        styles = styles == null ? List.of() : List.copyOf(styles);
        moods = moods == null ? List.of() : List.copyOf(moods);
        for (var key : java.util.stream.Stream.concat(styles.stream(), moods.stream()).toList()) {
            if (!key.matches("[a-z][a-z0-9-]{0,49}")) {
                throw new IllegalArgumentException("이미지 옵션 키는 소문자·숫자·하이픈 1~50자입니다.");
            }
        }
        if (styles.stream().distinct().count() != styles.size()
                || moods.stream().distinct().count() != moods.size()) {
            throw new IllegalArgumentException("이미지 옵션 키가 중복되었습니다.");
        }
    }

    public void requireAllowed(String style, String mood) {
        if (styles.isEmpty()) throw new BusinessException(ImageErrorCode.UNAVAILABLE);
        if (!styles.contains(style) || (mood != null && !moods.contains(mood))) {
            throw new BusinessException(ImageErrorCode.INVALID_OPTION);
        }
    }
}
