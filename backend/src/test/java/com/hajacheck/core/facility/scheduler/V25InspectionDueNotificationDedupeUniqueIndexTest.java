package com.hajacheck.core.facility.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * V25 마이그레이션이 추가한 {@code uq_notifications_inspection_due_dedupe} 부분 유니크 인덱스를 실제
 * PostgreSQL로 검증한다(#1050). InspectionDueNotificationScheduler는 이제 조회 없이 바로 발행을 시도하고
 * {@link DataIntegrityViolationException}이 나면 skip으로 처리하는데(insert-then-catch), 이 계약이
 * 실제로 성립하려면 (1) 같은 (user_id, facilityId, dueAt, kind) 재삽입이 정말 unique violation을
 * 던지는지, (2) kind가 다르면 같은 facilityId·dueAt이어도 통과하는지, (3) kind가 NULL인 레거시 행은
 * 이 인덱스로 못 잡는지(문서화된 사각지대 — 그래서 스케줄러에 별도 레거시 체크가 남아 있다)를 실제 DB로
 * 증명해야 한다. Mockito 단위 테스트(InspectionDueNotificationSchedulerTest)는 스케줄러가 예외를 올바로
 * 처리하는지만 검증할 뿐 이 제약 자체를 증명하지 못한다.
 *
 * <p>이 클래스는 {@link PostgresTestSupport}를 확장하지만, 그 기반 컨테이너는 Flyway가 아니라 canonical
 * DDL({@code docs/design/db/HajaCheck_script.sql})로 초기화된다({@code spring.flyway.enabled=false}가
 * 테스트 프로파일 기본값) — 그래서 이 인덱스가 실제로 존재하려면 그 canonical DDL에도 V25와 동일한
 * 인덱스 정의를 추가해둬야 한다(반영 완료, HajaCheck_script.sql 참고). 두 파일이 어긋나면 이 테스트가
 * "인덱스가 없어서" 조용히 실패한다(#1050 구현 중 실제로 겪음 — canonical DDL 미동기화로 최초 실행 시
 * unique violation이 안 던져졌다).
 *
 * <p>"동시" 요청 시나리오는 순차적으로 같은 키를 두 번 저장 시도하는 것으로 재현한다 — DB 유니크 인덱스는
 * 동시/순차 여부와 무관하게 동일한 원자적 보장을 제공하므로(그것이 이 설계로 전환한 핵심 이유), 순차
 * 재현만으로도 동시 요청 안전성의 근거가 충분하다. 진짜 멀티스레드 재현은 별도 커넥션·트랜잭션 동기화가
 * 필요해 이 계약 증명에 필수적이지 않은 복잡도를 더한다(과설계 방지).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class V25InspectionDueNotificationDedupeUniqueIndexTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private User seedUser(String emailPrefix) {
        return userRepository.saveAndFlush(User.createCompanyOwner(
                emailPrefix + "-" + UUID.randomUUID() + "@haja.test", "V25테스트유저",
                "<password-hash-placeholder>"));
    }

    @Test
    void 같은_facilityId_dueAt_kind로_두번째저장시도는_unique_violation을던진다() {
        User user = seedUser("v25-dup");
        String payload = "{\"facilityId\":1,\"facilityName\":\"시설A\",\"nextInspectionDueAt\":\"2026-07-21\","
                + "\"kind\":\"DUE\"}";

        notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, payload));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, payload)))
                .isInstanceOf(DataIntegrityViolationException.class)
                // InspectionDueNotificationScheduler의 skip 판정은 "예외 메시지에 인덱스명이 담긴다"는
                // 문자열 계약에 전적으로 의존한다(#1050). 그 계약을 실 PostgreSQL 예외 체인으로 고정한다 —
                // 드라이버·Hibernate 업그레이드로 메시지 포맷이 바뀌면 여기서 먼저 깨져야, 운영에서
                // "이미 발행된 전 시설물이 skipped 대신 failed로 집계되고 warn이 매일 쏟아지는" 회귀를 막는다.
                .extracting(e -> ((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
                .asString()
                .contains(InspectionDueNotificationScheduler.DEDUPE_UNIQUE_INDEX_NAME);
    }

    @Test
    void kind가다르면_같은_facilityId_dueAt이어도_모두저장된다() {
        User user = seedUser("v25-kind-differs");
        String before = "{\"facilityId\":2,\"facilityName\":\"시설B\",\"nextInspectionDueAt\":\"2026-07-21\","
                + "\"kind\":\"BEFORE\"}";
        String due = "{\"facilityId\":2,\"facilityName\":\"시설B\",\"nextInspectionDueAt\":\"2026-07-21\","
                + "\"kind\":\"DUE\"}";
        String overdue = "{\"facilityId\":2,\"facilityName\":\"시설B\",\"nextInspectionDueAt\":\"2026-07-21\","
                + "\"kind\":\"OVERDUE\"}";

        Notification saved1 = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, before));
        Notification saved2 = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, due));
        Notification saved3 = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, overdue));

        assertThat(saved1.getId()).isNotNull();
        assertThat(saved2.getId()).isNotNull();
        assertThat(saved3.getId()).isNotNull();
    }

    @Test
    void 다른사용자면_같은_facilityId_dueAt_kind여도_모두저장된다() {
        User owner1 = seedUser("v25-user1");
        User owner2 = seedUser("v25-user2");
        String payload = "{\"facilityId\":3,\"facilityName\":\"시설C\",\"nextInspectionDueAt\":\"2026-07-21\","
                + "\"kind\":\"DUE\"}";

        Notification saved1 = notificationRepository.saveAndFlush(
                Notification.create(owner1.getId(), NotificationType.INSPECTION_DUE, payload));
        Notification saved2 = notificationRepository.saveAndFlush(
                Notification.create(owner2.getId(), NotificationType.INSPECTION_DUE, payload));

        assertThat(saved1.getId()).isNotNull();
        assertThat(saved2.getId()).isNotNull();
    }

    @Test
    void kind가NULL인_레거시행은_중복이어도_유니크제약을_통과한다() {
        // ⚠️ 문서화된 사각지대(클래스 javadoc 참고) — PostgreSQL unique index는 NULL을 서로 다른 값으로
        // 취급하므로, kind 필드가 없는(#540 이전) payload끼리는 완전히 동일해도 이 인덱스가 막지 못한다.
        // 그래서 InspectionDueNotificationScheduler가 findLegacyKindLessInspectionDueByUserIdIn으로
        // 이 사각지대만 별도로 방어한다 — 이 테스트는 그 별도 방어가 왜 필요한지를 실제 DB로 증명한다.
        User user = seedUser("v25-legacy-null");
        String legacyPayload = "{\"facilityId\":4,\"facilityName\":\"시설D\",\"nextInspectionDueAt\":\"2026-07-21\"}";

        Notification saved1 = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, legacyPayload));
        Notification saved2 = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, legacyPayload));

        assertThat(saved1.getId()).isNotNull();
        assertThat(saved2.getId()).isNotNull();
        assertThat(saved1.getId()).isNotEqualTo(saved2.getId());
    }
}
