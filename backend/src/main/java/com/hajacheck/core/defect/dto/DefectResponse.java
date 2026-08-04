package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 하자 응답 DTO — Entity 직접 노출 금지(§0). 목록/상세 공용으로 사용한다
 * (bbox/이미지 등 별도 무거운 연관 엔티티가 없어 요약형과 상세형을 분리할 실익이 없음, HAJA-30 handoff 참고).
 *
 * <p>facilityName 은 대시보드 {@code PendingPriorityResponse}와 동일하게 "위치" 정보의 대체값이다 —
 * defects/media 스키마에 세부 위치(층·구역) 컬럼이 없어 시설물 명칭까지만 제공한다.
 *
 * <p>imageUrl(HAJA-314)은 defect.mediaId가 있을 때만 채워지며, 새 이미지 서빙 경로를 만들지 않고
 * {@link com.hajacheck.core.media.dto.MediaResponse}와 동일하게 기존 인가된 썸네일 엔드포인트
 * ({@code /api/media/{id}/thumbnail})를 재사용한다 — 원본은 직접 서빙하지 않는다는 PRD FR-2 정책을
 * 그대로 따른다.
 *
 * <p>actionPhotoUrl~actionAssigneeName(HAJA-393/#725)은 "조치 결과 등록"(PATCH /api/defects/{id}/action)
 * 이전에는 전부 null이다. actionAssigneeName은 엔티티가 Long id만 보유하므로 서비스 계층에서 조회해
 * {@link #from(Defect, String)}로 채운다. 이름을 조회하지 않는 목록 등에서는 기존 {@link #from(Defect)}가 null로 남긴다.
 *
 * <p>foundCycle(HAJA-488/#981)은 하자를 발견한 점검 회차이며, 별도 쿼리 없이 이미 로드된
 * {@code defect.getInspection().getRoundNo()}를 그대로 노출한다.
 *
 * <p>location(#970 갭3)은 검수자가 사후 편집한 하자 위치 텍스트, previousDefectId(HAJA-437)는
 * 검수자가 확정한 이전 회차 대응 하자 id(둘 다 nullable)를 엔티티 값 그대로 노출한다.
 *
 * <p>assigneeName(#970 갭3)은 actionAssigneeName과 별개로, 시설물 담당자({@code Facility.assigneeUserId})의
 * 이름이다. 신규 컬럼이 아니라 팀 결정으로 기존 Facility 필드를 재사용한 값이라 defects 테이블과
 * 무관하다. actionAssigneeName과 동일하게 Long id만 엔티티에 있으므로 서비스 계층에서 조회해
 * {@link #from(Defect, String, String)}로 채운다 — 목록({@link #from(Defect)})은 N+1 방지를 위해 조회하지 않는다.
 *
 * <p>groupSize/groupStatus(이미지 단위 보수 작업 v0.2, #1456)는 신규 저장 컬럼이 아니라 조치 등록
 * ({@code PATCH /api/defects/{id}/action}) 응답에서만 계산돼 채워지는 값이다 — 같은
 * inspection_id+media_id로 확정된(CONFIRMED 이상) 비삭제 하자 그룹의 크기와, 그 그룹 전체를
 * 대상으로 집계한 상태(전체 RESOLVED→RESOLVED, 일부 진행 이상→IN_PROGRESS, 그 외 CONFIRMED)다.
 * 목록/상세 등 다른 조회 경로는 계산하지 않고 null로 남긴다({@link #from(Defect)} 등 기존 팩토리는
 * 변경 없음, {@link #withGroupSummary(int, DefectStatus)}로만 채운다).
 *
 * <p>mediaId(#1456, 프론트 카드 그룹핑용)는 imageUrl에 이미 간접 포함돼 있던 값을 클라이언트가
 * 문자열 파싱 없이 바로 그룹 키로 쓸 수 있도록 그대로 노출한 것뿐이다 — 신규 계산이나 저장 없음.
 */
public record DefectResponse(
        Long id,
        Long inspectionId,
        Long facilityId,
        String facilityName,
        String facilityType,
        String location,
        String assigneeName,
        Integer foundCycle,
        DefectType type,
        String typeLabel,
        DefectGrade grade,
        DefectStatus status,
        Double confidence,
        boolean reviewed,
        Double bboxX,
        Double bboxY,
        Double bboxW,
        Double bboxH,
        Double crackWidthMm,
        Double crackLengthMm,
        String imageUrl,
        Long mediaId,
        Long previousDefectId,
        String actionPhotoUrl,
        String actionContent,
        LocalDate actionDate,
        Long actionAssigneeId,
        String actionAssigneeName,
        LocalDateTime createdAt,
        Integer groupSize,
        DefectStatus groupStatus
) {
    public static DefectResponse from(Defect defect) {
        return from(defect, null, null);
    }

    public static DefectResponse from(Defect defect, String actionAssigneeName) {
        return from(defect, actionAssigneeName, null);
    }

    public static DefectResponse from(Defect defect, String actionAssigneeName, String assigneeName) {
        Facility facility = defect.getInspection().getFacility();
        return new DefectResponse(
                defect.getId(),
                defect.getInspectionId(),
                facility.getId(),
                facility.getName(),
                facility.getType(),
                defect.getLocation(),
                assigneeName,
                defect.getInspection().getRoundNo(),
                defect.getType(),
                defect.getType().label(),
                defect.getGrade(),
                defect.getStatus(),
                defect.getConfidence(),
                defect.isReviewed(),
                defect.getBboxX(),
                defect.getBboxY(),
                defect.getBboxW(),
                defect.getBboxH(),
                defect.getCrackWidthMm(),
                defect.getCrackLengthMm(),
                defect.getMediaId() == null ? null : "/api/media/" + defect.getMediaId() + "/thumbnail",
                defect.getMediaId(),
                defect.getPreviousDefectId(),
                defect.getActionMediaId() == null ? null : "/api/media/" + defect.getActionMediaId() + "/thumbnail",
                defect.getActionContent(),
                defect.getActionDate(),
                defect.getActionAssigneeId(),
                actionAssigneeName,
                defect.getCreatedAt(),
                null,
                null
        );
    }

    public DefectResponse withGroupSummary(int groupSize, DefectStatus groupStatus) {
        return new DefectResponse(
                id, inspectionId, facilityId, facilityName, facilityType, location, assigneeName, foundCycle,
                type, typeLabel, grade, status, confidence, reviewed, bboxX, bboxY, bboxW, bboxH,
                crackWidthMm, crackLengthMm, imageUrl, mediaId, previousDefectId, actionPhotoUrl, actionContent,
                actionDate, actionAssigneeId, actionAssigneeName, createdAt, groupSize, groupStatus);
    }
}
