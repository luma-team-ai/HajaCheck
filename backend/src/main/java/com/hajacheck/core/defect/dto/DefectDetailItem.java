package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * 점검 회차별 하자 상세(뷰어·검수 공용 DTO).
 * 필드명은 camelCase(Jackson 자동 변환), DB 저장값(grade, is_deleted 등)은 openapi.yaml 스키마 준수.
 *
 * imageUrl(HAJA-314)은 defect.mediaId가 있을 때만 채워지며,
 * {@link com.hajacheck.core.defect.dto.DefectResponse}와 동일하게
 * 기존 인가된 썸네일 엔드포인트({@code /api/media/{id}/thumbnail})를 재사용한다.
 */
@Getter
@Builder
public class DefectDetailItem {
    private Long id;
    private Long inspectionId;
    private DefectType type;
    private DefectGrade grade;
    private DefectStatus status;
    private Double confidence;
    @JsonProperty("isReviewed")
    private boolean reviewed;
    private Double bboxX;
    private Double bboxY;
    private Double bboxW;
    private Double bboxH;
    private Double crackWidthMm;
    private Double crackLengthMm;
    private Long mediaId;
    private String imageUrl;
    private LocalDateTime createdAt;

    public static DefectDetailItem from(Defect defect) {
        return DefectDetailItem.builder()
                .id(defect.getId())
                .inspectionId(defect.getInspectionId())
                .type(defect.getType())
                .grade(defect.getGrade())
                .status(defect.getStatus())
                .confidence(defect.getConfidence())
                .reviewed(defect.isReviewed())
                .bboxX(defect.getBboxX())
                .bboxY(defect.getBboxY())
                .bboxW(defect.getBboxW())
                .bboxH(defect.getBboxH())
                .crackWidthMm(defect.getCrackWidthMm())
                .crackLengthMm(defect.getCrackLengthMm())
                .mediaId(defect.getMediaId())
                .imageUrl(defect.getMediaId() == null ? null : "/api/media/" + defect.getMediaId() + "/thumbnail")
                .createdAt(defect.getCreatedAt())
                .build();
    }
}
