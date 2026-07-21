package com.hajacheck.auth.config;

import com.hajacheck.auth.security.CsrfCookieFilter;
import com.hajacheck.auth.security.CustomOAuth2UserService;
import com.hajacheck.auth.security.OAuth2FailureHandler;
import com.hajacheck.auth.security.OAuth2SuccessHandler;
import com.hajacheck.auth.security.RestAccessDeniedHandler;
import com.hajacheck.auth.security.RestAuthenticationEntryPoint;
import com.hajacheck.auth.security.SessionUserRevalidationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 전역 인증 정책 최초 도입 — anyRequest().authenticated() (permitAll 목록 제외).
 * ⚠️ 팀 공지: 이 PR 이후 모든 API 는 기본 인증 필요. 신규 공개 엔드포인트는 permitAll 목록에 명시 추가할 것.
 *
 * 세션 저장: Spring Security 6 는 SecurityContext 명시적 save 필요 →
 *   HttpSessionSecurityContextRepository 를 주입하고, 자체 로그인은 컨트롤러에서 saveContext 호출.
 *   실제 세션 저장소는 Spring Session Redis (application.yml store-type=redis).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(OAuth2Properties.class)
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final CsrfCookieFilter csrfCookieFilter;
    private final SessionUserRevalidationFilter sessionUserRevalidationFilter;

    // securityContextRepository 는 아래 @Bean 으로 정의 — 순환 생성을 피하려 메서드 파라미터로 주입받는다.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        http
                // CSRF: double-submit(SPA axios 가 X-XSRF-TOKEN 자동 전송) — HttpOnly=false 쿠키.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                // CsrfFilter 직후 토큰을 강제 로드해 XSRF-TOKEN 쿠키를 응답에 심는다.
                .addFilterAfter(csrfCookieFilter, CsrfFilter.class)
                // ExceptionTranslationFilter 직후(=AuthorizationFilter 직전)에 둔다 — 강등된 role이
                // 뒤이은 hasRole 판정에 곧바로 반영되고, 정지 계정은 그 판정까지 가기 전에 401로 끊긴다(#405 리뷰 P1).
                .addFilterAfter(sessionUserRevalidationFilter, ExceptionTranslationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/actuator/health",
                                "/swagger-ui/**",
                                "/v3/api-docs/**")
                        .permitAll()
                        // 관리자 콘솔(#405 사용자 관리, #507 플랜·쿼터 관리) — 엔드포인트 레벨에서 ADMIN role 을
                        // 강제한다. 프론트 AdminRoute 는 UX 가드일 뿐 실제 차단은 이 경계가 최종 방어선이다.
                        // 회사 스코프·데이터 소유권은 각 서비스가 company_id 로 추가 필터링한다.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 그 밖은 "인증됨"만 요구. 소셜 셀프가입 계정(companyId=null·role=USER)에 대한
                        // companyId/role 기반 리소스 권한 경계는 각 도메인 엔드포인트에서 후속 과제로 부여한다.
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a.baseUri("/api/auth/oauth2"))
                        .userInfoEndpoint(u -> u.userService(customOAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        // CORS: dev(Vite proxy)·prod(nginx) 모두 프론트와 동일 오리진으로 프록시되므로 CORS 설정 불필요.
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        // DaoAuthenticationProvider 는 CustomUserDetailsService + PasswordEncoder 빈으로 자동 구성.
        return configuration.getAuthenticationManager();
    }
}
