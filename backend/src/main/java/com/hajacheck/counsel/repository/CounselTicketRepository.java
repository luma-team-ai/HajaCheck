package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.entity.CounselType;
import java.time.LocalDateTime;
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

    // 상담원 대기열 — 배정된 내 상담(#1019/#1001 후속). WAITING 이 아닌 상태(IN_PROGRESS 등)는 스킬이
    // 아니라 담당자 본인 여부로 좁힌다 — 이미 배정된 티켓은 스킬 매칭과 무관하게 그 상담원 전용이고,
    // 스킬로만 거르면 같은 스킬의 다른 상담원이 진행 중인 티켓까지 노출되는 IDOR성 누출이 된다.
    Page<CounselTicket> findByStatusAndCounselorIdOrderByCreatedAtAsc(
            CounselTicketStatus status, Long counselorId, Pageable pageable);

    // 내 상담 이력 — 본인 티켓 전체(최신순), 페이지네이션. userId 는 세션 주체에서만 채운다(IDOR 방지).
    Page<CounselTicket> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 내 상담 이력 — 본인 + 상태 필터(최신순).
    Page<CounselTicket> findByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId, CounselTicketStatus status, Pageable pageable);

    // 플랫폼 관리자 날짜별 상담 목록(#1168) — 접수일(createdAt) 기준 [start, end) 반열린구간, 최신순.
    // 필터 기준은 접수일이며 종료일(endedAt)이 아니다(그날 접수됐지만 아직 진행 중/미종료인 티켓도 포함).
    // id DESC 타이브레이커(#1263): createdAt 만으로 정렬하면 같은 마이크로초에 접수된 티켓들의 순서가
    // 쿼리마다 달라질 수 있어, 페이지 경계에서 같은 티켓이 두 페이지에 나오거나 아예 빠질 수 있다.
    // id 는 유일하므로 전순서가 확정된다.
    Page<CounselTicket> findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
            LocalDateTime start, LocalDateTime end, Pageable pageable);
}
