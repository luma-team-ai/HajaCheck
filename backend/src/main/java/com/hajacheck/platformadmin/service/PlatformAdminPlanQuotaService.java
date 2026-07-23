package com.hajacheck.platformadmin.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaStats;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaStatus;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaUser;
import com.hajacheck.platformadmin.repository.PlatformAdminPlanQuotaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * AdminPlanService#getPlanQuota(#507, 회사 스코프)와 동일한 도메인 규칙(이번 달 쿼터 사용량 = media 집계
 * 근사치)을 재사용하되, companyId 스코프를 걷어내고 전사 사용자를 사용자별로 나열한다 — 회사마다 구독
 * 플랜(=한도)이 다를 수 있어(#507 은 단일 회사라 한도가 모든 행에서 동일) 행마다 자기 소속 회사의 플랜을
 * 개별 조회한다.
 *
 * <p><b>"남은 기간" 산출 근거</b>: user_plans 에는 구독 만료 예정일 컬럼이 없다(ended_at 은 실제 종료 시각만
 * 기록, #507 설계 그대로). 이 화면은 구독을 "월 단위"로 간주해 {@code startedAt + 1개월}을 만료 예정일로
 * 근사 계산한다(계약 확정 — 사용자 지시, 2026-07-23). 이 계산은 순수 조회 시점 파생값이라 user_plans 에
 * 아무것도 쓰지 않는다(엔티티/스키마 변경 없음, 회원가입·플랜변경 등 다른 흐름에 영향 없음).
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
    private final MediaRepository mediaRepository;

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

        Map<Long, Long> usageByUser = mediaUsageByUser(users.getContent());
        Map<Long, String> companyNameById = companyNamesById(users.getContent());

        List<PlatformAdminPlanQuotaUser> content = users.getContent().stream()
                .map(u -> buildRow(u, planByCompany, planById, usageByUser, companyNameById))
                .toList();

        PlatformAdminPlanQuotaStats stats = buildStats(planByCompany, planById);

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
    // 소속 사용자만 "활성 사용자"로 센다. totalQuotaUsagePercent 는 유효 한도를 가진 사용자별 사용률의
    // 평균(회사마다 한도가 다를 수 있어 #507처럼 단일 한도로 나눌 수 없다).
    private PlatformAdminPlanQuotaStats buildStats(Map<Long, UserPlan> planByCompany, Map<Long, Plan> planById) {
        List<Long> validCompanyIds = planByCompany.entrySet().stream()
                .filter(entry -> resolveRemaining(entry.getValue().getStartedAt()).status()
                        != PlatformAdminPlanQuotaStatus.EXPIRED)
                .map(Map.Entry::getKey)
                .toList();

        if (validCompanyIds.isEmpty()) {
            return new PlatformAdminPlanQuotaStats(0, 0);
        }

        List<User> validUsers =
                platformAdminPlanQuotaRepository.findByCompanyIdInAndRoleNot(validCompanyIds, Role.PLATFORM_ADMIN);
        Map<Long, Long> usageByUser = mediaUsageByUser(validUsers);

        List<Double> usagePercents = validUsers.stream()
                .map(u -> {
                    Plan plan = planById.get(planByCompany.get(u.getCompanyId()).getPlanId());
                    Integer limit = plan.getMaxMonthlyAnalyses();
                    if (limit == null || limit <= 0) {
                        return null;
                    }
                    long used = usageByUser.getOrDefault(u.getId(), 0L);
                    return Math.min(100.0, used * 100.0 / limit);
                })
                .filter(percent -> percent != null)
                .toList();

        int averagePercent = usagePercents.isEmpty()
                ? 0
                : (int) Math.round(usagePercents.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        return new PlatformAdminPlanQuotaStats(validUsers.size(), averagePercent);
    }

    private PlatformAdminPlanQuotaUser buildRow(
            User user, Map<Long, UserPlan> planByCompany, Map<Long, Plan> planById, Map<Long, Long> usageByUser,
            Map<Long, String> companyNameById) {
        int quotaUsed = usageByUser.getOrDefault(user.getId(), 0L).intValue();
        UserPlan userPlan = user.getCompanyId() == null ? null : planByCompany.get(user.getCompanyId());
        String companyName = user.getCompanyId() == null ? null : companyNameById.get(user.getCompanyId());

        if (userPlan == null) {
            return new PlatformAdminPlanQuotaUser(
                    user.getId(), user.getName(), user.getEmail(), user.getCompanyId(), companyName,
                    null, quotaUsed, null, null, PlatformAdminPlanQuotaStatus.EXPIRED);
        }

        Plan plan = planById.get(userPlan.getPlanId());
        RemainingPlan remaining = resolveRemaining(userPlan.getStartedAt());
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

    // startedAt + 1개월을 만료 예정일로 근사해 남은 일수를 계산한다(클래스 상단 javadoc 참고).
    private RemainingPlan resolveRemaining(Instant startedAt) {
        Instant expiresAt = startedAt.atZone(KST).plusMonths(1).toInstant();
        long remainingDays = ChronoUnit.DAYS.between(Instant.now(), expiresAt);
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

    // AdminPlanService#mediaUsageByInspector 와 동일 근사치·KST 타임존 근거(Dockerfile -Duser.timezone=Asia/Seoul).
    private Map<Long, Long> mediaUsageByUser(List<User> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = users.stream().map(User::getId).toList();
        LocalDate period = currentPeriod();
        LocalDateTime from = period.atStartOfDay();
        LocalDateTime to = period.plusMonths(1).atStartOfDay();
        return mediaRepository.countByAssignedInspectorInAndCreatedAtBetween(userIds, from, to).stream()
                .collect(Collectors.toMap(
                        MediaRepository.InspectorMediaCount::getInspectorId,
                        MediaRepository.InspectorMediaCount::getMediaCount));
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
