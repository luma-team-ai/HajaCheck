package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    // #1667 — 동일 inspection_date 내 실제 수행 순서 보장을 위해 performed_at을 id보다 먼저 tie-break로
    // 쓴다(id는 생성 순서일 뿐 촬영 순서의 대리값이 아니었다). performed_at이 없는(미디어 미업로드) 회차는
    // nulls last로 뒤로 밀려 기존 id desc 동작을 그대로 유지한다.
    @Query("select i from Inspection i where i.facilityId in :facilityIds "
            + "order by i.inspectionDate desc, i.performedAt desc nulls last, i.id desc")
    List<Inspection> findRecentByFacilityIds(@Param("facilityIds") Collection<Long> facilityIds, Pageable pageable);

    long countByFacilityIdInAndStatusIn(Collection<Long> facilityIds, Collection<InspectionStatus> statuses);

    @Query("select count(i) from Inspection i where i.facilityId in :facilityIds and i.status in :statuses "
            + "and i.inspectionDate >= :from and i.inspectionDate < :to")
    long countByFacilityIdInAndStatusInAndInspectionDateRange(
            @Param("facilityIds") Collection<Long> facilityIds,
            @Param("statuses") Collection<InspectionStatus> statuses,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // 점검 회차 생성(dev-05-02) — 시설물별 현재 최대 회차 번호. #1702부터는 "다음 회차 채번"이
    // 아니라 (1) 시프트가 필요한지("삽입 위치 <= 현재 최댓값"이면 뒤 회차를 밀어야 한다) 판정과
    // (2) 시프트 오프셋 안전성 가드에 쓰인다.
    @Query("select coalesce(max(i.roundNo), 0) from Inspection i where i.facilityId = :facilityId")
    int findMaxRoundNoByFacilityId(@Param("facilityId") Long facilityId);

    // #1702 — 새 회차의 삽입 위치 계산. roundNo를 점검일 오름차순으로 유지하므로
    // "새 점검일 이하인 기존 회차 수 + 1"이 곧 새 회차의 번호가 된다. 경계를 `<=`(미만이 아니라
    // 이하)로 두어 같은 날짜의 기존 회차는 새 회차 <b>앞</b>에 남는다 = 같은 날짜는 뒤에 붙는다
    // (InspectionRepository#findLatestByFacilityIds 주석의 "동일 날짜 여러 회차 시 최신 등록분을
    // 최근으로 취급" 정책과 정합).
    long countByFacilityIdAndInspectionDateLessThanEqual(Long facilityId, LocalDate inspectionDate);

    // #1702 — 회차 시프트 1단계. unique(facility_id, round_no)는 non-deferrable이라(V1:523)
    // `round_no = round_no + 1` 단일 UPDATE는 PG가 행마다 즉시 제약을 검사하는 도중 아직 밀리지 않은
    // 다음 행과 충돌할 수 있다. 그래서 밀어야 할 행 전부를 한 번에 빈 상위 구간(>= offset)으로
    // 옮겨두고(1단계), 거기서 목표 위치로 되돌린다(2단계) — 두 단계 모두 출발 구간과 도착 구간이
    // 겹치지 않아 중간 충돌이 구조적으로 불가능하다. 제약을 DEFERRABLE로 바꾸는 방식은 채택하지
    // 않았다: 제약명이 바뀌면 InspectionService.ROUND_NO_UNIQUE_CONSTRAINT 상수와 캐노니컬 DDL·
    // testcontainers init 스크립트까지 연쇄 수정이 필요해 범위가 커진다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Inspection i set i.roundNo = i.roundNo + :offset "
            + "where i.facilityId = :facilityId and i.roundNo >= :fromRoundNo")
    int shiftRoundNoToStagingRange(
            @Param("facilityId") Long facilityId,
            @Param("fromRoundNo") int fromRoundNo,
            @Param("offset") int offset);

    // #1702 — 회차 시프트 2단계. 1단계에서 상위 구간(>= offset)으로 옮겨둔 행만 골라 원래 값 +1로
    // 되돌린다. 밀리지 않은 행은 전부 offset 미만이므로(호출부가 max(round_no) < offset을 보장) 이
    // WHERE는 정확히 1단계 대상만 집는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Inspection i set i.roundNo = i.roundNo - :offset + 1 "
            + "where i.facilityId = :facilityId and i.roundNo >= :offset")
    int settleShiftedRoundNo(@Param("facilityId") Long facilityId, @Param("offset") int offset);

    // #1706 — 알림센터 회차 표기를 조회 시점 파생값으로 계산하기 위한 배치 조회(N+1 방지).
    // 회차 번호만 필요하므로 엔티티가 아니라 (id, roundNo) 프로젝션만 읽는다.
    @Query("select i.id as id, i.roundNo as roundNo from Inspection i where i.id in :ids")
    List<InspectionRoundNoProjection> findRoundNosByIds(@Param("ids") Collection<Long> ids);

    // #1591 P2 — 다음 점검일 재계산의 "이 회차가 최신인가" 판정용. 반드시 status 조건이 필요하다:
    // 상태를 보지 않으면 회차 <b>존재</b>만으로 max가 올라가는데
    // (createInspection은 이전 회차의 REPORTED 여부를 막지 않는다 — 미래 날짜만 거부하고 미종료
    // 회차는 경고창일 뿐), 재계산이 비교해야 하는 건 "아직 분석 중인 회차"가 아니라 이미 확정된
    // 회차다. status 없이 비교하면 "3회차 생성됨(미확정) + 2회차 확정" 조합에서 2회차의 정당한
    // 재계산이 통째로 스킵돼 다음 점검일이 옛 값에 고착된다.
    // 호출부(FacilityService#recalculateNextInspectionDueAt)는 같은 트랜잭션에서 이 회차를 REPORTED로
    // 전이시킨 직후에 부르므로, JPQL 실행 시 auto-flush 로 현재 회차도 집계에 포함된다.
    @Query("select max(i.inspectionDate) from Inspection i "
            + "where i.facilityId = :facilityId and i.status = :status")
    Optional<LocalDate> findMaxInspectionDateByFacilityIdAndStatus(
            @Param("facilityId") Long facilityId, @Param("status") InspectionStatus status);

    // 회차 간 비교(HAJA-531/#1112) — 시설물 1건의 특정 회차 단건 조회.
    Optional<Inspection> findByFacilityIdAndRoundNo(Long facilityId, Integer roundNo);

    // 시설물 상세 "점검 이력" 탭(#1359/HAJA-616) — 시설물 1건의 전체 회차를 최신순으로 조회.
    List<Inspection> findByFacilityIdOrderByRoundNoDesc(Long facilityId);

    // 시설물 현황 목록(#540 ⑥, HAJA-378) — 시설물별 "최근 점검일" 1건씩만 필요하다.
    // findRecentByFacilityIds 는 전체 시설물이 뒤섞인 플랫 리스트라 시설물별 최신 1건 추출에는
    // 부적합(서비스단 재그룹 없이는 못 씀). Postgres DISTINCT ON 으로 시설물별 최신 1건만
    // DB 단에서 골라 N+1/인메모리 재그룹 없이 반환한다(정렬은 findRecentByFacilityIds 와 동일 기준:
    // inspection_date desc, id desc — 동일 날짜 여러 회차 시 최신 등록분을 "최근 점검"으로 취급).
    // #1667 — findRecentByFacilityIds와 동일하게 performed_at을 id보다 먼저 tie-break로 확장한다
    // (nulls last이므로 performed_at 미세팅 회차는 기존 id desc 동작을 그대로 유지).
    @Query(value = "select distinct on (i.facility_id) i.* from inspections i "
            + "where i.facility_id in (:facilityIds) "
            + "order by i.facility_id, i.inspection_date desc, i.performed_at desc nulls last, i.id desc",
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
    //
    // 증분 분석(V42, #1654) — allowExistingDefects는 InspectionAnalysisService가 "이 실행이 ANALYZED
    // 회차의 증분 분석"(비삭제 하자가 있어도 append only라 안전)이라고 이미 판단했을 때만 true로
    // 넘긴다. true면 "비삭제 하자 없음" 조건 자체를 건너뛴다 — 그 외(false, 기본)는 기존과 동일하게
    // 하자 존재만으로 선점을 막는 fail-closed를 그대로 유지한다. 이 플래그를 여기(원자적 UPDATE의
    // 실제 방어선)에 반영하지 않으면, 서비스 계층의 사전 체크만 완화되고 이 WHERE는 여전히 모든
    // 증분 분석 시도를 0행으로 거부해버린다.
    @Modifying
    @Query("update Inspection i set i.status = :analyzingStatus "
            + "where i.id = :id and i.status in :allowedStatuses "
            + "and (:allowExistingDefects = true "
            + "or not exists (select 1 from Defect d where d.inspectionId = i.id and d.deleted = false))")
    int startAnalyzingIfNotRunning(
            @Param("id") Long id,
            @Param("analyzingStatus") InspectionStatus analyzingStatus,
            @Param("allowedStatuses") Collection<InspectionStatus> allowedStatuses,
            @Param("allowExistingDefects") boolean allowExistingDefects);

    // 검수 확정(InspectionService.confirmReview, PR머신 리뷰 P2) — read-then-advanceTo(더티 체킹)
    // 대신 원자적 조건부 UPDATE로 ANALYZED→REVIEWED를 쓴다. 위 startAnalyzingIfNotRunning과 같은
    // 이유: 사전에 읽은 엔티티의 status를 그대로 믿고 advanceTo만 하면, 그 사이(특히 하자 0건
    // 회차에서) 다른 요청이 재분석을 원자적으로 선점(ANALYZED→ANALYZING)해도 이 read-then-write가
    // 그걸 못 보고 그대로 REVIEWED로 덮어써 실행 중인 워커를 고아화한다. WHERE에 "여전히 ANALYZED"를
    // 강제해 그 경합을 원천 차단하고, 영향 행 0건이면 호출부가 다른 요청이 먼저 상태를 바꿨다고
    // 판정한다.
    @Modifying
    @Query("update Inspection i set i.status = :reviewedStatus "
            + "where i.id = :id and i.status = :fromStatus")
    int confirmReviewIfAnalyzed(
            @Param("id") Long id,
            @Param("reviewedStatus") InspectionStatus reviewedStatus,
            @Param("fromStatus") InspectionStatus fromStatus);

    // 점검 수행 시각 자동 세팅(V43, #1667, 코드 리뷰 P1-1) — MediaWriter가 회차의 INSPECTION_SOURCE
    // 미디어 저장 직후 회차별 배치 최솟값 candidate로 이 원자적 UPDATE를 호출한다. 원래는
    // Inspection.applyPerformedAt(read-modify-write, dirty checking) 이었으나, 배치 업로드 두 건이
    // 동시에 같은 회차에 미디어를 올리면 두 트랜잭션이 같은 findAllById 스냅샷을 읽고 각자 다른
    // candidate로 dirty-check flush해 나중에 커밋되는 쪽이 먼저 커밋된 더 이른(=올바른) 값을 조용히
    // 덮어쓸 수 있었다(lost update). startAnalyzingIfNotRunning/confirmReviewIfAnalyzed와 동일하게
    // WHERE의 조건부 비교를 DB가 원자적으로 평가하게 해 이 경합을 차단한다 — "이미 값이 없거나, 있어도
    // candidate보다 늦은 경우"에만 SET이 적용되므로 두 트랜잭션이 동시에 커밋돼도 항상 더 이른 값이
    // 남는다(LEAST 대신 조건부 SET — 의미는 동일하되 다른 컬럼 값에 의존하지 않아 더 읽기 쉽다).
    // 영향 행 0건 = 이미 더 이른(또는 같은) performed_at이 있어 갱신 불필요 — 호출부(MediaWriter)는
    // 결과를 별도로 분기하지 않는다(멱등한 "최선을 다한 갱신"이라 실패로 취급하지 않음).
    //
    // i.performedAt은 Inspection.performedAt(KstFixedLocalDateTimeConverter)에 매핑된 엔티티 경로라,
    // JPQL 경로 표현식(네이티브 SQL이 아님)을 통해 파라미터 바인딩과 컬럼 비교 양쪽 모두 그 컨버터를
    // 그대로 탄다 — Media.capturedAt과 동일 KST 고정 해석으로 대소 비교가 어긋나지 않는다.
    @Modifying
    @Query("update Inspection i set i.performedAt = :candidate "
            + "where i.id = :id and (i.performedAt is null or i.performedAt > :candidate)")
    int applyPerformedAtIfEarlier(@Param("id") Long id, @Param("candidate") LocalDateTime candidate);

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

    // 플랫폼 관리자 분석 잡 큐(#1408) — 회사 스코프 없이 전체 최근 N건, facility(주소 표시용) N+1
    // 방지를 위해 join fetch. findRecentByFacilityIds(#351)와 동일하게 건수 제한은 파생 쿼리가
    // 아니라 Pageable로 받는다.
    @Query("select i from Inspection i join fetch i.facility order by i.createdAt desc, i.id desc")
    List<Inspection> findRecentOrderByCreatedAtDesc(Pageable pageable);
}
