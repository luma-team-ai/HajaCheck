package com.hajacheck.core.facility.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 시설물 점검 주기 설정 요청(dev-04-03, #268). 기준일(오늘)은 서버가 산출하므로
 * 요청 바디에 포함하지 않는다 — 1차 산출 기준은 "설정일(오늘)" (최종 점검일 기준 정교화는 dev-03-02 후속).
 */
public record FacilityScheduleRequest(
        // @Min(1): FacilityCreate/UpdateRequest 는 @Min(0)(주기 미설정 허용)이지만,
        // 이 엔드포인트는 주기를 '설정'하는 것이 목적이라 0(주기 없음)은 무의미 → 최소 1개월 강제.
        @NotNull @Min(1) Integer inspectionCycleMonths
) {
}
