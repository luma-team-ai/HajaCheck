package com.hajacheck.core.rag.repository;

import com.hajacheck.core.rag.entity.ChatMessageCitation;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageCitationRepository extends JpaRepository<ChatMessageCitation, Long> {

    /**
     * 세션 이력 조회 시 메시지별 인용을 한 번에 읽어온다(메시지 수만큼 조회하는 N+1 방지, #1467/HAJA-647).
     * 인용 저장 로직은 후속 이슈 범위이므로 현재는 항상 비어 있을 수 있다.
     */
    List<ChatMessageCitation> findByMessageIdIn(Collection<Long> messageIds);
}
