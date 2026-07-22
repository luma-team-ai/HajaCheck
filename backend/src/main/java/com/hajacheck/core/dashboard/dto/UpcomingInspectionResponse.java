package com.hajacheck.core.dashboard.dto;

import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * GET /api/dashboard/upcoming-inspections 응답 항목(dev-03-02) — 로그인 사용자 소유 시설물 중
 * nextInspectionDueAt 이 오늘 이후~조회범위 이내인 것을 오름차순으로 나열한다.
 *
 * <p>dDay 는 호출 시점의 today 기준 잔여일(nextInspectionDueAt - today)이며, 조회 조건상 오늘
 * 이후만 대상이므로 음수가 나올 수 없다.
 */
public record UpcomingInspectionResponse(
        Long facilityId,
        String facilityName,
        LocalDate nextInspectionDueAt,
        long dDay,
        Integer inspectionCycleMonths
) {

    public static UpcomingInspectionResponse from(Facility facility, LocalDate today) {
        return new UpcomingInspectionResponse(
                facility.getId(),
                facility.getName(),
                facility.getNextInspectionDueAt(),
                ChronoUnit.DAYS.between(today, facility.getNextInspectionDueAt()),
                facility.getInspectionCycleMonths());
    }
}
