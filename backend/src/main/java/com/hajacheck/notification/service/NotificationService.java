package com.hajacheck.notification.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NotificationService {

    // 알림 센터는 별도 페이지네이션 UI 없이 최근 이력만 보여주면 되므로(AP-020, PRD FR-9 인앱 폴링
    // 목록), 대시보드 위젯들과 동일하게 상한을 고정한 상위 N건 조회로 충분하다(DashboardService의
    // RECENT_LIMIT 패턴과 동일).
    // openapi.yaml "GET /api/notifications" 설명의 "상위 30건"과 동기화 유지 — 이 값을 바꾸면 계약도 함께 갱신.
    private static final int LIST_LIMIT = 30;

    private final NotificationRepository notificationRepository;

    /** 로그인 사용자에게 온 알림을 읽음/미읽음 모두 포함해 최신순 상위 {@value #LIST_LIMIT}건 반환한다(AP-020). */
    public List<NotificationResponse> getNotifications(Long userId) {
        return notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    /**
     * 알림을 읽음 처리한다(멱등). 이미 읽은 본인 알림 재호출도 성공 처리하며, 미존재 또는 타인 소유
     * 알림은 리소스 존재 열거(cross-user IDOR)를 막기 위해 NOTIFICATION_NOT_FOUND(404)로 통일한다.
     */
    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        if (notificationRepository.markAsReadIfUnread(notificationId, userId) > 0) {
            return;
        }
        if (!notificationRepository.existsByIdAndUserIdAndReadTrue(notificationId, userId)) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /**
     * 사용자에게 알림 1건을 발행한다(NOTI-01, #425). {@code Facility}·{@code INSPECTION_DUE} 같은 특정
     * 도메인/유형을 몰라도 되는 범용 진입점으로, 다른 도메인·다른 알림 유형도 그대로 호출할 수 있게 설계했다.
     * 시설물별 독립 커밋을 위해 클래스 기본값 대신 이 메서드에만 쓰기 트랜잭션을 건다(markAsRead 와 동일 패턴).
     */
    @Transactional
    public void notify(Long userId, NotificationType type, String payloadJson) {
        notificationRepository.save(Notification.create(userId, type, payloadJson));
    }
}
