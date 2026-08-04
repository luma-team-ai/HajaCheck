package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.ChatSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 세션 소유자 검증용 조회(#1467/HAJA-647) — id 단독 조회 후 애플리케이션에서 userId 를 비교하지 않고
     * 쿼리 조건에 소유자를 포함시켜 cross-user 접근을 원천 차단한다. 결과가 비어 있으면 "미존재"인지
     * "타인 소유"인지 구분하지 않고 동일하게 403(CHAT_SESSION_FORBIDDEN)으로 처리한다.
     */
    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);
}
