package com.hajacheck.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.hajacheck.admin.repository.AdminPlanRepository;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * AdminPlanService 단위 테스트 — PR#525 머신 리뷰 P2 지적: changePlan 의 동시성 경합(409) 경로가
 * 통합 테스트로 재현하기 어려워(uq_user_plans_active_company 부분 유니크가 진짜 동시 트랜잭션을
 * 요구) 전혀 검증되지 않고 있었다. 여기서는 리포지토리를 목으로 대체해 두 번째 saveAndFlush 가
 * DataIntegrityViolationException 을 던지는 상황을 직접 재현한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminPlanServiceTest {

    @Mock
    private AdminPlanRepository adminPlanRepository;
    @Mock
    private AdminUserRepository adminUserRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyMembershipRepository companyMembershipRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private MediaRepository mediaRepository;

    @InjectMocks
    private AdminPlanService service;

    @Test
    void 플랜변경_두번째saveAndFlush_동시성경합예외_409로매핑() {
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Plan targetPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true,
                new BigDecimal("29000.00"));

        Company company = Company.createPendingReview(adminUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(anyLong(), anyLong(), any()))
                .thenReturn(true);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.of(targetPlan));
        // 첫 saveAndFlush(만료 처리)는 성공, 두 번째(신규 ACTIVE 삽입)에서 부분 UQ 위반을 재현한다.
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenReturn(current)
                .thenThrow(new DataIntegrityViolationException("uq_user_plans_active_company violated"));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT));
    }

    @Test
    void 플랜변경_비소유자ADMIN멤버_PLAN_FORBIDDEN으로거부() {
        // PR#525 머신 리뷰 P1 지적: 승인된 멤버십만으로는 changePlan(즉시 ACTIVE 발급)을 허용해선 안
        // 된다 — requestUpgrade 와 동일하게 회사 소유자(owner)만 허용해야 결제/승인 게이트 우회를 막는다.
        Long adminUserId = 1L;
        Long ownerUserId = 2L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        Company company = Company.createPendingReview(ownerUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(anyLong(), anyLong(), any()))
                .thenReturn(true);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FORBIDDEN));
    }
}
