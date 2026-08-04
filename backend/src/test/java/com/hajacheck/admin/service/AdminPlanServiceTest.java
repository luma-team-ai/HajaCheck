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
import java.time.Instant;
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
    // #851 — changePlan 이 신규 구독 발급 직후 당월 사용량을 이월한다(결제 경로와 동일 규칙).
    @Mock
    private com.hajacheck.membership.service.PlanTransitionService planTransitionService;
    // #1105 — 즉시 변경 시 그 구독에 걸린 하향 예약(PENDING)을 무효화하고, 조회 응답에 대기 예약을 싣는다.
    @Mock
    private com.hajacheck.membership.repository.ScheduledPlanChangeRepository scheduledPlanChangeRepository;
    @Mock
    private com.hajacheck.membership.service.ScheduledPlanChangeCanceller scheduledPlanChangeCanceller;
    // #1177 — 미결제 유예 판정(예약 생성 차단·응답 한도 기준). 이 클래스의 시나리오는 유예와 무관하므로
    // 아래 BeforeEach 가 "유예 아님"(= 구독 요금제 그대로)으로 스텁한다.
    @Mock
    private com.hajacheck.membership.service.PaymentGraceService paymentGraceService;
    // 좌석 실측 단일 소스(#1473) — QuotaService#measureSeats 재사용 확인용.
    @Mock
    private com.hajacheck.membership.service.QuotaService quotaService;

    @InjectMocks
    private AdminPlanService service;

    @org.junit.jupiter.api.BeforeEach
    void 유예아님을_기본으로_stub한다() {
        org.mockito.Mockito.lenient()
                .when(paymentGraceService.resolveEffectivePlan(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @org.junit.jupiter.api.BeforeEach
    void 현재플랜조회를_기본으로_stub한다() {
        // #890 — changePlan 이 상향/하향 판정을 위해 현재 플랜(findPlan)을 조회한다. preview 자체는 이
        // 클래스에서 목이라 현재 플랜의 한도 값은 결과에 영향을 주지 않는다.
        //
        // ⚠️ #988 이후 <b>가격</b>은 결과에 영향을 준다 — 이 화면은 하향·FREE 전환 전용이라 현재보다 비싼
        // 요금제로 바꾸려 하면 PLAN_UPGRADE_REQUIRES_PAYMENT 로 거절된다. 이 클래스의 시나리오는 모두
        // 목표가 STANDARD(29000)이므로 현재 플랜을 그보다 비싼 ENTERPRISE(99000)로 두어 "하향" 상황을
        // 만든다(목표 플랜과 구별 가능한 값이어야 인자 순서 검증도 의미를 갖는다 — 재검토 P2).
        org.mockito.Mockito.lenient()
                .when(planRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.of(Plan.create(PlanName.ENTERPRISE, null, null, 50,
                        false, true, true, new BigDecimal("99000.00"))));
    }

    @Test
    void 현재플랜조회_좌석은_저장카운터가아니라_실측값이다() {
        // #1473 — usage_counters.seat_count(저장 카운터=99, 드리프트된 값)가 아니라 QuotaService#measureSeats
        // 실측값이 응답에 실려야 한다. MyPlanResponse 와 동일한 회귀 방지 테스트.
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Plan plan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true,
                new BigDecimal("29000.00"));
        com.hajacheck.membership.entity.UsageCounter usage = com.hajacheck.membership.entity.UsageCounter.create(
                current.getId(), java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")).atDay(1),
                10, 2, 3, 99, 0, 0); // seatCount=99(오염된 저장값)

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findById(100L)).thenReturn(Optional.of(plan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(current.getId()), any()))
                .thenReturn(Optional.of(usage));
        when(quotaService.measureSeats(companyId)).thenReturn(1);

        var response = service.getCurrentPlan(adminUserId);

        assertThat(response.usage().seatCount()).isEqualTo(1);
        // 분석·시설물 카운트는 여전히 저장 카운터 그대로(회귀 없음).
        assertThat(response.usage().analyzedImageCount()).isEqualTo(10);
        assertThat(response.usage().facilityCount()).isEqualTo(2);
    }

    @Test
    void 현재플랜조회_당월사용량행없어도_좌석은실측값() {
        // #1473 재현 시나리오 — usage_counters 행 자체가 없는 회사(좌석 점유 시점에 lazy 생성 안 됨).
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Plan plan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true,
                new BigDecimal("29000.00"));

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findById(100L)).thenReturn(Optional.of(plan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(current.getId()), any()))
                .thenReturn(Optional.empty());
        when(quotaService.measureSeats(companyId)).thenReturn(1);

        var response = service.getCurrentPlan(adminUserId);

        assertThat(response.usage().seatCount()).isEqualTo(1);
        assertThat(response.usage().analyzedImageCount()).isZero();
    }

    @Test
    void 플랜변경_상향이면_아무것도_쓰지않고_결제경로로_돌려보낸다() {
        // ⚠️ 리뷰 P1-A — 무결제 승격 경로 차단. 거절이 <b>쓰기 이전</b>에 일어나야 한다(구독 만료·신규
        // 발급이 시작된 뒤 롤백에 기대지 않는다). 현재 FREE(0원) → 목표 STANDARD(29000원) = 상향.
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
        // 현재 플랜을 FREE(0원)로 덮어 상향 상황을 만든다(클래스 기본 stub 은 ENTERPRISE).
        when(planRepository.findById(anyLong())).thenReturn(Optional.of(
                Plan.create(PlanName.FREE, 1, 50, 1, true, false, false, BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD, false, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_UPGRADE_REQUIRES_PAYMENT));

        // 부작용 부재 — 만료도 신규 발급도 사용량 이월도 시도되지 않는다.
        verify(adminPlanRepository, never()).saveAndFlush(any());
        verify(planTransitionService, never()).carryOverUsage(any(), any());
        assertThat(current.getStatus()).isEqualTo(UserPlanStatus.ACTIVE);
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
        // changePlan은 항상 4-인자 preview로 위임한다(재검토 F-9 — 3-인자 오버로드는 런타임 경로가 아니다).
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), any()))
                .thenReturn(DowngradeOverflow.none());
        // 첫 saveAndFlush(만료 처리)는 성공, 두 번째(신규 ACTIVE 삽입)에서 부분 UQ 위반을 재현한다.
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenReturn(current)
                .thenThrow(new DataIntegrityViolationException("uq_user_plans_active_company violated"));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD, false, List.of()))
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

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.STANDARD, false, List.of()))
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
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), any()))
                .thenReturn(new DowngradeOverflow(List.of(7L, 8L), 3));

        assertThatThrownBy(() -> service.changePlan(adminUserId, PlanName.FREE, false, List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED));

        // 부작용 부재가 이 테스트의 핵심 — 구독 전이도, 좌석 정지도 시도되지 않아야 한다.
        verify(adminPlanRepository, never()).saveAndFlush(any(UserPlan.class));
        verify(planDowngradeService, never())
                .applyOverflow(anyLong(), any(Plan.class), any(DowngradeOverflow.class));
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
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), any()))
                .thenReturn(DowngradeOverflow.none());
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.changePlan(adminUserId, PlanName.STANDARD, false, List.of());

        // (current, target) 순서가 뒤바뀌면 isNarrowing 판정이 통째로 뒤집혀 P1 이 재발한다(재검토 P2).
        // 같은 타입 파라미터가 인접해 스왑이 쉬우므로 구체 인자로 순서를 고정한다. applyOverflow는 이제
        // Plan을 하나만 받으므로(재검토 F-7/F-9 — DowngradeOverflow를 재계산하지 않고 그대로 적용) 이
        // 회귀선은 preview() 호출의 (currentPlan, targetPlan) 인자 순서에 재고정한다.
        ArgumentCaptor<Plan> plans = ArgumentCaptor.forClass(Plan.class);
        verify(planDowngradeService).preview(eq(companyId), plans.capture(), plans.capture(), any());
        assertThat(plans.getAllValues()).extracting(Plan::getName)
                .containsExactly(PlanName.ENTERPRISE, PlanName.STANDARD);
        verify(planDowngradeService).applyOverflow(eq(companyId), any(Plan.class), any(DowngradeOverflow.class));
    }

    @Test
    void 플랜변경_기존결제주기가_신규구독에_승계된다() {
        // #1104 — 관리자 콘솔 플랜 변경은 무결제 경로라, 결제일이 밀리지 않도록 기존 current_period_*
        // 를 그대로 승계해야 한다(결제 승인 전이의 리셋과 반대 규칙 — PlanTransitionServiceTest 참고).
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Instant existingPeriodStart = Instant.parse("2026-06-15T00:00:00Z");
        current.startNewBillingPeriod(existingPeriodStart); // 기존에 결제로 확정돼 있던 주기를 시뮬레이션.
        Plan targetPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true,
                new BigDecimal("29000.00"));
        Company company = Company.createPendingReview(adminUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.of(targetPlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), any()))
                .thenReturn(DowngradeOverflow.none());
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.changePlan(adminUserId, PlanName.STANDARD, false, List.of());

        // 첫 번째 saveAndFlush는 만료 처리(current 그대로), 두 번째가 신규 발급된 renewed다.
        ArgumentCaptor<UserPlan> saved = ArgumentCaptor.forClass(UserPlan.class);
        verify(adminPlanRepository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        UserPlan renewed = saved.getAllValues().get(1);
        assertThat(renewed.getCurrentPeriodStart()).isEqualTo(existingPeriodStart);
        assertThat(renewed.getCurrentPeriodEnd()).isEqualTo(current.getCurrentPeriodEnd());
    }

    @Test
    void 플랜변경_유료에서FREE로하향하면_신규구독의_currentPeriodEnd는_null이다() {
        // 리뷰 P1(#1104) 회귀 고정 — 유료→FREE 하향은 requireNotUpgrade가 막는 "상향"이 아니라 정상
        // 허용 경로다. carryOverBillingPeriod가 대상 플랜을 보지 않고 무조건 이전 만료일을 복사하면,
        // FREE 구독인데 마이페이지에 "다음 결제일"이 뜨고, 그 날짜가 지나면 PlatformAdminPlanQuotaService
        // 가 EXPIRED로 오판한다 — FREE는 currentPeriodEnd == null(무기한)이어야 하는 이 PR의 불변식이
        // 승계 경로에서만 깨졌던 버그다. currentPeriodStart는 무료여도 그대로 승계된다.
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Instant existingPeriodStart = Instant.parse("2026-06-15T00:00:00Z");
        current.startNewBillingPeriod(existingPeriodStart); // 기존 유료 구독의 결제 주기.
        Plan targetPlan = Plan.create(PlanName.FREE, 1, 50, 1, true, false, false, BigDecimal.ZERO);
        Company company = Company.createPendingReview(adminUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.of(targetPlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), any()))
                .thenReturn(DowngradeOverflow.none());
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.changePlan(adminUserId, PlanName.FREE, false, List.of());

        ArgumentCaptor<UserPlan> saved = ArgumentCaptor.forClass(UserPlan.class);
        verify(adminPlanRepository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        UserPlan renewed = saved.getAllValues().get(1);
        assertThat(renewed.getCurrentPeriodStart()).isEqualTo(existingPeriodStart);
        assertThat(renewed.getCurrentPeriodEnd()).isNull();
    }

    // ── keepUserIds 전달(#890 Phase 2) — AdminPlanService는 preview()를 정확히 한 번 호출해 그 결과를
    // overflowConfirmed 판정과 applyOverflow 양쪽에 재사용한다(재검토 F-7/F-9). 오버로드 선택 여부가 아니라
    // "어떤 keepUserIds 값이 그대로 preview에 전달됐는가"를 ArgumentCaptor로 단정한다 — 오버로드 구조 자체는
    // 런타임 의미가 없는 목 stub용 분기였으므로 계약으로 고정하지 않는다. 실제 검증(회사 스코프·좌석 초과·
    // owner·ACTIVE ADMIN)은 PlanDowngradeServiceTest가 고정한다.

    @Test
    @SuppressWarnings("unchecked")
    void keepUserIds가_비어있으면_preview에_빈리스트로_전달되고_preview결과가_그대로_applyOverflow에_재사용된다() {
        Long adminUserId = 1L;
        Long companyId = 10L;
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Plan targetPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, true,
                new BigDecimal("29000.00"));
        Company company = Company.createPendingReview(adminUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");
        DowngradeOverflow overflow = DowngradeOverflow.none();

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.STANDARD)).thenReturn(Optional.of(targetPlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), any()))
                .thenReturn(overflow);
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.changePlan(adminUserId, PlanName.STANDARD, false, List.of());

        ArgumentCaptor<List<Long>> keepUserIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(planDowngradeService)
                .preview(eq(companyId), any(Plan.class), any(Plan.class), keepUserIdsCaptor.capture());
        assertThat(keepUserIdsCaptor.getValue()).isEmpty();
        // preview 결과(overflow)가 그대로 applyOverflow에 재사용된다 — 재조회 없음(재검토 F-7).
        verify(planDowngradeService).applyOverflow(companyId, targetPlan, overflow);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepUserIds가_지정되면_preview에_그대로_전달되고_preview결과가_applyOverflow에_재사용된다() {
        Long adminUserId = 1L;
        Long companyId = 10L;
        List<Long> keepUserIds = List.of(3L, 4L);
        User admin = User.builder().companyId(companyId).email("admin@haja.com").name("관리자")
                .passwordHash("hash").build();
        UserPlan current = UserPlan.forCompany(companyId, 100L);
        Plan targetPlan = Plan.create(PlanName.FREE, 1, 50, 1, true, false, false, BigDecimal.ZERO);
        Company company = Company.createPendingReview(adminUserId, "회사", "123-45-67890",
                "대표", "주소", null, "url", "{\"source\":\"MANUAL_INPUT\"}");
        DowngradeOverflow overflow = DowngradeOverflow.none();

        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                companyId, UserPlanStatus.ACTIVE)).thenReturn(Optional.of(current));
        when(planRepository.findByName(PlanName.FREE)).thenReturn(Optional.of(targetPlan));
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), eq(keepUserIds)))
                .thenReturn(overflow);
        when(adminPlanRepository.saveAndFlush(any(UserPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.changePlan(adminUserId, PlanName.FREE, false, keepUserIds);

        ArgumentCaptor<List<Long>> keepUserIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(planDowngradeService)
                .preview(eq(companyId), any(Plan.class), any(Plan.class), keepUserIdsCaptor.capture());
        assertThat(keepUserIdsCaptor.getValue()).containsExactlyElementsOf(keepUserIds);
        verify(planDowngradeService).applyOverflow(companyId, targetPlan, overflow);
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewChange도_keepUserIds가_지정되면_preview에_그대로_전달된다() {
        Long adminUserId = 1L;
        Long companyId = 10L;
        List<Long> keepUserIds = List.of(7L);
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
        when(planDowngradeService.preview(anyLong(), any(Plan.class), any(Plan.class), eq(keepUserIds)))
                .thenReturn(new DowngradeOverflow(List.of(7L), 0));
        when(userRepository.findAllById(List.of(7L))).thenReturn(List.of());

        service.previewChange(adminUserId, PlanName.FREE, keepUserIds);

        ArgumentCaptor<List<Long>> keepUserIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(planDowngradeService)
                .preview(eq(companyId), any(Plan.class), any(Plan.class), keepUserIdsCaptor.capture());
        assertThat(keepUserIdsCaptor.getValue()).containsExactlyElementsOf(keepUserIds);
    }
}
