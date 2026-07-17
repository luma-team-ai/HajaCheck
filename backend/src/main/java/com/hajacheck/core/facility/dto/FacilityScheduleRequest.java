package com.hajacheck.core.facility.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 시설물 점검 주기 설정 요청(dev-04-03, #268). 기준일(오늘)은 서버가 산출하므로
 * 요청 바디에 포함하지 않는다 — 1차 산출 기준은 "설정일(오늘)" (최종 점검일 기준 정교화는 dev-03-02 후속).
 */
public record FacilityScheduleRequest(
        // @Min(1): FacilityCreate/UpdateRequest 는 @Min(0)(주기 미설정 허용)이지만,
        // 이 엔드포인트는 주기를 '설정'하는 것이 목적이라 0(주기 없음)은 무의미 → 최소 1개월 강제.
        // @Max(120): 상한(10년) 방어(PR #284 P2). 상한이 없으면 Integer.MAX_VALUE 같은 극단값이
        //   검증을 통과해 Facility.updateSchedule 의 baseDate.plusMonths(...) 에서 산술 오버플로우
        //   (DateTimeException)를 일으켜 미처리 500 위험 → 현실적 상한으로 400(INVALID_INPUT) 처리.
        @NotNull @Min(1) @Max(120) Integer inspectionCycleMonths
) {
}
