package com.hajacheck.notification.repository;

import com.hajacheck.notification.entity.Notification;
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
     * INSPECTION_DUE 알림 중 {@code kind} 필드가 없는(#540 이전 저장분) 레거시 payload만 조회한다(#1050).
     * V24 유니크 인덱스({@code uq_notifications_inspection_due_dedupe})는 {@code payload_json->>'kind'}가
     * NULL인 행을 원자적으로 방어하지 못한다 — PostgreSQL unique index는 NULL을 서로 다른 값으로 취급해
     * NULL 값이 있는 행끼리는 유니크 제약을 통과시킨다. 이 메서드는 그 좁은 사각지대만 별도로 방어하기
     * 위한 애플리케이션 레벨 체크에 쓰인다({@link com.hajacheck.core.facility.scheduler
     * .InspectionDueNotificationScheduler} 참고).
     *
     * <p>⚠️ 이 조회 대상은 <b>#540 배포 시점 이후로 늘어나지 않는 유한 집합</b>이다 — #540 이후 모든
     * 발행 경로({@code InspectionDueNotificationPayload#serialize})가 kind를 필수 인자로 받아 항상
     * 채우므로, 신규로 저장되는 INSPECTION_DUE payload는 전부 kind를 갖는다. 따라서 날짜 윈도우 없이
     * 조회해도 #1050 이전에 있었던 "무제한 누적" 문제(구 {@code NOTIFICATION_LOOKBACK_DAYS} 400일
     * 슬라이딩 윈도우, PR머신+사람검수 P2 #1032)가 재발하지 않는다.
     */
    @Query(value = """
            select * from notifications
             where user_id in (:userIds)
               and type = 'INSPECTION_DUE'
               and payload_json ->> 'kind' is null
            """, nativeQuery = true)
    List<Notification> findLegacyKindLessInspectionDueByUserIdIn(@Param("userIds") Set<Long> userIds);
}
