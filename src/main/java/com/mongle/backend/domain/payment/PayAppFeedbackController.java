package com.mongle.backend.domain.payment;

import com.mongle.backend.global.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController @RequiredArgsConstructor @Slf4j
public class PayAppFeedbackController implements PayAppFeedbackApi {
    private final PayAppFeedbackVerifier verifier;
    private final PaymentFeedbackService service;
    @Override @PostMapping(value="/api/v1/payments/payapp/feedback",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> feedback(HttpServletRequest request) {
        try {
            if(request.getContentLengthLong()>32768) return response(413,"FAIL");
            byte[] bytes=request.getInputStream().readNBytes(32769);
            if(bytes.length>32768) return response(413,"FAIL");
            var event=verifier.verify(PayAppForm.decode(new String(bytes,StandardCharsets.UTF_8)));
            service.accept(event); // 프록시 반환 시 트랜잭션 커밋도 완료됐다.
            return response(200,"SUCCESS");
        } catch(BusinessException e) {
            return response(e.getErrorCode().getHttpStatus().value(),"FAIL");
        } catch(IllegalArgumentException e) {
            return response(400,"FAIL");
        } catch(Exception e) {
            // 통보 원문·인증 값·전화번호·SQL 바인딩·외부 오류를 로깅하지 않는다.
            log.warn("페이앱 통보 저장 실패: code=PAYMENT_FEEDBACK_RETRY");
            return response(503,"FAIL");
        }
    }
    private ResponseEntity<String> response(int status,String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).cacheControl(CacheControl.noStore()).body(body);
    }
}
