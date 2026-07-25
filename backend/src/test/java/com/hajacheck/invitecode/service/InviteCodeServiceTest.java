package com.hajacheck.invitecode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.invitecode.dto.InviteCodeIssueResponse;
import com.hajacheck.invitecode.support.InviteCodeStore;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * InviteCodeService 단위 테스트(#794, PR머신 리뷰 P2 대응) — 발급 충돌 재시도, 폐기의 회사 스코프,
 * redeem의 상태전이 가드·코드 소비 순서를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class InviteCodeServiceTest {

    private static final Duration TTL = Duration.ofSeconds(180);

    @Mock
    private InviteCodeStore inviteCodeStore;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AuthService authService;

    @Mock
    private AuthProperties authProperties;

    @InjectMocks
    private InviteCodeService inviteCodeService;

    // ── issue ──

    @Test
    void issue_성공하면_코드와_ttl초를_반환한다() {
        when(authProperties.getInviteCodeTtl()).thenReturn(TTL);
        when(inviteCodeStore.issueIfAbsent(anyString(), eq("10"), eq(TTL))).thenReturn(true);

        InviteCodeIssueResponse response = inviteCodeService.issue(10L);

        assertThat(response.code()).isNotBlank();
        assertThat(response.ttlSeconds()).isEqualTo(180L);
    }

    @Test
    void issue_companyId가_없으면_FORBIDDEN() {
        assertThatThrownBy(() -> inviteCodeService.issue(null))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void issue_충돌이_나면_재시도해서_성공한다() {
        when(authProperties.getInviteCodeTtl()).thenReturn(TTL);
        // 처음 두 번은 이미 존재(충돌), 세 번째에 선점 성공.
        when(inviteCodeStore.issueIfAbsent(anyString(), eq("10"), eq(TTL)))
                .thenReturn(false, false, true);

        InviteCodeIssueResponse response = inviteCodeService.issue(10L);

        assertThat(response.code()).isNotBlank();
        verify(inviteCodeStore, times(3)).issueIfAbsent(anyString(), eq("10"), eq(TTL));
    }

    @Test
    void issue_5회_연속_충돌하면_INTERNAL_ERROR() {
        when(authProperties.getInviteCodeTtl()).thenReturn(TTL);
        when(inviteCodeStore.issueIfAbsent(anyString(), eq("10"), eq(TTL))).thenReturn(false);

        assertThatThrownBy(() -> inviteCodeService.issue(10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(inviteCodeStore, times(5)).issueIfAbsent(anyString(), eq("10"), eq(TTL));
    }

    // ── revoke ──

    @Test
    void revoke_발급회사가_요청하면_삭제한다() {
        when(inviteCodeStore.peek("ABC123")).thenReturn(Optional.of("10"));

        inviteCodeService.revoke("ABC123", 10L);

        verify(inviteCodeStore).delete("ABC123");
    }

    @Test
    void revoke_다른회사가_요청하면_삭제하지않는다() {
        // 발급 회사=10, 요청자 회사=99(cross-company) — 존재 여부를 알려주지 않고 조용히 무시.
        when(inviteCodeStore.peek("ABC123")).thenReturn(Optional.of("10"));

        inviteCodeService.revoke("ABC123", 99L);

        verify(inviteCodeStore, never()).delete(anyString());
    }

    @Test
    void revoke_존재하지않는코드는_예외없이_무시한다() {
        when(inviteCodeStore.peek("ABC123")).thenReturn(Optional.empty());

        inviteCodeService.revoke("ABC123", 10L);

        verify(inviteCodeStore, never()).delete(anyString());
    }

    // ── redeem ──

    @Test
    void redeem_WAITING사용자가_유효코드로_성공하면_ACTIVE로_전환되고_코드가_소비된다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-1", "a@haja.com", "홍길동");
        when(userRepository.findById(1L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.consumeIfPresent("ABC123")).thenReturn(Optional.of("10"));
        when(companyRepository.existsById(10L)).thenReturn(true);
        UserResponse expected = new UserResponse(1L, "a@haja.com", "홍길동", Role.USER, 10L, null, null, null,
                UserStatus.ACTIVE);
        when(authService.getMe(1L)).thenReturn(expected);

        UserResponse result = inviteCodeService.redeem("ABC123", 1L);

        assertThat(result).isEqualTo(expected);
        assertThat(waitingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(waitingUser.getCompanyId()).isEqualTo(10L);
        verify(inviteCodeStore).consumeIfPresent("ABC123");
    }

    // PR머신 리뷰 P2 회귀 고정 — 이미 ACTIVE인 계정의 redeem 시도는 코드를 소비(consumeIfPresent)하기
    // *전에* 막혀야 한다. 순서가 바뀌면 정당한 코드가 이 요청 하나로 소각돼 실제 대상자가 못 쓴다.
    @Test
    void redeem_이미ACTIVE인사용자가_요청하면_코드를_소비하지않고_예외를던진다() {
        User activeUser = User.createByAdmin("b@haja.com", "김철수", Role.USER, "$2a$hash", 5L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(activeUser));

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 2L))
                .isInstanceOf(DomainStateTransitionException.class);

        verify(inviteCodeStore, never()).consumeIfPresent(anyString());
        verify(inviteCodeStore, never()).peek(anyString());
    }

    @Test
    void redeem_코드가_유효하지않으면_AUTH_INVITE_CODE_INVALID() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-2", "c@haja.com", "박영희");
        when(userRepository.findById(3L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.consumeIfPresent("BADCODE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteCodeService.redeem("BADCODE", 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVITE_CODE_INVALID));
        assertThat(waitingUser.getStatus()).isEqualTo(UserStatus.WAITING);
    }

    @Test
    void redeem_사용자가_없으면_USER_NOT_FOUND이고_코드를_소비하지않는다() {
        when(userRepository.findById(4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 4L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
        verify(inviteCodeStore, never()).consumeIfPresent(anyString());
    }

    // PR머신 리뷰 P2 추가 지적 — 발급 시점엔 있던 회사가 redeem 시점엔 삭제됐을 수 있다.
    @Test
    void redeem_회사가_존재하지않으면_AUTH_INVITE_CODE_INVALID이고_사용자는_WAITING으로_남는다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-3", "d@haja.com", "이순신");
        when(userRepository.findById(5L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.consumeIfPresent("ABC123")).thenReturn(Optional.of("999"));
        when(companyRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 5L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVITE_CODE_INVALID));
        assertThat(waitingUser.getStatus()).isEqualTo(UserStatus.WAITING);
        assertThat(waitingUser.getCompanyId()).isNull();
    }
}
