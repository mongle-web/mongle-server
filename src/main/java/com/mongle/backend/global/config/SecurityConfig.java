package com.mongle.backend.global.config;

import com.mongle.backend.domain.auth.oauth.MongleOAuth2UserService;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.security.MongleJwtAuthenticationConverter;
import com.mongle.backend.domain.auth.oauth.MongleOidcUserService;
import com.mongle.backend.domain.auth.oauth.OAuthLoginHandlers;
import com.mongle.backend.global.error.CommonErrorCode;
import com.mongle.backend.global.security.SecurityResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment,
            ObjectProvider<ClientRegistrationRepository> registrations,
            MongleOAuth2UserService kakao, MongleOidcUserService google,
            OAuthLoginHandlers handlers, SecurityResponseWriter responses,
            MongleJwtAuthenticationConverter jwtUsers, CookieCsrfTokenRepository csrfTokens,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        AccessDeniedHandler denied = (request, response, exception) -> {
            var current = SecurityContextHolder.getContext().getAuthentication();
            boolean pending = !(exception instanceof CsrfException) && current != null
                    && current.getPrincipal() instanceof Jwt
                    && current.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ONBOARDED"));
            responses.failure(response, pending ? UserErrorCode.ONBOARDING_REQUIRED : CommonErrorCode.FORBIDDEN);
        };
        var corsFilter = new CorsFilter(corsSource);
        corsFilter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse response) throws IOException {
                // CORS에서 거절한 요청도 공통 JSON 오류 형식으로 반환한다.
                if (response instanceof ServletServerHttpResponse servlet) {
                    responses.failure(servlet.getServletResponse(), CommonErrorCode.FORBIDDEN);
                } else {
                    super.rejectRequest(response);
                }
            }
        });
        http.cors(AbstractHttpConfigurer::disable)
                .addFilterBefore(corsFilter, CsrfFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        // 업무 API는 Bearer 인증만 사용한다. 쿠키를 쓰는 재발급·로그아웃은 CSRF를 검증한다.
                        .ignoringRequestMatchers(request -> {
                            String path = request.getServletPath();
                            return path.startsWith("/api/v1/")
                                    && !path.equals("/api/v1/auth/refresh")
                                    && !path.equals("/api/v1/auth/logout");
                        }))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtUsers))
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.failure(response, AuthErrorCode.INVALID_TOKEN))
                        .accessDeniedHandler(denied))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // 같은 HTTP 요청의 ASYNC/ERROR 디스패치에서 JWT 인증을 다시 읽는다.
                // 세션에는 저장하지 않으므로 다음 HTTP 요청은 여전히 Bearer 토큰 인증이 필요하다.
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.failure(response, CommonErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler(denied))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                    "/actuator/health", "/oauth2/authorization/**", "/login/oauth2/code/**").permitAll();
                    if (local) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/users/me").hasRole("USER")
                            .requestMatchers(HttpMethod.POST, "/api/v1/users/me/onboarding").hasRole("USER")
                            .anyRequest().hasRole("ONBOARDED");
                });
        if (local) {
            http.csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**"))
                    .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                    .userInfoEndpoint(info -> info.userService(kakao).oidcUserService(google))
                    .successHandler(handlers.success())
                    .failureHandler(handlers.failure()));
        }
        return http.build();
    }
}
