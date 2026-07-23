package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * INSPECTION_DUE 멱등성 dedupe 키가 <b>실제 PostgreSQL jsonb 저장 왕복</b>을 거친 뒤에도
 * {@link InspectionDueNotificationPayload#dedupeKeyOf}와 정확히 일치하는지 고정한다(NOTI-01, #425).
 *
 * <p>jsonb는 저장 시 공백 제거·키 재정렬 등 정규화를 하므로, mock/직접 재파싱으로는 이 계약이 증명되지 않는다.
 * 실제 저장 → {@code em.clear()}로 영속성 컨텍스트 캐시 우회 → DB 재조회한 payload로 검증한다. 이 계약이 깨지면
 * overdue 매일 중복 발행 스팸이 조용히 재발하므로 회귀로 고정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class InspectionDueNotificationDedupeJsonbRoundTripTest extends PostgresTestSupport {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("dedupe 키는 실제 jsonb 저장→재조회 후에도 dedupeKeyOf(facility)와 정확히 같다")
    void dedupeKey_jsonb저장왕복후에도_dedupeKeyOf와일치() {
        // notifications.user_id 는 users FK 이므로 owner User 를 먼저 시드한다.
        User owner = User.createCompanyOwner("dedupe@haja.com", "소유자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        em.flush();

        Facility facility = Facility.builder()
                .companyId(owner.getId())
                .name("강남빌딩")
                .type("BUILDING")
                .nextInspectionDueAt(LocalDate.of(2026, 7, 21))
                .build();
        ReflectionTestUtils.setField(facility, "id", 10L);

        String expectedKey = InspectionDueNotificationPayload.dedupeKeyOf(facility);
        String payload = InspectionDueNotificationPayload.serialize(facility);

        Notification saved = notificationRepository.save(
                Notification.create(owner.getId(), NotificationType.INSPECTION_DUE, payload));
        em.flush();
        em.clear(); // 1차 캐시 우회 — 이후 findById 는 DB(jsonb 정규화 결과)에서 다시 읽는다.

        Notification reread = notificationRepository.findById(saved.getId()).orElseThrow();

        assertThat(InspectionDueNotificationPayload.extractDedupeKey(reread.getPayloadJson()))
                .isEqualTo(expectedKey)
                .isEqualTo("10|2026-07-21");
    }
}
