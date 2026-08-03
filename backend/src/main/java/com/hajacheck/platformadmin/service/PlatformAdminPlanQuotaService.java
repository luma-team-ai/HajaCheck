package com.hajacheck.platformadmin.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaStats;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaStatus;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaUser;
import com.hajacheck.platformadmin.repository.PlatformAdminPlanQuotaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 플랜·쿼터 관리(#624, frontend PlatformAdminPlanQuotaPage.tsx 대응).
 * AdminPlanService#getPlanQuota(#507, 회사 스코프)와 비슷한 화면이지만, companyId 스코프를 걷어내고
 * 전사 사용자를 사용자별로 나열한다 — 회사마다 구독 플랜(=한도)이 다를 수 있어(#507 은 단일 회사라 한도가
 * 모든 행에서 동일) 행마다 자기 소속 회사의 플랜을 개별 조회한다. 이번 달 쿼터 사용량은 media 집계
 * 근사치가 아니라 usage_counters(쿼터 차감의 진짜 원천, 회사=UserPlan 단위 풀링)를 조회한다(#1407 후속).
 *
 * <p><b>"남은 기간" 산출 근거</b>: {@code user_plans.current_period_end}(#1104 / HAJA-525)를 만료 예정일로
 * 그대로 쓴다. 과거에는 이 컬럼이 없어 {@code startedAt + 1개월}을 조회 시점마다 근사 계산했는데, 그 계산에
 * FREE 제외 필터가 없어 가입한 지 한 달 넘은 FREE 회사가 전부 "만료됨"으로 표시되던 버그가 있었다(#1104가
 * 함께 고침). {@code current_period_end == null}(FREE, 무기한)은 이제 컬럼 자체가 그 의미를 담고 있으므로
 * EXPIRED 로 판정하지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlatformAdminPlanQuotaService {

    // 만료 임박(주의) 임계 — frontend 목데이터 주석의 "30일 이하"는 연 단위 구독(remainingDays 최대
    // 300+)을 가정한 값이라 이 화면(월 단위, 최대 remainingDays ≈ 28~31)에 그대로 쓰면 갱신 직후를 뺀
    // 사실상 전 기간이 WARNING이 된다. 월 주기에 맞춰 "갱신 임박 1주" 기준으로 축소한다.
    private static final int WARNING_THRESHOLD_DAYS = 7;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PlatformAdminPlanQuotaRepository platformAdminPlanQuotaRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final CompanyRepository companyRepository;
    private final UsageCounterRepository usageCounterRepository;

    public PlatformAdminPlanQuotaResponse getPlanQuota(int page, int size, String keyword, PlanName plan) {
        Map<Long, UserPlan> planByCompany = activeCompanyPlansByCompanyId();
        Map<Long, Plan> planById = plansById(planByCompany.values());

        boolean hasPlanFilter = plan != null;
        List<Long> planCompanyIds = hasPlanFilter ? companyIdsByPlan(planByCompany, planById, plan) : Collections.emptyList();

        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        Page<User> users = hasPlanFilter && planCompanyIds.isEmpty()
                ? Page.empty(pageable)
                : platformAdminPlanQuotaRepository.search(
                        buildLikePattern(keyword), Role.PLATFORM_ADMIN, hasPlanFilter, planCompanyIds, pageable);

        // 표(content)·KPI 카드가 같은 회사(UserPlan) 집합의 이번 달 usage_counters 를 공유한다 — 쿼리 한 번으로
        // 재사용(#1407 후속, 회사 단위 풀링 쿼터라 사용자별이 아니라 UserPlan 별로 조회).
        LocalDate period = currentPeriod();
        Map<Long, UsageCounter> usageByUserPlanId = usageByUserPlanId(planByCompany.values(), period);
        Map<Long, String> companyNameById = companyNamesById(users.getContent());

        List<PlatformAdminPlanQuotaUser> content = users.getContent().stream()
                .map(u -> buildRow(u, planByCompany, planById, usageByUserPlanId, companyNameById))
                .toList();

        PlatformAdminPlanQuotaStats stats = buildStats(planByCompany, planById, usageByUserPlanId);

        return new PlatformAdminPlanQuotaResponse(content, page, size, users.getTotalElements(), stats);
    }

    // plan 필터 — 회사 단위 구독이라 "이 플랜을 구독 중인 회사" 집합으로 변환해 사용자 검색에 넘긴다.
    private List<Long> companyIdsByPlan(Map<Long, UserPlan> planByCompany, Map<Long, Plan> planById, PlanName plan) {
        return planByCompany.entrySet().stream()
                .filter(entry -> {
                    Plan candidate = planById.get(entry.getValue().getPlanId());
                    return candidate != null && candidate.getName() == plan;
                })
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<Long, String> companyNamesById(List<User> users) {
        List<Long> companyIds = users.stream()
                .map(User::getCompanyId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (companyIds.isEmpty()) {
            return Map.of();
        }
        return companyRepository.findAllById(companyIds).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));
    }

    // KPI 카드 — 검색어와 무관한 전체 기준(#507 stats 계약과 동일). 유효(비만료) 플랜을 가진 회사
    // 소속 사용자만 "활성 사용자"로 센다. totalQuotaUsagePercent 는 media 집계 근사치가 아니라
    // usage_counters(쿼터 차감의 진짜 원천, QuotaService#consumeAnalysisQuota 참고)를 회사(UserPlan)
    // 단위로 조회한 analyzedImageCount 를 회사별 한도로 나눈 사용률의 평균이다(#1407 — AdminPlanService
    // #computeUsagePercent 와 동일 원천, 회사마다 한도가 다를 수 있어 #507처럼 단일 한도로 나눌 수 없다는
    // 점만 다르다). usageByUserPlanId 는 getPlanQuota() 가 표(content) 행과 공유하려고 미리 조회해 넘긴 것.
    private PlatformAdminPlanQuotaStats buildStats(
            Map<Long, UserPlan> planByCompany, Map<Long, Plan> planById, Map<Long, UsageCounter> usageByUserPlanId) {
        List<UserPlan> validPlans = planByCompany.values().stream()
                .filter(userPlan -> resolveRemaining(userPlan.getCurrentPeriodEnd()).status()
                        != PlatformAdminPlanQuotaStatus.EXPIRED)
                .toList();

        if (validPlans.isEmpty()) {
            return new PlatformAdminPlanQuotaStats(0, 0, 0);
        }

        List<Long> validCompanyIds = validPlans.stream().map(UserPlan::getCompanyId).toList();
        long activeUsers =
                platformAdminPlanQuotaRepository.countByCompanyIdInAndRoleNot(validCompanyIds, Role.PLATFORM_ADMIN);

        List<Double> usagePercents = new ArrayList<>();
        long unlimitedPlanUsageTotal = 0;
        for (UserPlan userPlan : validPlans) {
            Plan plan = planById.get(userPlan.getPlanId());
            Integer limit = plan.getMaxMonthlyAnalyses();
            UsageCounter usage = usageByUserPlanId.get(userPlan.getId());
            int used = usage == null ? 0 : usage.getAnalyzedImageCount();
            if (limit == null || limit <= 0) {
                // 무제한 플랜 — "사용량 ÷ 한도"가 정의되지 않아 평균에서 제외하되(#1407), 사용량 자체는
                // unlimitedPlanUsageTotal 로 별도 합산해 화면에서 사라지지 않게 한다.
                unlimitedPlanUsageTotal += used;
                continue;
            }
            usagePercents.add(Math.min(100.0, used * 100.0 / limit));
        }

        int averagePercent = usagePercents.isEmpty()
                ? 0
                : (int) Math.round(usagePercents.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        return new PlatformAdminPlanQuotaStats(activeUsers, averagePercent, unlimitedPlanUsageTotal);
    }

    private PlatformAdminPlanQuotaUser buildRow(
            User user, Map<Long, UserPlan> planByCompany, Map<Long, Plan> planById,
            Map<Long, UsageCounter> usageByUserPlanId, Map<Long, String> companyNameById) {
        UserPlan userPlan = user.getCompanyId() == null ? null : planByCompany.get(user.getCompanyId());
        String companyName = user.getCompanyId() == null ? null : companyNameById.get(user.getCompanyId());

        if (userPlan == null) {
            return new PlatformAdminPlanQuotaUser(
                    user.getId(), user.getName(), user.getEmail(), user.getCompanyId(), companyName,
                    null, 0, null, null, PlatformAdminPlanQuotaStatus.EXPIRED);
        }

        // 쿼터는 회사 단위 풀링이라(DTO javadoc 참고) 같은 회사 소속 사용자는 전부 같은 usage_counters
        // 값을 본다 — 개인별 media 근사치가 아니다(#1407 후속).
        UsageCounter usage = usageByUserPlanId.get(userPlan.getId());
        int quotaUsed = usage == null ? 0 : usage.getAnalyzedImageCount();
        Plan plan = planById.get(userPlan.getPlanId());
        RemainingPlan remaining = resolveRemaining(userPlan.getCurrentPeriodEnd());
        return new PlatformAdminPlanQuotaUser(
                user.getId(), user.getName(), user.getEmail(), user.getCompanyId(), companyName,
                plan.getName(), quotaUsed, plan.getMaxMonthlyAnalyses(), remaining.days(), remaining.status());
    }

    private Map<Long, UserPlan> activeCompanyPlansByCompanyId() {
        return userPlanRepository.findByCompanyIdIsNotNullAndStatus(UserPlanStatus.ACTIVE).stream()
                .collect(Collectors.toMap(UserPlan::getCompanyId, Function.identity()));
    }

    private Map<Long, Plan> plansById(java.util.Collection<UserPlan> userPlans) {
        List<Long> planIds = userPlans.stream().map(UserPlan::getPlanId).distinct().toList();
        return planRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));
    }

    // current_period_end 로 남은 일수를 계산한다(클래스 상단 javadoc 참고). NULL(FREE, 무기한)은
    // 만료가 없으므로 EXPIRED 로 판정하지 않는다(#1104 — 이 조건 부재가 원래 버그였다).
    private RemainingPlan resolveRemaining(Instant currentPeriodEnd) {
        if (currentPeriodEnd == null) {
            return new RemainingPlan(null, PlatformAdminPlanQuotaStatus.ACTIVE);
        }
        long remainingDays = ChronoUnit.DAYS.between(Instant.now(), currentPeriodEnd);
        if (remainingDays <= 0) {
            return new RemainingPlan(null, PlatformAdminPlanQuotaStatus.EXPIRED);
        }
        PlatformAdminPlanQuotaStatus status = remainingDays <= WARNING_THRESHOLD_DAYS
                ? PlatformAdminPlanQuotaStatus.WARNING
                : PlatformAdminPlanQuotaStatus.ACTIVE;
        return new RemainingPlan(remainingDays, status);
    }

    private record RemainingPlan(Long days, PlatformAdminPlanQuotaStatus status) {
    }

    // 회사(UserPlan) 단위 이번 달 usage_counters 배치 조회 — 표 행·KPI 카드가 공유한다(#1407 후속).
    private Map<Long, UsageCounter> usageByUserPlanId(java.util.Collection<UserPlan> userPlans, LocalDate period) {
        if (userPlans.isEmpty()) {
            return Map.of();
        }
        List<Long> userPlanIds = userPlans.stream().map(UserPlan::getId).toList();
        return usageCounterRepository.findByUserPlanIdInAndPeriod(userPlanIds, period).stream()
                .collect(Collectors.toMap(UsageCounter::getUserPlanId, Function.identity()));
    }

    private LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }

    private String buildLikePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
