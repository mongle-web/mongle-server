package com.mongle.backend.domain.world.folder;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldFolderErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "WORLD_404_1", "세계관 폴더를 찾을 수 없습니다."),
    INVALID_NAME(HttpStatus.BAD_REQUEST, "WORLD_400_1", "폴더 이름은 공백을 제외한 내용이 있어야 하며 최대 100자입니다."),
    INVALID_PAGE(HttpStatus.BAD_REQUEST, "WORLD_400_2", "조회 개수는 1~50개이며 이전 폴더 ID는 양수여야 합니다."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "WORLD_400_3", "올바른 꿈 목록 커서를 전달해주세요."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "WORLD_409_1", "폴더가 변경되었습니다. 최신 폴더를 불러온 뒤 다시 시도해주세요."),
    DREAM_NOT_COMPLETED(HttpStatus.CONFLICT, "WORLD_409_2", "기록을 완성한 꿈만 선택할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
