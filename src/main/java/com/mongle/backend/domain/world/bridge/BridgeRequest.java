package com.mongle.backend.domain.world.bridge;

/** 체크 순서가 아닌 dreamedAt 순서로 정규화한다. 최신 버전 선택은 호출자가 명시한다. */
public record BridgeRequest(Long firstStoryVersionId, Long secondStoryVersionId) {}
