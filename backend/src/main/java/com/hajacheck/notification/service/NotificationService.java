package com.hajacheck.notification.service;

import com.hajacheck.notification.dto.NotificationResponse;
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

    /** 이미 읽은 알림에 대한 재호출도 성공으로 처리하는 멱등 연산. */
    @Transactional
    public boolean markAsRead(Long notificationId, Long userId) {
        if (notificationRepository.markAsReadIfUnread(notificationId, userId) > 0) {
            return true;
        }
        return notificationRepository.existsByIdAndUserIdAndReadTrue(notificationId, userId);
    }
}
