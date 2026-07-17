package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.config.AppMailProperties;
import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.PasswordResetLinkRequest;
import com.hajacheck.auth.dto.PasswordResetLinkResponse;
import com.hajacheck.auth.dto.PasswordResetRequest;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.PasswordResetMailDispatcher;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryPasswordResetTokenStore;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.RecordingPasswordResetMailSender;
import com.hajacheck.support.RecordingPasswordResetMailSender.Sent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 비밀번호 재설정 서비스 — 계약의 보안 요건을 테스트로 고정한다(#194).
 *
 * <p>Redis 없이 검증하려고 fake(토큰 저장소·rate-limiter)를 주입한다. 디스패처는 프록시 없이 직접
 * 생성하므로 발송이 동기로 실행된다 — 덕분에 "발송이 실패해도 응답은 동일"을 결정적으로 검증할 수 있다
 * (@Async 자체의 부착 여부는 PasswordResetMailDispatcherTest 가 따로 고정한다).
 */
class PasswordResetServiceTest {

    private static final String EMAIL = "owner@haja.com";
    private static final String SOCIAL_EMAIL = "social@haja.com";
    private static final String BASE_URL = "https://app.hajacheck.test";
    private static final long USER_ID = 42L;
    private static final long SOCIAL_USER_ID = 77L;

    private Instant now;
    private InMemoryPasswordResetTokenStore tokenStore;
    private InMemoryRateLimiter rateLimiter;
    private RecordingPasswordResetMailSender mailSender;
    private UserRepository userRepository;
    private AuthProperties authProperties;
    private PasswordEncoder passwordEncoder;
    private PasswordResetService service;
    private User user;
    private User socialUser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-17T00:00:00Z");
        tokenStore = new InMemoryPasswordResetTokenStore(() -> now);
        rateLimiter = new InMemoryRateLimiter(() -> now);
        mailSender = new RecordingPasswordResetMailSender();
        userRepository = mock(UserRepository.class);
        authProperties = new AuthProperties();
        passwordEncoder = new BCryptPasswordEncoder();

        AppMailProperties mailProperties = new AppMailProperties();
        mailProperties.setFrontendBaseUrl(BASE_URL);

        user = User.createCompanyOwner(EMAIL, "김대표", passwordEncoder.encode("oldpass1"));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        // 소셜 전용 계정 — passwordHash=null(CustomUserDetailsService 가 비밀번호 로그인을 금지하는 계정).
        socialUser = User.createSocialUser(SocialProvider.KAKAO, "social-id-1", SOCIAL_EMAIL, "소셜사용자");
        ReflectionTestUtils.setField(socialUser, "id", SOCIAL_USER_ID);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail(SOCIAL_EMAIL)).thenReturn(Optional.of(socialUser));
        when(userRepository.findById(SOCIAL_USER_ID)).thenReturn(Optional.of(socialUser));
        when(userRepository.findByEmail("nobody@haja.com")).thenReturn(Optional.empty());

        service = new PasswordResetService(userRepository, tokenStore, rateLimiter,
                new PasswordResetMailDispatcher(mailSender), passwordEncoder, authProperties, mailProperties);
    }

    private PasswordResetLinkResponse request(String email) {
        return service.requestResetLink(new PasswordResetLinkRequest(email));
    }

    private String tokenFromMail() throws Exception {
        Sent sent = mailSender.awaitSent(Duration.ofSeconds(2));
        assertThat(sent).as("메일이 발송되어야 함").isNotNull();
        return sent.resetLink().substring(sent.resetLink().indexOf("token=") + "token=".length());
    }

    // ---------- 1단계: 최초 P1(토큰 응답 노출) 재발 방지 ----------

    @Test
    void 응답에_재설정_토큰이_포함되지_않는다() throws Exception {
        PasswordResetLinkResponse response = request(EMAIL);
        String token = tokenFromMail();

        // 최초 P1: 이메일+사업자번호(둘 다 준공개)만으로 토큰을 응답에 반환 → 누구나 계정 탈취.
        String json = objectMapper.writeValueAsString(response);
        assertThat(json).isEqualTo("{\"requested\":true}").doesNotContain(token);
    }

    @Test
    void 존재하는_이메일과_존재하지_않는_이메일의_응답이_완전히_동일하다() throws Exception {
        PasswordResetLinkResponse existing = request(EMAIL);
        PasswordResetLinkResponse missing = request("nobody@haja.com");

        // 계정 열거 방지 — 바디가 한 글자라도 다르면 존재 여부가 새어나간다.
        assertThat(objectMapper.writeValueAsString(existing))
                .isEqualTo(objectMapper.writeValueAsString(missing));
        assertThat(existing).isEqualTo(missing);
    }

    @Test
    void 존재하지_않는_이메일에는_메일을_보내지_않는다() throws Exception {
        request("nobody@haja.com");

        assertThat(mailSender.nothingSentWithin(Duration.ofMillis(300))).isTrue();
    }

    @Test
    void 메일_발송이_실패해도_200_동일_바디를_반환한다() {
        // 발송 실패를 응답에 반영하면 그 자체가 "계정이 존재한다"는 단서가 된다.
        mailSender.failWith(new IllegalStateException("SMTP 연결 실패"));

        PasswordResetLinkResponse response = request(EMAIL);

        assertThat(response).isEqualTo(PasswordResetLinkResponse.accepted());
    }

    // ---------- 링크 조립(poisoning 방어) ----------

    @Test
    void 링크는_설정된_FRONTEND_BASE_URL_기준으로_생성된다() throws Exception {
        request(EMAIL);

        Sent sent = mailSender.awaitSent(Duration.ofSeconds(2));
        assertThat(sent.toEmail()).isEqualTo(EMAIL);
        assertThat(sent.resetLink()).startsWith(BASE_URL + "/reset-password?token=");
    }

    // ---------- 2단계: 1회용·만료·통일 메시지 ----------

    @Test
    void 토큰은_1회용이다() throws Exception {
        request(EMAIL);
        String token = tokenFromMail();

        service.reset(new PasswordResetRequest(token, "newpass1"));

        assertThatThrownBy(() -> service.reset(new PasswordResetRequest(token, "newpass2")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID);
    }

    @Test
    void 만료된_토큰은_거부된다() throws Exception {
        request(EMAIL);
        String token = tokenFromMail();

        // TTL(기본 10분) 경과 — fake TokenStore 에 주입한 시계를 밀어 Redis 없이 만료를 재현한다.
        now = now.plus(authProperties.getPasswordResetTtl()).plusSeconds(1);

        assertThatThrownBy(() -> service.reset(new PasswordResetRequest(token, "newpass1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID);
    }

    @Test
    void 유효기간_직전_토큰은_통과한다() throws Exception {
        // 만료 테스트가 "항상 실패"라서 통과하는 게 아님을 확인(경계 반대편).
        request(EMAIL);
        String token = tokenFromMail();

        now = now.plus(authProperties.getPasswordResetTtl()).minusSeconds(1);

        assertThat(service.reset(new PasswordResetRequest(token, "newpass1")).reset()).isTrue();
    }

    @Test
    void 무효_만료_사용됨_세_경우의_에러가_동일하다() throws Exception {
        request(EMAIL);
        String usedToken = tokenFromMail();
        service.reset(new PasswordResetRequest(usedToken, "newpass1"));

        request(EMAIL);
        String expiredToken = tokenFromMail();
        now = now.plus(authProperties.getPasswordResetTtl()).plusSeconds(1);

        // 어느 쪽인지 노출하면 토큰 상태를 열거할 수 있다 → 코드·메시지가 모두 같아야 한다.
        for (String token : new String[]{"완전히-무효한-토큰", usedToken, expiredToken}) {
            assertThatThrownBy(() -> service.reset(new PasswordResetRequest(token, "newpass1")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID);
        }
    }

    @Test
    void 재설정에_성공하면_비밀번호가_인코딩되어_바뀐다() throws Exception {
        request(EMAIL);
        String token = tokenFromMail();

        service.reset(new PasswordResetRequest(token, "newpass1"));

        assertThat(user.getPasswordHash()).isNotEqualTo("newpass1");
        assertThat(passwordEncoder.matches("newpass1", user.getPasswordHash())).isTrue();
    }

    // ---------- 재발급 시 이전 토큰 무효화 ----------

    @Test
    void 재발급하면_이전_토큰이_무효화된다() throws Exception {
        request(EMAIL);
        String firstToken = tokenFromMail();

        request(EMAIL);
        String secondToken = tokenFromMail();
        assertThat(secondToken).isNotEqualTo(firstToken);

        // 동시 다발 링크 방지 — 첫 링크는 더 이상 통하지 않아야 한다.
        assertThatThrownBy(() -> service.reset(new PasswordResetRequest(firstToken, "newpass1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID);
        // 최신 링크는 정상 동작.
        assertThat(service.reset(new PasswordResetRequest(secondToken, "newpass1")).reset()).isTrue();
    }

    @Test
    void 토큰_소비_시_인덱스도_정리된다() throws Exception {
        request(EMAIL);
        String token = tokenFromMail();

        service.reset(new PasswordResetRequest(token, "newpass1"));

        // compare-and-delete — 방금 소비한 토큰을 가리키던 인덱스는 사라진다(키 누수 방지).
        assertThat(tokenStore.currentTokenHash(USER_ID)).isNull();
        // (인덱스 정리는 consume 과 같은 Lua 안에서 원자적으로 일어난다.)
    }

    @Test
    void 재발급은_단일_원자연산이라_나중에_발급된_토큰이_항상_살아남는다() throws Exception {
        // P2-1 회귀: 예전엔 issue() 와 rotate() 가 따로였다. 그 사이가 열려 있으면 동시 요청 시
        //   T1 issue(A) → T2 issue(B) → T2 rotate(B) → T1 rotate(A) 로 인터리브되어
        //   "나중에 발송된 메일(B)의 링크가 죽고 먼저 발급된 A 가 사는" 사용자 체감 버그가 났다.
        // 이제 발급은 issueAndRotate 단일 호출(단일 Lua)이라 마지막 발급이 항상 이긴다.
        // (재발급 횟수는 이메일 축 한도 안에서 — 한도 초과는 여기 관심사가 아니다.)
        for (int i = 0; i < authProperties.getPasswordResetRateLimit().getEmailLimit(); i++) {
            request(EMAIL);
        }
        String latest = null;
        Sent sent;
        while ((sent = mailSender.awaitSent(Duration.ofMillis(200))) != null) {
            latest = sent.resetLink().substring(sent.resetLink().indexOf("token=") + "token=".length());
        }

        // 마지막으로 발급(=발송)된 토큰이 유효해야 한다 — 사용자가 최신 메일을 누르는 시나리오.
        assertThat(latest).isNotNull();
        assertThat(service.reset(new PasswordResetRequest(latest, "newpass1")).reset()).isTrue();
    }

    @Test
    void 구토큰_소비는_현재_유효_토큰의_인덱스를_지우지_않는다() throws Exception {
        // compare-and-delete 가 아니라 무조건 삭제였다면, 아래에서 인덱스가 날아가 다음 재발급의
        // "이전 토큰 무효화"가 조용히 실패한다.
        request(EMAIL);
        String firstToken = tokenFromMail();
        request(EMAIL);
        String secondToken = tokenFromMail();

        // 이미 무효화된 구토큰으로 시도(실패) → 인덱스는 두 번째 토큰을 계속 가리켜야 한다.
        assertThatThrownBy(() -> service.reset(new PasswordResetRequest(firstToken, "newpass1")))
                .isInstanceOf(BusinessException.class);

        assertThat(tokenStore.currentTokenHash(USER_ID))
                .isEqualTo(com.hajacheck.auth.support.TokenKeys.hash(secondToken));
    }

    // ---------- 소셜 전용 계정(P2-3) ----------

    @Test
    void 소셜_전용_계정에는_재설정_메일을_보내지_않는다() throws Exception {
        // CustomUserDetailsService 가 소셜 전용 계정의 비밀번호 로그인을 명시적으로 금지한다.
        // 재설정이 비밀번호를 심으면 그 계층 규칙을 이 경로가 말없이 뒤집는 셈이 된다.
        request(SOCIAL_EMAIL);

        assertThat(mailSender.nothingSentWithin(Duration.ofMillis(300))).isTrue();
    }

    @Test
    void 소셜_전용_계정의_응답은_일반_계정과_완전히_동일하다() throws Exception {
        // 차단하되 존재 단서가 되면 안 된다 — 응답이 다르면 "이 이메일은 소셜 계정"임이 새어나간다.
        PasswordResetLinkResponse social = request(SOCIAL_EMAIL);
        PasswordResetLinkResponse normal = request(EMAIL);
        PasswordResetLinkResponse missing = request("nobody@haja.com");

        assertThat(objectMapper.writeValueAsString(social))
                .isEqualTo(objectMapper.writeValueAsString(normal))
                .isEqualTo(objectMapper.writeValueAsString(missing));
    }

    @Test
    void 소셜_전용_계정_토큰으로는_비밀번호가_설정되지_않는다() {
        // 심층방어: 발급 후 계정이 소셜 전용으로 바뀌었거나 다른 경로로 토큰이 생긴 경우.
        String token = tokenStore.issueAndRotate(SOCIAL_USER_ID, authProperties.getPasswordResetTtl());

        assertThatThrownBy(() -> service.reset(new PasswordResetRequest(token, "newpass1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID);
        assertThat(socialUser.hasPassword()).isFalse();
    }

    // ---------- rate-limit ----------

    @Test
    void 이메일_기준_한도_초과_시_429다() {
        int limit = authProperties.getPasswordResetRateLimit().getEmailLimit();
        for (int i = 0; i < limit; i++) {
            assertThat(request(EMAIL)).isEqualTo(PasswordResetLinkResponse.accepted());
        }

        assertThatThrownBy(() -> request(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void 이메일_기준_한도는_존재하지_않는_계정에도_동일하게_걸린다() {
        // 429 가 계정 존재 여부에 따라 달라지면 그것이 곧 열거 수단이 된다.
        int limit = authProperties.getPasswordResetRateLimit().getEmailLimit();
        for (int i = 0; i < limit; i++) {
            request("nobody@haja.com");
        }

        assertThatThrownBy(() -> request("nobody@haja.com"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void 이메일_축은_대소문자를_구분하지_않는다() {
        // 대문자만 바꿔 한도를 우회할 수 있으면 메일 폭탄 방어가 무의미해진다.
        int limit = authProperties.getPasswordResetRateLimit().getEmailLimit();
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        for (int i = 0; i < limit; i++) {
            request("Target@Haja.com");
        }

        assertThatThrownBy(() -> request("TARGET@haja.com"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void 창이_지나면_이메일_한도가_리셋된다() {
        int limit = authProperties.getPasswordResetRateLimit().getEmailLimit();
        for (int i = 0; i < limit; i++) {
            request(EMAIL);
        }

        now = now.plus(authProperties.getPasswordResetRateLimit().getEmailWindow()).plusSeconds(1);

        assertThat(request(EMAIL)).isEqualTo(PasswordResetLinkResponse.accepted());
    }

    @Test
    void 전역_상한_초과_시_429다() {
        // 이메일 축에 걸리지 않게 매번 다른 이메일로 전역 상한만 채운다(공격자 시나리오).
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        int globalLimit = authProperties.getPasswordResetRateLimit().getGlobalLimit();
        for (int i = 0; i < globalLimit; i++) {
            request("user" + i + "@haja.com");
        }

        assertThatThrownBy(() -> request("another@haja.com"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void 이메일_축에서_막힌_요청은_전역_카운터를_소모하지_않는다() {
        // 순서가 반대면, 한 이메일을 두드리는 공격자가 전역 상한까지 밀어올려 정상 사용자를 429로 막을 수 있다.
        int emailLimit = authProperties.getPasswordResetRateLimit().getEmailLimit();
        int globalLimit = authProperties.getPasswordResetRateLimit().getGlobalLimit();
        for (int i = 0; i < emailLimit + 50; i++) {
            try {
                request(EMAIL);
            } catch (BusinessException ignored) {
                // 이메일 축 429 — 기대된 동작.
            }
        }

        // 전역 카운터는 이메일 축을 통과한 emailLimit 건만 소모됐어야 한다 → 다른 이메일은 여전히 통과.
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        for (int i = 0; i < globalLimit - emailLimit; i++) {
            assertThat(request("other" + i + "@haja.com")).isEqualTo(PasswordResetLinkResponse.accepted());
        }
    }
}
