package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 전이(기존 ACTIVE 만료 → 신규 ACTIVE 발급) 공통 규칙(#988 / HAJA-489).
 *
 * <p>결제 승인 경로({@code PaymentWriter})가 쓰는 전이 로직이다. 원래는 모의 결제
 * ({@code MembershipService#checkout}, #711)가 같은 일을 인라인으로 했는데, 실결제 도입으로 그 메서드가
 * 사라지면서 "현재 구독 조회 → 소유자 인가 → 만료/발급 → 사용량 이월"을 이 클래스로 모았다.
 *
 * <p><b>MembershipService 와의 중복에 대하여</b>: {@code MembershipService} 에도 같은 우선순위
 * (ACTIVE → UPGRADE_REQUESTED)로 현재 구독을 찾는 private 헬퍼가 남아 있다. 그쪽은 조회 전용 화면
 * (내 플랜·좌석)과 업그레이드 문의 전이가 쓰는 경로이고, 여기는 결제 쓰기 경로다. 한쪽으로 합치려면
 * MembershipService 의 리포지토리 의존을 이 빈으로 갈아끼워야 해서, 결제 도입 PR 의 변경 범위를 넘는
 * 리팩터링이 된다 — 두 곳이 <b>같은 판정 규칙</b>을 쓴다는 사실을 서로의 javadoc 으로 고정해 두고,
 * 통합은 별도 과제로 남긴다.
 *
 * <p><b>#851 — 사용량 이월</b>: 신규 구독 발급 직후 같은 period 의 집계를 새 구독으로 옮긴다. 이월이
 * 없으면 결제할 때마다 월 분석 한도가 0으로 리셋되어 한도 강제(#843)가 무력화된다
 * ({@code UsageCounterRepository#carryOverPeriodUsage} javadoc 참고).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlanTransitionService {

    // usage_counters.period 집계 존과 동일하게 고정(#711 — MembershipService·AdminPlanService 와 같은 값).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CompanyRepository companyRepository;
    private final UserPlanRepository userPlanRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final ScheduledPlanChangeRepository scheduledPlanChangeRepository;

    /**
     * 현재 구독 조회 — ACTIVE 우선, 없으면 UPGRADE_REQUESTED(여전히 유효한 구독). EXPIRED 만 남았으면
     * 활성 구독 없음(PLAN_NOT_FOUND)이다({@code MembershipService} 와 동일 규칙).
     */
    public UserPlan resolveCurrentUserPlan(Long userId, Long companyId) {
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

    /**
     * 구독 소유자 인가 — 회사 구독이면 {@code company.ownerUserId}, 개인 구독이면
     * {@link UserPlan#isOwnedByUser}({@code MembershipService#requestUpgrade}·
     * {@code AdminPlanService#requireCompanyOwner} 와 동일 기준). "인증됨 ≠ 인가됨" — 회사에 속했다는
     * 사실만으로는 그 회사의 요금제를 결제할 수 없다.
     *
     * @return 회사 구독이면 그 회사, 개인 구독이면 {@code null}
     */
    public Company requireSubscriptionOwner(Long userId, Long companyId, UserPlan current) {
        if (companyId == null) {
            if (!current.isOwnedByUser(userId)) {
                throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
            }
            return null;
        }
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_FORBIDDEN));
        if (!userId.equals(company.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }
        return company;
    }

    /**
     * 구독 전이 — <b>반드시 호출부 트랜잭션 안에서</b>({@code MANDATORY}) 실행한다. 만료와 신규 발급이
     * 원자적이지 않으면 "구독이 하나도 없는" 순간이 커밋될 수 있고, 그 사이 요청은 PLAN_NOT_FOUND 로 죽는다.
     *
     * <p>순서: 만료 UPDATE 를 먼저 flush 한 뒤 INSERT 한다 — 부분 UQ
     * ({@code uq_user_plans_active_user}/{@code uq_user_plans_active_company}: ACTIVE 최대 1건)를 만족시키기
     * 위해서다({@code AdminPlanService#changePlan} 과 동일 순서).
     *
     * @return 새로 발급된 ACTIVE 구독
     * @throws BusinessException 동시 전이 경합으로 부분 UQ 를 밟으면 PLAN_ACTIVE_SUBSCRIPTION_CONFLICT
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserPlan transitionTo(Long userId, Long companyId, UserPlan current, Plan targetPlan) {
        // 이 구독에 걸린 하향 예약(#1105)은 여기서 무효화한다 — 결제로 요금제가 올라간(또는 바뀐) 뒤에도
        // 예약이 남아 있으면, 방금 결제한 구독을 한 달 뒤 스케줄러가 다시 내리려 든다. 실행 시점 재검증
        // (구독이 EXPIRED 라 무효 판정)이 최종 방어선이지만, 원장에 죽은 PENDING 을 남기지 않는 편이
        // 운영 조회·디버깅에 낫다. 대기 예약이 없으면 no-op(0행)이다.
        scheduledPlanChangeRepository.cancelPendingByUserPlanId(
                current.getId(), "결제 전이로 예약이 무효화됨");

        current.expire();
        userPlanRepository.saveAndFlush(current);

        UserPlan renewed = companyId != null
                ? UserPlan.forCompany(companyId, targetPlan.getId())
                : UserPlan.forUser(userId, targetPlan.getId());
        // 새로 돈을 냈으므로 주기를 리셋한다(#1104) — 관리자 무결제 변경(AdminPlanService)의 승계와 반대 규칙.
        renewed.startNewBillingPeriod(renewed.getStartedAt());
        UserPlan saved;
        try {
            // try 범위를 이 한 줄로 좁힌다 — 아래 사용량 이월에서 나온 무결성 위반까지 "이미 활성 구독
            // 존재"로 오분류하지 않기 위해서다(AdminPlanService 의 동일 주석과 같은 이유).
            saved = userPlanRepository.saveAndFlush(renewed);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        carryOverUsage(current.getId(), saved.getId());
        return saved;
    }

    /**
     * 당월 사용량 이월(#851) — 신규 구독 발급 직후 같은 트랜잭션에서 호출해야 한다. 별도 트랜잭션으로
     * 미루면 "플랜은 바뀌었는데 사용량은 0" 인 창이 열려 그 사이 요청이 한도를 초과 소비할 수 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void carryOverUsage(Long previousUserPlanId, Long newUserPlanId) {
        if (previousUserPlanId == null || previousUserPlanId.equals(newUserPlanId)) {
            return;
        }
        int carried = usageCounterRepository.carryOverPeriodUsage(
                previousUserPlanId, newUserPlanId, currentPeriod());
        if (carried > 0) {
            log.info("플랜 전이 사용량 이월 — previousUserPlanId={} newUserPlanId={}",
                    previousUserPlanId, newUserPlanId);
        }
    }

    private LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }
}
