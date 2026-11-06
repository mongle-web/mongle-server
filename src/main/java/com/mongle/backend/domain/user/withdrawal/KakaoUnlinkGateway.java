package com.mongle.backend.domain.user.withdrawal;

import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.HttpClient;

public final class KakaoUnlinkGateway implements AutoCloseable {
    private final WithdrawalProperties properties;
    private final HttpClient http;
    private final RestClient rest;
    private final JsonMapper json=JsonMapper.builder().build();
    public KakaoUnlinkGateway(WithdrawalProperties properties) {
        this.properties=properties;
        var uri=URI.create(properties.unlinkUrl());
        if(!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))))
            throw new IllegalArgumentException("연결 해제 URL은 HTTPS 또는 로컬 테스트 주소여야 합니다.");
        http=HttpClient.newBuilder().connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.requestTimeout());
        rest=RestClient.builder().requestFactory(factory).build();
    }
    public boolean available() { return !properties.kakaoAdminKey().isBlank(); }
    public boolean unlink(String providerId) {
        if(!available() || !providerId.matches("[1-9][0-9]{0,19}")) return false;
        var body=new LinkedMultiValueMap<String,String>();
        body.add("target_id_type","user_id"); body.add("target_id",providerId);
        try {
            return rest.post().uri(properties.unlinkUrl()).header(HttpHeaders.AUTHORIZATION,"KakaoAK "+properties.kakaoAdminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).exchange((request,response)->{
                        byte[] bytes=response.getBody().readNBytes(4097);
                        if(bytes.length>4096) return false;
                        var payload=json.readTree(bytes);
                        return (response.getStatusCode().is2xxSuccessful() && payload.has("id")
                                && providerId.equals(payload.get("id").asString()))
                                || (response.getStatusCode().value()==400 && payload.has("code") && payload.get("code").asInt()==-101);
                    });
        } catch(RuntimeException e) { return false; } // HTTP 메시지/원문/키를 로그 또는 오류 응답에 노출하지 않는다.
    }
    @Override public void close() { http.close(); }
}
