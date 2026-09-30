package com.mongle.backend.global.error;

import com.mongle.backend.global.response.ApiResponse;
import com.mongle.backend.global.response.FieldErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 직접 정의한 업무 규칙 위반 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();

        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error(
                    "업무 처리 실패: method={}, path={}, code={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    errorCode.getCode(),
                    exception
            );
        } else {
            log.debug(
                    "업무 요청 거절: method={}, path={}, code={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    errorCode.getCode()
            );
        }

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode));
    }

    // @Valid 요청 DTO 검증 실패
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<FieldErrorDetail> errors = exception.getBindingResult()
                .getAllErrors()
                .stream()
                .map(this::toFieldErrorDetail)
                .toList();

        return handleExceptionInternal(
                exception,
                ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED, errors),
                headers,
                status,
                request
        );
    }

    // @RequestParam, @PathVariable 등에 붙인 제약조건 검증 실패
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        // 반환값 검증 실패는 서버 문제이므로 입력 오류로 처리하지 않는다.
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(
                    exception,
                    null,
                    headers,
                    status,
                    request
            );
        }

        List<FieldErrorDetail> errors = new ArrayList<>();

        for (var result : exception.getParameterValidationResults()) {
            String parameterName = Objects.requireNonNullElse(
                    result.getMethodParameter().getParameterName(),
                    "argument" + result.getMethodParameter().getParameterIndex()
            );

            for (var error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError
                        ? fieldError.getField()
                        : parameterName;

                String message = Objects.requireNonNullElse(
                        error.getDefaultMessage(),
                        "입력값이 올바르지 않습니다."
                );

                errors.add(new FieldErrorDetail(field, message));
            }
        }

        for (var error : exception.getCrossParameterValidationResults()) {
            errors.add(new FieldErrorDetail(
                    "request",
                    Objects.requireNonNullElse(
                            error.getDefaultMessage(),
                            "입력값의 조합이 올바르지 않습니다."
                    )
            ));
        }

        return handleExceptionInternal(
                exception,
                ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED, errors),
                headers,
                status,
                request
        );
    }

    // Spring MVC 기본 예외의 상태와 헤더는 유지하고 응답 본문을 통일한다.
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Object responseBody = body instanceof ApiResponse<?>
                ? body
                : ApiResponse.failure(CommonErrorCode.fromStatus(status));

        if (status.is5xxServerError()) {
            log.error(
                    "Spring MVC 처리 실패: status={}, request={}",
                    status.value(),
                    request.getDescription(false),
                    exception
            );
        } else {
            log.debug(
                    "잘못된 HTTP 요청: status={}, request={}",
                    status.value(),
                    request.getDescription(false)
            );
        }

        return super.handleExceptionInternal(
                exception,
                responseBody,
                headers,
                status,
                request
        );
    }

    // 예상하지 못한 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "예상하지 못한 서버 오류: method={}, path={}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        ErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode));
    }

    private FieldErrorDetail toFieldErrorDetail(ObjectError error) {
        String field = error instanceof FieldError fieldError
                ? fieldError.getField()
                : "request";

        String message;

        if (error instanceof FieldError fieldError
                && fieldError.isBindingFailure()) {
            message = "입력값의 형식이 올바르지 않습니다.";
        } else {
            message = Objects.requireNonNullElse(
                    error.getDefaultMessage(),
                    "입력값이 올바르지 않습니다."
            );
        }

        return new FieldErrorDetail(field, message);
    }
}