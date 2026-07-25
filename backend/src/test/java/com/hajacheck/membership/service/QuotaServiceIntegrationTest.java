package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랜 한도 강제/사용량 집계 계약 검증(#843 / HAJA-441) — 단일 스레드 시나리오.
 * 경합(동시 요청) 검증은 {@link QuotaEnforcementConcurrencyTest} 가 담당한다.
 *
 * <p>{@code @Transactional} 테스트라 각 메서드 종료 시 롤백된다 — 여기서는 자가 프로비저닝
 * (REQUIRES_NEW, 롤백되지 않음) 경로를 건드리지 않도록 항상 구독을 미리 만들어 둔다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuotaServiceIntegrationTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private FacilityService facilityService;
    @Autowired
    private MembershipService membershipService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @PersistenceContext
    private EntityManager em;

    private Long ownerId;
    private Long companyId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("quota-int-owner-" + System.nanoTime() + "@haja.com")
                .name("한도계약대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());
        String brn = "ibrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)한도계약", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), owner.getId()));

        this.ownerId = owner.getId();
        this.companyId = company.getId();
    }

    @Test
    void 한도_초과_에러코드는_403이며_업그레이드를_안내한다() {
        // 409(재시도 가능한 충돌)/429(시간이 지나면 풀리는 rate-limit)가 아니라 403(엔타이틀먼트)이어야 한다.
        assertThat(ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.PLAN_ANALYSIS_QUOTA_EXCEEDED.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 시설물_등록시_사용량이_적립되고_마이페이지_사용량에_반영된다() {
        givenCompanyPlan(PlanName.STANDARD);

        facilityService.create(ownerId, companyId, facilityRequest("사용량반영시설"));

        MyPlanResponse myPlan = membershipService.getMyPlan(ownerId);
        assertThat(myPlan.usage().facilityCount()).isEqualTo(1);
        assertThat(myPlan.usage().period()).isEqualTo(currentPeriod());
        // 좌석은 집계 행 생성 시 실측(대표 1명)으로 시드된다 — 화면이 0으로 보이던 문제(#843)의 직접 원인.
        assertThat(myPlan.usage().seatCount()).isEqualTo(1);
    }

    @Test
    void 시설물_삭제하면_보유량_카운터가_다시_내려간다() {
        givenCompanyPlan(PlanName.STANDARD);
        FacilityResponse first = facilityService.create(ownerId, companyId, facilityRequest("삭제대상"));
        facilityService.create(ownerId, companyId, facilityRequest("유지대상"));
        assertThat(currentUsage().getFacilityCount()).isEqualTo(2);

        facilityService.delete(ownerId, companyId, first.id());

        // 감소가 없으면 카운터가 영구 드리프트해 한도를 잘못 막는다.
        assertThat(currentUsage().getFacilityCount()).isEqualTo(1);
        assertThat(facilityRepository.countByCompanyId(companyId)).isEqualTo(1);
    }

    @Test
    void 한도_초과상태의_기존_회사도_조회_수정_삭제는_계속된다_신규등록만_막힌다() {
        UserPlan userPlan = givenCompanyPlan(PlanName.FREE);
        int limit = planRepository.findByName(PlanName.FREE).orElseThrow().getMaxFacilities();
        // 이미 한도를 넘겨 보유 중인 상태를 직접 만든다(FREE 회사가 시설물 11건인 실제 데이터 재현).
        for (int i = 0; i < limit + 3; i++) {
            facilityRepository.save(Facility.builder()
                    .companyId(companyId).name("기존시설" + i).type("BUILDING").build());
        }
        facilityRepository.flush();
        Facility existing = facilityRepository
                .findByCompanyIdOrderByIdAsc(companyId, org.springframework.data.domain.PageRequest.of(0, 10))
                .get(0);

        // 기존 자산에 대한 조회·수정·삭제는 한도 검사를 타지 않으므로 그대로 동작해야 한다.
        assertThatCode(() -> facilityService.get(ownerId, companyId, existing.getId())).doesNotThrowAnyException();
        assertThatCode(() -> facilityService.update(ownerId, companyId, existing.getId(),
                new FacilityUpdateRequest("이름변경", "BUILDING", null, null, null,
                        null, null, null, null, null, null, null))).doesNotThrowAnyException();
        assertThatCode(() -> facilityService.delete(ownerId, companyId, existing.getId()))
                .doesNotThrowAnyException();
        assertThat(userPlan.getId()).isNotNull();

        // 막히는 것은 신규 등록뿐이다.
        assertThatThrownBy(() -> facilityService.create(ownerId, companyId, facilityRequest("신규는차단")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED));
    }

    @Test
    void 월분석_한도를_넘기는_요청은_거부되고_사용량은_그대로다() {
        givenCompanyPlan(PlanName.FREE);
        int limit = planRepository.findByName(PlanName.FREE).orElseThrow().getMaxMonthlyAnalyses();

        quotaService.consumeAnalysisQuota(ownerId, companyId, limit - 1);
        assertThat(currentUsage().getAnalyzedImageCount()).isEqualTo(limit - 1);

        // 이번 요청(이미지 2장)을 수행하면 한도를 넘으므로 부분 차감 없이 통째로 거부한다.
        assertThatThrownBy(() -> quotaService.consumeAnalysisQuota(ownerId, companyId, 2))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_ANALYSIS_QUOTA_EXCEEDED));
        assertThat(currentUsage().getAnalyzedImageCount()).isEqualTo(limit - 1);

        // 딱 맞게 채우는 요청은 통과해야 한다(경계값).
        quotaService.consumeAnalysisQuota(ownerId, companyId, 1);
        assertThat(currentUsage().getAnalyzedImageCount()).isEqualTo(limit);
    }

    @Test
    void 분석_실패시_보상차감이_요청수와_이미지수를_되돌린다() {
        givenCompanyPlan(PlanName.STANDARD);

        quotaService.consumeAnalysisQuota(ownerId, companyId, 7);
        assertThat(currentUsage().getAnalyzedImageCount()).isEqualTo(7);
        assertThat(currentUsage().getAnalysisRequestCount()).isEqualTo(1);

        quotaService.refundAnalysisQuota(ownerId, companyId, 7);

        assertThat(currentUsage().getAnalyzedImageCount()).isZero();
        assertThat(currentUsage().getAnalysisRequestCount()).isZero();
    }

    @Test
    void 보상차감은_음수로_내려가지_않는다() {
        givenCompanyPlan(PlanName.STANDARD);
        quotaService.consumeAnalysisQuota(ownerId, companyId, 1);

        // 차감 이력보다 큰 보상(월 경계에서 집계 행이 새로 만들어진 경우 등)도 CHECK 제약을 깨지 않는다.
        quotaService.refundAnalysisQuota(ownerId, companyId, 100);

        assertThat(currentUsage().getAnalyzedImageCount()).isZero();
        assertThat(currentUsage().getAnalysisRequestCount()).isZero();
    }

    @Test
    void 무제한_요금제는_한도없이_통과하고_사용량만_적립한다() {
        Plan enterprise = planRepository.findByName(PlanName.ENTERPRISE).orElseThrow();
        assertThat(enterprise.getMaxFacilities()).as("ENTERPRISE 는 무제한(null) 시드여야 한다").isNull();
        assertThat(enterprise.getMaxMonthlyAnalyses()).isNull();
        userPlanRepository.saveAndFlush(UserPlan.forCompany(companyId, enterprise.getId()));

        for (int i = 0; i < 5; i++) {
            facilityService.create(ownerId, companyId, facilityRequest("무제한시설" + i));
        }
        quotaService.consumeAnalysisQuota(ownerId, companyId, 100_000);

        UsageCounter usage = currentUsage();
        assertThat(usage.getFacilityCount()).isEqualTo(5);
        assertThat(usage.getAnalyzedImageCount()).isEqualTo(100_000);
    }

    private UserPlan givenCompanyPlan(PlanName planName) {
        Plan plan = planRepository.findByName(planName).orElseThrow();
        return userPlanRepository.saveAndFlush(UserPlan.forCompany(companyId, plan.getId()));
    }

    private FacilityCreateRequest facilityRequest(String name) {
        return new FacilityCreateRequest(name, "BUILDING", null, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * 카운터 증감은 네이티브 UPDATE(1차 캐시 우회)라, 같은 트랜잭션에서 엔티티를 다시 읽으려면 영속성
     * 컨텍스트를 비워 DB 값을 새로 가져와야 한다(안 그러면 직전 스냅샷이 그대로 나온다).
     */
    private UsageCounter currentUsage() {
        em.flush();
        em.clear();
        return usageCounterRepository.findByUserPlanIdAndPeriod(currentUserPlanId(), currentPeriod()).orElseThrow();
    }

    private Long currentUserPlanId() {
        return userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(
                        companyId, com.hajacheck.membership.entity.UserPlanStatus.ACTIVE)
                .orElseThrow()
                .getId();
    }

    private static LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }
}
