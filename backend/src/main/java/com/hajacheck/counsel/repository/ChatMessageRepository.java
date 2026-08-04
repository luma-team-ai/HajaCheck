package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 세션 대화 이력(생성순) — 상담방 진입 시 과거 메시지 복원용.
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    // RAG 챗봇 최근 이력 조회 전용(#1493/HAJA-657, PR #1510 P2 픽스) — 세션 전체를 읽지 않고 최근
    // 6건(최대 3턴 페어)만 역순으로 가져온다. 호출부(AiProxyService.buildRecentHistory)가 프롬프트
    // 순서를 맞추기 위해 다시 시간순(asc)으로 뒤집는다.
    List<ChatMessage> findTop6BySessionIdOrderByCreatedAtDesc(Long sessionId);
}
