package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.scheduler.InspectionDueNotificationPayload.Kind;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InspectionDueNotificationPayload 순수 단위 테스트(NOTI-01, #425 / kind 확장, #540 ③). 스프링
 * 컨텍스트·mock 불요. Facility.id 는 DB 생성값이라 테스트에서는 ReflectionTestUtils 로 직접 주입한다.
 */
class InspectionDueNotificationPayloadTest {

    private Facility facility(Long id, String name, LocalDate dueAt) {
        Facility f = Facility.builder()
                .companyId(1L)
                .name(name)
                .type("BUILDING")
                .nextInspectionDueAt(dueAt)
                .build();
        ReflectionTestUtils.setField(f, "id", id);
        return f;
    }

    @Test
    @DisplayName("serialize 는 facilityId/facilityName/nextInspectionDueAt/kind 필드를 담는다")
    void serialize_필드포함() {
        String json = InspectionDueNotificationPayload.serialize(
                facility(7L, "강남빌딩", LocalDate.of(2026, 7, 21)), Kind.DUE);

        assertThat(json)
                .contains("\"facilityId\":7")
                .contains("\"facilityName\":\"강남빌딩\"")
                .contains("\"nextInspectionDueAt\":\"2026-07-21\"")
                .contains("\"kind\":\"DUE\"");
    }

    @Test
    @DisplayName("serialize 는 OVERDUE kind도 그대로 담는다")
    void serialize_overdueKind포함() {
        String json = InspectionDueNotificationPayload.serialize(
                facility(7L, "강남빌딩", LocalDate.of(2026, 7, 21)), Kind.OVERDUE);

        assertThat(json).contains("\"kind\":\"OVERDUE\"");
    }

    @Test
    @DisplayName("extractFacilityId 는 정상 payload 에서 올바른 값을 추출한다")
    void extractFacilityId_정상추출() {
        String json = InspectionDueNotificationPayload.serialize(
                facility(42L, "테스트시설", LocalDate.of(2026, 7, 21)), Kind.DUE);

        assertThat(InspectionDueNotificationPayload.extractFacilityId(json)).isEqualTo(42L);
    }

    @Test
    @DisplayName("substring 오탐 회귀: facilityId=1 과 facilityId=10 을 구분한다")
    void extractFacilityId_1과10구분() {
        // "facilityId":1 은 "facilityId":10 의 부분 문자열이라 contains() 매칭이면 오탐한다 — 구조적 파싱은 정확히 구분.
        assertThat(InspectionDueNotificationPayload.extractFacilityId("{\"facilityId\":1}")).isEqualTo(1L);
        assertThat(InspectionDueNotificationPayload.extractFacilityId("{\"facilityId\":10}")).isEqualTo(10L);
    }

    @Test
    @DisplayName("null·파싱실패·필드없음 payload 는 null 을 반환한다(예외 없음)")
    void extractFacilityId_실패시null() {
        assertThat(InspectionDueNotificationPayload.extractFacilityId(null)).isNull();
        assertThat(InspectionDueNotificationPayload.extractFacilityId("")).isNull();
        assertThat(InspectionDueNotificationPayload.extractFacilityId("{invalid json")).isNull();
        assertThat(InspectionDueNotificationPayload.extractFacilityId("{\"other\":1}")).isNull();
    }

    @Test
    @DisplayName("extractDedupeKey 는 정상 payload 에서 facilityId|nextInspectionDueAt|kind 키를 추출한다")
    void extractDedupeKey_정상추출() {
        String json = InspectionDueNotificationPayload.serialize(
                facility(7L, "강남빌딩", LocalDate.of(2026, 7, 21)), Kind.DUE);

        assertThat(InspectionDueNotificationPayload.extractDedupeKey(json)).isEqualTo("7|2026-07-21|DUE");
    }

    @Test
    @DisplayName("dedupeKeyOf(Facility, Kind) 는 serialize/extract 와 동일 형식의 키를 만든다")
    void dedupeKeyOf_serialize와일치() {
        Facility f = facility(42L, "테스트시설", LocalDate.of(2026, 7, 21));

        String fromFacility = InspectionDueNotificationPayload.dedupeKeyOf(f, Kind.DUE);
        String fromPayload = InspectionDueNotificationPayload.extractDedupeKey(
                InspectionDueNotificationPayload.serialize(f, Kind.DUE));

        assertThat(fromFacility).isEqualTo("42|2026-07-21|DUE");
        assertThat(fromFacility).isEqualTo(fromPayload);
    }

    @Test
    @DisplayName("같은 시설물·같은 도래일이어도 kind가 다르면 dedupe 키가 다르다(#540 ③ — DUE와 OVERDUE는 별개 알림)")
    void dedupeKeyOf_kind다르면키도다르다() {
        Facility f = facility(42L, "테스트시설", LocalDate.of(2026, 7, 21));

        String dueKey = InspectionDueNotificationPayload.dedupeKeyOf(f, Kind.DUE);
        String overdueKey = InspectionDueNotificationPayload.dedupeKeyOf(f, Kind.OVERDUE);

        assertThat(dueKey).isNotEqualTo(overdueKey);
    }

    @Test
    @DisplayName("하위호환: kind 필드가 없는 구(舊) payload는 DUE로 취급한다(#540 이전 저장분 롤아웃 중복방지)")
    void extractDedupeKey_kind필드없으면DUE로취급() {
        // #540 이전에는 {facilityId, facilityName, nextInspectionDueAt} 만 저장했다(kind 없음).
        String legacyPayload = "{\"facilityId\":7,\"facilityName\":\"강남빌딩\",\"nextInspectionDueAt\":\"2026-07-21\"}";

        assertThat(InspectionDueNotificationPayload.extractDedupeKey(legacyPayload)).isEqualTo("7|2026-07-21|DUE");
    }

    @Test
    @DisplayName("substring 오탐 회귀: 1|날짜|DUE 와 10|날짜|DUE 를 구분한다(도래일 포함 키라 오탐 위험 더 큼)")
    void extractDedupeKey_유사키구분() {
        // "1|2026-07-21|DUE" 는 "10|2026-07-21|DUE" 의 부분 문자열이라 contains() 매칭이면 오탐 — 구조적 파싱은 정확히 구분.
        String k1 = InspectionDueNotificationPayload.extractDedupeKey(
                "{\"facilityId\":1,\"nextInspectionDueAt\":\"2026-07-21\",\"kind\":\"DUE\"}");
        String k10 = InspectionDueNotificationPayload.extractDedupeKey(
                "{\"facilityId\":10,\"nextInspectionDueAt\":\"2026-07-21\",\"kind\":\"DUE\"}");

        assertThat(k1).isEqualTo("1|2026-07-21|DUE");
        assertThat(k10).isEqualTo("10|2026-07-21|DUE");
        assertThat(k1).isNotEqualTo(k10);
    }

    @Test
    @DisplayName("extractDedupeKey 는 null·파싱실패·필드누락(facilityId 또는 도래일) 시 null 을 반환한다")
    void extractDedupeKey_실패시null() {
        assertThat(InspectionDueNotificationPayload.extractDedupeKey(null)).isNull();
        assertThat(InspectionDueNotificationPayload.extractDedupeKey("")).isNull();
        assertThat(InspectionDueNotificationPayload.extractDedupeKey("{invalid json")).isNull();
        // facilityId 만 있고 도래일 없음 → null
        assertThat(InspectionDueNotificationPayload.extractDedupeKey("{\"facilityId\":1}")).isNull();
        // 도래일만 있고 facilityId 없음 → null
        assertThat(InspectionDueNotificationPayload.extractDedupeKey(
                "{\"nextInspectionDueAt\":\"2026-07-21\"}")).isNull();
    }
}