package com.hajacheck.platformadmin.service;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.platformadmin.dto.PlatformAdminAnalysisRequestTrendPoint;
import com.hajacheck.platformadmin.dto.PlatformAdminMonthlySummaryRow;
import com.hajacheck.platformadmin.dto.PlatformAdminMonthlyTrend;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanDistributionItem;
import com.hajacheck.platformadmin.dto.PlatformAdminServiceStatsKpi;
import com.hajacheck.platformadmin.dto.PlatformAdminServiceStatsResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminSubscriberTrendPoint;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 서비스 통계(#633, frontend PlatformAdminStatsPage.tsx 대응, Figma node-id 177-3515).
 * companyId 스코프 없이 플랫폼 전체를 집계한다(PLATFORM_ADMIN 인가는 SecurityConfig URL 매처가 강제).
 *
 * <p><b>구독 이력 재구성</b>: user_plans 는 스냅샷 테이블이 아니지만, 플랜 변경 시 기존 행을
 * {@link UserPlan#expire()}로 EXPIRED 처리하고 신규 ACTIVE 행을 발급하는 방식(UserPlan 클래스 상단 javadoc)이라
 * 회사별 전체 이력만으로 "특정 시점에 구독 중이었는가"를 순수 조회로 재구성할 수 있다 — 각 회사의 이력을
 * startedAt 오름차순으로 정렬해 두면, 임의 시점 T 에 대해 startedAt&lt;=T &amp;&amp;
 * (endedAt==null || endedAt&gt;T) 인 행이 하나라도 있으면 그 시점에 구독 중이었다는 뜻이다.
 *
 * <p><b>가입자 추이/신규 가입자</b>: 회사 이력의 가장 이른 startedAt 이 속한 달을 "그 회사가 처음 가입한 달"로
 * 본다(플랜 변경은 신규 가입이 아니라 최초 구독 시점만 신규로 센다).
 *
 * <p><b>업그레이드 전환</b>: 같은 회사 이력에서 연속한 두 행(prev, curr)이 curr.plan 의 등급이 prev.plan 보다
 * 높으면(FREE&lt;STANDARD&lt;ENTERPRISE, {@link PlanName} 선언 순서) curr.startedAt 이 속한 달에 전환 1건으로
 * 센다 — Free→Standard 뿐 아니라 Free→Enterprise, Standard→Enterprise 도 모두 포함한다.
 *
 * <p><b>분석 요청/상담 건수</b>: usage_counters(개인+회사 구독 전체)의 월별 합계 — KPI 의 누적치는 트렌드
 * 윈도우(최근 {@value #TREND_MONTHS}개월) monthlySummary 합계와 항상 일치한다.
 *
 * <p><b>상담 유형 분포는 이번 범위 밖</b>(사용자 지시, 2026-07-23) — 항상 빈 목록을 반환한다
 * (PlatformAdminCounselTypeDistributionItem 참고).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlatformAdminServiceStatsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int TREND_MONTHS = 6;

    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final UsageCounterRepository usageCounterRepository;

    public PlatformAdminServiceStatsResponse getStats() {
        YearMonth currentMonth = YearMonth.now(KST);
        List<YearMonth> months = monthsWindow(currentMonth);
        YearMonth previousMonth = currentMonth.minusMonths(1);

        Map<Long, List<UserPlan>> historyByCompany = companyHistoryById();
        Map<Long, Plan> planById = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));

        Map<YearMonth, Long> subscriberCountByMonth = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            subscriberCountByMonth.put(ym, countActiveAsOf(historyByCompany, pointInTimeOf(ym, currentMonth)));
        }
        long totalSubscribersNow = subscriberCountByMonth.get(currentMonth);
        long totalSubscribersLastMonth =
                countActiveAsOf(historyByCompany, pointInTimeOf(previousMonth, currentMonth));

        Map<YearMonth, Long> newSubscribersByMonth = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            newSubscribersByMonth.put(ym, countNewSubscribers(historyByCompany, ym));
        }
        long newSubscribersThisMonth = newSubscribersByMonth.get(currentMonth);
        long newSubscribersLastMonth = countNewSubscribers(historyByCompany, previousMonth);

        Map<YearMonth, Long> analysisRequestsByMonth =
                usageSumByMonth(months, UsageCounter::getAnalysisRequestCount);
        Map<YearMonth, Long> counselCountByMonth = usageSumByMonth(months, UsageCounter::getCounselTicketCount);
        Map<YearMonth, Long> upgradeConversionsByMonth = conversionsByMonth(historyByCompany, planById);

        PlatformAdminServiceStatsKpi kpi = new PlatformAdminServiceStatsKpi(
                totalSubscribersNow,
                totalSubscribersNow - totalSubscribersLastMonth,
                newSubscribersThisMonth,
                changePercent(newSubscribersThisMonth, newSubscribersLastMonth),
                analysisRequestsByMonth.values().stream().mapToLong(Long::longValue).sum(),
                counselCountByMonth.values().stream().mapToLong(Long::longValue).sum());

        List<PlatformAdminSubscriberTrendPoint> subscriberTrend = months.stream()
                .map(ym -> new PlatformAdminSubscriberTrendPoint(monthLabel(ym), subscriberCountByMonth.get(ym)))
                .toList();

        List<PlatformAdminAnalysisRequestTrendPoint> analysisRequestTrend = months.stream()
                .map(ym -> new PlatformAdminAnalysisRequestTrendPoint(monthLabel(ym), analysisRequestsByMonth.get(ym)))
                .toList();

        List<PlatformAdminPlanDistributionItem> planDistribution = planDistribution(planById);

        List<PlatformAdminMonthlySummaryRow> monthlySummary = new ArrayList<>();
        for (int i = months.size() - 1; i >= 0; i--) {
            YearMonth ym = months.get(i);
            long thisNew = newSubscribersByMonth.get(ym);
            long prevNew = i == 0 ? newSubscribersLastMonth : newSubscribersByMonth.get(months.get(i - 1));
            monthlySummary.add(new PlatformAdminMonthlySummaryRow(
                    monthLabel(ym), thisNew, analysisRequestsByMonth.get(ym), counselCountByMonth.get(ym),
                    upgradeConversionsByMonth.getOrDefault(ym, 0L), trendOf(thisNew, prevNew)));
        }

        return new PlatformAdminServiceStatsResponse(
                kpi, subscriberTrend, analysisRequestTrend, planDistribution, List.of(), monthlySummary);
    }

    // 오래된 달 → 최근 달 순(size=TREND_MONTHS), 현재 달 포함.
    private List<YearMonth> monthsWindow(YearMonth currentMonth) {
        List<YearMonth> months = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            months.add(currentMonth.minusMonths(i));
        }
        return months;
    }

    // 현재 달은 "지금 이 순간"을, 그 외 달은 "그 달 말일 24시"를 스냅샷 시점으로 삼는다(과거 달은 이미
    // 끝났으니 월말 기준, 이번 달은 아직 끝나지 않았으니 조회 시점 기준 — KPI totalSubscribers 와
    // subscriberTrend 마지막 값이 항상 같아야 하므로 이 구분이 필요).
    private Instant pointInTimeOf(YearMonth ym, YearMonth currentMonth) {
        if (ym.equals(currentMonth)) {
            return Instant.now();
        }
        return ym.atEndOfMonth().plusDays(1).atStartOfDay(KST).toInstant();
    }

    private Map<Long, List<UserPlan>> companyHistoryById() {
        Map<Long, List<UserPlan>> historyByCompany = userPlanRepository.findByCompanyIdIsNotNull().stream()
                .collect(Collectors.groupingBy(UserPlan::getCompanyId));
        historyByCompany.values().forEach(history -> history.sort(Comparator.comparing(UserPlan::getStartedAt)));
        return historyByCompany;
    }

    private long countActiveAsOf(Map<Long, List<UserPlan>> historyByCompany, Instant pointInTime) {
        return historyByCompany.values().stream()
                .filter(history -> isActiveAt(history, pointInTime))
                .count();
    }

    private boolean isActiveAt(List<UserPlan> history, Instant pointInTime) {
        return history.stream().anyMatch(userPlan ->
                !userPlan.getStartedAt().isAfter(pointInTime)
                        && (userPlan.getEndedAt() == null || userPlan.getEndedAt().isAfter(pointInTime)));
    }

    private long countNewSubscribers(Map<Long, List<UserPlan>> historyByCompany, YearMonth month) {
        return historyByCompany.values().stream()
                .filter(history -> !history.isEmpty())
                .filter(history -> YearMonth.from(history.get(0).getStartedAt().atZone(KST)).equals(month))
                .count();
    }

    private Map<YearMonth, Long> conversionsByMonth(
            Map<Long, List<UserPlan>> historyByCompany, Map<Long, Plan> planById) {
        Map<YearMonth, Long> result = new LinkedHashMap<>();
        for (List<UserPlan> history : historyByCompany.values()) {
            for (int i = 1; i < history.size(); i++) {
                PlanName prevPlan = planNameOf(history.get(i - 1), planById);
                PlanName currPlan = planNameOf(history.get(i), planById);
                if (prevPlan != null && currPlan != null && currPlan.ordinal() > prevPlan.ordinal()) {
                    YearMonth month = YearMonth.from(history.get(i).getStartedAt().atZone(KST));
                    result.merge(month, 1L, Long::sum);
                }
            }
        }
        return result;
    }

    private PlanName planNameOf(UserPlan userPlan, Map<Long, Plan> planById) {
        Plan plan = planById.get(userPlan.getPlanId());
        return plan == null ? null : plan.getName();
    }

    private Map<YearMonth, Long> usageSumByMonth(List<YearMonth> months, ToIntFunction<UsageCounter> field) {
        List<UsageCounter> counters = usageCounterRepository.findByPeriodBetween(
                months.get(0).atDay(1), months.get(months.size() - 1).atEndOfMonth());
        Map<YearMonth, Long> sumByMonth = counters.stream().collect(Collectors.groupingBy(
                counter -> YearMonth.from(counter.getPeriod()),
                LinkedHashMap::new,
                Collectors.summingLong(field::applyAsInt)));
        Map<YearMonth, Long> result = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            result.put(ym, sumByMonth.getOrDefault(ym, 0L));
        }
        return result;
    }

    // 현재 유효(비만료) 구독을 기준으로 한 플랜별 비중(#624 PlatformAdminPlanQuotaService 와 동일 소스).
    private List<PlatformAdminPlanDistributionItem> planDistribution(Map<Long, Plan> planById) {
        List<UserPlan> activePlans = userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE);
        if (activePlans.isEmpty()) {
            return List.of();
        }
        Map<PlanName, Long> countByPlan = activePlans.stream()
                .collect(Collectors.groupingBy(userPlan -> planNameOf(userPlan, planById), Collectors.counting()));
        long total = activePlans.size();
        return java.util.Arrays.stream(PlanName.values())
                .filter(countByPlan::containsKey)
                .map(planName -> new PlatformAdminPlanDistributionItem(
                        planName, Math.round(countByPlan.get(planName) * 100.0f / total)))
                .toList();
    }

    private int changePercent(long current, long previous) {
        if (previous == 0) {
            return current > 0 ? 100 : 0;
        }
        return Math.round((current - previous) * 100.0f / previous);
    }

    private PlatformAdminMonthlyTrend trendOf(long current, long previous) {
        if (current > previous) {
            return PlatformAdminMonthlyTrend.UP;
        }
        if (current < previous) {
            return PlatformAdminMonthlyTrend.DOWN;
        }
        return PlatformAdminMonthlyTrend.FLAT;
    }

    private String monthLabel(YearMonth ym) {
        return ym.getMonthValue() + "월";
    }
}
