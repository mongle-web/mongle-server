package com.mongle.backend.domain.ai.liner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * 연결 풀을 재사용하는 LINER 전용 클라이언트. 다른 외부 서비스의 통신 설정과 분리한다.
 *
 * <p>Spring이 먼저 LinerProperties 설정 객체를 준비하고 linerHttpClient()에 주입한다.
 * 이 메서드의 반환 객체도 Bean으로 관리한다. LinerClient는 @Qualifier로 이 Bean을 선택한다.
 * Bean은 Spring이 생성·주입·종료를 관리하는 객체이며 여기서는 HTTP 클라이언트를 공유하기 위해 쓴다.</p>
 */
// Spring 설정 클래스다. 이 설정에는 Bean 메서드 간 호출을 가로채는 프록시가 필요 없어 비활성화한다.
@Configuration(proxyBeanMethods = false)
// LinerProperties를 설정 Bean으로 등록하고 mongle.ai.liner 값을 생성자에 연결한다.
@EnableConfigurationProperties(LinerProperties.class)
// LINER 전용 HTTP 클라이언트를 생성하는 설정 클래스의 정의를 시작한다.
public class LinerConfig {
    // 반환 객체를 Bean으로 등록하고 서버 종료 시 shutdownNow()로 남은 통신 작업을 정리한다.
    @Bean(destroyMethod = "shutdownNow")
    // 설정 Bean을 주입받아 이름이 linerHttpClient인 HTTP 클라이언트 Bean을 만든다.
    public HttpClient linerHttpClient(LinerProperties properties) {
        // HTTP 클라이언트 Builder를 만들고 아래 옵션을 이어서 설정한 결과를 반환한다.
        return HttpClient.newBuilder()
                // 새 연결을 만드는 데 허용할 시간을 설정한다. 이미 연결된 요청의 전체 시간 제한과는 별개다.
                .connectTimeout(properties.connectTimeout())
                // 인증 헤더를 가진 요청이 다른 주소로 자동 이동하지 않도록 한다.
                // 302 같은 리다이렉트를 자동으로 따라가지 않는다. 다른 주소로 인증 정보를 전송하지 않기 위한 정책이다.
                .followRedirects(HttpClient.Redirect.NEVER)
                // 지금까지 지정한 옵션으로 재사용할 HTTP 클라이언트 객체를 완성한다.
                .build();
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }
// 위에서 시작한 코드 블록의 범위를 끝낸다.
}
