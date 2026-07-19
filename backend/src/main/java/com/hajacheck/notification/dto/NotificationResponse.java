package com.hajacheck.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
