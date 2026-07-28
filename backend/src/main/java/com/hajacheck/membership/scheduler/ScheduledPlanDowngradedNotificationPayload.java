package com.hajacheck.membership.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.membership.service.ScheduledPlanChangeResult;

/**
 * PLAN_DOWNGRADED 알림 payload 직렬화(#1105 / HAJA-526).
 *
 * <p>{@code {previousPlanName, newPlanName, effectiveAt, suspendedSeatCount}} 형태의 저장용 JSON 을
 * 만든다. MAPPER 에 {@code JavaTimeModule} 이 없으므로 시각은 {@code toString()} 으로 미리 문자열로
 * 변환해 record 에 담는다({@link PlanExpiredNotificationPayload} 와 동일한 plain ObjectMapper 패턴).
 *
 * <p>개인정보(이메일·사업자번호 등)는 담지 않는다 — 화면이 필요로 하는 건 "어느 플랜에서 어디로 언제
 * 내려갔고 좌석이 몇 개 정지됐는가"뿐이다. 정지된 <b>구성원 id</b>도 담지 않는다: 알림은 신청자에게
 * 보이는 화면 데이터라 운영 로그(복구용 id 목록)와 목적이 다르다.
 */
public final class ScheduledPlanDowngradedNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledPlanDowngradedNotificationPayload() {
    }

    public static String serialize(ScheduledPlanChangeResult result) {
        if (result == null || !result.applied()) {
            throw new DomainValidationException("PLAN_DOWNGRADED 알림 payload 대상은 적용된 예약이어야 한다");
        }
        Payload payload = new Payload(
                result.previousPlanName() == null ? null : result.previousPlanName().name(),
                result.targetPlanName() == null ? null : result.targetPlanName().name(),
                result.effectiveAt() == null ? null : result.effectiveAt().toString(),
                result.suspendedSeatCount());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("PLAN_DOWNGRADED 알림 payload를 직렬화할 수 없다");
        }
    }

    private record Payload(String previousPlanName, String newPlanName, String effectiveAt,
            int suspendedSeatCount) {
    }
}
