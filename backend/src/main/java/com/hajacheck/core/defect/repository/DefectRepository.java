package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    // 대시보드 조치대기 우선순위 목록(HAJA-17) — 등급(E→A) 우선, 최신순.
    List<Defect> findTop10ByInspectionIdInAndStatusAndDeletedFalseOrderByGradeDescCreatedAtDesc(
            Collection<Long> inspectionIds, DefectStatus status);

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

    // AI 주간 브리핑(#248 / HAJA-197) — 등록 기준 주간 하자 count(전 상태 포함, createdAt 기준 [from,to) 는
    // 호출측이 to 를 다음 경계 직전(-1ns)으로 넘겨 사실상 반열림 구간으로 사용한다).
    long countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
            Collection<Long> inspectionIds, LocalDateTime from, LocalDateTime to);

    // AI 주간 브리핑(#248 / HAJA-197) — 최다 발생 결함 유형 선정용, count 내림차순 정렬.
    @Query("select d.type as type, count(d) as cnt from Defect d "
            + "where d.inspectionId in :inspectionIds and d.deleted = false group by d.type order by cnt desc")
    List<DefectTypeCountProjection> countGroupByTypeOrderByCntDesc(
            @Param("inspectionIds") Collection<Long> inspectionIds);
}
