package com.mongle.backend.domain.world.folder;

import org.jspecify.annotations.Nullable;
import java.time.LocalDate;

/** 선택 화면에 필요한 원본 메타데이터만 반환하며 AI 생성 결과를 로딩하지 않는다. */
public record SelectedDreamResponse(Long dreamId, LocalDate dreamedAt, @Nullable String title, String originalText) {}
