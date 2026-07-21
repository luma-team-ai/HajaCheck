package com.hajacheck.admin.service;

import com.hajacheck.admin.dto.AdminPlanCatalogResponse;
import com.hajacheck.admin.dto.AdminPlanHistoryResponse;
import com.hajacheck.admin.dto.AdminPlanQuotaMember;
import com.hajacheck.admin.dto.AdminPlanQuotaResponse;
import com.hajacheck.admin.dto.AdminPlanQuotaStats;
import com.hajacheck.admin.dto.AdminPlanResponse;
import com.hajacheck.admin.repository.AdminPlanRepository;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 콘솔 — 플랜·쿼터 관리(FR-8-A, #507 / HAJA-258, frontend #508 대응). 기업 관리자(ADMIN)가 자기
 * 회사(company_id)의 요금제를 조회·변경하고 이번 달 사용량(월 분석 장수 등)을 확인한다.
 *
 * <p><b>인가</b>: ADMIN role 자체는 SecurityConfig 의 URL 매처("/api/admin/**" → hasRole(ADMIN))가 필터
 * 단계에서 강제한다(프론트 가드는 UX 용, 백엔드가 최종 방어선). 이 서비스는 그 위에 <b>회사 스코프 + 상속</b>을
 * 얹는다: companyId 가 없으면(개인 회원 등) FORBIDDEN, 있어도 그 회사에 <b>유효한 승인 멤버십</b>이 없으면
 * 회사 플랜을 상속하지 않으므로(§2.6 "미승인 멤버십은 상속 대상 아님") PLAN_NOT_FOUND 로 응답한다.
 *
 * <p><b>플랜 변경 이력</b>: table_design.md §user_plans 가 규정한 대로 "기존 ACTIVE 를 EXPIRED 로 내리고 신규를
 * ACTIVE 로 올리는" 단일 트랜잭션 전이로 처리한다 — user_plans 행 자체가 "언제·어느 요금제에서·어느 요금제로"의
 * 변경 이력이 되며 {@code getHistory} 로 조회한다. (변경 주체 user_id 까지 남기려면 별도 audit 컬럼/테이블이
 * 필요하며, 이는 canonical DDL + 증분 마이그레이션 + 스키마 패리티 검증을 수반하는 스키마 변경이라 별도 과제.)
 *
 * <p>사용량(usage_counters)은 <b>읽기 전용</b>으로만 다룬다 — 카운터 증가는 QuotaInterceptor 의 원자적 조건부
 * UPDATE 책임이며(#337) 여기서 read-modify-write 로 되돌리지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminPlanService {

    private final AdminPlanRepository adminPlanRepository;
    private final AdminUserRepository adminUserRepository;
    private final PlanRepository planRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final UserRepository userRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final MediaRepository mediaRepository;

    /** 제공 요금제 카탈로그(변경 선택지) — 회사 스코프와 무관한 참조 데이터라 ADMIN 이면 조회 가능. */
    public AdminPlanCatalogResponse getPlanCatalog() {
        List<Plan> plans = planRepository.findAll();
        plans.sort(Comparator.comparing(Plan::getId));
        return AdminPlanCatalogResponse.from(plans);
    }

    /** 현재 회사 구독 + 이번 달 사용량 조회. */
    public AdminPlanResponse getCurrentPlan(Long adminUserId) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        UserPlan userPlan = resolveCurrentCompanyPlan(companyId);
        Plan plan = findPlan(userPlan.getPlanId());
        LocalDate period = currentPeriod();
        UsageCounter usage = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), period)
                .orElse(null);
        return AdminPlanResponse.from(userPlan, plan, usage, period);
    }

    /**
     * 회사 구독의 요금제 변경 — 기존 ACTIVE(또는 UPGRADE_REQUESTED) 구독을 EXPIRED 로 내리고 새 ACTIVE 구독을
     * 발급한다(단일 트랜잭션). 대상 요금제가 현재와 같고 이미 ACTIVE 면 멱등 no-op(200).
     */
    @Transactional
    public AdminPlanResponse changePlan(Long adminUserId, PlanName targetPlanName) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        UserPlan current = resolveCurrentCompanyPlan(companyId);
        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        // 대상이 현재와 동일 + 이미 ACTIVE → 변경 없음(불필요한 이력 행/한도 리셋 방지).
        if (current.getStatus() == UserPlanStatus.ACTIVE
                && current.getPlanId().equals(targetPlan.getId())) {
            return buildResponseWithUsage(current, targetPlan);
        }

        // 기존 구독 만료 후 신규 ACTIVE 발급 — 부분 UQ(uq_user_plans_active_company: ACTIVE 최대 1건)를
        // 만족하도록 만료 UPDATE 를 먼저 flush 한 뒤 INSERT 한다.
        current.expire();
        adminPlanRepository.saveAndFlush(current);

        UserPlan renewed = UserPlan.forCompany(companyId, targetPlan.getId());
        try {
            UserPlan saved = adminPlanRepository.saveAndFlush(renewed);
            return buildResponseWithUsage(saved, targetPlan);
        } catch (DataIntegrityViolationException e) {
            // 동시 플랜 변경 경합 — 다른 트랜잭션이 이미 새 ACTIVE 를 만들어 부분 UQ 위반.
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }
    }

    /** 회사 구독 변경 이력(최신 순). */
    public AdminPlanHistoryResponse getHistory(Long adminUserId) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        return new AdminPlanHistoryResponse(adminPlanRepository.findHistoryByCompanyId(companyId));
    }

    /**
     * 회사 소속 활성 멤버별 이번 달 쿼터 사용 현황(#507, frontend PlanQuotaPage.tsx). 회사에 활성 구독이
     * 없으면(PLAN_NOT_FOUND) 목록 조회 자체는 실패시키지 않고 plan/quotaLimit 을 전부 null 로 반환한다 —
     * getCurrentPlan/getHistory 와 달리 이 화면은 "구독 없음"도 정상 상태로 보여줘야 하는 목록 뷰이기 때문이다.
     */
    public AdminPlanQuotaResponse getPlanQuota(Long adminUserId, int page, int size, String keyword) {
        Long companyId = resolveInheritedCompanyId(adminUserId);

        Plan companyPlan = null;
        Integer companyQuotaLimit = null;
        int totalQuotaUsagePercent = 0;
        try {
            UserPlan current = resolveCurrentCompanyPlan(companyId);
            companyPlan = findPlan(current.getPlanId());
            companyQuotaLimit = companyPlan.getMaxMonthlyAnalyses();
            LocalDate period = currentPeriod();
            UsageCounter usage = usageCounterRepository
                    .findByUserPlanIdAndPeriod(current.getId(), period)
                    .orElse(null);
            totalQuotaUsagePercent = computeUsagePercent(usage, companyQuotaLimit);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.PLAN_NOT_FOUND) {
                throw e;
            }
            // 활성 구독 없음 — 아래 content 는 plan/quotaLimit null 로, companyPlan 도 null 로 응답한다.
        }

        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        Page<User> members = adminUserRepository.searchActiveMembers(
                companyId, UserStatus.ACTIVE, buildLikePattern(keyword), pageable);

        Map<Long, Long> usedByMember = mediaUsageByInspector(members.getContent());

        String planName = companyPlan == null ? null : companyPlan.getName().name();
        Integer quotaLimit = companyQuotaLimit;
        List<AdminPlanQuotaMember> content = members.getContent().stream()
                .map(u -> new AdminPlanQuotaMember(
                        u.getId(),
                        u.getName(),
                        u.getEmail(),
                        planName,
                        usedByMember.getOrDefault(u.getId(), 0L).intValue(),
                        quotaLimit))
                .toList();

        long activeUsers = adminUserRepository.countByCompanyIdAndStatus(companyId, UserStatus.ACTIVE);
        AdminPlanQuotaStats stats = new AdminPlanQuotaStats(activeUsers, totalQuotaUsagePercent, planName);

        return new AdminPlanQuotaResponse(content, page, size, members.getTotalElements(), stats);
    }

    private AdminPlanResponse buildResponseWithUsage(UserPlan userPlan, Plan plan) {
        LocalDate period = currentPeriod();
        UsageCounter usage = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), period)
                .orElse(null);
        return AdminPlanResponse.from(userPlan, plan, usage, period);
    }

    // 요청 관리자의 회사를 확정하고 상속 자격(유효 승인 멤버십)을 검증한다.
    // companyId 없음 = 회사 관리 대상 아님(FORBIDDEN). 유효 승인 멤버십 없음 = 회사 플랜 상속 대상 아님(§2.6).
    private Long resolveInheritedCompanyId(Long adminUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Long companyId = admin.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }
        boolean inherits = companyMembershipRepository
                .existsEffectiveApprovedMembership(companyId, adminUserId, Instant.now());
        if (!inherits) {
            // 미승인/무효 멤버십 → 회사 귀속 플랜을 상속하지 않음 → 활성 구독 없음과 동일하게 응답.
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        return companyId;
    }

    private UserPlan resolveCurrentCompanyPlan(Long companyId) {
        return adminPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(companyId, UserPlanStatus.ACTIVE)
                .or(() -> adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                        companyId, UserPlanStatus.UPGRADE_REQUESTED))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private Plan findPlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    private LocalDate currentPeriod() {
        // 사용량 집계(usage_counters.period) 존과 일치 — 서버 기본 타임존에 의존하지 않고 KST 로 고정(마이페이지와 동일).
        return YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);
    }

    // AdminUserService#buildLikePattern 과 동일한 이스케이프 규칙(각 화면이 자기 검색 계약을 독립적으로 유지).
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

    // 페이지에 표시할 멤버들의 "이번 달 분석 이미지 장수" 근사치 — MediaRepository 문서 참고(정확한 값은
    // usage_counters 회사 단위 집계뿐이라, 여기서는 화면 표의 멤버별 분포 표시용으로만 쓴다).
    private Map<Long, Long> mediaUsageByInspector(List<User> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Long> memberIds = members.stream().map(User::getId).toList();
        LocalDate period = currentPeriod();
        LocalDateTime from = period.atStartOfDay();
        LocalDateTime to = period.plusMonths(1).atStartOfDay();
        return mediaRepository.countByAssignedInspectorInAndCreatedAtBetween(memberIds, from, to).stream()
                .collect(Collectors.toMap(
                        MediaRepository.InspectorMediaCount::getInspectorId,
                        MediaRepository.InspectorMediaCount::getMediaCount));
    }

    private int computeUsagePercent(UsageCounter usage, Integer quotaLimit) {
        if (usage == null || quotaLimit == null || quotaLimit <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round(usage.getAnalyzedImageCount() * 100.0 / quotaLimit));
    }
}
