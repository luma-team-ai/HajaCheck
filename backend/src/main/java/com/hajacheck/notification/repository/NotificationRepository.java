package com.hajacheck.notification.repository;

import com.hajacheck.notification.entity.Notification;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findAllByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
