package com.mongle.backend.domain.ai.dto.request;

import com.mongle.backend.domain.ai.entity.AiTaskType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 도메인 서비스가 Gateway에 전달하는 내부 요청.
 *
 * <p>인증된 사용자 정보와 작업 맥락은 서버가 구성한다. 사용자 ID와 프롬프트 버전은
 * 추후 호출 로그에 사용할 맥락이며, 외부 제공자로 반드시 전송해야 하는 필드는 아니다.
 * 모델 선택, 타임아웃, 재시도 및 API 키는 제공자 어댑터의 설정에서 관리한다.</p>
 *
 * @param userId 호출 주체인 저장된 사용자의 양수 ID. 소유권 검증은 도메인의 책임이다.
 * @param taskType 현재 요청의 작업 종류. 기존 도메인 Enum을 재사용한다.
 * @param promptVersion 도메인이 관리하는 비어 있지 않은 프롬프트 버전
 * @param messages 하나 이상의 메시지. 순서를 유지한 불변 목록으로 복사한다.
 * @param outputSchema JSON Schema 출력 요청. {@code null}이면 일반 텍스트 생성으로 처리한다.
 */
public record AiGenerationRequest(
        long userId,
        AiTaskType taskType,
        String promptVersion,
        List<AiMessage> messages,
        @Nullable AiJsonSchema outputSchema
) {

    public AiGenerationRequest {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(promptVersion, "promptVersion must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion must not be blank");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        // null 원소도 거절한다. 호출 후 외부 목록을 수정해도 메시지 순서와 내용이 바뀌지 않는다.
        messages = List.copyOf(messages);
    }
}
