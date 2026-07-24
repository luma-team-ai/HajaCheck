package com.hajacheck.core.facility.dto;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 시설물 현황 전용 목록 응답(#540 ⑥, HAJA-378) — GET /api/facilities/status.
 * 대시보드 성격의 요약 테이블 화면 전용이라 {@link FacilityResponse}(CRUD 상세)와 별도 DTO로 둔다.
 *
 * <p>initialGrade 는 시설물 등록 시 입력한 정적 초기 등급({@link FacilityInitialGrade})이다.
 * 점검 이력 기반으로 동적 산출되는 등급은 별도 미해결 과제(out of scope)이며 여기서는 계산하지 않는다.
 *
 * <p>dDay 는 호출 시점 today 기준 nextInspectionDueAt 까지의 잔여일(dev-03-02 UpcomingInspectionResponse
 * 와 동일한 ChronoUnit.DAYS.between(today, dueAt) 관례) — nextInspectionDueAt 이 없으면 null.
 * assigneeUserId/assigneeName 은 담당자 미배정 시 null, lastInspectedAt 은 점검 이력이 없으면 null.
 */
public record FacilityStatusResponse(
        Long facilityId,
        String facilityName,
        FacilityInitialGrade initialGrade,
        LocalDate nextInspectionDueAt,
        Long dDay,
        Long assigneeUserId,
        String assigneeName,
        LocalDate lastInspectedAt
) {

    public static FacilityStatusResponse of(
            Facility facility, LocalDate today, String assigneeName, LocalDate lastInspectedAt) {
        LocalDate dueAt = facility.getNextInspectionDueAt();
        Long dDay = dueAt == null ? null : ChronoUnit.DAYS.between(today, dueAt);
        return new FacilityStatusResponse(
                facility.getId(),
                facility.getName(),
                facility.getInitialGrade(),
                dueAt,
                dDay,
                facility.getAssigneeUserId(),
                assigneeName,
                lastInspectedAt);
    }
}
