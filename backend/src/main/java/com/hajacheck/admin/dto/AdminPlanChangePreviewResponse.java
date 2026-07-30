package com.hajacheck.admin.dto;

import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.PlanName;
import java.util.List;

/**
 * GET /api/admin/plan/change-preview 응답 — 이 요금제로 바꾸면 무엇이 정지·읽기전용이 되는지 미리 본다(#890).
 *
 * <p>부작용이 전혀 없는 조회다. 관리자가 이 결과를 확인한 뒤 {@code PATCH /api/admin/plan} 에
 * {@code confirmOverflow=true} 를 실어 보내야 실제 변경이 일어난다.
 *
 * <p>정지 대상은 <b>id 만이 아니라 이름·이메일까지</b> 반환한다 — "3명이 정지됩니다"만으로는 관리자가
 * 누구인지 몰라 판단할 수 없다.
 *
 * @param seatsToSuspend      정지될 구성원(오름차순). <b>owner 는 절대 포함되지 않는다.</b>
 * @param facilityOverflowCount 읽기 전용으로 전환될 시설물 수(조회·기존 이력은 유지, 신규 점검 생성만 차단)
 * @param requiresConfirmation 위 둘 중 하나라도 있으면 true — 그대로 변경하려면 confirmOverflow 가 필요하다
 */
public record AdminPlanChangePreviewResponse(
        PlanName targetPlan,
        boolean requiresConfirmation,
        List<SuspendTarget> seatsToSuspend,
        int facilityOverflowCount) {

    /** 정지 예정 구성원 — 관리자가 "누가 빠지는지" 보고 판단할 수 있어야 한다. */
    public record SuspendTarget(Long userId, String name, String email) {
    }

    public static AdminPlanChangePreviewResponse of(
            PlanName targetPlan, DowngradeOverflow overflow, List<SuspendTarget> targets) {
        return new AdminPlanChangePreviewResponse(
                targetPlan, overflow.exists(), targets, overflow.facilityOverflowCount());
    }
}
