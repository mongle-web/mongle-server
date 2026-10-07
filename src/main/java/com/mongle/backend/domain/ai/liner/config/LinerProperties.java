package com.mongle.backend.domain.ai.liner.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * LINER 연결 정책. 도메인 요청에는 인증 정보나 통신 정책을 섞지 않는다.
 * 키 없이도 다른 기능을 개발할 수 있도록 서버 기동은 허용하고, 실제 호출 시에 키를 확인한다.
 * 기본값은 초기 운영을 위한 제한이며 출력량과 응답 시간을 관찰하면서 환경변수로 조정한다.
 *
 * <p>Spring이 YAML·환경변수에서 값을 읽어 이 record를 만든다. record 항목은 apiKey(),
 * requestTimeout() 같은 자동 생성 메서드로 조회한다. 생성자에서 잘못된 값이면 예외를 던져
 * 설정을 사용하는 객체가 만들어지기 전에 실패시킨다. 키의 빈 값만은 로컬 개발을 위해 허용한다.
 * 각 항목 선언 → 생성자 검증 → positiveDuration() 공통 검증 → toString() 키 숨김 순으로 읽는다.</p>
 */
// application.yaml의 mongle.ai.liner 하위 설정을 이 record의 각 항목에 연결한다.
@ConfigurationProperties("mongle.ai.liner")
// 설정용 record를 선언한다. 아래 항목들의 생성자·조회 메서드가 자동으로 만들어진다.
public record LinerProperties(
        // API 키를 저장한다. 설정이 없으면 빈 문자열이며 apiKey()로 읽는다.
        @DefaultValue("") String apiKey,
        // 호출할 전체 URL이다. 생략하면 LINER의 Chat Completions 주소를 사용한다.
        @DefaultValue("https://platform.liner.com/api/v1/chat/completions") URI endpoint,
        // 요청할 공개 모델명이다. 실제 응답에서 보고된 모델명과 구분된다.
        @DefaultValue("liner-mark-1.1") String model,
        // 생성 토큰의 상한이다. 응답 길이와 비용이 과도하게 커지지 않도록 제한한다.
        @DefaultValue("4096") int maxCompletionTokens,
        // LINER에 전달할 추론 강도다. 생략하면 medium을 사용한다.
        @DefaultValue("medium") String reasoningEffort,
        // 새 HTTP 연결을 기다릴 최대 시간이다. 문자열 3s가 Duration으로 변환된다.
        @DefaultValue("3s") Duration connectTimeout,
        // 한 번의 HTTP 시도에 허용할 최대 시간이다.
        @DefaultValue("45s") Duration requestTimeout,
        // 최초 요청부터 재시도 대기까지 합쳐 사용하는 전체 시간 예산이다.
        @DefaultValue("60s") Duration totalTimeout,
        // 최초 호출 이후 추가로 허용할 재시도 횟수다. 1이면 총 HTTP 시도는 최대 두 번이다.
        @DefaultValue("1") int maxRetries,
        // 재시도 기본 대기 시간이다. Gateway가 이 값을 기준으로 무작위 대기 시간을 계산한다.
        @DefaultValue("300ms") Duration retryBackoff,
        // 자동 재시도에서 허용할 최대 대기 시간이다. 제공자가 더 오래 기다리라고 하면 재시도를 포기한다.
        @DefaultValue("5s") Duration maxRetryDelay
// 설정 항목 선언을 마치고 record의 생성자 검증·보조 메서드 정의를 시작한다.
) {
    // record의 compact constructor다. 설정 값이 필드에 저장되기 전에 정리하고 검증한다.
    public LinerProperties {
        // null이면 빈 값으로 바꾸고 키 앞뒤의 공백을 제거한다. 내부 공백은 아래에서 거절한다.
        apiKey = apiKey == null ? "" : apiKey.strip();
        // 키의 각 문자를 검사해 내부 공백·제어 문자·비 ASCII 문자가 하나라도 있는지 확인한다.
        if (apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            // 잘못된 키 형식이면 설정 객체 생성을 중단한다. 키 원문은 메시지에 넣지 않는다.
            throw new IllegalArgumentException("LINER API 키에는 공백이나 제어 문자를 사용할 수 없습니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 실서비스에는 HTTPS를 사용한다. 평문 HTTP는 로컬 모의 서버 검증에만 허용한다.
        // 주소 누락·호스트 누락·URL 내부 인증 정보 중 하나라도 있으면 잘못된 주소로 본다.
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                // 주소 뒤의 쿼리 문자열이나 # 조각도 허용하지 않는다.
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                // HTTPS이거나 아래 조건을 만족하는 로컬 HTTP 주소인지 확인한다. !는 조건을 반대로 만든다.
                || !("https".equals(endpoint.getScheme()) || ("http".equals(endpoint.getScheme())
                // HTTP를 허용하는 호스트를 루프백 주소 세 가지로 제한한다. &&는 앞 조건도 만족해야 한다는 뜻이다.
                && List.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())))) {
            // 허용하지 않는 주소면 외부 요청을 보내기 전에 설정 단계에서 실패시킨다.
            throw new IllegalArgumentException("LINER 주소는 인증 정보와 쿼리가 없는 HTTPS 주소여야 합니다. 로컬 테스트만 HTTP를 허용합니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 모델명이 없거나 공백뿐인지 확인한다.
        if (model == null || model.isBlank()) {
            // 모델명 없는 설정은 생성하지 않는다.
            throw new IllegalArgumentException("LINER 모델명은 필수입니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 출력 제한이 양수이며 정한 최대 범위 이내인지 검사한다. 숫자의 밑줄은 읽기 편한 구분자다.
        if (maxCompletionTokens <= 0 || maxCompletionTokens > 65_536) {
            // 허용 범위를 벗어난 출력 제한이면 설정 오류를 던진다.
            throw new IllegalArgumentException("LINER 출력 토큰 제한은 1~65536이어야 합니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 추론 강도가 허용된 다섯 문자열 중 하나인지 확인한다.
        if (reasoningEffort == null || !List.of("none", "low", "medium", "high", "max").contains(reasoningEffort)) {
            // 지원하지 않는 추론 강도면 호출 전에 설정 오류를 던진다.
            throw new IllegalArgumentException("LINER 추론 강도는 none, low, medium, high, max 중 하나여야 합니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 연결 제한 시간이 양수이고 10분 이내인지 공통 함수로 검사한다.
        positiveDuration(connectTimeout, "연결 제한 시간");
        // 한 번의 호출 제한 시간을 같은 기준으로 검사한다.
        positiveDuration(requestTimeout, "호출 제한 시간");
        // 전체 호출 예산을 같은 기준으로 검사한다.
        positiveDuration(totalTimeout, "전체 제한 시간");
        // 재시도 기본 대기 시간이 유효한 시간 길이인지 검사한다.
        positiveDuration(retryBackoff, "재시도 기본 대기 시간");
        // 최대 대기 시간이 유효한 시간 길이인지 검사한다.
        positiveDuration(maxRetryDelay, "최대 재시도 대기 시간");
        // compareTo가 양수면 왼쪽 시간이 더 길다. 연결 시간이 호출보다, 호출 시간이 전체보다 긴지 확인한다.
        if (connectTimeout.compareTo(requestTimeout) > 0 || requestTimeout.compareTo(totalTimeout) > 0
                // 기본 대기 시간이 최대 대기 시간을 초과하는지도 함께 검사한다.
                || retryBackoff.compareTo(maxRetryDelay) > 0) {
            // 서로 모순되는 시간 설정이면 설정 객체 생성을 거절한다.
            throw new IllegalArgumentException("LINER 시간 제한은 연결 ≤ 호출 ≤ 전체, 기본 대기 ≤ 최대 대기 순서여야 합니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 생성 요청의 재전송은 중복 생성·과금 가능성이 있어 최초 호출 외에 최대 한 번만 허용한다.
        // 재시도 횟수는 비활성화하는 0 또는 한 번 허용하는 1만 받는다.
        if (maxRetries < 0 || maxRetries > 1) {
            // 중복 생성·과금 위험을 늘리는 여러 번의 재시도 설정을 거절한다.
            throw new IllegalArgumentException("LINER 재시도 횟수는 0 또는 1이어야 합니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 여러 시간 설정에 공통으로 사용할 검증 함수다. name은 오류 메시지에 넣을 설정 이름이다.
    private static void positiveDuration(Duration duration, String name) {
        // 누락·음수·0·10분 초과 시간 중 하나인지 확인한다.
        if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(10)) > 0) {
            // 어떤 시간 설정이 잘못됐는지 이름을 포함해 알려준다.
            throw new IllegalArgumentException(name + "은 0보다 크고 10분 이하여야 합니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    /** 설정 객체를 로그로 출력하더라도 API 키가 노출되지 않도록 record의 기본 출력을 대체한다. */
    // 상위 인터페이스 또는 부모 타입에 정의된 메서드를 구현·재정의한다는 표시다.
    @Override
    // record가 자동 생성하는 문자열 출력 대신 키를 숨기는 출력을 정의한다.
    public String toString() {
        // 주소와 모델만 보여 주고 실제 API 키는 별표로 가린다.
        return "LinerProperties[apiKey=***, endpoint=" + endpoint + ", model=" + model + "]";
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }
// 위에서 시작한 코드 블록의 범위를 끝낸다.
}
