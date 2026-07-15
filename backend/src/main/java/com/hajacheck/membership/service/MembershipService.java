package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.dto.SeatsResponse;
import com.hajacheck.membership.dto.UpgradeInquiryResponse;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PlanRepository planRepository;
    private final UserPlanRepository userPlanRepository;
    private final UsageCounterRepository usageCounterRepository;

    public MyPlanResponse getMyPlan(Long userId) {
        User user = findUser(userId);
        UserPlan userPlan = resolveCurrentUserPlan(userId, user.getCompanyId());
        Plan plan = findPlan(userPlan.getPlanId());
        LocalDate period = currentPeriod();
        UsageCounter usage = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), period)
                .orElse(null);
        return MyPlanResponse.from(userPlan, plan, usage, period);
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
        List<User> members = userRepository.findByCompanyId(companyId);
        long used = userRepository.countByCompanyId(companyId);
        return SeatsResponse.of((int) used, members, plan.getMaxSeats());
    }

    @Transactional
    public UpgradeInquiryResponse requestUpgrade(Long userId) {
        User user = findUser(userId);
        Long companyId = user.getCompanyId();
        UserPlan userPlan = resolveCurrentUserPlan(userId, companyId);

        if (companyId != null) {
            Company company = companyRepository.findById(companyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_FORBIDDEN));
            if (!company.getOwnerUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
            }
        } else if (!userPlan.isOwnedByUser(userId)) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }

        userPlan.requestUpgrade();
        return UpgradeInquiryResponse.from(userPlan);
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
                .orElseThrow(() -> new IllegalStateException("user_plans.plan_id 가 가리키는 요금제가 없습니다: " + planId));
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
    }

    private LocalDate currentPeriod() {
        return YearMonth.now().atDay(1);
    }
}
