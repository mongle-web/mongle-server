package com.mongle.backend.domain.dream.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DreamEmotion {
    HAPPY("행복"), CALM("편안함"), EXCITED("설렘"), SAD("슬픔"),
    ANXIOUS("불안"), ANGRY("화남"), CONFUSED("당황");

    private final String label;
}
