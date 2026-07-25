package com.hajacheck.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * 월 분석 보상이 <b>차감했던 좌표</b>(구독 + 기간)를 그대로 되돌리는지 고정한다(#843 머신 검수 P2/P3-1).
 *
 * <p>{@code InspectionAnalysisWorker}는 {@code @Async}로 수 분(PRD: 100장 10분)을 돌기 때문에 차감과 보상
 * 사이에 <b>월이 넘어가거나</b> <b>요금제가 바뀔</b> 수 있다. 예전 구현은 보상 시점에 {@code currentPeriod()}
 * 와 live 플랜을 다시 계산해서, 말일 차감 → 익월 보상이면 소비하지도 않은 다음 달 한도를 깎거나(또는 0행
 * 갱신으로 보상이 소실되고) 플랜 전환 후에는 새 구독 행을 깎았다.
 *
 * <p>시계는 {@code SchedulingConfig}가 제공하는 {@link Clock} 빈을 {@link MutableClock}으로 덮어써
 * 결정적으로 재현한다({@code @TestConfiguration}이라 이 클래스 전용 컨텍스트에서만 유효 — 다른 테스트의
 * 시계에는 영향이 없다). 클래스 레벨 {@code @Transactional}은 붙이지 않는다: {@code refundAnalysisQuota}가
 * {@code REQUIRES_NEW}라 미커밋 차감을 볼 수 없어, 실제 커밋 경로로 검증해야 의미가 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnalysisQuotaChargeCoordinateTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 말일 23:59 KST — 여기서 차감하고 몇 분 뒤 익월에 보상하는 시나리오를 만든다. */
    private static final Instant MONTH_END = Instant.parse("2026-01-31T14:59:00Z");
    private static final Instant NEXT_MONTH = Instant.parse("2026-01-31T15:05:00Z");
    private static final LocalDate PERIOD_M = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_M_PLUS_1 = LocalDate.of(2026, 2, 1);

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(MONTH_END);
        }
    }

    /** 테스트가 "지금"을 옮길 수 있는 KST 고정 시계. */
    static class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void moveTo(Instant next) {
            this.now = next;
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private MutableClock testClock;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;

    private Long ownerId;
    private Long companyId;
    private Long ownerMembershipId;
    private final List<Long> userPlanIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testClock.moveTo(MONTH_END);

        User owner = userRepository.save(User.builder()
                .email("charge-owner-" + System.nanoTime() + "@haja.com")
                .name("좌표테스트대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());
        String brn = "cbrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)좌표테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);
        CompanyMembership ownerMembership = companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), owner.getId()));

        this.ownerId = owner.getId();
        this.companyId = company.getId();
        this.ownerMembershipId = ownerMembership.getId();
    }

    @AfterEach
    void tearDown() {
        testClock.moveTo(MONTH_END);
        for (Long userPlanId : userPlanIds) {
            usageCounterRepository.findAll().stream()
                    .filter(usage -> usage.getUserPlanId().equals(userPlanId))
                    .forEach(usageCounterRepository::delete);
            userPlanRepository.deleteById(userPlanId);
        }
        userPlanIds.clear();
        companyMembershipRepository.deleteById(ownerMembershipId);
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.assignToCompany(null);
        userRepository.save(owner);
        companyRepository.deleteById(companyId);
        userRepository.deleteById(ownerId);
    }

    @Test
    void 말일에_차감하고_익월에_보상해도_차감했던_그달_행만_되돌아간다() {
        Long userPlanId = givenCompanyPlan(PlanName.STANDARD);

        // M월(1월) 말일에 10장 차감.
        AnalysisQuotaCharge charge = quotaService.consumeAnalysisQuota(ownerId, companyId, 10);
        assertThat(charge.period()).isEqualTo(PERIOD_M);
        assertThat(charge.userPlanId()).isEqualTo(userPlanId);
        assertThat(usage(userPlanId, PERIOD_M).getAnalyzedImageCount()).isEqualTo(10);

        // 워커가 도는 동안 자정을 넘겨 M+1월(2월)이 됐고, 그 사이 다른 요청이 M+1 행을 만들어 3장을 썼다.
        testClock.moveTo(NEXT_MONTH);
        quotaService.consumeAnalysisQuota(ownerId, companyId, 3);
        assertThat(usage(userPlanId, PERIOD_M_PLUS_1).getAnalyzedImageCount()).isEqualTo(3);

        // 이제 전량 실패로 보상한다 — 기간을 재계산하던 예전 구현은 여기서 M+1(2월) 행을 깎았다.
        quotaService.refundAnalysisQuota(charge);

        UsageCounter monthM = usage(userPlanId, PERIOD_M);
        assertThat(monthM.getAnalyzedImageCount()).isZero();
        assertThat(monthM.getAnalysisRequestCount()).isZero();

        UsageCounter monthMPlus1 = usage(userPlanId, PERIOD_M_PLUS_1);
        assertThat(monthMPlus1.getAnalyzedImageCount()).as("소비하지 않은 다음 달 한도는 건드리지 않는다").isEqualTo(3);
        assertThat(monthMPlus1.getAnalysisRequestCount()).isEqualTo(1);
    }

    @Test
    void 차감후_요금제가_바뀌어도_차감했던_원래구독_행이_되돌아간다() {
        Long originalPlanId = givenCompanyPlan(PlanName.FREE);

        AnalysisQuotaCharge charge = quotaService.consumeAnalysisQuota(ownerId, companyId, 8);
        assertThat(charge.userPlanId()).isEqualTo(originalPlanId);
        assertThat(usage(originalPlanId, PERIOD_M).getAnalyzedImageCount()).isEqualTo(8);

        // 분석이 도는 사이 요금제 전환(모의 결제·관리자 플랜 변경) — 기존 ACTIVE 를 만료시키고 새 구독 발급.
        UserPlan original = userPlanRepository.findById(originalPlanId).orElseThrow();
        original.expire();
        userPlanRepository.saveAndFlush(original);
        Long switchedPlanId = givenCompanyPlan(PlanName.STANDARD);
        // 새 구독에서도 사용이 발생해 자기 집계 행을 갖는다.
        quotaService.consumeAnalysisQuota(ownerId, companyId, 5);
        assertThat(usage(switchedPlanId, PERIOD_M).getAnalyzedImageCount()).isEqualTo(5);

        // live 플랜을 재조회하던 예전 구현은 여기서 전환된 새 구독 행을 깎았다(머신 검수 P3-1).
        quotaService.refundAnalysisQuota(charge);

        assertThat(usage(originalPlanId, PERIOD_M).getAnalyzedImageCount()).isZero();
        assertThat(usage(switchedPlanId, PERIOD_M).getAnalyzedImageCount())
                .as("전환된 새 구독의 사용량은 건드리지 않는다").isEqualTo(5);
    }

    @Test
    void 차감이_없었으면_보상은_아무것도_하지않는다() {
        Long userPlanId = givenCompanyPlan(PlanName.STANDARD);
        quotaService.consumeAnalysisQuota(ownerId, companyId, 4);

        // 이미지가 0장이면 차감 자체가 없다 — 호출부는 그 반환값을 그대로 보상에 넘기기만 하면 된다.
        AnalysisQuotaCharge empty = quotaService.consumeAnalysisQuota(ownerId, companyId, 0);
        assertThat(empty.isCharged()).isFalse();

        quotaService.refundAnalysisQuota(empty);

        assertThat(usage(userPlanId, PERIOD_M).getAnalyzedImageCount()).isEqualTo(4);
        assertThat(usage(userPlanId, PERIOD_M).getAnalysisRequestCount()).isEqualTo(1);
    }

    private Long givenCompanyPlan(PlanName planName) {
        Plan plan = planRepository.findByName(planName).orElseThrow();
        Long id = userPlanRepository.saveAndFlush(UserPlan.forCompany(companyId, plan.getId())).getId();
        userPlanIds.add(id);
        return id;
    }

    private UsageCounter usage(Long userPlanId, LocalDate period) {
        return usageCounterRepository.findByUserPlanIdAndPeriod(userPlanId, period).orElseThrow();
    }
}
