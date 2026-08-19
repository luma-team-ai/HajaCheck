package com.hajacheck.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hajacheck.notification.entity.Notification;
import java.time.LocalDateTime;

/**
 * 알림 목록 응답(AP-020, #25 / HAJA-38 FR-9). payload(jsonb)는 이중 이스케이프된 문자열이 아니라
 * 구조화된 JSON 객체로 노출한다 — 이 레포에서 jsonb 문자열을 다루는 확립된 방식(JsonValidator,
 * GroundingCheckTarget.hash())과 동일하게 Jackson {@link JsonNode}로 파싱한다.
 */
public record NotificationResponse(
        Long id,
        String type,
        JsonNode payload,
        @JsonProperty("isRead") boolean read,
        LocalDateTime createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                parsePayload(notification),
                notification.isRead(),
                notification.getCreatedAt());
    }

    /**
     * payload의 {@code description}(알림센터 부제목)만 교체한 사본을 돌려준다(#1706).
     *
     * <p>ANALYSIS_DONE/REVIEW_PENDING 알림은 발행 시점에 "{roundNo}회차"를 문자열로 굳혀 저장하는데,
     * #1702 재정렬로 회차가 밀리면 그 값이 stale해진다(클릭해 들어간 화면의 회차와 어긋남). 그래서
     * {@code NotificationService}가 조회 시점에 현재 회차로 다시 계산해 이 메서드로 덮어쓴다. 프런트는
     * {@code payload.description}을 그대로 렌더링하므로 응답 형태는 바뀌지 않는다.
     *
     * <p>payload가 JSON 객체가 아니거나({@code null} 포함) 덮어쓸 값이 없으면 원본을 그대로 둔다 —
     * 회차 개념이 없는 다른 알림 유형(INSPECTION_DUE·상담 답변·쿼터 등)은 애초에 호출 대상이 아니고,
     * 호출되더라도 payload를 건드리지 않는다.
     */
    public NotificationResponse withDescription(String description) {
        if (description == null || !(payload instanceof ObjectNode object)) {
            return this;
        }
        ObjectNode replaced = object.deepCopy();
        replaced.put("description", description);
        return new NotificationResponse(id, type, replaced, read, createdAt);
    }

    private static JsonNode parsePayload(Notification notification) {
        String payloadJson = notification.getPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            // Notification.create()가 쓰기 시점에 JsonValidator로 이미 검증하므로 정상 경로에서는
            // 도달하지 않는다 — 데이터 손상 등 예외 상황을 안전하게 표면화한다.
            throw new IllegalStateException(
                    "알림 payload가 유효한 JSON이 아닙니다: notificationId=" + notification.getId(), e);
        }
    }
}
