package com.hajacheck.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.support.PostgresTestSupport;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notification.lock_version(HAJA-25 P2 — 다른 가변 Entity와의 일관성을 위해 추가, 상태 머신은 아님)이
 * 동시 markAsRead() 갱신 중 stale 갱신을 실제 PostgreSQL에서 거부하는지 검증한다.
 * StateTransitionOptimisticLockTest와 동일한 패턴(두 트랜잭션이 같은 행을 읽고, 먼저 커밋한 쪽만 성공).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class NotificationOptimisticLockTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentMarkAsRead_onlyFirstTransitionCommits() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long[] ids = inTransaction(() -> {
            User user = userRepository.saveAndFlush(
                    User.createCompanyOwner(
                            "lock-" + suffix + "@haja.test",
                            "optimistic-lock-user",
                            "<password-hash-placeholder>"));
            Notification notification = notificationRepository.saveAndFlush(
                    Notification.create(user.getId(), NotificationType.ANALYSIS_DONE, null));
            return new Long[]{user.getId(), notification.getId()};
        });

        try {
            Notification firstRequest = inTransaction(
                    () -> notificationRepository.findById(ids[1]).orElseThrow());
            Notification staleSecondRequest = inTransaction(
                    () -> notificationRepository.findById(ids[1]).orElseThrow());

            inTransaction(() -> {
                firstRequest.markAsRead();
                return notificationRepository.saveAndFlush(firstRequest);
            });

            assertThatThrownBy(() -> inTransaction(() -> {
                staleSecondRequest.markAsRead();
                return notificationRepository.saveAndFlush(staleSecondRequest);
            })).isInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            inTransaction(() -> {
                notificationRepository.deleteById(ids[1]);
                userRepository.deleteById(ids[0]);
                return null;
            });
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void markAsReadIfUnread_재호출은멱등() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long[] ids = inTransaction(() -> {
            User user = userRepository.saveAndFlush(
                    User.createCompanyOwner(
                            "atomic-" + suffix + "@haja.test",
                            "atomic-read-user",
                            "<password-hash-placeholder>"));
            Notification notification = notificationRepository.saveAndFlush(
                    Notification.create(user.getId(), NotificationType.ANALYSIS_DONE, null));
            return new Long[]{user.getId(), notification.getId()};
        });

        try {
            assertThat(inTransaction(() -> notificationRepository.markAsReadIfUnread(ids[1], ids[0])))
                    .isEqualTo(1);
            assertThat(inTransaction(() -> notificationRepository.markAsReadIfUnread(ids[1], ids[0])))
                    .isZero();
            assertThat(inTransaction(() -> notificationRepository.existsByIdAndUserIdAndReadTrue(ids[1], ids[0])))
                    .isTrue();
        } finally {
            inTransaction(() -> {
                notificationRepository.deleteById(ids[1]);
                userRepository.deleteById(ids[0]);
                return null;
            });
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
