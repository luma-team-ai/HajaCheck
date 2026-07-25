package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
}
