package com.hajacheck.core.facility.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.global.exception.DomainValidationException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * INSPECTION_DUE 알림 payload 직렬화/역파싱 유틸(NOTI-01, #425 / 알림설정 게이팅, #540 ③ /
 * 알림센터 부제목, #1233).
 *
 * <p>{@code {facilityId, facilityName, nextInspectionDueAt, kind, description}} 형태의 저장용
 * JSON을 만들고, 배치가 멱등성 체크에 쓸 수 있도록 payload에서 dedupe 키
 * ({@code facilityId|nextInspectionDueAt|kind})를 다시 뽑아낸다. MAPPER는 {@code JavaTimeModule}이
 * 없으므로 {@link LocalDate}를 직접 넣지 않고 {@code toString()}으로 미리 String 변환해 record에 담는다
 * (GroundingReportContentSerializer 의 plain ObjectMapper 패턴과 동일).
 *
 * <p>{@link Kind}는 #540 ③(알림설정)에서 3종으로 확장됐다(사람검수 P2 #1032) — 사전 알림
 * ({@link Kind#BEFORE}, 예정일 도래 전 창 안), 당일 알림({@link Kind#DUE}, 예정일 == 오늘),
 * 경과 알림({@link Kind#OVERDUE}, 예정일 경과 후)을 같은 (facilityId, nextInspectionDueAt) 조합에
 * 대해서도 서로 다른 멱등 키로 취급해야, 한쪽을 이미 보냈다고 다른 쪽까지 스킵되는 회귀가 생기지 않는다.
 * ⚠️ 하위호환: #540 이전에 저장된 payload는 {@code kind} 필드가 없다 — {@link #extractDedupeKey}는
 * 그 경우 {@link Kind#DUE}와 {@link Kind#OVERDUE} 키를 모두 반환한다(사람검수 P2 #1032). 구 스케줄러는
 * "dueAt &lt;= 오늘"이면 당일/연체 구분 없이 발행했으므로 어느 kind로 재조회되든 매칭돼야 하기 때문 —
 * DUE 하나만 반환하면(#540 최초 구현의 버그) 연체(OVERDUE) 상태로 재조회되는 시설물의 구 발행 이력을
 * 못 찾아 중복 재발행됐다.
 */
public final class InspectionDueNotificationPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InspectionDueNotificationPayload() {
    }

    /**
     * 알림 종류(#540 ③, 3종 확장은 사람검수 P2 #1032) — 예정일 도래 전 창 안(BEFORE), 예정일 당일(DUE),
     * 예정일 경과 후(OVERDUE). BEFORE/DUE를 분리한 이유: dedupe 키가 dueAt 기준으로 도래일당 1회만
     * 허용하는데, 사전창 전체(D-notifyBeforeDays~D-day)를 같은 Kind.DUE로 묶으면 D-7에 1회 발행된 뒤
     * D-day에도 같은 키라 스킵돼 "당일 발행"이라는 기존 프로덕션 동작이 사라지는 회귀가 있었다.
     */
    public enum Kind {
        BEFORE,
        DUE,
        OVERDUE
    }

    /**
     * @param today 알림센터 부제목(description)의 D-day 문구 계산 기준일 — 스케줄러가 배치 전체에
     *              쓰는 {@code Clock} 기반 {@code today}를 그대로 넘겨받는다(#1233). 이 메서드 안에서
     *              {@code LocalDate.now()}를 새로 부르면 배치의 kind 판정 기준일과 어긋날 수 있어
     *              (자정 근처 실행 시) 호출부의 today를 그대로 재사용한다.
     */
    public static String serialize(Facility facility, Kind kind, LocalDate today) {
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
                kind.name(),
                describe(facility.getName(), dueAt, today));
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("INSPECTION_DUE 알림 payload를 직렬화할 수 없다");
        }
    }

    /**
     * "{시설물명} D-{n}" 형태의 알림센터 부제목(#1233, Figma node-id 208-2458). D-day 라벨 포맷은
     * frontend {@code inspectionCycleStatus.ts}/{@code UpcomingInspectionCard.tsx}와 동일하게
     * 맞춘다(D-DAY/D-n/D+n) — 프론트가 별도 계산 없이 그대로 표시만 하므로 여기서 포맷을 통일해야 한다.
     */
    private static String describe(String facilityName, LocalDate dueAt, LocalDate today) {
        if (dueAt == null || today == null) {
            return facilityName;
        }
        long diffDays = ChronoUnit.DAYS.between(today, dueAt);
        String dDayLabel = diffDays == 0 ? "D-DAY" : diffDays > 0 ? "D-" + diffDays : "D+" + Math.abs(diffDays);
        return facilityName + " " + dDayLabel;
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
     * payload에서 멱등성 dedupe 키({@code facilityId|nextInspectionDueAt|kind}) 후보 전체를 구조적으로
     * 파싱해 반환한다. "이 시설물의 <b>현재 도래일</b>로 이 알림이 이미 발행됐는가"를 판정하는 데 쓰이며,
     * 도래일 값이 바뀌지 않는 한(=재스케줄 전까지) 같은 종류의 알림이 매일 재발행되는 스팸을 막는다.
     * facilityId 또는 nextInspectionDueAt가 없거나 파싱 실패 시 빈 Set(예외 없음 — 배치 중단 금지).
     *
     * <p>⚠️ {@code kind} 필드가 있는 payload(#540 이후)는 그 kind 하나만 담은 단일 원소 Set을 반환한다.
     * {@code kind} 필드가 없는 구(舊) payload(#540 이전 저장분)는 {@link Kind#DUE}와
     * {@link Kind#OVERDUE} 두 키를 모두 반환한다(사람검수 P2 #1032, 하위호환 — 클래스 상단 javadoc
     * 참고) — 구 스케줄러는 dueAt&lt;=오늘이면 당일/연체 구분 없이 발행했으므로, 재조회 시점에 그
     * 시설물이 어느 kind로 판정되든(당일이었을 수도, 그 사이 연체로 넘어갔을 수도) 매칭돼야 한다.
     *
     * <p>⚠️ 문자열 {@code contains()} 매칭 금지 — facilityId뿐 아니라 도래일까지 합친 키라 substring 오탐
     * 위험이 더 크다({@code 1|2026-07-21|DUE}가 {@code 10|2026-07-21|DUE}의 부분 문자열). 반드시 JSON
     * 트리로 파싱한다.
     */
    public static Set<String> extractDedupeKey(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            JsonNode idNode = node.get("facilityId");
            JsonNode dueNode = node.get("nextInspectionDueAt");
            if (idNode == null || idNode.isNull() || !idNode.canConvertToLong()) {
                return Set.of();
            }
            if (dueNode == null || dueNode.isNull() || !dueNode.isTextual()) {
                return Set.of();
            }
            long facilityId = idNode.asLong();
            String dueAt = dueNode.asText();
            JsonNode kindNode = node.get("kind");
            if (kindNode != null && kindNode.isTextual() && !kindNode.asText().isBlank()) {
                return Set.of(dedupeKey(facilityId, dueAt, kindNode.asText()));
            }
            // kind 필드 없는 구 payload — DUE/OVERDUE 두 키 모두 반환(위 javadoc 참고).
            return Set.of(
                    dedupeKey(facilityId, dueAt, Kind.DUE.name()),
                    dedupeKey(facilityId, dueAt, Kind.OVERDUE.name()));
        } catch (JsonProcessingException e) {
            return Set.of();
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

    private record Payload(
            Long facilityId, String facilityName, String nextInspectionDueAt, String kind, String description) {
    }
}