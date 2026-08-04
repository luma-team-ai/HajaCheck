package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.dto.SeatsResponse;
import com.hajacheck.membership.dto.UpgradeInquiryResponse;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private PaymentGraceService paymentGraceService;
    // 좌석 실측 단일 소스(#1473) — QuotaService#measureSeats 재사용 확인용.
    @Mock
    private QuotaService quotaService;
    @InjectMocks
    private MembershipService service;

    /**
     * 미결제 유예(#1177) 판정 — 이 테스트들은 유예와 무관하므로 <b>항상 구독 요금제 그대로</b>를
     * 돌려주도록 스텁한다(유예가 아닐 때의 실제 동작과 같다).
     */
    @BeforeEach
    void stubNoPaymentGrace() {
        when(paymentGraceService.resolveEffectivePlan(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 10L;
    private static final Long PLAN_ID = 100L;
    private static final Long ENTERPRISE_PLAN_ID = 101L;

    private User individualUser;
    private User companyUser;
    private Plan standardPlan;
    private Plan enterprisePlan;

    @BeforeEach
    void setUp() {
        individualUser = user(USER_ID, null);
        companyUser = user(USER_ID, COMPANY_ID);
        standardPlan = Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, false,
                BigDecimal.valueOf(99000));
        setId(standardPlan, PLAN_ID);
        enterprisePlan = Plan.create(PlanName.ENTERPRISE, null, null, null, false, true, true,
                BigDecimal.valueOf(299000));
        setId(enterprisePlan, ENTERPRISE_PLAN_ID);
    }

    // ── getMyPlan ──

    @Test
    void 내플랜조회_개인구독_ACTIVE_사용량있음() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);
        UsageCounter usage = UsageCounter.create(500L, period, 786, 4, 12, 1, 0, 2);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(500L, period)).thenReturn(Optional.of(usage));

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().name()).isEqualTo("STANDARD");
        assertThat(response.plan().status()).isEqualTo("ACTIVE");
        assertThat(response.limits().maxFacilities()).isEqualTo(10);
        assertThat(response.limits().maxSeats()).isEqualTo(3);
        assertThat(response.usage().analyzedImageCount()).isEqualTo(786);
        assertThat(response.usage().facilityCount()).isEqualTo(4);
        assertThat(response.usage().period()).isEqualTo(period);
    }

    @Test
    void 내플랜조회_당월사용량행없음_시설물분석은0_좌석은실측() {
        // #1473 회귀 방지 — usage_counters 행이 없을 때 facilityCount/analyzedImageCount 는 여전히 0(실측
        // 원천이 없는 기간 누적)이지만, seatCount 는 "현재 상태"라 카운터 행 존재 여부와 무관하게 항상
        // QuotaService#measureSeats 실측값이어야 한다(이전에는 0으로 떨어져 버그가 났다).
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(501L, period)).thenReturn(Optional.empty());
        when(quotaService.measureSeats(COMPANY_ID)).thenReturn(1);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.usage().facilityCount()).isZero();
        assertThat(response.usage().analyzedImageCount()).isZero();
        assertThat(response.usage().seatCount()).isEqualTo(1);
        assertThat(response.usage().period()).isEqualTo(period);
    }

    @Test
    void 내플랜조회_저장카운터의seatCount가실측과어긋나도_실측값이이긴다() {
        // #1473 — usage_counters.seat_count(저장 카운터)가 드리프트로 실제와 달라도(예: 5로 저장돼 있는데
        // 실제 활성 인원은 1명), 응답은 항상 실측값을 반환해야 한다.
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);
        UsageCounter usage = UsageCounter.create(501L, period, 10, 2, 3, 5, 0, 0); // seatCount=5(오염된 저장값)

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(501L, period)).thenReturn(Optional.of(usage));
        when(quotaService.measureSeats(COMPANY_ID)).thenReturn(1);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.usage().seatCount()).isEqualTo(1);
        // 시설물·분석 카운트는 여전히 저장 카운터 값 그대로(회귀 없음).
        assertThat(response.usage().facilityCount()).isEqualTo(2);
        assertThat(response.usage().analyzedImageCount()).isEqualTo(10);
    }

    @Test
    void 내플랜조회_개인구독은_좌석실측1() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(500L, period)).thenReturn(Optional.empty());
        when(quotaService.measureSeats(null)).thenReturn(1);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.usage().seatCount()).isEqualTo(1);
    }

    @Test
    void 내플랜조회_ACTIVE없고_UPGRADE_REQUESTED만있으면_그대로반환() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        userPlan.requestUpgrade();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.UPGRADE_REQUESTED))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(500L), any())).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().status()).isEqualTo("UPGRADE_REQUESTED");
    }

    @Test
    void 내플랜조회_활성구독없음_PLAN_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(eq(USER_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyPlan(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void 내플랜조회_회사소속은_회사구독으로조회() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(501L, period)).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().name()).isEqualTo("STANDARD");
    }

    @Test
    void 내플랜조회_유료플랜_다음결제일은_currentPeriodEnd를_KST_LocalDate로변환한값() {
        // #1104 — nextBillingDate는 더 이상 startedAt에서 파생되지 않고 current_period_end 컬럼을
        // 그대로 읽는다. startNewBillingPeriod가 KST 기준 1개월 후로 채우므로 그 값을 그대로 검증한다.
        Instant periodStart = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant();
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        userPlan.startNewBillingPeriod(periodStart);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(500L), any())).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().nextBillingDate()).isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void 내플랜조회_FREE플랜은_다음결제일_null() {
        Plan freePlan = Plan.create(PlanName.FREE, 1, 50, 1, true, false, false, BigDecimal.ZERO);
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(freePlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(500L), any())).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().nextBillingDate()).isNull();
    }

    @Test
    void 내플랜조회_국세청검증_provenance가있으면_businessVerified_true() {
        // #1324 P1 — 배지 근거는 verification_status 가 아니라 ocr_raw.ntsOutcome(provenance) 이다.
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID, "{\"ntsOutcome\":\"VERIFIED\"}");
        company.markBusinessVerified();

        stubCompanyPlan(userPlan, company);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isTrue();
    }

    @Test
    void 내플랜조회_레거시검증분도_businessVerified_true() {
        // V38 이 스탬프한 "#1324 이전 진짜 검증" 회사 — 배지가 거짓으로 꺼지면 안 된다.
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID, "{\"ntsOutcome\":\"LEGACY_VERIFIED\"}");
        company.markBusinessVerified();

        stubCompanyPlan(userPlan, company);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isTrue();
    }

    @Test
    void 내플랜조회_자동승인만된회사는_VERIFIED여도_businessVerified_false() {
        // #1324 P1 회귀 방지 — 국세청 SKIPPED(장애·키 미설정)로 통과한 회사는 verification_status 가
        // VERIFIED 라도 "사업자 인증 완료"라고 말하면 안 된다(허위 표시).
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID, "{\"ntsOutcome\":\"SKIPPED\"}");
        company.markBusinessVerified();
        company.autoApprove();

        stubCompanyPlan(userPlan, company);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isFalse();
    }

    @Test
    void 내플랜조회_V38소급승인분은_businessVerified_false() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID, "{\"ntsOutcome\":\"UNKNOWN_BACKFILL\"}");
        company.markBusinessVerified();

        stubCompanyPlan(userPlan, company);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isFalse();
    }

    @Test
    void 내플랜조회_provenance키가없으면_businessVerified_false() {
        // 증명할 수 없으면 인증 완료라고 말하지 않는다(fail-safe).
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID, "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();

        stubCompanyPlan(userPlan, company);

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isFalse();
    }

    private void stubCompanyPlan(UserPlan userPlan, Company company) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(userPlan.getId()), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void 내플랜조회_회사구독인데_company조회실패시_businessVerified_false_null아님() {
        // 방어적 케이스(정상 데이터에선 발생 불가) — userPlan.companyId 로 "회사 구독"임은 확정됐는데
        // companyRepository.findById 가 비어 company==null 이 되더라도, 계약("회사 구독=boolean")상
        // 개인 구독의 null 과 구분되어야 하므로 false 여야 한다.
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.empty());
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(501L), any())).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isFalse();
    }

    @Test
    void 내플랜조회_개인구독은_businessVerified_null() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(eq(500L), any())).thenReturn(Optional.empty());

        MyPlanResponse response = service.getMyPlan(USER_ID);

        assertThat(response.plan().businessVerified()).isNull();
    }

    // ── getSeats ──

    @Test
    void 좌석조회_회사소속_멤버목록과한도() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        User member2 = user(2L, COMPANY_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(quotaService.measureSeats(COMPANY_ID)).thenReturn(2);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser, member2));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(2);
        assertThat(response.limit()).isEqualTo(3);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void 좌석조회_정지된구성원은_제외() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        // findByCompanyIdAndStatusOrderByIdAsc(..., ACTIVE, ...) 자체가 정지 구성원을 제외한 결과를 반환한다는
        // 전제 — 리포지토리가 활성 사용자만 돌려주므로 서비스가 이를 그대로 신뢰함을 검증.
        when(quotaService.measureSeats(COMPANY_ID)).thenReturn(1);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(1);
        assertThat(response.members()).hasSize(1);
    }

    @Test
    void 좌석조회_상한초과시_목록은상한만_used는실제총원() {
        // 좌석 목록 조회 상한(#484) — members 목록은 방어적으로 잘리지만 used(총원 수) 는
        // 실제 활성 인원 수를 정확히 반영해야 한다(목록 truncation 이 used 집계를 왜곡하면 안 됨).
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        User member2 = user(2L, COMPANY_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(quotaService.measureSeats(COMPANY_ID)).thenReturn(250);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser, member2));

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(250);
        assertThat(response.members()).hasSize(2);
    }

    @Test
    void 좌석조회_개인계정_본인1건() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(quotaService.measureSeats(null)).thenReturn(1);

        SeatsResponse response = service.getSeats(USER_ID);

        assertThat(response.used()).isEqualTo(1);
        assertThat(response.members()).hasSize(1);
        assertThat(response.members().get(0).userId()).isEqualTo(USER_ID);
    }

    @Test
    void 좌석조회_활성구독없음_PLAN_NOT_FOUND() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(eq(COMPANY_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSeats(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void 좌석_getMyPlan과getSeats는_항상같은실측소스를쓴다() {
        // #1473 — 두 엔드포인트가 QuotaService#measureSeats 라는 같은 단일 소스를 거치므로 결과가 항상
        // 일치해야 한다(같은 companyId 로 호출되는지까지 검증해 "두 곳에서 따로 계산" 회귀를 잡는다).
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        LocalDate period = YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(standardPlan));
        when(usageCounterRepository.findByUserPlanIdAndPeriod(501L, period)).thenReturn(Optional.empty());
        when(quotaService.measureSeats(COMPANY_ID)).thenReturn(1);
        when(userRepository.findByCompanyIdAndStatusOrderByIdAsc(eq(COMPANY_ID), eq(UserStatus.ACTIVE), any()))
                .thenReturn(List.of(companyUser));

        MyPlanResponse planResponse = service.getMyPlan(USER_ID);
        SeatsResponse seatsResponse = service.getSeats(USER_ID);

        assertThat(planResponse.usage().seatCount()).isEqualTo(seatsResponse.used());
        assertThat(planResponse.usage().seatCount()).isEqualTo(1);
        verify(quotaService, org.mockito.Mockito.times(2)).measureSeats(COMPANY_ID);
    }

    // ── requestUpgrade ──

    @Test
    void 업그레이드문의_개인구독_소유자본인_성공() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));

        UpgradeInquiryResponse response = service.requestUpgrade(USER_ID);

        assertThat(response.status()).isEqualTo("UPGRADE_REQUESTED");
        assertThat(userPlan.getStatus()).isEqualTo(UserPlanStatus.UPGRADE_REQUESTED);
    }

    @Test
    void 업그레이드문의_이미요청상태면_멱등_그대로200() {
        UserPlan userPlan = withId(UserPlan.forUser(USER_ID, PLAN_ID), 500L);
        userPlan.requestUpgrade();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.UPGRADE_REQUESTED))
                .thenReturn(Optional.of(userPlan));

        UpgradeInquiryResponse response = service.requestUpgrade(USER_ID);

        assertThat(response.status()).isEqualTo("UPGRADE_REQUESTED");
    }

    @Test
    void 업그레이드문의_회사구독_소유자아니면_PLAN_FORBIDDEN() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, 999L); // 다른 사람이 오너

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.requestUpgrade(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_FORBIDDEN);
        assertThat(userPlan.getStatus()).isEqualTo(UserPlanStatus.ACTIVE);
    }

    @Test
    void 업그레이드문의_회사구독_소유자본인_성공() {
        UserPlan userPlan = withId(UserPlan.forCompany(COMPANY_ID, PLAN_ID), 501L);
        Company company = company(COMPANY_ID, USER_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(COMPANY_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan));
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        UpgradeInquiryResponse response = service.requestUpgrade(USER_ID);

        assertThat(response.status()).isEqualTo("UPGRADE_REQUESTED");
    }

    // 모의 결제(checkout, #711) 테스트는 제거됐다 — 그 메서드가 실결제(#988)로 대체되면서
    // MembershipService 에서 사라졌다. 이관된 전이·가드 검증은 PlanTransitionServiceTest·
    // PaymentWriterTest 가 이어받는다.

    // ── fixtures ──

    private static User user(Long id, Long companyId) {
        User u = User.builder()
                .email("user" + id + "@haja.com")
                .name("사용자" + id)
                .role(Role.USER)
                .passwordHash("$2a$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build();
        setId(u, id);
        return u;
    }

    private static Company company(Long id, Long ownerUserId) {
        return company(id, ownerUserId, "{}");
    }

    private static Company company(Long id, Long ownerUserId, String ocrRaw) {
        Company c = Company.createPendingReview(ownerUserId, "(주)하자체크", "1234567890",
                "김민수", "서울시 강남구", null, "http://files/brn.png", ocrRaw);
        setId(c, id);
        return c;
    }

    private static UserPlan withId(UserPlan userPlan, Long id) {
        setId(userPlan, id);
        return userPlan;
    }

    /** 테스트 전용 — IDENTITY 생성 id 를 리플렉션으로 세팅(엔티티에 setter 를 두지 않기 위함). */
    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
