package com.hajacheck.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * NotificationRepository의 날짜 제한 배치 조회를 실제 PostgreSQL로 검증한다(PR머신 P2 #1032).
 *
 * <p>{@link NotificationRepository#findAllByUserIdInAndTypeAndCreatedAtAfter}는
 * {@link com.hajacheck.core.facility.scheduler.InspectionDueNotificationScheduler}가 무제한 전체
 * 이력 로딩(구 {@code findAllByUserIdInAndType})을 슬라이딩 윈도우로 대체한 메서드다. 파생 쿼리
 * 메서드명 오타·조건 누락은 컴파일은 통과하지만 실제로 날짜를 걸러내지 못할 수 있어, Mockito 단위
 * 테스트(스케줄러가 올바른 컷오프 인자를 넘기는지)만으로는 증명되지 않는다 — 여기서는 실제 알림 행을
 * 시딩하고 컷오프보다 오래된 행이 결과에서 제외되는지 DB로 직접 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class NotificationRepositoryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findAllByUserIdInAndTypeAndCreatedAtAfter_컷오프이전알림은_결과에서_제외한다() {
        User user = userRepository.saveAndFlush(User.createCompanyOwner(
                "notif-repo-" + UUID.randomUUID() + "@haja.test", "슬라이딩윈도우테스트",
                "<password-hash-placeholder>"));

        Notification recent = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, "{\"facilityId\":1}"));
        Notification old = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.INSPECTION_DUE, "{\"facilityId\":2}"));

        // @CreatedDate는 INSERT 시점에만 자동 설정되므로, "400일보다 오래된 알림"을 재현하려면
        // 시딩 이후 직접 created_at을 과거로 되돌려야 한다(스케줄러와 동일한 컷오프: 오늘-400일 자정).
        LocalDateTime cutoff = LocalDate.now().minusDays(400).atStartOfDay();
        jdbcTemplate.update("update notifications set created_at = ? where id = ?",
                cutoff.minusDays(1), old.getId());

        List<Notification> result = notificationRepository.findAllByUserIdInAndTypeAndCreatedAtAfter(
                Set.of(user.getId()), NotificationType.INSPECTION_DUE, cutoff);

        assertThat(result).extracting(Notification::getId).containsExactly(recent.getId());
    }
}
