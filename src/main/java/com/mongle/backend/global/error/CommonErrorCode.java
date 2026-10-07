package com.mongle.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    BAD_REQUEST(
            HttpStatus.BAD_REQUEST,
            "COMMON_400_1",
            "잘못된 요청입니다."
    ),

    VALIDATION_FAILED(
            HttpStatus.BAD_REQUEST,
            "COMMON_400_2",
            "입력값을 확인해주세요."
    ),

    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "COMMON_401_1",
            "로그인이 필요합니다."
    ),

    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "COMMON_403_1",
            "접근 권한이 없습니다."
    ),

    NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "COMMON_404_1",
            "요청한 리소스를 찾을 수 없습니다."
    ),

    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "COMMON_405_1",
            "지원하지 않는 요청 방식입니다."
    ),

    NOT_ACCEPTABLE(
            HttpStatus.NOT_ACCEPTABLE,
            "COMMON_406_1",
            "요청한 응답 형식을 지원하지 않습니다."
    ),

    CONFLICT(
            HttpStatus.CONFLICT,
            "COMMON_409_1",
            "현재 리소스 상태와 충돌하는 요청입니다."
    ),

    PAYLOAD_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "COMMON_413_1",
            "요청 데이터가 너무 큽니다."
    ),

    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "COMMON_415_1",
            "지원하지 않는 데이터 형식입니다."
    ),

    TOO_MANY_REQUESTS(
            HttpStatus.TOO_MANY_REQUESTS,
            "COMMON_429_1",
            "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
    ),

    REQUEST_FAILED(
            HttpStatus.BAD_REQUEST,
            "COMMON_400_3",
            "요청을 처리할 수 없습니다."
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON_500_1",
            "서버 오류가 발생했습니다."
    ),

    SERVICE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "COMMON_503_1",
            "서비스를 일시적으로 사용할 수 없습니다."
    ),

    GATEWAY_TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "COMMON_504_1",
            "외부 서비스의 응답 시간이 초과되었습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    public static CommonErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            case 500 -> INTERNAL_SERVER_ERROR;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            default -> status.is5xxServerError()
                    ? INTERNAL_SERVER_ERROR
                    : REQUEST_FAILED;
        };
    }
}
