package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InspectionRepository extends JpaRepository<Inspection, Long>, InspectionRepositoryCustom {

    // 대시보드 개요(HAJA-17) — 소유 시설물 범위 내 점검 전체(최근 점검 목록 조합용 createdBy/facilityId 매핑 포함).
    List<Inspection> findByFacilityIdIn(Collection<Long> facilityIds);

    // 대시보드 최근 점검 목록 — 건수 제한을 파생 쿼리(findTop10)가 아니라 Pageable 로 받는다(#351).
    // 메서드명에 매직넘버 10 이 박히면 호출부의 RECENT_LIMIT 상수가 죽는다. PR #349 의
    // pending-priority 패턴(@Query + Pageable + 상수)과 동일하게 맞춘다.
    @Query("select i from Inspection i where i.facilityId in :facilityIds "
            + "order by i.inspectionDate desc, i.id desc")
    List<Inspection> findRecentByFacilityIds(@Param("facilityIds") Collection<Long> facilityIds, Pageable pageable);

    long countByFacilityIdInAndStatusIn(Collection<Long> facilityIds, Collection<InspectionStatus> statuses);

    @Query("select count(i) from Inspection i where i.facilityId in :facilityIds and i.status in :statuses "
            + "and i.inspectionDate >= :from and i.inspectionDate < :to")
    long countByFacilityIdInAndStatusInAndInspectionDateRange(
            @Param("facilityIds") Collection<Long> facilityIds,
            @Param("statuses") Collection<InspectionStatus> statuses,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // 점검 회차 생성(dev-05-02) — 시설물별 다음 회차 번호 계산.
    @Query("select coalesce(max(i.roundNo), 0) from Inspection i where i.facilityId = :facilityId")
    int findMaxRoundNoByFacilityId(@Param("facilityId") Long facilityId);

    // 시설물 현황 목록(#540 ⑥, HAJA-378) — 시설물별 "최근 점검일" 1건씩만 필요하다.
    // findRecentByFacilityIds 는 전체 시설물이 뒤섞인 플랫 리스트라 시설물별 최신 1건 추출에는
    // 부적합(서비스단 재그룹 없이는 못 씀). Postgres DISTINCT ON 으로 시설물별 최신 1건만
    // DB 단에서 골라 N+1/인메모리 재그룹 없이 반환한다(정렬은 findRecentByFacilityIds 와 동일 기준:
    // inspection_date desc, id desc — 동일 날짜 여러 회차 시 최신 등록분을 "최근 점검"으로 취급).
    @Query(value = "select distinct on (i.facility_id) i.* from inspections i "
            + "where i.facility_id in (:facilityIds) "
            + "order by i.facility_id, i.inspection_date desc, i.id desc",
            nativeQuery = true)
    List<Inspection> findLatestByFacilityIds(@Param("facilityIds") Collection<Long> facilityIds);

    // AI 분석 시작(dev-05-04) — check-then-act(조회 후 별도 UPDATE) 대신 단일 조건부 UPDATE로
    // ANALYZING 선점을 원자적으로 수행한다(코드 리뷰 P2: 동시 POST /analyze 시 이중 실행 방지).
    // 영향 행 수 0 = 이미 ANALYZING(다른 요청이 선점했거나 고착) → 호출부가 ANALYSIS_ALREADY_RUNNING으로 응답.
    @Modifying
    @Query("update Inspection i set i.status = com.hajacheck.core.inspection.entity.InspectionStatus.ANALYZING "
            + "where i.id = :id and i.status <> com.hajacheck.core.inspection.entity.InspectionStatus.ANALYZING")
    int startAnalyzingIfNotRunning(@Param("id") Long id);
}
