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
 * 배치가 멱등성 체크에 쓸 수 있도록 payload에서 dedupe 키({@code facilityId|nextInspectionDueAt})를 다시 뽑아낸다.
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

    /**
     * payload에서 멱등성 dedupe 키({@code facilityId|nextInspectionDueAt})를 구조적으로 파싱해 반환한다.
     * "이 시설물의 <b>현재 도래일</b>로 이미 INSPECTION_DUE가 발행됐는가"를 판정하는 데 쓰이며, 도래일 값이
     * 바뀌지 않는 한(=재스케줄 전까지) overdue 시설물이 매일 재알림되는 스팸을 막는다. facilityId 또는
     * nextInspectionDueAt가 없거나 파싱 실패 시 null(예외 없음 — 배치 중단 금지).
     *
     * <p>⚠️ 문자열 {@code contains()} 매칭 금지 — facilityId뿐 아니라 도래일까지 합친 키라 substring 오탐
     * 위험이 더 크다({@code 1|2026-07-21}이 {@code 10|2026-07-21}의 부분 문자열). 반드시 JSON 트리로 파싱한다.
     */
    public static String extractDedupeKey(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            JsonNode idNode = node.get("facilityId");
            JsonNode dueNode = node.get("nextInspectionDueAt");
            if (idNode == null || idNode.isNull() || !idNode.canConvertToLong()) {
                return null;
            }
            if (dueNode == null || dueNode.isNull() || !dueNode.isTextual()) {
                return null;
            }
            return dedupeKey(idNode.asLong(), dueNode.asText());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * {@link Facility}로부터 {@link #extractDedupeKey}와 동일 형식({@code facilityId|nextInspectionDueAt})의
     * dedupe 키를 만든다. {@link #serialize}가 payload에 담는 조합과 정확히 일치해야 비교가 성립한다.
     * id 또는 도래일이 없으면 null.
     */
    public static String dedupeKeyOf(Facility facility) {
        if (facility == null) {
            return null;
        }
        Long id = facility.getId();
        LocalDate dueAt = facility.getNextInspectionDueAt();
        if (id == null || dueAt == null) {
            return null;
        }
        return dedupeKey(id, dueAt.toString());
    }

    private static String dedupeKey(long facilityId, String nextInspectionDueAt) {
        return facilityId + "|" + nextInspectionDueAt;
    }

    private record Payload(Long facilityId, String facilityName, String nextInspectionDueAt) {
    }
}
