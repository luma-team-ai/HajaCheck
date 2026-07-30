package com.hajacheck.core.analysis.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;

/**
 * ANALYSIS_DONE/REVIEW_PENDING 알림 payload 직렬화 유틸(NOTI-01 나머지, #494/#495) — 두 알림은
 * 같은 전이 지점({@code Inspection}이 {@code ANALYZED}로 바뀌는 순간)에서 함께 발행되고 같은 대상
 * 회차를 가리키므로 {@code {inspectionId, description}} 형태의 payload 하나를 공유한다(수신자만
 * createdBy/assignedInspectorId로 다르다 — {@link com.hajacheck.core.analysis.service.InspectionAnalysisWorker}
 * 참고). INSPECTION_DUE와 달리 배치 멱등성 체크 대상이 아니라(전이 시점 1회성 이벤트) dedupe 키
 * 추출은 없다 — CounselReplyNotificationPayload와 동일한 최소 구성.
 */
public final class InspectionAnalysisNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InspectionAnalysisNotificationPayload() {
    }

    /**
     * @param roundNo 알림센터 부제목(#1233 패턴 재사용) — "{roundNo}회차" 형태로 표시한다. null이면
     *                description 없이 inspectionId만 담는다(방어적 — 실제 호출부는 항상 값을 넘긴다).
     */
    public static String serialize(Long inspectionId, Integer roundNo) {
        if (inspectionId == null) {
            throw new DomainValidationException("ANALYSIS_DONE/REVIEW_PENDING 알림 payload 대상 점검 회차는 필수다");
        }
        String description = roundNo == null ? null : roundNo + "회차";
        try {
            return MAPPER.writeValueAsString(new Payload(inspectionId, description));
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("ANALYSIS_DONE/REVIEW_PENDING 알림 payload를 직렬화할 수 없다");
        }
    }

    private record Payload(Long inspectionId, String description) {
    }
}
