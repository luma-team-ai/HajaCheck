package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * detailUrl(#788)은 분석 결과 뷰어 전용 — 그리드용 썸네일(400px 상한)로는 크랙 폭 같은 하자를
 * 육안으로 판별하기 어려워 더 큰 해상도의 {@code /api/media/{id}/detail}을 가리킨다.
 *
 * <p>areaMm2(#1658/#1668)는 crackWidthMm과 동일하게 AI 서버가 카드 기준물로 환산해 내려준 결함
 * 실측 면적(mm²)이다 — SPALLING/REBAR_EXPOSURE만 값이 있을 수 있고, 카드 미검출이거나 CRACK
 * 타입이면 null.
 *
 * <p>areaMm2ReferenceGrade(#1682/#1683)는 areaMm2 기반으로 AI 서버가 산출한 mm² 참고 등급(A~E)이다 —
 * areaMm2가 없으면 null. 본등급 grade와 무관·불변인 참고값이라 enum 변환 없이 문자열 그대로 노출한다.
 */
// 전 필드 required — @JsonInclude 를 쓰지 않으므로 Jackson 이 값이 null 이어도 키는 항상 내보낸다.
// OpenApiConfig 의 ModelConverter 는 record 만 승격하는데 이 DTO 는 class 라 대상 밖이어서, 여기서
// 직접 명시한다(필드마다 @Schema 를 다는 대신 클래스 레벨 한 줄). 필드 추가 시 여기도 같이 추가할 것.
// reviewed 는 @JsonProperty("isReviewed") 로 나가므로 JSON 키 이름으로 적는다.
@Schema(requiredProperties = {
        "id", "inspectionId", "type", "typeLabel", "grade", "status", "confidence", "isReviewed",
        "bboxX", "bboxY", "bboxW", "bboxH", "crackWidthMm", "crackLengthMm", "areaRatio", "areaMm2",
        "areaMm2ReferenceGrade", "mediaId", "imageUrl", "detailUrl", "createdAt"})
@Getter
@Builder
public class DefectDetailItem {
    private Long id;
    private Long inspectionId;
    private DefectType type;
    private String typeLabel;
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
    private Double areaRatio;
    private Double areaMm2;
    private String areaMm2ReferenceGrade;
    private Long mediaId;
    private String imageUrl;
    private String detailUrl;
    private LocalDateTime createdAt;

    public static DefectDetailItem from(Defect defect) {
        return DefectDetailItem.builder()
                .id(defect.getId())
                .inspectionId(defect.getInspectionId())
                .type(defect.getType())
                .typeLabel(defect.getType().label())
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
                .areaRatio(defect.getAreaRatio())
                .areaMm2(defect.getAreaMm2())
                .areaMm2ReferenceGrade(defect.getAreaMm2ReferenceGrade())
                .mediaId(defect.getMediaId())
                .imageUrl(defect.getMediaId() == null ? null : "/api/media/" + defect.getMediaId() + "/thumbnail")
                .detailUrl(defect.getMediaId() == null ? null : "/api/media/" + defect.getMediaId() + "/detail")
                .createdAt(defect.getCreatedAt())
                .build();
    }
}
