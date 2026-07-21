package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.Facility;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByOwnerId(Long ownerId);

    // 시설물 목록 조회 상한(#484) — FacilityService.list() 전용. 계약(응답=배열) 은 유지한 채
    // 무제한 반환을 막는 방어적 상한이라 정렬은 id asc(등록순, 결정적) 로 고정한다.
    List<Facility> findByOwnerIdOrderByIdAsc(Long ownerId, Pageable pageable);

    Optional<Facility> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    // 대시보드 개요(HAJA-17) — 전월 대비 증감률 계산용: 기준 시각 이전 누적 등록 수.
    long countByOwnerIdAndCreatedAtBefore(Long ownerId, LocalDateTime before);

    // 점검 회차 채번 동시성 경쟁 방지(dev-05-02) — 같은 시설물에 대한 동시 점검 생성 요청을
    // 트랜잭션 종료 시점까지 직렬화하기 위한 행 잠금. 시설물은 항상 존재가 보장된 상태에서 호출되므로
    // (InspectionService 가 소유권 검증 이후에만 호출) 값 자체는 사용하지 않고 잠금 획득 용도로만 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Facility f where f.id = :id")
    Optional<Facility> findByIdForUpdate(@Param("id") Long id);

    // 다가오는 점검 예정 조회(dev-03-02, HAJA-대시보드 위젯) — owner_id 단일 스코프,
    // next_inspection_due_at is not null 이며 [from, to] 범위(오늘~오늘+days) 내인 시설물만,
    // nextInspectionDueAt 오름차순으로 최대 limit(Pageable) 건 반환.
    @Query("select f from Facility f where f.ownerId = :ownerId and f.nextInspectionDueAt is not null "
            + "and f.nextInspectionDueAt >= :from and f.nextInspectionDueAt <= :to "
            + "order by f.nextInspectionDueAt asc")
    List<Facility> findUpcomingByOwnerId(@Param("ownerId") Long ownerId, @Param("from") LocalDate from,
                                          @Param("to") LocalDate to, Pageable pageable);

    // INSPECTION_DUE 알림 배치(NOTI-01, #425) — findUpcomingByOwnerId 와 달리 owner 스코프가 없는 전역 쿼리.
    // 배치가 모든 owner의 마감 도래(overdue 포함, 오늘 이하) 시설물을 순회해야 하므로 의도적으로 unscoped 다.
    // 전역 대상이라 미페이징 로딩은 메모리 위험 — 반드시 Pageable 로 페이지 단위 순회한다(스케줄러가 hasNext 로 반복).
    Page<Facility> findAllByNextInspectionDueAtLessThanEqual(LocalDate date, Pageable pageable);
}
