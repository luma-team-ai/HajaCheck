package com.hajacheck.membership.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.service.PlanExpiryResult;

/**
 * PLAN_EXPIRED 알림 payload 직렬화(#1145 / HAJA-549).
 *
 * <p>{@code {previousPlanName, newPlanName, periodEndAt, suspendedSeatCount}} 형태의 저장용 JSON 을
 * 만든다. MAPPER 에 {@code JavaTimeModule} 이 없으므로 시각은 {@code toString()} 으로 미리 문자열로
 * 변환해 record 에 담는다({@code InspectionDueNotificationPayload} 와 동일한 plain ObjectMapper 패턴).
 *
 * <p>개인정보(이메일·사업자번호 등)는 담지 않는다 — 화면이 필요로 하는 건 "어느 플랜에서 언제 내려갔고
 * 좌석이 몇 개 정지됐는가"뿐이다.
 */
public final class PlanExpiredNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanExpiredNotificationPayload() {
    }

    public static String serialize(PlanExpiryResult result) {
        if (result == null || !result.downgraded()) {
            throw new DomainValidationException("PLAN_EXPIRED 알림 payload 대상은 강등된 구독이어야 한다");
        }
        Payload payload = new Payload(
                result.previousPlanName() == null ? null : result.previousPlanName().name(),
                PlanName.FREE.name(),
                result.periodEndAt() == null ? null : result.periodEndAt().toString(),
                result.suspendedSeatCount());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("PLAN_EXPIRED 알림 payload를 직렬화할 수 없다");
        }
    }

    private record Payload(String previousPlanName, String newPlanName, String periodEndAt,
            int suspendedSeatCount) {
    }
}
