package com.mongle.backend.global.config;

import com.mongle.backend.domain.auth.oauth.MongleOAuth2UserService;
import com.mongle.backend.domain.auth.oauth.MongleOidcUserService;
import com.mongle.backend.domain.auth.oauth.OAuthLoginHandlers;
import com.mongle.backend.global.error.CommonErrorCode;
import com.mongle.backend.global.security.SecurityResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment,
            ObjectProvider<ClientRegistrationRepository> registrations,
            MongleOAuth2UserService kakao, MongleOidcUserService google,
            OAuthLoginHandlers handlers, SecurityResponseWriter responses) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        http.formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.failure(response, CommonErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                responses.failure(response, CommonErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                    "/actuator/health", "/oauth2/authorization/**", "/login/oauth2/code/**").permitAll();
                    if (local) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    // JWT 검증을 연결하기 전까지 업무 API에 대한 접근을 차단한다.
                    auth.anyRequest().denyAll();
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
