package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.PasswordChangeRequest;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryRateLimiter;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로그인 후 비밀번호 변경 서비스(#1315) — 계약의 보안 요건을 테스트로 고정한다.
 *
 * <p>Redis 없이 검증하려고 rate-limiter fake 를 주입하고(시계 주입 가능 → 창 만료를 대기 없이 검증),
 * 저장소는 mock 으로 둔다. 실제 HTTP 상태코드·세션 종료·principal 기준 동작은
 * {@code PasswordChangeIntegrationTest} 가 따로 고정한다.
 */
class PasswordChangeServiceTest {

    private static final long USER_ID = 42L;
    private static final long SOCIAL_USER_ID = 77L;
    private static final String CURRENT_PASSWORD = "oldpass1";
    private static final String NEW_PASSWORD = "newpass1";

    private Instant now;
    private InMemoryRateLimiter rateLimiter;
    private UserRepository userRepository;
    private AuthProperties authProperties;
    private PasswordEncoder passwordEncoder;
    private PasswordChangeService service;
    private User user;
    private User socialUser;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-30T00:00:00Z");
        rateLimiter = new InMemoryRateLimiter(() -> now);
        userRepository = mock(UserRepository.class);
        authProperties = new AuthProperties();
        passwordEncoder = new BCryptPasswordEncoder();

        user = User.createCompanyOwner("owner@haja.com", "김대표", passwordEncoder.encode(CURRENT_PASSWORD));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        // 소셜 전용 계정 — passwordHash=null(CustomUserDetailsService 가 비밀번호 로그인을 금지하는 계정).
        socialUser = User.createSocialUser(SocialProvider.KAKAO, "social-id-1", "social@haja.com", "소셜사용자");
        ReflectionTestUtils.setField(socialUser, "id", SOCIAL_USER_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.findById(SOCIAL_USER_ID)).thenReturn(Optional.of(socialUser));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        service = new PasswordChangeService(userRepository, passwordEncoder, rateLimiter, authProperties);
    }

    private void change(long userId, String currentPassword, String newPassword) {
        service.changePassword(userId, new PasswordChangeRequest(currentPassword, newPassword));
    }

    // ---------- 성공 ----------

    @Test
    void 변경에_성공하면_비밀번호가_인코딩되어_바뀐다() {
        change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD);

        // 평문 저장 금지 + 새 비밀번호로 검증 통과.
        assertThat(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
        // 옛 비밀번호는 더 이상 통하지 않는다.
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, user.getPasswordHash())).isFalse();
    }

    @Test
    void 같은_평문이라도_해시는_매번_달라진다() {
        String beforeHash = user.getPasswordHash();

        change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD);

        // bcrypt salt — 해시가 그대로면 인코딩을 건너뛴(평문/기존 해시 재사용) 것이다.
        assertThat(user.getPasswordHash()).isNotEqualTo(beforeHash);
    }

    // ---------- 현재 비밀번호 검증 ----------

    @Test
    void 현재_비밀번호가_틀리면_401이고_비밀번호는_그대로다() {
        assertThatThrownBy(() -> change(USER_ID, "wrongpw1", NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isFalse();
    }

    @Test
    void 현재_비밀번호를_모르면_신구동일_여부를_알아낼_수_없다() {
        // 오라클 방지: "신·구 동일" 검사를 현재 비밀번호 확인보다 먼저 두면, 현재 비밀번호를 모르는
        // 호출자가 newPassword 에 후보를 넣어 400(=맞음)/401(=틀림) 차이로 비밀번호를 알아낼 수 있다.
        // 즉 현재 비밀번호를 모르는 요청은 newPassword 가 실제 현재 비밀번호여도 401 이어야 한다.
        assertThatThrownBy(() -> change(USER_ID, "wrongpw1", CURRENT_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    // ---------- 신·구 동일 ----------

    @Test
    void 새_비밀번호가_현재와_같으면_400이다() {
        assertThatThrownBy(() -> change(USER_ID, CURRENT_PASSWORD, CURRENT_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_PASSWORD_UNCHANGED);

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, user.getPasswordHash())).isTrue();
    }

    // ---------- 소셜 전용 계정 ----------

    @Test
    void 소셜_전용_계정은_400이고_비밀번호가_설정되지_않는다() {
        // CustomUserDetailsService 가 소셜 전용 계정의 비밀번호 로그인을 명시적으로 금지한다.
        // 여기서 비밀번호를 심으면 그 계층 규칙을 이 경로가 말없이 뒤집는 셈이 된다.
        assertThatThrownBy(() -> change(SOCIAL_USER_ID, "anything1", NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_PASSWORD_NOT_SET);

        assertThat(socialUser.hasPassword()).isFalse();
    }

    // ---------- 정지 계정(심층방어) ----------

    @Test
    void 정지_계정은_403이고_비밀번호는_그대로다() {
        // 정상 배선에선 SessionUserRevalidationFilter 가 먼저 401 로 끊지만, 서비스 자체도 막아야
        // "정지 계정은 비밀번호 로그인 불가" 규칙이 진입점 변화에 따라 조용히 뚫리지 않는다.
        ReflectionTestUtils.setField(user, "status", UserStatus.SUSPENDED);

        assertThatThrownBy(() -> change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_ACCOUNT_SUSPENDED);

        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, user.getPasswordHash())).isTrue();
    }

    // ---------- 미존재 계정(방어적) ----------

    @Test
    void 존재하지_않는_사용자는_404다() {
        assertThatThrownBy(() -> change(999L, CURRENT_PASSWORD, NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ---------- rate-limit ----------

    @Test
    void 사용자_기준_한도_초과_시_429다() {
        int limit = authProperties.getPasswordChangeRateLimit().getUserLimit();
        for (int i = 0; i < limit; i++) {
            // 실패 시도도 한도를 소모한다 — 무차별 대입 방어가 목적이므로 성공만 세면 의미가 없다.
            assertThatThrownBy(() -> change(USER_ID, "wrongpw1", NEW_PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        assertThatThrownBy(() -> change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        // 한도에 걸린 요청은 실제 변경도 하지 않아야 한다.
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void 한도는_사용자별로_독립이다() {
        // 한 사용자의 시도가 다른 사용자의 변경을 막으면 그 자체가 DoS 수단이 된다.
        int limit = authProperties.getPasswordChangeRateLimit().getUserLimit();
        for (int i = 0; i < limit + 1; i++) {
            assertThatThrownBy(() -> change(USER_ID, "wrongpw1", NEW_PASSWORD))
                    .isInstanceOf(BusinessException.class);
        }

        // 다른 사용자(소셜 전용)는 자기 사유(AUTH_PASSWORD_NOT_SET)로 걸릴 뿐 429 가 아니다.
        assertThatThrownBy(() -> change(SOCIAL_USER_ID, "anything1", NEW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_PASSWORD_NOT_SET);
    }

    @Test
    void 창이_지나면_한도가_리셋된다() {
        int limit = authProperties.getPasswordChangeRateLimit().getUserLimit();
        for (int i = 0; i < limit; i++) {
            assertThatThrownBy(() -> change(USER_ID, "wrongpw1", NEW_PASSWORD))
                    .isInstanceOf(BusinessException.class);
        }

        now = now.plus(authProperties.getPasswordChangeRateLimit().getUserWindow()).plusSeconds(1);

        change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
    }
}
