package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounselTicketRepository extends JpaRepository<CounselTicket, Long> {

    // 티켓 생성 시 대기열 순번 스냅샷 산출용(WAITING 건수). 실시간 재계산은 하지 않는다(후속 과제).
    long countByStatus(CounselTicketStatus status);

    // 상담원 대기열 — 상태별 목록(생성순 = FIFO), 페이지네이션.
    Page<CounselTicket> findByStatusOrderByCreatedAtAsc(CounselTicketStatus status, Pageable pageable);
}
