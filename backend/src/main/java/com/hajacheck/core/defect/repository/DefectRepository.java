package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DefectRepository extends JpaRepository<Defect, Long>, DefectRepositoryCustom {

    // 하자 목록 조회(HAJA-30)는 DefectRepositoryCustom/DefectRepositoryImpl(Criteria API)로 이전됨 —
    // JPQL "( :param is null or col = :param )" 패턴이 PostgreSQL named enum(defect_type 등)
    // null 바인딩 시 "could not determine data type of parameter"를 일으켜, 필터를 하나도 선택하지
    // 않은 기본 목록 조회가 항상 실패하는 버그가 있었다. Criteria API로 필터 미지정 시 predicate
    // 자체를 생성하지 않도록 전환.

    // 상세 조회 — id + 회사 스코프 단건. 미존재/타회사 소유 모두 빈 Optional(cross-company IDOR 방지,
    // FacilityRepository.findByIdAndCompanyId 와 동일 원칙).
    @Query("select d from Defect d join fetch d.inspection i join fetch i.facility f "
            + "where d.id = :id and f.companyId = :companyId and d.deleted = false")
    Optional<Defect> findByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);

    // 시설물 상세→하자 오버레이 직행(HAJA-434 갭1) — 시설물의 대표(최신) 하자 1건 id. 회사 스코프는
    // findByIdAndCompanyId와 동일 원칙(IDOR 방지), Pageable(0,1)로 최신 1건만 가져온다.
    @Query("select d.id from Defect d join d.inspection i join i.facility f "
            + "where f.id = :facilityId and f.companyId = :companyId and d.deleted = false "
            + "order by d.createdAt desc")
    List<Long> findLatestIdsByFacility(
            @Param("facilityId") Long facilityId, @Param("companyId") Long companyId, Pageable pageable);

    // 시설물 목록 배치용(HAJA-434 갭1 P1 픽스) — findLatestIdsByFacility를 시설물 수만큼 반복 호출하면
    // N+1이 되므로, 대상 시설물 전체의 (facilityId, defectId) 쌍을 한 번에 가져온다. facilityId asc,
    // createdAt desc로 정렬해 서비스 계층에서 facilityId별 첫 값만 취하면 시설물당 최신 1건이 된다
    // (Collectors.toMap의 mergeFunction으로 첫 값 유지 — listStatus()의 배치 조립 패턴과 동일 원칙).
    @Query("select f.id as facilityId, d.id as defectId from Defect d "
            + "join d.inspection i join i.facility f "
            + "where f.id in :facilityIds and f.companyId = :companyId and d.deleted = false "
            + "order by f.id asc, d.createdAt desc")
    List<FacilityLatestDefectProjection> findLatestByFacilityIds(
            @Param("facilityIds") Collection<Long> facilityIds, @Param("companyId") Long companyId);

    // 대시보드 조치대기 우선순위 목록(HAJA-17) — 등급(E→A) 우선, 미분류(grade=null)는 최하단, 동일 등급
    // 내에서는 최신순. PostgreSQL은 "ORDER BY ... DESC" 시 기본이 NULLS FIRST라, 파생 쿼리
    // (OrderByGradeDescCreatedAtDesc)로는 "nulls last"를 표현할 수 없어 미분류 하자가 등급 E보다
    // 위(최상단)로 노출되는 버그가 있었다(#327) — JPQL 로 전환해 명시적으로 nulls last 지정.
    // Top10 제한은 파생 쿼리의 findTop10 대신 Pageable(PageRequest.of(0, 10))로 유지한다.
    @Query("select d from Defect d where d.inspectionId in :inspectionIds and d.status = :status "
            + "and d.deleted = false order by d.grade desc nulls last, d.createdAt desc")
    List<Defect> findPendingPriorityDefects(
            @Param("inspectionIds") Collection<Long> inspectionIds,
            @Param("status") DefectStatus status,
            Pageable pageable);

    long countByInspectionIdInAndStatusAndDeletedFalse(Collection<Long> inspectionIds, DefectStatus status);

    @Query("select count(d) from Defect d where d.inspectionId in :inspectionIds and d.status = :status "
            + "and d.deleted = false and d.createdAt >= :from and d.createdAt < :to")
    long countByInspectionIdInAndStatusAndDeletedFalseAndCreatedAtRange(
            @Param("inspectionIds") Collection<Long> inspectionIds,
            @Param("status") DefectStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("select d.grade as grade, count(d) as cnt from Defect d "
            + "where d.inspectionId in :inspectionIds and d.deleted = false and d.grade is not null "
            + "group by d.grade")
    List<GradeCountProjection> countGroupByGrade(@Param("inspectionIds") Collection<Long> inspectionIds);

    @Query("select d.inspectionId as inspectionId, count(d) as cnt from Defect d "
            + "where d.inspectionId in :inspectionIds and d.deleted = false group by d.inspectionId")
    List<InspectionDefectCountProjection> countGroupByInspectionId(
            @Param("inspectionIds") Collection<Long> inspectionIds);

    // 마이페이지 "내 보고서" gradeDots(#844) — 점검별 실제 존재하는 등급만(중복 없이) 배치 조회.
    // countGroupByGrade는 inspectionId 전체를 하나로 합산해 카드별 분포 복원이 불가능해 별도로 둔다.
    @Query("select distinct d.inspectionId as inspectionId, d.grade as grade from Defect d "
            + "where d.inspectionId in :inspectionIds and d.deleted = false and d.grade is not null")
    List<InspectionGradeProjection> findDistinctGradesByInspectionIds(
            @Param("inspectionIds") Collection<Long> inspectionIds);

    // 점검 목록 등급분포(#893/HAJA-458) — 점검별 등급별 하자 건수 배치 조회. 목록 화면 카드 배지가
    // "등급 A 3건, 등급 B 1건" 처럼 점검별 세부 분포를 보여줘야 해서, 전체 합산인 countGroupByGrade와
    // 별도로 inspectionId 를 함께 그룹핑한다.
    @Query("select d.inspectionId as inspectionId, d.grade as grade, count(d) as cnt from Defect d "
            + "where d.inspectionId in :inspectionIds and d.deleted = false and d.grade is not null "
            + "group by d.inspectionId, d.grade")
    List<InspectionGradeCountProjection> countGroupByInspectionIdAndGrade(
            @Param("inspectionIds") Collection<Long> inspectionIds);

    // AI 주간 브리핑(#248 / HAJA-197) — 등록 기준 주간 하자 count(전 상태 포함), createdAt 기준
    // 명시적 반열림 [from,to) — PG timestamp 는 마이크로초 정밀도라 "-1ns" 트릭은 다음 자정으로
    // 반올림되어 BETWEEN(양끝 포함)과 사실상 동일해지고 주 경계 자정 값이 이중집계된다(리뷰 P1 픽스,
    // countByInspectionIdInAndStatusAndDeletedFalseAndCreatedAtRange 와 동일 패턴으로 대체).
    @Query("select count(d) from Defect d where d.inspectionId in :inspectionIds "
            + "and d.deleted = false and d.createdAt >= :from and d.createdAt < :to")
    long countByInspectionIdInAndDeletedFalseAndCreatedAtRange(
            @Param("inspectionIds") Collection<Long> inspectionIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // AI 주간 브리핑(#248 / HAJA-197) — 최다 발생 결함 유형 선정용, count 내림차순 + 동률 시 type asc
    // 로 결정적 정렬(리뷰 P2 픽스 — 동률일 때 순서가 비결정적이면 안 됨).
    @Query("select d.type as type, count(d) as cnt from Defect d "
            + "where d.inspectionId in :inspectionIds and d.deleted = false "
            + "group by d.type order by cnt desc, d.type asc")
    List<DefectTypeCountProjection> countGroupByTypeOrderByCntDesc(
            @Param("inspectionIds") Collection<Long> inspectionIds);

    // 보고서 생성(#446) — 확정된(검토 완료) 하자만 AI 보고서 입력으로 사용한다. DETECTED(AI 자동탐지 직후,
    // 사람 검토 전)는 제외하고 CONFIRMED/ACTION_PENDING/IN_PROGRESS/RESOLVED 만 포함한다.
    List<Defect> findByInspectionIdAndStatusInAndDeletedFalse(
            Long inspectionId, Collection<DefectStatus> statuses);

    // 점검 회차별 하자 목록 조회(검수·뷰어 공용) — deleted=false만, id 오름차순 정렬
    @Query("select d from Defect d where d.inspectionId = :inspectionId and d.deleted = false "
            + "order by d.id asc")
    List<Defect> findByInspectionIdAndNotDeleted(@Param("inspectionId") Long inspectionId);

    // AI 재분석 fail-closed 가드(코드 리뷰 P1 5차) — ANALYZED 회차에 비삭제 하자가 하나라도 있으면
    // 재분석을 거부한다(InspectionAnalysisService.hasExistingDefects). 목록을 로딩하지 않고 존재만 확인.
    boolean existsByInspectionIdAndDeletedFalse(Long inspectionId);
}
