package com.mongle.backend.domain.payment;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@Tag(name="PayAppFeedback",description="페이앱 서버 전용 결제 통보. 프론트에서 호출하지 않습니다.")
public interface PayAppFeedbackApi {
    @Operation(summary="페이앱 결제 통보",description="FORM POST. 서버 인증 값·응답 시각·해시·주문별 비밀 상관값·금액·거래를 검증합니다. DB 커밋 완료 후에만 plain text SUCCESS를 반환합니다. 중복 통보는 재지급하지 않습니다. 오류는 FAIL이며 payload를 반환하지 않습니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/x-www-form-urlencoded",schema=@Schema(implementation=Fields.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="커밋 완료 또는 정확한 중복 통보",content=@Content(mediaType="text/plain",schema=@Schema(type="string",example="SUCCESS")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="통보 검증 실패")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="처리 실패. 지급하지 않고 재통보 대기")
    ResponseEntity<String> feedback(@Parameter(hidden=true) HttpServletRequest request);
    record Fields(String userid,String linkkey,String linkval,String responsedate,String hash,
            String var1,String var2,String mul_no,String pay_state,String price,String orig_mul_no,String orig_price) {}
}
