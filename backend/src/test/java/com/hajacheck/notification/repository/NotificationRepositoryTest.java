package com.hajacheck.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * NotificationRepository의 레거시(kind 없음) INSPECTION_DUE 알림 조회를 실제 PostgreSQL로 검증한다(#1050).
 *
 * <p>{@link NotificationRepository#findLegacyKindLessInspectionDueByUserIdIn}는
 * {@link com.hajacheck.core.facility.scheduler.InspectionDueNotificationScheduler}가 V25 유니크
 * 인덱스(kind가 NULL인 행은 못 잡음)의 사각지대만 애플리케이션 레벨로 방어하기 위한 좁힌 조회다.
 * 파생/네이티브 쿼리의 JSONB 연산자·조건 오타는 컴파일은 통과하지만 실제로 걸러내지 못할 수 있어,
 * 여기서는 실제 알림 행을 시딩해 kind 유무·type·user_id로 정확히 필터링되는지 DB로 직접 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class NotificationRepositoryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void findLegacyKindLessInspectionDueByUserIdIn_kind필드없는행만_반환한다() {
        User user = userRepository.saveAndFlush(User.createCompanyOwner(
                "notif-repo-" + UUID.randomUUID() + "@haja.test", "레거시조회테스트",
                "<password-hash-placeholder>"));

        // kind 없는 구 payload(#540 이전 저장분 재현) — 이게 조회 대상이어야 한다.
        Notification legacy = notificationRepository.saveAndFlush(Notification.create(
                user.getId(), NotificationType.INSPECTION_DUE,
                "{\"facilityId\":1,\"facilityName\":\"레거시시설\",\"nextInspectionDueAt\":\"2026-07-21\"}"));

        // kind 있는 신규 payload — V25 유니크 인덱스가 전담하므로 이 조회 대상이 아니어야 한다.
        notificationRepository.saveAndFlush(Notification.create(
                user.getId(), NotificationType.INSPECTION_DUE,
                "{\"facilityId\":2,\"facilityName\":\"신규시설\",\"nextInspectionDueAt\":\"2026-07-22\","
                        + "\"kind\":\"DUE\"}"));

        List<Notification> result =
                notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(Set.of(user.getId()));

        assertThat(result).extracting(Notification::getId).containsExactly(legacy.getId());
    }

    @Test
    void findLegacyKindLessInspectionDueByUserIdIn_다른유형은제외한다() {
        User user = userRepository.saveAndFlush(User.createCompanyOwner(
                "notif-repo-" + UUID.randomUUID() + "@haja.test", "유형필터테스트",
                "<password-hash-placeholder>"));

        // kind 없는 payload라도 type이 INSPECTION_DUE가 아니면 대상이 아니다.
        notificationRepository.saveAndFlush(Notification.create(
                user.getId(), NotificationType.ANALYSIS_DONE, "{\"facilityId\":1}"));

        List<Notification> result =
                notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(Set.of(user.getId()));

        assertThat(result).isEmpty();
    }

    @Test
    void findLegacyKindLessInspectionDueByUserIdIn_다른사용자는제외한다() {
        User user = userRepository.saveAndFlush(User.createCompanyOwner(
                "notif-repo-" + UUID.randomUUID() + "@haja.test", "사용자필터테스트",
                "<password-hash-placeholder>"));
        User otherUser = userRepository.saveAndFlush(User.createCompanyOwner(
                "notif-repo-other-" + UUID.randomUUID() + "@haja.test", "다른사용자",
                "<password-hash-placeholder>"));

        notificationRepository.saveAndFlush(Notification.create(
                otherUser.getId(), NotificationType.INSPECTION_DUE, "{\"facilityId\":1}"));

        List<Notification> result =
                notificationRepository.findLegacyKindLessInspectionDueByUserIdIn(Set.of(user.getId()));

        assertThat(result).isEmpty();
    }
}
