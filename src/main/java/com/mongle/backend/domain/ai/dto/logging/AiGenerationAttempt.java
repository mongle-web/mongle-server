package com.mongle.backend.domain.ai.dto.logging;

import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import org.jspecify.annotations.Nullable;

/**
 * HTTP 시도 하나의 관측 정보. Gateway와 저장 서비스 사이에서만 사용한다.
 * 메시지·프롬프트·생성 원문·키·외부 오류 본문을 담을 필드를 만들지 않아 저장 경계를 제한한다.
 * 최초 호출과 재시도는 callId를 공유하며 attemptNo는 1부터 증가한다.
 */
public record AiGenerationAttempt(
        String callId,                 // 앱에서 발급한 논리 호출 UUID. 제공자의 requestId와 별개다.
        int attemptNo,                 // 이번 호출의 시도 순서. 재시도도 별도 행으로 남긴다.
        long userId,                   // 이미 저장된 사용자의 PK. 외부 제공자에게 전송하지 않는다.
        AiTaskType taskType,           // 호출한 도메인에서 전달한 작업 종류.
        String promptVersion,          // 호출한 도메인이 관리하는 프롬프트 버전.
        String provider,               // 통신 제공자 식별자. 현재는 liner.
        String requestedModel,         // 설정으로 요청한 모델명.
        @Nullable String modelName,    // 응답에 보고된 모델명. 요청 모델로 추정하지 않는다.
        @Nullable String requestId,    // x-request-id 헤더. 응답 본문의 completion id와 다르다.
        @Nullable Integer httpStatus,  // 완성된 응답이 없는 시간 초과·연결 실패에는 null.
        boolean httpAttempted,         // 키 누락 등 전송 전 실패와 실제 통신 시도를 구분한다.
        AiTokenUsage usage,            // 누락은 null, 보고된 실제 0은 0인 수량 객체.
        @Nullable String finishReason, // 제공자 종료 사유 원문. 미제공이면 null.
        long latencyMs,                // 이번 통신·응답 변환 시간. 재시도 대기와 DB 저장은 제외한다.
        boolean success,               // Gateway 결과 반환 여부. 도메인의 분석 성공 여부는 아니다.
        @Nullable String errorCode     // 프로젝트의 공통 AI 오류 코드. 외부 message는 저장하지 않는다.
) { }
