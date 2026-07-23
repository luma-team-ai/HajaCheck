package com.hajacheck.platformadmin.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanCatalogResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanPolicyUpdateRequest;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 플랜 정책 설정(#624 후속, 사용자 지시 2026-07-23). Plan(plans 테이블)은 그동안
 * 조회 전용이었으나, 이 화면에서만 가격·한도·기능 제공 여부를 편집한다(다른 화면은 여전히 조회 전용 —
 * Plan#updatePolicy 참고).
 *
 * <p><b>영향 범위</b>: plans 는 회사 스코프 없는 전역 참조 데이터라, 이 화면에서 값을 바꾸면 즉시 그
 * 플랜을 구독 중인 모든 회사/개인의 한도·가격 표시가 바뀐다(플랜 변경 자체를 일으키지 않음 — user_plans
 * 는 손대지 않는다). 그래서 요청은 FREE/STANDARD/ENTERPRISE 3건을 한 번에 원자적으로 교체하도록 강제한다
 * (부분 업데이트로 한 플랜만 바뀐 채 나머지가 낡은 값으로 방치되는 상태를 허용하지 않음).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlatformAdminPlanPolicyService {

    private static final EnumSet<PlanName> REQUIRED_PLAN_NAMES = EnumSet.allOf(PlanName.class);

    private final PlanRepository planRepository;

    public PlatformAdminPlanCatalogResponse getCatalog() {
        List<Plan> plans = planRepository.findAll();
        plans.sort(Comparator.comparing(Plan::getId));
        return PlatformAdminPlanCatalogResponse.from(plans);
    }

    @Transactional
    public PlatformAdminPlanCatalogResponse updatePolicies(PlatformAdminPlanPolicyUpdateRequest request) {
        Map<PlanName, PlatformAdminPlanPolicyUpdateRequest.Entry> entriesByName = validateAndIndex(request);

        Map<PlanName, Plan> plansByName = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getName, Function.identity()));

        for (PlanName planName : REQUIRED_PLAN_NAMES) {
            Plan plan = plansByName.get(planName);
            if (plan == null) {
                // FREE/STANDARD/ENTERPRISE 시드는 배포 전제(#517) — 없으면 설정 오류이므로 fail-fast.
                throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
            }
            PlatformAdminPlanPolicyUpdateRequest.Entry entry = entriesByName.get(planName);
            plan.updatePolicy(
                    entry.priceMonthly(), entry.maxFacilities(), entry.maxMonthlyAnalyses(), entry.maxSeats(),
                    entry.hasPdfWatermark(), entry.hasCounselorAccess());
        }

        return getCatalog();
    }

    // "FREE/STANDARD/ENTERPRISE 각각 정확히 한 번씩"을 Bean Validation으로 표현할 수 없어 여기서 검증한다.
    private Map<PlanName, PlatformAdminPlanPolicyUpdateRequest.Entry> validateAndIndex(
            PlatformAdminPlanPolicyUpdateRequest request) {
        if (request.plans().size() != REQUIRED_PLAN_NAMES.size()) {
            throw new BusinessException(ErrorCode.PLAN_POLICY_INVALID);
        }
        Map<PlanName, PlatformAdminPlanPolicyUpdateRequest.Entry> byName = request.plans().stream()
                .collect(Collectors.toMap(
                        PlatformAdminPlanPolicyUpdateRequest.Entry::name, Function.identity(), (a, b) -> a));
        if (byName.size() != REQUIRED_PLAN_NAMES.size() || !byName.keySet().equals(REQUIRED_PLAN_NAMES)) {
            // 중복 이름이 있으면 toMap 병합으로 크기가 줄어 앞선 size 비교로도 걸리지만, 서로 다른
            // 이름 3개가 REQUIRED_PLAN_NAMES와 정확히 일치하는지까지 명시적으로 재확인한다.
            throw new BusinessException(ErrorCode.PLAN_POLICY_INVALID);
        }
        return byName;
    }
}
