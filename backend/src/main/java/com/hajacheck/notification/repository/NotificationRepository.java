package com.hajacheck.notification.repository;

import com.hajacheck.notification.entity.Notification;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findAllByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 알림 센터 목록 조회(AP-020) — 읽음/미읽음 모두 포함한 이력 표시용, 최신순.
     * created_at 동률 시 id desc로 결정적 정렬(P3-1) — idx_notifications_user_history
     * (user_id, created_at desc, id desc)가 이 정렬을 그대로 커버한다.
     */
    List<Notification> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.read = true, n.lockVersion = n.lockVersion + 1
            where n.id = :notificationId
              and n.userId = :userId
              and n.read = false
            """)
    int markAsReadIfUnread(
            @Param("notificationId") Long notificationId,
            @Param("userId") Long userId);

    boolean existsByIdAndUserIdAndReadTrue(Long notificationId, Long userId);
}
