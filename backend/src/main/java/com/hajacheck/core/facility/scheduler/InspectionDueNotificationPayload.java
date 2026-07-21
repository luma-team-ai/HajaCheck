package com.hajacheck.core.facility.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.global.exception.DomainValidationException;
import java.time.LocalDate;

/**
 * INSPECTION_DUE 알림 payload 직렬화/역파싱 유틸(NOTI-01, #425).
 *
 * <p>{@code {facilityId, facilityName, nextInspectionDueAt}} 형태의 저장용 JSON을 만들고,
 * 배치가 멱등성 체크에 쓸 수 있도록 payload에서 {@code facilityId}만 다시 뽑아낸다.
 * MAPPER는 {@code JavaTimeModule}이 없으므로 {@link LocalDate}를 직접 넣지 않고
 * {@code toString()}으로 미리 String 변환해 record에 담는다
 * (GroundingReportContentSerializer 의 plain ObjectMapper 패턴과 동일).
 */
public final class InspectionDueNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InspectionDueNotificationPayload() {
    }

    public static String serialize(Facility facility) {
        if (facility == null) {
            throw new DomainValidationException("INSPECTION_DUE 알림 payload 대상 시설물은 필수다");
        }
        LocalDate dueAt = facility.getNextInspectionDueAt();
        Payload payload = new Payload(
                facility.getId(),
                facility.getName(),
                dueAt == null ? null : dueAt.toString());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("INSPECTION_DUE 알림 payload를 직렬화할 수 없다");
        }
    }

    /**
     * payload에서 {@code facilityId}를 구조적으로 파싱해 반환한다. 스케줄러의 멱등성 체크에 쓰이며,
     * 한 건 파싱 실패가 배치를 중단시키면 안 되므로 null/파싱 실패 시 예외 대신 null을 반환한다.
     *
     * <p>⚠️ 문자열 {@code contains()} 매칭 금지 — {@code "facilityId":1}이 {@code "facilityId":10}의
     * 부분 문자열이라 facilityId=1 알림이 있으면 10도 있다고 오판한다. 반드시 JSON 트리로 파싱해 비교한다.
     */
    public static Long extractFacilityId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            JsonNode idNode = node.get("facilityId");
            if (idNode == null || idNode.isNull() || !idNode.canConvertToLong()) {
                return null;
            }
            return idNode.asLong();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private record Payload(Long facilityId, String facilityName, String nextInspectionDueAt) {
    }
}
