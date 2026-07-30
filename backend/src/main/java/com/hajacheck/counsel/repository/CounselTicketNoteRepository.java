package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselTicketNote;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounselTicketNoteRepository extends JpaRepository<CounselTicketNote, Long> {

    // 티켓당 메모 1개(unique ticket_id) — 조회/upsert 모두 이 메서드로 존재 여부를 먼저 확인한다.
    Optional<CounselTicketNote> findByTicketId(Long ticketId);
}
