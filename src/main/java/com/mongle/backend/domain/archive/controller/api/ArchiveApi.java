package com.mongle.backend.domain.archive.controller.api;

import com.mongle.backend.domain.archive.dto.response.ArchiveDetail;
import com.mongle.backend.domain.archive.dto.response.ArchivePage;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/** API 설명은 인터페이스에, HTTP 연결과 응답 처리는 구현 Controller에 둔다. */
@Tag(name = "Archive", description = "내 완성한 꿈 기록 탐색")
@SecurityRequirement(name = "bearerAuth")
public interface ArchiveApi {
    @Operation(summary = "Archive 목록 조회", description = "감정 선택을 완료한 내 꿈을 꿈 날짜·ID 내림차순으로 조회합니다. "
            + "month(YYYY-MM) 또는 date(YYYY-MM-DD) 중 하나를 사용하고 생략하면 전체를 조회합니다. "
            + "기본 size=20, 최대 50입니다. hasNext이면 같은 필터와 nextCursor로 이어서 조회합니다. "
            + "초안·삭제한 기록은 제외하며 분석·이미지가 없는 기록도 포함합니다. 수정·삭제에는 각 카드의 revision을 사용합니다.")
    ResponseEntity<ApiResponse<ArchivePage>> list(@Parameter(hidden = true) Jwt jwt, String month, String date, String cursor, int size);

    @Operation(summary = "Archive 상세 조회", description = "dream에 카드 정보·수정 표시·최신 revision·분석/서사/이미지 상태와 ID를, "
            + "originalText에 사용자의 원문을 반환합니다. 감정은 기존 7종이며 키워드는 AI 생성값입니다. "
            + "타인·초안·삭제·없는 기록은 같은 404입니다. image.hasResult이면 GET /images/{imageId}/download-url로 임시 URL을 받습니다. "
            + "수정·삭제는 기존 /dreams/{dreamId} API를 사용합니다. 생성 본문은 해당 분석·이야기 API에서 조회합니다.")
    ResponseEntity<ApiResponse<ArchiveDetail>> detail(@Parameter(hidden = true) Jwt jwt,
            @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId);
}
