package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

class PayAppFeedbackVerifierTest {
    private static final Instant NOW=Instant.parse("2026-10-25T15:01:00Z");
    private final PayAppProperties properties=PayAppGatewayTest.properties(Duration.ofSeconds(2));
    private final PayAppFeedbackVerifier verifier=new PayAppFeedbackVerifier(properties,Clock.fixed(NOW,ZoneOffset.UTC));

    private Map<String,String> event(Instant time) {
        var values=new LinkedHashMap<String,String>();
        String date=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(time);
        values.put("userid",properties.sellerId()); values.put("linkkey",properties.linkKey());
        values.put("linkval",properties.linkValue()); values.put("responsedate",date);
        values.put("hash",PaymentHashes.sha256(properties.sellerId()+date+properties.requestKey()));
        values.put("var1","2233e4d7-992c-48e3-b519-334fc754e925");
        values.put("var2","1233e4d7-992c-48e3-b519-334fc754e925");
        values.put("mul_no","12345"); values.put("price","1100"); values.put("pay_state","4");
        return PayAppForm.decode(PayAppForm.encode(values));
    }
    @Test void kstMidnightAndEncodedCredentialsPreserveStrictFreshnessWindow() {
        assertThat(event(NOW).get("responsedate")).isEqualTo("20261026000100");
        assertThat(event(NOW.minusSeconds(900)).get("responsedate")).isEqualTo("20261025234600");
        for(Instant time:List.of(NOW,NOW.minusSeconds(900),NOW.plusSeconds(120))) {
            var verified=verifier.verify(event(time));
            assertThat(verified.respondedAt()).isEqualTo(time);
            assertThatCode(() -> verifier.requireFresh(verified)).doesNotThrowAnyException();
        }
        for(Instant time:List.of(NOW.minusSeconds(901),NOW.plusSeconds(121)))
            assertThatThrownBy(() -> verifier.requireFresh(verifier.verify(event(time))))
                    .isInstanceOf(BusinessException.class);
    }
    @Test void invalidDateOrChangedCredentialNeverPassesHashVerification() {
        var invalid=event(NOW); invalid.put("responsedate","20260230000100");
        invalid.put("hash",PaymentHashes.sha256(properties.sellerId()+invalid.get("responsedate")+properties.requestKey()));
        assertThatThrownBy(() -> verifier.verify(invalid)).isInstanceOf(BusinessException.class);
        var tampered=event(NOW); tampered.put("linkval","wrong");
        assertThatThrownBy(() -> verifier.verify(tampered)).isInstanceOf(BusinessException.class);
    }
}
