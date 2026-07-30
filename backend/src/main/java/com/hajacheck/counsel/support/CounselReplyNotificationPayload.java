package com.hajacheck.counsel.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;

/**
 * COUNSEL_REPLIED 알림 payload 직렬화 유틸(NOTI-01 나머지, #493 / 알림센터 부제목, #1233) —
 * {@code {ticketId, description}} 형태의 저장용 JSON을 만든다. INSPECTION_DUE와 달리 배치 멱등성
 * 체크 대상이 아니라(메시지 발신마다 1회성 이벤트) dedupe 키 추출은 없다 —
 * {@code InspectionDueNotificationPayload}의 직렬화 부분만 재사용한 최소 구성.
 */
public final class CounselReplyNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CounselReplyNotificationPayload() {
    }

    /**
     * @param description 알림센터 부제목(#1233, Figma node-id 208-2458) — 상담 티켓의 진입 시나리오
     *                     제목({@code CounselTicket.title}, 예: "요금제 문의")을 호출부에서 그대로 넘긴다.
     */
    public static String serialize(Long ticketId, String description) {
        if (ticketId == null) {
            throw new DomainValidationException("COUNSEL_REPLIED 알림 payload 대상 티켓은 필수다");
        }
        try {
            return MAPPER.writeValueAsString(new Payload(ticketId, description));
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("COUNSEL_REPLIED 알림 payload를 직렬화할 수 없다");
        }
    }

    private record Payload(Long ticketId, String description) {
    }
}
