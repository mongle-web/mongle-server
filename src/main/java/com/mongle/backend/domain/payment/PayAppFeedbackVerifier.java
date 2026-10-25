package com.mongle.backend.domain.payment;

import com.mongle.backend.global.error.BusinessException;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.time.*;
import java.time.format.*;
import java.util.*;

@Component @RequiredArgsConstructor
public class PayAppFeedbackVerifier {
    private final PayAppProperties properties;
    private final Clock authClock;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    PayAppFeedback verify(Map<String,String> values) {
        if(!properties.enabled()) throw new BusinessException(PaymentErrorCode.UNAVAILABLE);
        try {
            String seller=field(values,"userid",512),key=field(values,"linkkey",512),value=field(values,"linkval",512);
            String date=field(values,"responsedate",14),hash=field(values,"hash",64);
            if(!PaymentHashes.equal(seller,properties.sellerId()) || !PaymentHashes.equal(key,properties.linkKey())
                    || !PaymentHashes.equal(value,properties.linkValue()) || !hash.matches("[0-9a-fA-F]{64}")
                    || !PaymentHashes.equal(hash.toLowerCase(Locale.ROOT),PaymentHashes.sha256(seller+date+properties.requestKey()))) invalid();
            if(!date.matches("[0-9]{14}")) invalid();
            Instant responded=LocalDateTime.parse(date,DATE).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            String order=field(values,"var1",36),token=field(values,"var2",36),transaction=id(values,"mul_no");
            if(!UUID.fromString(order).toString().equals(order) || !UUID.fromString(token).toString().equals(token)) invalid();
            int state=Integer.parseInt(field(values,"pay_state",2));
            if(!Set.of(1,4,8,32,9,64,10,70,71).contains(state)) invalid();
            long amount=amount(values,"price");
            String original=null; Long originalAmount=null;
            if(state==70 || state==71) { original=id(values,"orig_mul_no"); originalAmount=amount(values,"orig_price"); }
            String canonical=String.join(":",order,token,transaction,Integer.toString(state),Long.toString(amount),
                    original==null?"":original,originalAmount==null?"":originalAmount.toString(),date);
            return new PayAppFeedback(order,token,transaction,state,amount,original,originalAmount,responded,PaymentHashes.sha256(canonical));
        } catch(RuntimeException e) { throw new BusinessException(PaymentErrorCode.INVALID_FEEDBACK); }
    }
    void requireFresh(PayAppFeedback event) {
        Instant now=authClock.instant();
        if(event.respondedAt().isBefore(now.minus(Duration.ofMinutes(15))) || event.respondedAt().isAfter(now.plusSeconds(120))) invalid();
    }
    private static String field(Map<String,String> m,String key,int max) {
        String s=m.get(key); if(s==null || s.isEmpty() || s.length()>max) invalid(); return s;
    }
    private static String id(Map<String,String> m,String key) {
        String s=field(m,key,40); if(!s.matches("[1-9][0-9]{0,39}")) invalid(); return s;
    }
    private static long amount(Map<String,String> m,String key) {
        String s=field(m,key,10); if(!s.matches("[1-9][0-9]{0,9}")) invalid();
        long n=Long.parseLong(s); if(n>Integer.MAX_VALUE) invalid(); return n;
    }
    private static void invalid() { throw new BusinessException(PaymentErrorCode.INVALID_FEEDBACK); }
}
