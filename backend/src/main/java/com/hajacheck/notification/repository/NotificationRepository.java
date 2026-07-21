package com.hajacheck.notification.repository;

import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import java.time.LocalDateTime;
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

    /**
     * 특정 사용자·알림 유형·생성 시각 구간의 알림 이력 조회. INSPECTION_DUE 배치(NOTI-01, #425)의
     * 하루 단위 멱등성 체크에 쓰이며, 유형/구간을 파라미터로 받아 다른 트리거의 멱등성 체크에도 재사용 가능하게 둔다.
     */
    List<Notification> findAllByUserIdAndTypeAndCreatedAtBetween(
            Long userId, NotificationType type, LocalDateTime from, LocalDateTime to);
}
