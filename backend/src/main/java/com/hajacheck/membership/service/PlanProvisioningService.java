package com.hajacheck.membership.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 시 FREE 플랜 자동 배정(#517 / HAJA-308).
 *
 * <p>FREE {@link Plan} 은 배포 전제 시드({@code plans} 3건, migrations/20260721_01_plans_seed_free_assign.sql)로
 * 항상 존재해야 한다 — 조회 실패는 설정 오류이므로 fail-fast 로 {@link ErrorCode#PLAN_DATA_INVALID} 를 던진다.
 *
 * <p>멱등: 대상(회사/개인)에 이미 ACTIVE 또는 UPGRADE_REQUESTED {@link UserPlan} 이 있으면 no-op —
 * {@link MembershipService#resolveCurrentUserPlan} 이 "현재 구독"으로 인정하는 상태와 정합시켜 중복 구독 방지.
 * DB 부분 유니크 인덱스({@code uq_user_plans_active_user}/{@code uq_user_plans_active_company})가 동시성
 * 경합에서도 ACTIVE 중복 삽입을 최종 방어한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlanProvisioningService {

    private static final List<UserPlanStatus> LIVE_STATUSES =
            List.of(UserPlanStatus.ACTIVE, UserPlanStatus.UPGRADE_REQUESTED);

    private final PlanRepository planRepository;
    private final UserPlanRepository userPlanRepository;

    @Transactional
    public void ensureFreePlanForCompany(Long companyId) {
        if (userPlanRepository.existsByCompanyIdAndStatusIn(companyId, LIVE_STATUSES)) {
            return;
        }
        userPlanRepository.save(UserPlan.forCompany(companyId, findFreePlanId()));
    }

    @Transactional
    public void ensureFreePlanForUser(Long userId) {
        if (userPlanRepository.existsByUserIdAndStatusIn(userId, LIVE_STATUSES)) {
            return;
        }
        userPlanRepository.save(UserPlan.forUser(userId, findFreePlanId()));
    }

    /**
     * 한도 검사(#843 {@code QuotaService})가 "활성 구독 없음"을 만났을 때의 자가 치유 경로 —
     * 소유 대상(회사 우선, 없으면 개인)에 FREE 구독을 <b>독립 트랜잭션</b>으로 보장한다.
     *
     * <p>{@code REQUIRES_NEW} 인 이유: 호출부는 시설물 등록·좌석 배정 같은 실제 쓰기 트랜잭션 안에 있다.
     * 같은 트랜잭션에서 INSERT 하면 동시 프로비저닝 경합으로 부분 UQ
     * ({@code uq_user_plans_active_company}/{@code uq_user_plans_active_user})를 밟는 순간
     * 호출부 트랜잭션까지 롤백 전용으로 오염된다. 분리해 두면 경합에서 진 쪽만 롤백되고, 호출부는
     * 예외를 삼킨 뒤 재조회로 승자의 구독을 그대로 쓸 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionFreePlanInNewTransaction(Long userId, Long companyId) {
        if (companyId != null) {
            ensureFreePlanForCompany(companyId);
            return;
        }
        if (userId != null) {
            ensureFreePlanForUser(userId);
        }
    }

    private Long findFreePlanId() {
        return planRepository.findByName(PlanName.FREE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID))
                .getId();
    }
}
