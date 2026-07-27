package com.hajacheck.core.facility.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.global.exception.DomainValidationException;
import java.time.LocalDate;

/**
 * INSPECTION_DUE 알림 payload 직렬화/역파싱 유틸(NOTI-01, #425 / 알림설정 게이팅, #540 ③).
 *
 * <p>{@code {facilityId, facilityName, nextInspectionDueAt, kind}} 형태의 저장용 JSON을 만들고,
 * 배치가 멱등성 체크에 쓸 수 있도록 payload에서 dedupe 키({@code facilityId|nextInspectionDueAt|kind})를
 * 다시 뽑아낸다. MAPPER는 {@code JavaTimeModule}이 없으므로 {@link LocalDate}를 직접 넣지 않고
 * {@code toString()}으로 미리 String 변환해 record에 담는다
 * (GroundingReportContentSerializer 의 plain ObjectMapper 패턴과 동일).
 *
 * <p>{@link Kind}는 #540 ③(알림설정)에서 추가됐다 — 사전 알림({@link Kind#DUE}, 예정일 도래 전~당일)과
 * 경과 알림({@link Kind#OVERDUE}, 예정일 경과 후)을 같은 (facilityId, nextInspectionDueAt) 조합에 대해
 * 서로 다른 멱등 키로 취급해야, 한쪽을 이미 보냈다고 다른 쪽까지 스킵되는 회귀가 생기지 않는다.
 * ⚠️ 하위호환: #540 이전에 저장된 payload는 {@code kind} 필드가 없다 — {@link #extractDedupeKey}는
 * 그 경우 {@link Kind#DUE}로 취급해, 이미 발행된 "도래일 당일" 알림이 롤아웃 직후 중복 재발행되지 않게 한다.
 */
public final class InspectionDueNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InspectionDueNotificationPayload() {
    }

    /** 알림 종류 — 예정일 도래 전~당일(DUE) vs 예정일 경과 후(OVERDUE, #540 ③ 신규). */
    public enum Kind {
        DUE,
        OVERDUE
    }

    public static String serialize(Facility facility, Kind kind) {
        if (facility == null) {
            throw new DomainValidationException("INSPECTION_DUE 알림 payload 대상 시설물은 필수다");
        }
        if (kind == null) {
            throw new DomainValidationException("INSPECTION_DUE 알림 payload 종류(kind)는 필수다");
        }
        LocalDate dueAt = facility.getNextInspectionDueAt();
        Payload payload = new Payload(
                facility.getId(),
                facility.getName(),
                dueAt == null ? null : dueAt.toString(),
                kind.name());
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
     * payload에서 멱등성 dedupe 키({@code facilityId|nextInspectionDueAt|kind})를 구조적으로 파싱해
     * 반환한다. "이 시설물의 <b>현재 도래일</b>로 이 종류(kind)의 알림이 이미 발행됐는가"를 판정하는 데
     * 쓰이며, 도래일 값이 바뀌지 않는 한(=재스케줄 전까지) 같은 종류의 알림이 매일 재발행되는 스팸을 막는다.
     * facilityId 또는 nextInspectionDueAt가 없거나 파싱 실패 시 null(예외 없음 — 배치 중단 금지).
     *
     * <p>⚠️ {@code kind} 필드가 없는 구(舊) payload(#540 이전 저장분)는 {@link Kind#DUE}로 취급한다
     * (하위호환 — 클래스 상단 javadoc 참고).
     *
     * <p>⚠️ 문자열 {@code contains()} 매칭 금지 — facilityId뿐 아니라 도래일까지 합친 키라 substring 오탐
     * 위험이 더 크다({@code 1|2026-07-21|DUE}가 {@code 10|2026-07-21|DUE}의 부분 문자열). 반드시 JSON
     * 트리로 파싱한다.
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
            JsonNode kindNode = node.get("kind");
            String kind = (kindNode != null && kindNode.isTextual() && !kindNode.asText().isBlank())
                    ? kindNode.asText()
                    : Kind.DUE.name();
            return dedupeKey(idNode.asLong(), dueNode.asText(), kind);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * {@link Facility}로부터 {@link #extractDedupeKey}와 동일 형식
     * ({@code facilityId|nextInspectionDueAt|kind})의 dedupe 키를 만든다. {@link #serialize}가
     * payload에 담는 조합과 정확히 일치해야 비교가 성립한다. id 또는 도래일이 없으면 null.
     */
    public static String dedupeKeyOf(Facility facility, Kind kind) {
        if (facility == null || kind == null) {
            return null;
        }
        Long id = facility.getId();
        LocalDate dueAt = facility.getNextInspectionDueAt();
        if (id == null || dueAt == null) {
            return null;
        }
        return dedupeKey(id, dueAt.toString(), kind.name());
    }

    private static String dedupeKey(long facilityId, String nextInspectionDueAt, String kind) {
        return facilityId + "|" + nextInspectionDueAt + "|" + kind;
    }

    private record Payload(Long facilityId, String facilityName, String nextInspectionDueAt, String kind) {
    }
}