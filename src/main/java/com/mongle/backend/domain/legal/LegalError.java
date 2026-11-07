package com.mongle.backend.domain.legal;
import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
@Getter @RequiredArgsConstructor
public enum LegalError implements ErrorCode {
    INVALID(HttpStatus.BAD_REQUEST,"LEGAL_400_1","필수 동의 두 항목과 만 14세 이상 여부를 확인해주세요."),
    REQUIRED(HttpStatus.FORBIDDEN,"LEGAL_403_1","최신 이용약관과 개인정보 수집·이용에 동의해주세요."),
    VERSION(HttpStatus.CONFLICT,"LEGAL_409_1","문서가 변경되었습니다. 최신 문서를 확인해주세요."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,"LEGAL_503_1","동의 문서가 준비되지 않았습니다."),
    DOCUMENT_CONFLICT(HttpStatus.SERVICE_UNAVAILABLE,"LEGAL_503_2","동의 문서 버전과 내용이 일치하지 않습니다. 잠시 후 다시 확인해주세요.");
    private final HttpStatus httpStatus; private final String code; private final String message;
}
