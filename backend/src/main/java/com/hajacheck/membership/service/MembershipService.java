package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지 — 내 플랜·사용량·좌석(HAJA-177). 조회 위주 + 업그레이드 문의 상태전이 1건.
 *
 * <p>구독 소유 판별: companyId 있으면 회사 구독(user_plans.company_id), 없으면 개인 구독(user_plans.user_id)
 * (DDL ck_user_plans_owner_xor 와 정합). "현재 구독"은 ACTIVE 우선, 없으면 UPGRADE_REQUESTED(여전히 유효한
 * 구독) 순으로 조회하고 EXPIRED 만 남아있으면 활성 구독 없음(PLAN_NOT_FOUND)으로 판단한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MembershipService {

    // 좌석 목록 조회 상한(#484) — maxSeats 자체가 실질 상한 역할을 하지만, 방어적으로 목록 반환 건수도
    // 제한한다(회사 소속 활성 사용자 전체 무제한 반환 방지). "used" 는 이 상한과 무관하게 실제 총원 수를
    // count 쿼리로 산출해 정확성을 유지한다.
    private static final int MEMBERSHIP_SEATS_MAX = 200;

    // usage_counters.period 집계 존과 nextBillingDate(startedAt 기준 파생 계산) 존을 동일하게 고정(#711).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PlanRepository planRepository;
    private final UserPlanRepository userPlanRepository;
    private final UsageCounterRepository usageCounterRepository;

    public MyPlanResponse getMyPlan(Long userId) {
        User user = findUser(userId);
        UserPlan userPlan = resolveCurrentUserPlan(userId, user.getCompanyId());
        Plan plan = findPlan(userPlan.getPlanId());
        Company company = user.getCompanyId() == null
                ? null
                : companyRepository.findById(user.getCompanyId()).orElse(null);
        return buildResponseWithUsage(userPlan, plan, company);
    }

    public SeatsResponse getSeats(Long userId) {
        User user = findUser(userId);
        Long companyId = user.getCompanyId();

        if (companyId == null) {
            UserPlan userPlan = resolveCurrentUserPlan(userId, null);
            Plan plan = findPlan(userPlan.getPlanId());
            return SeatsResponse.of(1, List.of(user), plan.getMaxSeats());
        }

        UserPlan userPlan = resolveCurrentUserPlan(userId, companyId);
        Plan plan = findPlan(userPlan.getPlanId());
        long totalActive = userRepository.countByCompanyIdAndStatus(companyId, UserStatus.ACTIVE);
        List<User> members = userRepository.findByCompanyIdAndStatusOrderByIdAsc(
                companyId, UserStatus.ACTIVE, PageRequest.of(0, MEMBERSHIP_SEATS_MAX));
        return SeatsResponse.of((int) totalActive, members, plan.getMaxSeats());
    }

    @Transactional
    public UpgradeInquiryResponse requestUpgrade(Long userId) {
        User user = findUser(userId);
        Long companyId = user.getCompanyId();
        UserPlan userPlan = resolveCurrentUserPlan(userId, companyId);

        if (companyId != null) {
            Company company = companyRepository.findById(companyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_FORBIDDEN));
            if (!userId.equals(company.getOwnerUserId())) {
                throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
            }
        } else if (!userPlan.isOwnedByUser(userId)) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }

        userPlan.requestUpgrade();
        return UpgradeInquiryResponse.from(userPlan);
    }

    /**
     * 모의 결제(PG 실결제 없음, #711) — {@code AdminPlanService#changePlan} 과 동일한 전이 패턴을 개인/회사
     * 구독 양쪽에 적용한다: 기존 ACTIVE(또는 UPGRADE_REQUESTED) 구독을 {@link UserPlan#expire()} 로 내리고
     * 신규 ACTIVE 구독을 발급한다(단일 트랜잭션). 대상 요금제가 이미 ACTIVE 구독과 같으면 멱등 no-op(200).
     *
     * <p><b>인가</b>: {@link #requestUpgrade} 와 동일 기준 — 회사 구독은 {@code company.ownerUserId}, 개인
     * 구독은 {@link UserPlan#isOwnedByUser}.
     */
    @Transactional
    public MyPlanResponse checkout(Long userId, PlanName targetPlanName) {
        if (targetPlanName == PlanName.FREE) {
            // FREE 다운그레이드는 이 모의 결제 범위 밖(계획서 §1-3) — 업그레이드 결제 대체 흐름만 다룬다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        User user = findUser(userId);
        Long companyId = user.getCompanyId();
        UserPlan current = resolveCurrentUserPlan(userId, companyId);

        Company company = null;
        if (companyId != null) {
            company = companyRepository.findById(companyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_FORBIDDEN));
            if (!userId.equals(company.getOwnerUserId())) {
                throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
            }
        } else if (!current.isOwnedByUser(userId)) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }

        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        // 대상이 현재와 동일 + 이미 ACTIVE → 변경 없음(불필요한 이력 행/한도 리셋 방지).
        if (current.getStatus() == UserPlanStatus.ACTIVE && current.getPlanId().equals(targetPlan.getId())) {
            return buildResponseWithUsage(current, targetPlan, company);
        }

        // 기존 구독 만료 후 신규 ACTIVE 발급 — 부분 UQ(ACTIVE 최대 1건)를 만족하도록 만료 UPDATE 를 먼저
        // flush 한 뒤 INSERT 한다(AdminPlanService#changePlan 과 동일 순서).
        current.expire();
        userPlanRepository.saveAndFlush(current);

        UserPlan renewed = companyId != null
                ? UserPlan.forCompany(companyId, targetPlan.getId())
                : UserPlan.forUser(userId, targetPlan.getId());
        try {
            UserPlan saved = userPlanRepository.saveAndFlush(renewed);
            return buildResponseWithUsage(saved, targetPlan, company);
        } catch (DataIntegrityViolationException e) {
            // 동시 결제 경합 — 다른 트랜잭션이 이미 새 ACTIVE 를 만들어 부분 UQ 위반.
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }
    }

    private UserPlan resolveCurrentUserPlan(Long userId, Long companyId) {
        if (companyId != null) {
            return userPlanRepository
                    .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(companyId, UserPlanStatus.ACTIVE)
                    .or(() -> userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(
                            companyId, UserPlanStatus.UPGRADE_REQUESTED))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        }
        return userPlanRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(userId, UserPlanStatus.ACTIVE)
                .or(() -> userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
                        userId, UserPlanStatus.UPGRADE_REQUESTED))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private Plan findPlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private MyPlanResponse buildResponseWithUsage(UserPlan userPlan, Plan plan, Company company) {
        LocalDate period = currentPeriod();
        UsageCounter usage = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), period)
                .orElse(null);
        return MyPlanResponse.from(userPlan, plan, usage, period, company, KST);
    }

    private LocalDate currentPeriod() {
        // 사용량 집계(usage_counters.period) 존과 일치 — 서버 기본 타임존에 의존하지 않고 KST 로 고정.
        return YearMonth.now(KST).atDay(1);
    }
}
