package com.mongle.backend.domain.dream.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import java.util.List;

@Getter
public class DreamUpdateRequest {
    @NotNull(message = "현재 버전을 전달해주세요.")
    @PositiveOrZero(message = "버전은 0 이상이어야 합니다.")
    private Long revision;
    private String originalText;
    private List<DreamEmotion> emotions;
    private String title;
    @JsonIgnore @Schema(hidden = true) private boolean textProvided;
    @JsonIgnore @Schema(hidden = true) private boolean emotionsProvided;
    @JsonIgnore @Schema(hidden = true) private boolean titleProvided;

    public void setRevision(Long value) { revision = value; }
    @JsonSetter("originalText")
    public void setOriginalText(String value) { originalText = value; textProvided = true; }
    @JsonSetter("emotions")
    public void setEmotions(List<DreamEmotion> value) { emotions = value; emotionsProvided = true; }
    @JsonSetter("title")
    public void setTitle(String value) { title = value; titleProvided = true; }

    // 날짜·사용자 ID 등 계약에 없는 값을 조용히 무시하지 않는다.
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("수정할 수 없는 항목입니다.");
    }
}
