package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.Facility;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByCompanyId(Long companyId);

    // 시설물 목록 조회 상한(#484) — FacilityService.list() 전용. 계약(응답=배열) 은 유지한 채
    // 무제한 반환을 막는 방어적 상한이라 정렬은 id asc(등록순, 결정적) 로 고정한다.
    List<Facility> findByCompanyIdOrderByIdAsc(Long companyId, Pageable pageable);

    Optional<Facility> findByIdAndCompanyId(Long id, Long companyId);

    long countByCompanyId(Long companyId);

    // 플랜 하향 시 "읽기 전용 시설물" 계산 판정(#890) — 회사 시설물을 id 오름차순으로 봤을 때 이 시설물의
    // 순위(자기 자신 포함)다. 순위가 plans.max_facilities 를 넘으면 읽기 전용으로 본다. 상태 컬럼을 두지
    // 않는 이유는 한도가 다시 올라갔을 때 자동 복구되기 때문(PlanDowngradeService javadoc 참고).
    long countByCompanyIdAndIdLessThanEqual(Long companyId, Long id);

    // 대시보드 개요(HAJA-17) — 전월 대비 증감률 계산용: 기준 시각 이전 누적 등록 수.
    long countByCompanyIdAndCreatedAtBefore(Long companyId, LocalDateTime before);

    // 점검 회차 채번 동시성 경쟁 방지(dev-05-02) — 같은 시설물에 대한 동시 점검 생성 요청을
    // 트랜잭션 종료 시점까지 직렬화하기 위한 행 잠금. 시설물은 항상 존재가 보장된 상태에서 호출되므로
    // (InspectionService 가 소유권 검증 이후에만 호출) 값 자체는 사용하지 않고 잠금 획득 용도로만 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Facility f where f.id = :id")
    Optional<Facility> findByIdForUpdate(@Param("id") Long id);

    // 다가오는 점검 예정 조회(dev-03-02, HAJA-대시보드 위젯) — company_id 단일 스코프,
    // next_inspection_due_at is not null 이며 [from, to] 범위(오늘~오늘+days) 내인 시설물만,
    // nextInspectionDueAt 오름차순으로 최대 limit(Pageable) 건 반환.
    @Query("select f from Facility f where f.companyId = :companyId and f.nextInspectionDueAt is not null "
            + "and f.nextInspectionDueAt >= :from and f.nextInspectionDueAt <= :to "
            + "order by f.nextInspectionDueAt asc")
    List<Facility> findUpcomingByCompanyId(@Param("companyId") Long companyId, @Param("from") LocalDate from,
                                            @Param("to") LocalDate to, Pageable pageable);

    // INSPECTION_DUE 알림 배치(NOTI-01, #425) — findUpcomingByCompanyId 와 달리 회사 스코프가 없는 전역 쿼리.
    // 배치가 모든 회사의 마감 도래(overdue 포함, 오늘 이하) 시설물을 순회해야 하므로 의도적으로 unscoped 다.
    // 전역 대상이라 미페이징 로딩은 메모리 위험 — 반드시 Pageable 로 페이지 단위 순회한다(스케줄러가 hasNext 로 반복).
    // ⚠️ OrderByIdAsc 필수: ORDER BY 없이는 Postgres 가 페이지 요청 간 행 순서를 보장하지 않아 페이지 순회 중
    // 행 중복 노출/누락(그 시설물 owner가 알림 미수신)이 발생한다. id asc 결정적 정렬로 안정 페이징을 강제한다.
    // 반환 타입은 Page 대신 Slice(#510 P2) — 스케줄러는 getContent/hasNext 만 쓰고 총 건수는 쓰지 않는데,
    // Page 는 페이지마다 별도 COUNT(*) 쿼리를 실행한다. next_inspection_due_at 무인덱스라 이 COUNT 도 전체
    // 스캔이 돼 스캔 비용을 배가시킨다 — Slice 는 COUNT 없이 limit+1 조회만으로 hasNext 를 판정한다.
    Slice<Facility> findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(LocalDate date, Pageable pageable);

    // 지도 전용 경량 엔드포인트(#1656) — findByCompanyIdOrderByIdAsc(Pageable) 이 쓰는 FACILITY_LIST_MAX(500)
    // 상한에 걸리면 500건 초과 회사의 지도 마커가 무고지로 누락된다(P1). 지도는 회사 보유 시설물 전체
    // 좌표가 필요하므로 상한을 두지 않고, 무거운 엔티티 전체 로딩 대신 마커 렌더링에 필요한 컬럼만
    // 프로젝션한다. id asc 로 정렬해 다른 목록 조회와 동일하게 결정적 순서를 유지한다.
    // address(#1656 리뷰 보강, #1657 리뷰에서 발견) — 구 지도 흐름(GET /facilities)의 address를 지도
    // 검색(주소 매칭)·지오코딩 백필이 쓰는데 최초 경량 계약에서 빠져 주소 검색이 조용히 무력화됐다.
    // 컬럼 하나만 추가해 경량 취지는 유지한다.
    @Query("select f.id as id, f.name as name, f.type as type, f.address as address, "
            + "f.latitude as latitude, f.longitude as longitude "
            + "from Facility f where f.companyId = :companyId order by f.id asc")
    List<FacilityMapProjection> findMapProjectionsByCompanyId(@Param("companyId") Long companyId);
}
