package com.hajacheck.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.admin.repository.AdminPlanRepository;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;

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
    private CompanyRepository companyRepository;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private com.hajacheck.membership.service.PlanDowngradeService planDowngradeService;

    @InjectMocks
    private AdminPlanService service;

    @org.junit.jupiter.api.BeforeEach
    void 현재플랜조회를_기본으로_stub한다() {
        // #890 — changePlan 이 하향 판정을 위해 현재 플랜(findPlan)을 조회한다. preview 자체는 이 클래스에서
        // 목이라 현재 플랜 값은 결과에 영향을 주지 않으므로, 비어 있지 않기만 하면 된다.
        org.mockito.Mockito.lenient()
                .when(planRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                // 목표 플랜(STANDARD)과 구별 가능한 값이어야 인자 순서 검증이 의미를 갖는다(재검토 P2).
                .thenReturn(Optional.of(Plan.create(PlanName.FREE, 1, 50, 1,
                        true, false, false, BigDecimal.ZERO)));
    }

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
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.of(targetPlan));
        // 이 테스트의 관심사는 동시성 경합(409) 매핑이라 하향 초과는 없는 상황으로 고정한다(#890).
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class))).thenReturn(DowngradeOverflow.none());
        // 첫 saveAndFlush(만료 처리)는 성공, 두 번째(신규 ACTIVE 삽입)에서 부분 UQ 위반을 재현한다.
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenReturn(current)
                .thenThrow(new DataIntegrityViolationException("uq_user_plans_active_company violated"));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD, false))
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
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FORBIDDEN));
    }

    @Test
    void 플랜쿼터목록조회_승인멤버십없어도_companyId정상반환_PLAN_NOT_FOUND아님() {
        // #887 방어 처리: 실제 가입 경로(CompanyAccountWriter)는 CompanyMembership 행을 만들지 않으므로,
        // 승인 멤버십이 전혀 없어도(companyMembershipRepository mock 자체를 두지 않음) companyId만으로
        // 정상 조회되어야 한다 — user_plans 도 없는 상태라 plan/quotaLimit은 null로 응답(활성 구독 없음).
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.empty());
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.UPGRADE_REQUESTED)).thenReturn(Optional.empty());
        when(adminUserRepository.searchActiveMembers(any(), any(), any(), any()))
                .thenReturn(Page.empty());
        when(adminUserRepository.countByCompanyIdAndStatus(any(), any())).thenReturn(0L);
        when(mediaRepository.countByAssignedInspectorInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        var response = service.getPlanQuota(adminUserId, 1, 20, null);

        assertThat(response.stats().companyPlan()).isNull();
    }

    @Test
    void 하향으로_한도초과가생기는데_확인이없으면_아무것도바꾸지않고_409() {
        // #890 핵심 안전장치 — 관리자가 모르는 사이에 구성원이 정지되면 안 된다. 확인 플래그가 없으면
        // 플랜 만료/신규 발급(saveAndFlush) 자체에 도달하지 않아야 한다(부작용 0).
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Plan targetPlan = Plan.create(PlanName.FREE, 1, 50, 1, true, false, false, BigDecimal.ZERO);
        Company company = Company.createPendingReview(adminUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.of(targetPlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class)))
                .thenReturn(new DowngradeOverflow(List.of(7L, 8L), 3));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.FREE, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED));

        // 부작용 부재가 이 테스트의 핵심 — 구독 전이도, 좌석 정지도 시도되지 않아야 한다.
        verify(adminPlanRepository, never()).saveAndFlush(any(UserPlan.class));
        verify(planDowngradeService, never()).applyOverflow(anyLong(), any(Plan.class), any(Plan.class));
    }

    @Test
    void 하향이라도_초과가없으면_확인없이_그대로변경된다() {
        // 불필요한 마찰 금지(#890) — 넘치는 자원이 없으면 확인 플래그를 요구하지 않는다.
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
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.of(targetPlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class))).thenReturn(DowngradeOverflow.none());
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.changePlan(adminUserId, PlanName.STANDARD, false);

        // (current, target) 순서가 뒤바뀌면 isNarrowing 판정이 통째로 뒤집혀 P1 이 재발한다(재검토 P2).
        // 같은 타입 파라미터가 인접해 스왑이 쉬우므로 구체 인자로 순서를 고정한다.
        ArgumentCaptor<Plan> plans = ArgumentCaptor.forClass(Plan.class);
        verify(planDowngradeService).applyOverflow(eq(companyId), plans.capture(), plans.capture());
        assertThat(plans.getAllValues()).extracting(Plan::getName)
                .containsExactly(PlanName.FREE, PlanName.STANDARD);
    }
}
