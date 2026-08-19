package com.hajacheck.invitecode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.invitecode.dto.InviteCodeIssueResponse;
import com.hajacheck.invitecode.support.InviteCodeStore;
import com.hajacheck.membership.service.QuotaService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * InviteCodeService 단위 테스트(#794, PR머신 리뷰 P2 대응) — 발급 충돌 재시도, 폐기의 회사 스코프,
 * redeem의 상태전이 가드·rate-limit·코드 소비 순서(afterCommit)를 고정한다.
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

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private QuotaService quotaService;

    @Mock
    private CompanyMembershipRepository companyMembershipRepository;

    @InjectMocks
    private InviteCodeService inviteCodeService;

    @BeforeEach
    void setUpCommonStubs() {
        // redeem 테스트 대부분이 rate-limit 통과를 전제하므로 기본값을 통과로 깔아둔다(개별 테스트가 재정의 가능).
        lenient().when(authProperties.getInviteCodeRedeemRateLimit())
                .thenReturn(new AuthProperties.InviteCodeRedeemRateLimit());
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        // issue() 테스트 대부분이 좌석 여유를 전제하므로 기본값을 통과로 깔아둔다(#857 선검사 — 개별 테스트가 재정의 가능).
        lenient().when(quotaService.hasAvailableSeat(anyLong())).thenReturn(true);
        // redeem 성공 경로는 멤버십 발급(#1474)을 거친다 — 기존 행 없음이 기본값(신규 발급 분기).
        lenient().when(companyMembershipRepository.findByCompanyIdAndUserId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        // redeem()의 @Transactional 안에서 registerSynchronization을 호출하므로, 실제 트랜잭션 없는
        // 단위 테스트에서도 활성화해둬야 IllegalStateException 없이 동작한다(afterCommit은 테스트에서 수동 트리거).
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDownSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 테스트에서 실제 트랜잭션 매니저 없이 afterCommit 콜백을 수동으로 발화시킨다. */
    private static void triggerAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

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

    // #857 — 좌석 잔여 선검사. 실패 시 inviteCodeStore에 아무 부작용도 남으면 안 되는 것이 이 작업의 핵심 계약.
    @Test
    void issue_좌석여유가_없으면_PLAN_SEAT_QUOTA_EXCEEDED이고_코드를_생성하지않는다() {
        when(quotaService.hasAvailableSeat(10L)).thenReturn(false);

        assertThatThrownBy(() -> inviteCodeService.issue(10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED));
        // 좌석 검사가 코드 생성보다 먼저 일어나야 한다 — 실패한 발급이 inviteCodeStore에 흔적을 남기면 안 된다.
        verify(inviteCodeStore, never()).issueIfAbsent(anyString(), anyString(), any());
        verify(authProperties, never()).getInviteCodeTtl();
    }

    @Test
    void issue_좌석여유가_있으면_평소대로_발급된다() {
        when(authProperties.getInviteCodeTtl()).thenReturn(TTL);
        when(quotaService.hasAvailableSeat(10L)).thenReturn(true);
        when(inviteCodeStore.issueIfAbsent(anyString(), eq("10"), eq(TTL))).thenReturn(true);

        InviteCodeIssueResponse response = inviteCodeService.issue(10L);

        assertThat(response.code()).isNotBlank();
        verify(quotaService).hasAvailableSeat(10L);
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
    void redeem_rate_limit_초과시_AUTH_TOO_MANY_REQUESTS이고_아무것도_조회하지않는다() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 6L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS));
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void redeem_WAITING사용자가_유효코드로_성공하면_ACTIVE로_전환되고_커밋후_코드가_소비된다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-1", "a@haja.com", "홍길동");
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.peek("ABC123")).thenReturn(Optional.of("10"));
        when(companyRepository.existsById(10L)).thenReturn(true);
        UserResponse expected = new UserResponse(1L, "a@haja.com", "홍길동", Role.USER, 10L, null, null, null,
                UserStatus.ACTIVE, false);
        when(authService.getMe(1L)).thenReturn(expected);

        UserResponse result = inviteCodeService.redeem("ABC123", 1L);

        assertThat(result).isEqualTo(expected);
        assertThat(waitingUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(waitingUser.getCompanyId()).isEqualTo(10L);
        // 커밋 전에는 아직 Redis 코드가 소비되지 않는다(afterCommit 콜백에서만 소비).
        verify(inviteCodeStore, never()).consumeIfPresent(anyString());

        triggerAfterCommit();

        verify(inviteCodeStore).consumeIfPresent("ABC123");
    }

    // #1474 회귀 고정 — redeem이 company_memberships 행을 만들지 않아 CompanyScopeGuard 4조건 중 첫 번째가
    // 영구히 불충족이었고, 초대로 합류한 사용자의 회사 스코프 API가 전부 403이었다. users.company_id만
    // 채우는 것으로는 부족하다는 계약을 고정한다.
    @Test
    void redeem_성공하면_APPROVED_멤버십이_같은_트랜잭션에서_발급된다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-9", "m@haja.com", "이초대");
        when(userRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.peek("MEM001")).thenReturn(Optional.of("10"));
        when(companyRepository.existsById(10L)).thenReturn(true);

        inviteCodeService.redeem("MEM001", 9L);

        ArgumentCaptor<CompanyMembership> captor = ArgumentCaptor.forClass(CompanyMembership.class);
        verify(companyMembershipRepository).save(captor.capture());
        CompanyMembership saved = captor.getValue();
        assertThat(saved.getCompanyId()).isEqualTo(10L);
        assertThat(saved.getUserId()).isEqualTo(9L);
        // 아래 4개가 CompanyScopeGuard.existsEffectiveApprovedMembership이 요구하는 멤버십 측 조건 전부다.
        assertThat(saved.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
        assertThat(saved.getApprovedAt()).isNotNull();
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getExpiresAt()).isNull();
        // 오너 팩토리를 재사용하면 초대받은 사람이 회사 오너가 된다 — invitedBy는 발급자 정보가 없어 null.
        assertThat(saved.getInvitedBy()).isNull();
    }

    // 유니크 제약(company_id, user_id) 때문에 기존 행이 있으면 새로 넣을 수 없다 — 500 대신 재승인으로 처리한다.
    @Test
    void redeem_기존_멤버십행이_있으면_새로_저장하지않고_재승인한다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-10", "n@haja.com", "박재초대");
        when(userRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.peek("MEM002")).thenReturn(Optional.of("10"));
        when(companyRepository.existsById(10L)).thenReturn(true);
        CompanyMembership revoked = CompanyMembership.approvedMember(10L, 11L);
        revoked.revoke();
        when(companyMembershipRepository.findByCompanyIdAndUserId(10L, 11L)).thenReturn(Optional.of(revoked));

        inviteCodeService.redeem("MEM002", 11L);

        verify(companyMembershipRepository, never()).save(any());
        assertThat(revoked.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
        assertThat(revoked.getRevokedAt()).isNull();
        assertThat(revoked.getApprovedAt()).isNotNull();
    }

    // PR머신 리뷰 P2 회귀 고정 — 이미 ACTIVE인 계정의 redeem 시도는 코드를 조회/소비하기 *전에* 막혀야 한다.
    @Test
    void redeem_이미ACTIVE인사용자가_요청하면_코드를_건드리지않고_예외를던진다() {
        User activeUser = User.createByAdmin("b@haja.com", "김철수", Role.USER, "$2a$hash", 5L);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(activeUser));

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 2L))
                .isInstanceOf(DomainStateTransitionException.class);

        verify(inviteCodeStore, never()).peek(anyString());
        verify(inviteCodeStore, never()).consumeIfPresent(anyString());
    }

    @Test
    void redeem_코드가_유효하지않으면_AUTH_INVITE_CODE_INVALID() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-2", "c@haja.com", "박영희");
        when(userRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.peek("BADCODE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteCodeService.redeem("BADCODE", 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVITE_CODE_INVALID));
        assertThat(waitingUser.getStatus()).isEqualTo(UserStatus.WAITING);
    }

    @Test
    void redeem_사용자가_없으면_USER_NOT_FOUND이고_코드를_건드리지않는다() {
        when(userRepository.findByIdForUpdate(4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 4L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
        verify(inviteCodeStore, never()).peek(anyString());
    }

    // PR머신 리뷰 P2 추가 지적 — 발급 시점엔 있던 회사가 redeem 시점엔 삭제됐을 수 있다.
    @Test
    void redeem_회사가_존재하지않으면_AUTH_INVITE_CODE_INVALID이고_사용자는_WAITING으로_남고_코드는_소비되지않는다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-3", "d@haja.com", "이순신");
        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.peek("ABC123")).thenReturn(Optional.of("999"));
        when(companyRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> inviteCodeService.redeem("ABC123", 5L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVITE_CODE_INVALID));
        assertThat(waitingUser.getStatus()).isEqualTo(UserStatus.WAITING);
        assertThat(waitingUser.getCompanyId()).isNull();
        verify(inviteCodeStore, never()).consumeIfPresent(anyString());
    }

    // PR머신 리뷰 P2 핵심 회귀 — DB 커밋이 실패(트랜잭션 롤백)하면 코드가 소비되지 않고 그대로 남아
    // 재시도할 수 있어야 한다. afterCommit 콜백 자체가 호출되지 않으면 이 계약이 자동으로 지켜진다.
    @Test
    void redeem_DB커밋이_실패하면_afterCommit이_호출되지않아_코드가_남는다() {
        User waitingUser = User.createSocialUser(SocialProvider.KAKAO, "social-4", "e@haja.com", "정약용");
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(waitingUser));
        when(inviteCodeStore.peek("ABC123")).thenReturn(Optional.of("10"));
        when(companyRepository.existsById(10L)).thenReturn(true);
        when(authService.getMe(7L)).thenReturn(
                new UserResponse(7L, "e@haja.com", "정약용", Role.USER, 10L, null, null, null,
                        UserStatus.ACTIVE, false));

        inviteCodeService.redeem("ABC123", 7L);
        // 커밋 트리거를 호출하지 않음(=DB 커밋 실패/롤백 시나리오와 동일하게 afterCommit 미발화).

        verify(inviteCodeStore, never()).consumeIfPresent(anyString());
    }
}
