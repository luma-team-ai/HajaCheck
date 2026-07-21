package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InspectionDueNotificationPayload 순수 단위 테스트(NOTI-01, #425). 스프링 컨텍스트·mock 불요.
 * Facility.id 는 DB 생성값이라 테스트에서는 ReflectionTestUtils 로 직접 주입한다.
 */
class InspectionDueNotificationPayloadTest {

    private Facility facility(Long id, String name, LocalDate dueAt) {
        Facility f = Facility.builder()
                .ownerId(1L)
                .name(name)
                .type("BUILDING")
                .nextInspectionDueAt(dueAt)
                .build();
        ReflectionTestUtils.setField(f, "id", id);
        return f;
    }

    @Test
    @DisplayName("serialize 는 facilityId/facilityName/nextInspectionDueAt 필드를 담는다")
    void serialize_필드포함() {
        String json = InspectionDueNotificationPayload.serialize(
                facility(7L, "강남빌딩", LocalDate.of(2026, 7, 21)));

        assertThat(json)
                .contains("\"facilityId\":7")
                .contains("\"facilityName\":\"강남빌딩\"")
                .contains("\"nextInspectionDueAt\":\"2026-07-21\"");
    }

    @Test
    @DisplayName("extractFacilityId 는 정상 payload 에서 올바른 값을 추출한다")
    void extractFacilityId_정상추출() {
        String json = InspectionDueNotificationPayload.serialize(
                facility(42L, "테스트시설", LocalDate.of(2026, 7, 21)));

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
}
