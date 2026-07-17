package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DefectRepository extends JpaRepository<Defect, Long> {

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
}
