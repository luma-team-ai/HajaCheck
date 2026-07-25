package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 세션 대화 이력(생성순) — 상담방 진입 시 과거 메시지 복원용.
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
