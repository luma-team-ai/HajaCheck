package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.entity.CounselType;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounselTicketRepository extends JpaRepository<CounselTicket, Long> {

    // 티켓 생성 시 대기열 순번 스냅샷 산출용(WAITING 건수). 실시간 재계산은 하지 않는다(후속 과제).
    long countByStatus(CounselTicketStatus status);

    // 상담원 대기열 — 상태별 목록(생성순 = FIFO), 페이지네이션. PLATFORM_ADMIN 전용(스킬 필터 없음).
    Page<CounselTicket> findByStatusOrderByCreatedAtAsc(CounselTicketStatus status, Pageable pageable);

    // 상담원 대기열 — 스킬 필터(#1019/HAJA-501). COUNSELOR 는 본인이 보유한 counselType 으로만 좁힌다.
    Page<CounselTicket> findByStatusAndCounselTypeInOrderByCreatedAtAsc(
            CounselTicketStatus status, Collection<CounselType> counselTypes, Pageable pageable);

    // 내 상담 이력 — 본인 티켓 전체(최신순), 페이지네이션. userId 는 세션 주체에서만 채운다(IDOR 방지).
    Page<CounselTicket> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 내 상담 이력 — 본인 + 상태 필터(최신순).
    Page<CounselTicket> findByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId, CounselTicketStatus status, Pageable pageable);
}
