package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InspectionRepository extends JpaRepository<Inspection, Long>, InspectionRepositoryCustom {

    // 대시보드 개요(HAJA-17) — 소유 시설물 범위 내 점검 전체(최근 점검 목록 조합용 createdBy/facilityId 매핑 포함).
    List<Inspection> findByFacilityIdIn(Collection<Long> facilityIds);

    List<Inspection> findByFacilityIdInAndInspectionDateGreaterThanEqualAndInspectionDateLessThan(
            Collection<Long> facilityIds, LocalDate from, LocalDate to);

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

    // #1291 — roundNo는 항상 생성 순서(max+1)로 채번돼 점검일과 독립적이라, 새 회차의 점검일이
    // 기존 최신 회차보다 앞서도 막히지 않았다(회차 간 비교·추이 차트가 전부 회차 번호 오름차순=
    // 시간 순서로 가정해 깨짐). 생성 시 검증용 — 회차가 하나도 없으면 empty.
    @Query("select max(i.inspectionDate) from Inspection i where i.facilityId = :facilityId")
    Optional<LocalDate> findMaxInspectionDateByFacilityId(@Param("facilityId") Long facilityId);

    // 회차 간 비교(HAJA-531/#1112) — 시설물 1건의 특정 회차 단건 조회.
    Optional<Inspection> findByFacilityIdAndRoundNo(Long facilityId, Integer roundNo);

    // 시설물 상세 "점검 이력" 탭(#1359/HAJA-616) — 시설물 1건의 전체 회차를 최신순으로 조회.
    List<Inspection> findByFacilityIdOrderByRoundNoDesc(Long facilityId);

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
    // 영향 행 수 0 = 선점 불가(다른 요청이 선점했거나 허용되지 않은 소스 상태) → 호출부가 응답 매핑.
    //
    // 코드 리뷰 P1(10차 종결) — 예전엔 WHERE가 `status <> ANALYZING`만 검사해, 애플리케이션 레벨
    // 사전 체크(InspectionAnalysisService의 ANALYSIS_ALLOWED_SOURCE_STATUSES)와 조건이 어긋나 있었다.
    // 그 결과 사전 체크와 이 UPDATE 사이에 REVIEWED/REPORTED로 전이되면(예: 검수 확정과 재분석
    // 트리거가 동시에 발생) 사람이 확정한 하자가 재분석 소프트삭제에 덮여 유실되는 TOCTOU가 있었다.
    // 원자적 UPDATE 자체가 허용 소스 상태 불변식을 강제하도록 `status in :allowedStatuses`로 좁힌다 —
    // 사전 체크는 명확한 에러 메시지(NOT_ALLOWED vs ALREADY_RUNNING)용으로만 남고, 실제 동시성
    // 방어선은 이 조건부 UPDATE다. 호출부는 반드시 ANALYSIS_ALLOWED_SOURCE_STATUSES를 넘긴다.
    //
    // CI 실측 픽스(#701) — SET절에 `InspectionStatus.ANALYZING`을 JPQL 리터럴로 박아두면 Hibernate가
    // 이를 `'ANALYZING'::InspectionStatus`로 캐스팅하는데, 실제 PG 이넘 타입명은 (Inspection 엔티티
    // @Column(columnDefinition=...) 그대로) `inspection_status_type`이라 "type InspectionStatus does not
    // exist"로 즉시 실패한다(@DataJpaTest 실 PostgreSQL 대상 InspectionRepositoryTest로 처음 노출됨 —
    // 그 전엔 이 메서드를 실제 DB로 검증하는 테스트가 없어 잠재해 있었다). status/statuses를 bind
    // parameter로 넘기는 다른 메서드(findByFacilityCompanyIdAndStatus 등, 전부 정상 동작)와 동일하게
    // enum을 파라미터로 바인딩하면 @JdbcTypeCode(NAMED_ENUM) 타입 서술자를 그대로 타 안전하다.
    // 코드 리뷰 P1(머신 검수 2차) — 사전 체크(hasExistingDefects)와 이 UPDATE 사이(또는 UPLOADING/
    // CREATED 회차가 애초에 사전 체크를 거치지 않던 예전 경로)에 createManualDefect로 하자가 끼면,
    // 재분석이 그 사람 하자를 원자적 선점은 통과시키고 이후 워커의 소프트삭제가 지워버리는 TOCTOU가
    // 있었다. 소스 상태 TOCTOU를 WHERE의 allowedStatuses로 닫은 것과 동일한 방식으로, "비삭제 하자
    // 없음"도 이 원자적 UPDATE의 WHERE에 함께 강제해 선점 성공 자체를 막는다.
    @Modifying
    @Query("update Inspection i set i.status = :analyzingStatus "
            + "where i.id = :id and i.status in :allowedStatuses "
            + "and not exists (select 1 from Defect d where d.inspectionId = i.id and d.deleted = false)")
    int startAnalyzingIfNotRunning(
            @Param("id") Long id,
            @Param("analyzingStatus") InspectionStatus analyzingStatus,
            @Param("allowedStatuses") Collection<InspectionStatus> allowedStatuses);

    // 회사별 분석 동시 실행 상한(코드 리뷰 P2 4차/10차) — analysisTaskExecutor는 테넌트 구분 없는
    // 전역 공유 풀이라, 한 회사가 대량 요청으로 큐를 독점하면 다른 회사까지 막힌다(noisy-neighbor).
    // 공유 풀에 넣기 전에 이 목록으로 회사별 상한을 강제하되, "살아있는 잡"만 세도록 호출부가
    // isStuck으로 고착 유령을 제외한다(단순 count가 아니라 목록을 반환하는 이유 — 카운트에 하트비트
    // 기반 stale 판정이 필요한데 그건 SQL로 표현할 수 없다). i.facility(지연 로딩 연관관계)를 거쳐
    // JPQL 조인 — Facility 목록을 먼저 조회할 필요 없다.
    @Query("select i from Inspection i "
            + "where i.facility.companyId = :companyId and i.status = :status")
    List<Inspection> findByFacilityCompanyIdAndStatus(
            @Param("companyId") Long companyId, @Param("status") InspectionStatus status);

    // ANALYZING 고착 리퍼(코드 리뷰 P2 10차) — 상태별 전체 조회. 리퍼가 하트비트로 고착 회차를
    // 걸러 복원하므로 고착 유령이 누적되지 않아 ANALYZING 집합은 실사용상 작게 유지된다.
    List<Inspection> findByStatus(InspectionStatus status);

    // 마이페이지 "내 점검 이력" 요약(#844) — "내 점검" = assignedInspectorId 또는 createdBy가 본인.
    // 회사 스코프(facility.companyId)는 findPageByCompanyIdAndFilters와 동일 원칙으로 강제한다
    // (요청자가 회사를 이동해도 과거 소속 회사의 점검이 현재 컨텍스트에 섞이지 않도록).
    // statuses는 항상 서비스가 상수 EnumSet을 넘기므로(null 아님) named enum IN절 null 바인딩 문제와 무관.
    @Query("select count(i) from Inspection i where i.facility.companyId = :companyId "
            + "and (i.assignedInspectorId = :userId or i.createdBy = :userId)")
    long countMine(@Param("companyId") Long companyId, @Param("userId") Long userId);

    @Query("select count(i) from Inspection i where i.facility.companyId = :companyId "
            + "and (i.assignedInspectorId = :userId or i.createdBy = :userId) and i.status in :statuses")
    long countMineByStatusIn(
            @Param("companyId") Long companyId,
            @Param("userId") Long userId,
            @Param("statuses") Collection<InspectionStatus> statuses);
}
