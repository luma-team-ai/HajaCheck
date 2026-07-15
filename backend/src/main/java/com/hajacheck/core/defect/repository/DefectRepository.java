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
}
