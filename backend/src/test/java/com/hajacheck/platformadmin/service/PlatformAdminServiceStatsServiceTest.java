package com.hajacheck.platformadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.platformadmin.dto.PlatformAdminMonthlyTrend;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanDistributionItem;
import com.hajacheck.platformadmin.dto.PlatformAdminServiceStatsResponse;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 플랫폼 관리자 서비스 통계(#633) 단위 테스트 — DashboardServiceTest 와 동일 스타일(Mockito + 리플렉션으로
 * 엔티티의 생성 시각/식별자 등 팩토리 메서드로 노출되지 않는 필드를 직접 세팅).
 */
@ExtendWith(MockitoExtension.class)
class PlatformAdminServiceStatsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private UsageCounterRepository usageCounterRepository;

    @InjectMocks
    private PlatformAdminServiceStatsService service;

    @Test
    void 데이터가없으면_전부0이거나빈목록() {
        when(userPlanRepository.findByCompanyIdIsNotNull()).thenReturn(List.of());
        when(planRepository.findAll()).thenReturn(List.of());
        when(userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE)).thenReturn(List.of());
        when(usageCounterRepository.findByPeriodBetween(any(), any())).thenReturn(List.of());

        PlatformAdminServiceStatsResponse response = service.getStats();

        assertThat(response.kpi().totalSubscribers()).isZero();
        assertThat(response.kpi().totalSubscribersDelta()).isZero();
        assertThat(response.kpi().newSubscribersThisMonth()).isZero();
        assertThat(response.kpi().analysisRequests()).isZero();
        assertThat(response.kpi().counselCount()).isZero();
        assertThat(response.subscriberTrend()).hasSize(6).allMatch(point -> point.subscribers() == 0);
        assertThat(response.monthlySummary()).hasSize(6);
        assertThat(response.planDistribution()).isEmpty();
        assertThat(response.counselTypeDistribution()).isEmpty();
    }

    @Test
    void 이번달신규가입회사는_총가입자와신규가입자에반영된다() {
        Plan free = plan(1L, PlanName.FREE);
        UserPlan thisMonthSub = companyPlan(100L, 1L, UserPlanStatus.ACTIVE, Instant.now(), null);

        when(userPlanRepository.findByCompanyIdIsNotNull()).thenReturn(List.of(thisMonthSub));
        when(planRepository.findAll()).thenReturn(List.of(free));
        when(userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE))
                .thenReturn(List.of(thisMonthSub));
        when(usageCounterRepository.findByPeriodBetween(any(), any())).thenReturn(List.of());

        PlatformAdminServiceStatsResponse response = service.getStats();

        assertThat(response.kpi().totalSubscribers()).isEqualTo(1);
        assertThat(response.kpi().totalSubscribersDelta()).isEqualTo(1);
        assertThat(response.kpi().newSubscribersThisMonth()).isEqualTo(1);
        assertThat(response.subscriberTrend().get(5).subscribers()).isEqualTo(1);
        assertThat(response.subscriberTrend().get(4).subscribers()).isZero();
        assertThat(response.monthlySummary().get(0).newSubscribers()).isEqualTo(1);
        assertThat(response.monthlySummary().get(0).trend()).isEqualTo(PlatformAdminMonthlyTrend.UP);
        assertThat(response.planDistribution())
                .containsExactly(new PlatformAdminPlanDistributionItem(PlanName.FREE, 100));
    }

    @Test
    void 지난달가입후만료된회사는_이번달가입자수에서제외된다() {
        Plan free = plan(1L, PlanName.FREE);
        Instant twoMonthsAgo = YearMonth.now(KST).minusMonths(2).atDay(15).atStartOfDay(KST).toInstant();
        Instant lastMonthEnd = YearMonth.now(KST).minusMonths(1).atEndOfMonth().atStartOfDay(KST).toInstant();
        UserPlan expired = companyPlan(200L, 1L, UserPlanStatus.EXPIRED, twoMonthsAgo, lastMonthEnd);

        when(userPlanRepository.findByCompanyIdIsNotNull()).thenReturn(List.of(expired));
        when(planRepository.findAll()).thenReturn(List.of(free));
        when(userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE)).thenReturn(List.of());
        when(usageCounterRepository.findByPeriodBetween(any(), any())).thenReturn(List.of());

        PlatformAdminServiceStatsResponse response = service.getStats();

        assertThat(response.kpi().totalSubscribers()).isZero();
        assertThat(response.kpi().newSubscribersThisMonth()).isZero();
        // 가입은 2개월 전(트렌드 윈도우 안), 그 시점엔 활성이었어야 한다.
        assertThat(response.subscriberTrend().get(3).subscribers()).isEqualTo(1);
    }

    @Test
    void freeToStandard전환은_전환달의월별요약에반영된다() {
        Plan free = plan(1L, PlanName.FREE);
        Plan standard = plan(2L, PlanName.STANDARD);
        Instant expiredAt = Instant.now().minusSeconds(60);
        UserPlan expiredFree = companyPlan(300L, 1L, UserPlanStatus.EXPIRED, expiredAt.minusSeconds(3600), expiredAt);
        UserPlan newStandard = companyPlan(300L, 2L, UserPlanStatus.ACTIVE, expiredAt, null);

        when(userPlanRepository.findByCompanyIdIsNotNull()).thenReturn(List.of(expiredFree, newStandard));
        when(planRepository.findAll()).thenReturn(List.of(free, standard));
        when(userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE))
                .thenReturn(List.of(newStandard));
        when(usageCounterRepository.findByPeriodBetween(any(), any())).thenReturn(List.of());

        PlatformAdminServiceStatsResponse response = service.getStats();

        assertThat(response.monthlySummary().get(0).freeToStandardConversions()).isEqualTo(1);
    }

    @Test
    void 사용량합계는_트렌드윈도우합과KPI누적치가일치한다() {
        when(userPlanRepository.findByCompanyIdIsNotNull()).thenReturn(List.of());
        when(planRepository.findAll()).thenReturn(List.of());
        when(userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE)).thenReturn(List.of());

        UsageCounter thisMonth = usageCounter(YearMonth.now(KST).atDay(1), 120, 8);
        UsageCounter lastMonth = usageCounter(YearMonth.now(KST).minusMonths(1).atDay(1), 80, 5);
        when(usageCounterRepository.findByPeriodBetween(any(), any())).thenReturn(List.of(thisMonth, lastMonth));

        PlatformAdminServiceStatsResponse response = service.getStats();

        long analysisSum = response.monthlySummary().stream().mapToLong(r -> r.analysisCount()).sum();
        long counselSum = response.monthlySummary().stream().mapToLong(r -> r.counselCount()).sum();
        assertThat(response.kpi().analysisRequests()).isEqualTo(analysisSum).isEqualTo(200);
        assertThat(response.kpi().counselCount()).isEqualTo(counselSum).isEqualTo(13);
    }

    private Plan plan(Long id, PlanName name) {
        Plan plan = Plan.create(name, 10, 300, 5, false, true, true, new BigDecimal("29000.00"));
        setField(plan, "id", id);
        return plan;
    }

    private UserPlan companyPlan(Long companyId, Long planId, UserPlanStatus status, Instant startedAt, Instant endedAt) {
        UserPlan userPlan = UserPlan.forCompany(companyId, planId);
        setField(userPlan, "status", status);
        setField(userPlan, "startedAt", startedAt);
        setField(userPlan, "endedAt", endedAt);
        return userPlan;
    }

    private UsageCounter usageCounter(java.time.LocalDate period, int analysisRequestCount, int counselTicketCount) {
        return UsageCounter.create(1L, period, 0, 0, analysisRequestCount, 0, counselTicketCount, 0);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
