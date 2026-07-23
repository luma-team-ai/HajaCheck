package com.hajacheck.platformadmin.dto;

import com.hajacheck.membership.entity.Plan;
import java.math.BigDecimal;

/**
 * 요금제 카탈로그 항목(플랫폼 관리자 "플랜 정책 설정", #624 후속). AdminPlanItem(#507, 회사 관리자 화면)과
 * 필드 구성은 동일하다 — plans 테이블 자체가 회사 스코프 없는 전역 참조 데이터라 두 화면이 같은 데이터를
 * 보되, PLATFORM_ADMIN 전용 경로로 분리해 이 화면에서만 편집(PUT)을 허용한다.
 */
public record PlatformAdminPlanItem(
        Long id,
        String name,
        Integer maxFacilities,
        Integer maxMonthlyAnalyses,
        Integer maxSeats,
        boolean hasPdfWatermark,
        boolean hasCounselorAccess,
        boolean hasAiAddon,
        BigDecimal priceMonthly) {

    public static PlatformAdminPlanItem from(Plan plan) {
        return new PlatformAdminPlanItem(
                plan.getId(),
                plan.getName().name(),
                plan.getMaxFacilities(),
                plan.getMaxMonthlyAnalyses(),
                plan.getMaxSeats(),
                plan.isHasPdfWatermark(),
                plan.isHasCounselorAccess(),
                plan.isHasAiAddon(),
                plan.getPriceMonthly());
    }
}
