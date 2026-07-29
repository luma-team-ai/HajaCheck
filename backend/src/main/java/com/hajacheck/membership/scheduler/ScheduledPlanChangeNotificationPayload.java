package com.hajacheck.membership.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.service.ScheduledPlanChangeFailure;
import com.hajacheck.membership.service.ScheduledPlanChangeResult;

/**
 * 예약 하향 알림 payload 직렬화(#1105 / HAJA-526) — 적용(PLAN_DOWNGRADED)과 실패(PLAN_DOWNGRADE_FAILED)
 * 두 유형을 같은 규칙으로 만든다.
 *
 * <p>MAPPER 에 {@code JavaTimeModule} 이 없으므로 시각은 {@code toString()} 으로 미리 문자열로 변환해
 * record 에 담는다({@link PlanExpiredNotificationPayload} 와 동일한 plain ObjectMapper 패턴).
 *
 * <p>개인정보(이메일·사업자번호 등)는 담지 않는다 — 화면이 필요로 하는 건 "어느 플랜에서 어디로 언제
 * 내려갔고 좌석이 몇 개 정지됐는가"뿐이다. 정지된 <b>구성원 id</b>도 담지 않는다: 알림은 신청자에게
 * 보이는 화면 데이터라 운영 로그(복구용 id 목록)와 목적이 다르다.
 */
public final class ScheduledPlanChangeNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledPlanChangeNotificationPayload() {
    }

    /** 예약이 예정대로 적용됐을 때(PLAN_DOWNGRADED). */
    public static String serializeApplied(ScheduledPlanChangeResult result) {
        if (result == null || !result.applied()) {
            throw new DomainValidationException("PLAN_DOWNGRADED 알림 payload 대상은 적용된 예약이어야 한다");
        }
        return write(new AppliedPayload(
                result.previousPlanName() == null ? null : result.previousPlanName().name(),
                result.targetPlanName() == null ? null : result.targetPlanName().name(),
                result.effectiveAt() == null ? null : result.effectiveAt().toString(),
                result.suspendedSeatCount()));
    }

    /**
     * 예약이 FAILED 로 종료됐을 때(PLAN_DOWNGRADE_FAILED, 리뷰 P2-5) — FAILED 는 종료 상태라 재시도가
     * 없고 조회는 PENDING 만 노출하므로, 이 알림이 없으면 신청자는 예약이 사라진 사실을 영원히 모른다.
     *
     * <p>{@code failureReason} 은 스케줄러가 만든 <b>ErrorCode 이름</b>이다 — 예외 메시지 원문을 싣지
     * 않는다(무결성 위반 메시지에는 위반 컬럼의 실제 값이 들어 있어 개인정보가 샐 수 있다).
     */
    public static String serializeFailed(ScheduledPlanChangeFailure failure, String failureReason) {
        if (failure == null || !failure.marked()) {
            throw new DomainValidationException(
                    "PLAN_DOWNGRADE_FAILED 알림 payload 대상은 실패로 종료된 예약이어야 한다");
        }
        PlanName targetPlanName = failure.targetPlanName();
        return write(new FailedPayload(
                targetPlanName == null ? null : targetPlanName.name(), failureReason));
    }

    private static String write(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("예약 하향 알림 payload를 직렬화할 수 없다");
        }
    }

    private record AppliedPayload(String previousPlanName, String newPlanName, String effectiveAt,
            int suspendedSeatCount) {
    }

    private record FailedPayload(String targetPlanName, String failureReason) {
    }
}
