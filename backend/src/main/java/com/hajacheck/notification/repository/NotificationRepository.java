package com.hajacheck.notification.repository;

import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import java.util.List;
import java.util.Set;
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
     * 여러 사용자의 특정 유형 알림 이력을 한 번에 조회. INSPECTION_DUE 배치(NOTI-01, #425)가 owner별 개별 조회
     * (N+1)를 피해 대상 owner 전체 알림을 1쿼리로 가져와 dedupe 키 집합을 만드는 데 쓴다. 유형을 파라미터로
     * 받아 다른 트리거의 멱등성 체크에도 재사용 가능하게 둔다.
     */
    List<Notification> findAllByUserIdInAndType(Set<Long> userIds, NotificationType type);
}
