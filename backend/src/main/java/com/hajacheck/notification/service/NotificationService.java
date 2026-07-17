package com.hajacheck.notification.service;

import com.hajacheck.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 이미 읽은 알림에 대한 재호출도 성공으로 처리하는 멱등 연산. */
    @Transactional
    public boolean markAsRead(Long notificationId, Long userId) {
        if (notificationRepository.markAsReadIfUnread(notificationId, userId) > 0) {
            return true;
        }
        return notificationRepository.existsByIdAndUserIdAndReadTrue(notificationId, userId);
    }
}
