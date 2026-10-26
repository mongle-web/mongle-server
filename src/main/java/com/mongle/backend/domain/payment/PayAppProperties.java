package com.mongle.backend.domain.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("mongle.payment.payapp")
public record PayAppProperties(@DefaultValue("false") boolean enabled, String sellerId, String linkKey,
        String linkValue, String requestKey, URI feedbackUrl, URI returnUrl,
        @DefaultValue("3s") Duration connectTimeout, @DefaultValue("30s") Duration totalTimeout) {
    public PayAppProperties {
        if(connectTimeout==null || totalTimeout==null || connectTimeout.toMillis()<100
                || connectTimeout.compareTo(Duration.ofSeconds(10))>0 || totalTimeout.compareTo(connectTimeout)<0
                || totalTimeout.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("페이앱 호출 제한 시간이 올바르지 않습니다.");
        if(enabled) {
            for(String value:new String[]{sellerId,linkKey,linkValue,requestKey})
                if(value==null || value.isBlank() || value.length()>512)
                    throw new IllegalArgumentException("페이앱 인증 설정을 확인해주세요.");
            https(feedbackUrl); https(returnUrl);
            if(feedbackUrl.getRawQuery()!=null || !feedbackUrl.getPath().equals("/api/v1/payments/payapp/feedback"))
                throw new IllegalArgumentException("페이앱 통보 URL 경로를 확인해주세요.");
            if(org.springframework.web.util.UriComponentsBuilder.fromUri(returnUrl).build().getQueryParams().containsKey("orderId"))
                throw new IllegalArgumentException("복귀 URL의 orderId는 서버가 설정합니다.");
        }
    }
    private static void https(URI u) {
        if(u==null || !"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null
                || u.getPort()!=-1 && u.getPort()!=443)
            throw new IllegalArgumentException("페이앱 연동 URL은 인증 정보 없는 HTTPS 주소여야 합니다.");
    }
    @Override public String toString() { return "PayAppProperties[redacted]"; }
}
