package com.mongle.backend.domain.dream.image;

/** 외부 응답 본문/요청/인증 값을 포함하지 않는 Provider 실패 분류. */
public final class ImageProviderException extends RuntimeException {
    public enum Reason {
        PROVIDER_AUTH_FAILED, PROVIDER_RATE_LIMITED, PROVIDER_TIMEOUT,
        PROVIDER_UNAVAILABLE, PROVIDER_REJECTED
    }
    private final Reason reason;
    public ImageProviderException(Reason reason) {
        super("이미지 제공자 요청을 완료하지 못했습니다.");
        this.reason = reason;
    }
    public String failureCode() { return reason.name(); }
}
