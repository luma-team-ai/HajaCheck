package com.hajacheck.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hajacheck.admin.dto.AdminUserCreateRequest;
import com.hajacheck.admin.dto.AdminUserResponse;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.service.DemoAccountGuard;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AdminUserService 단위 테스트.
 *
 * <p>고정하는 계약 두 가지:
 * <ul>
 *   <li>changeStatus의 상태 화이트리스트 가드(#794, PR머신 리뷰 P3) — WAITING은 companyId=null 소셜 가입
 *       전용 자동 상태라 관리자가 임의로 부여할 수 없다(불변식 "WAITING=companyId 없음" 보호). 실제로는
 *       WAITING 사용자가 companyId=null이라 회사 스코프 findByIdAndCompanyId로 애초에 조회되지 않는
 *       방어 심층이지만, 화이트리스트 자체가 회귀 없이 유지되는지 별도로 고정해둔다(향후 UserStatus 값
 *       추가 시 무단 허용을 막기 위함).</li>
 *   <li>createUser의 유효 멤버십 발급(#1433) — company_memberships APPROVED 행이 없으면 회사 스코프 API가
 *       전부 403이 된다. 인가를 "검사하는 코드"와 "충족시키는 코드"가 쌍으로 유지되는지 고정한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long REQUESTING_ADMIN_ID = 99L;
    private static final Long CREATED_USER_ID = 42L;

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private QuotaService quotaService;

    @Mock
    private DemoAccountGuard demoAccountGuard;

    @Mock
    private CompanyMembershipRepository companyMembershipRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    @Test
    void changeStatus_WAITING_부여시도는_ADMIN_STATUS_NOT_ASSIGNABLE이고_조회하지않는다() {
        assertThatThrownBy(() -> adminUserService.changeStatus(1L, UserStatus.WAITING, COMPANY_ID,
                REQUESTING_ADMIN_ID))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_STATUS_NOT_ASSIGNABLE));

        verify(adminUserRepository, never()).findByIdAndCompanyId(anyLong(), anyLong());
        verify(adminUserRepository, never()).countByCompanyIdAndRoleAndStatus(
                anyLong(), any(Role.class), any(UserStatus.class));
    }

    @Test
    void createUser_성공시_유효한_APPROVED_멤버십을_함께_저장한다() {
        given(adminUserRepository.existsByEmail("new@haja.test")).willReturn(false);
        given(passwordEncoder.encode("pw12345678")).willReturn("hashed");
        given(adminUserRepository.save(any(User.class))).willAnswer(invocation -> {
            User argument = invocation.getArgument(0);
            // IDENTITY 채번을 흉내내되, id를 심은 '별도 인스턴스'를 반환한다 — 인자 인스턴스에 그대로
            // 심어 되돌려주면 구현이 user.getId()를 써도 통과해 "save가 반환한 id를 써야 한다"는 계약이
            // 검증되지 않는다. 인자 쪽 id는 null로 남으므로 그 뮤테이션이 실제로 잡힌다.
            User persisted = User.createByAdmin(argument.getEmail(), argument.getName(), argument.getRole(),
                    "hashed", argument.getCompanyId());
            ReflectionTestUtils.setField(persisted, "id", CREATED_USER_ID);
            return persisted;
        });

        AdminUserResponse response = adminUserService.createUser(
                new AdminUserCreateRequest("new@haja.test", "pw12345678", "신규", Role.INSPECTOR),
                COMPANY_ID, REQUESTING_ADMIN_ID);

        assertThat(response.id()).isEqualTo(CREATED_USER_ID);

        ArgumentCaptor<CompanyMembership> captor = ArgumentCaptor.forClass(CompanyMembership.class);
        verify(companyMembershipRepository).save(captor.capture());
        CompanyMembership membership = captor.getValue();
        assertThat(membership.getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(membership.getUserId()).isEqualTo(CREATED_USER_ID);
        // 감사 추적 — 이 경로는 승인 주체가 곧 요청 관리자라 invitedBy를 채운다(초대 코드 redeem은 null).
        assertThat(membership.getInvitedBy()).isEqualTo(REQUESTING_ADMIN_ID);
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
        // existsEffectiveApprovedMembership가 approvedAt is not null / revokedAt is null /
        // expiresAt(무기한)을 함께 요구한다 — 하나라도 어긋나면 저장돼도 여전히 403이다.
        assertThat(membership.getApprovedAt()).isNotNull();
        assertThat(membership.getRevokedAt()).isNull();
        assertThat(membership.getExpiresAt()).isNull();
    }

    // 좌석 예약(#872 후속)은 User 저장 직전에 호출되므로, 한도 초과 시 계정도 멤버십도 남지 않아야 한다 —
    // 멤버십만 먼저 만들면 "좌석 없는 유효 소속"이라는 유령 상태가 생긴다.
    @Test
    void createUser_좌석한도초과면_계정도_멤버십도_저장하지_않는다() {
        willThrow(new BusinessException(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED))
                .given(quotaService).reserveSeat(COMPANY_ID);

        assertThatThrownBy(() -> adminUserService.createUser(
                new AdminUserCreateRequest("overseat@haja.test", "pw12345678", "좌석초과", Role.USER),
                COMPANY_ID, REQUESTING_ADMIN_ID))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED));

        verify(adminUserRepository, never()).save(any(User.class));
        verify(companyMembershipRepository, never()).save(any(CompanyMembership.class));
    }

    @Test
    void createUser_이메일중복이면_멤버십을_저장하지_않는다() {
        given(adminUserRepository.existsByEmail("dup@haja.test")).willReturn(true);

        assertThatThrownBy(() -> adminUserService.createUser(
                new AdminUserCreateRequest("dup@haja.test", "pw12345678", "중복", Role.USER),
                COMPANY_ID, REQUESTING_ADMIN_ID))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_EMAIL_DUPLICATED));

        verify(adminUserRepository, never()).save(any(User.class));
        verify(companyMembershipRepository, never()).save(any(CompanyMembership.class));
    }
}
