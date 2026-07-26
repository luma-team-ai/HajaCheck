package com.hajacheck.core.report.repository;

import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

    @Query(value = "select r from Report r join fetch r.inspection i join fetch i.facility f "
            + "where f.companyId = :companyId and r.status in :statuses "
            + "and (:facilityId = -1 or f.id = :facilityId) "
            + "and (:query = '' or lower(f.name) like lower(concat('%', :query, '%'))) "
            + "and r.updatedAt >= :updatedAtFrom",
            countQuery = "select count(r) from Report r join r.inspection i join i.facility f "
                    + "where f.companyId = :companyId and r.status in :statuses "
                    + "and (:facilityId = -1 or f.id = :facilityId) "
                    + "and (:query = '' or lower(f.name) like lower(concat('%', :query, '%'))) "
                    + "and r.updatedAt >= :updatedAtFrom")
    Page<Report> findCompanyPage(@Param("companyId") Long companyId,
            @Param("statuses") List<ReportStatus> statuses, @Param("facilityId") Long facilityId,
            @Param("query") String query, @Param("updatedAtFrom") LocalDateTime updatedAtFrom,
            Pageable pageable);

    @Query("select count(r) as totalCount, "
            + "coalesce(sum(case when r.status = :finalized then 1 else 0 end), 0) as finalizedCount, "
            + "coalesce(sum(case when r.status = :draft then 1 else 0 end), 0) as draftCount, "
            + "coalesce(sum(case when r.status = :finalized and r.updatedAt >= :monthStart then 1 else 0 end), 0) "
            + "as issuedThisMonthCount from Report r join r.inspection i join i.facility f "
            + "where f.companyId = :companyId")
    CompanyReportSummaryProjection summarizeCompany(@Param("companyId") Long companyId,
            @Param("finalized") ReportStatus finalized, @Param("draft") ReportStatus draft,
            @Param("monthStart") LocalDateTime monthStart);

    // 보고서 버전 목록(최신순) — #446.
    List<Report> findByInspectionIdOrderByVersionDesc(Long inspectionId);

    // 다음 버전 계산용 — 최신 버전 1건만 조회해 서비스에서 +1 한다.
    Optional<Report> findFirstByInspectionIdOrderByVersionDesc(Long inspectionId);

    // 마이페이지 "내 보고서" 목록(#844) — "내 점검"(assignedInspectorId 또는 createdBy가 본인) 소속이면서
    // 회사 스코프 안의 FINALIZED 보고서만, 최신순(updatedAt desc)으로 상한(Pageable) 안에서 반환한다.
    // issuedAtFrom은 not-null 필수(period=ALL이면 서비스가 LocalDateTime.MIN을 넘긴다) — 실측(#844)상
    // "(:param is null or 컬럼 >= :param)" 형태는 PG 확장 프로토콜에서 같은 이름 파라미터라도 Hibernate가
    // JPQL의 각 텍스트 등장 위치를 별도 JDBC 바인드 위치로 분리해, "IS NULL" 단독 위치는 타입 추론
    // 문맥이 없어 named enum이 아닌 LocalDateTime에도 "could not determine data type of parameter"로
    // 실패한다(AdminUserRepository 주석의 "keyword는 String이라 영향 없음" 가정은 이 케이스에선 성립하지
    // 않음 — 이 리포지토리는 Criteria API 대신 항상-바인딩 sentinel로 우회).
    @Query("select r from Report r join fetch r.inspection i join fetch i.facility f "
            + "where r.status = :status and f.companyId = :companyId "
            + "and (i.assignedInspectorId = :userId or i.createdBy = :userId) "
            + "and r.updatedAt >= :issuedAtFrom "
            + "order by r.updatedAt desc, r.id desc")
    List<Report> findMyFinalizedReports(
            @Param("userId") Long userId,
            @Param("companyId") Long companyId,
            @Param("status") ReportStatus status,
            @Param("issuedAtFrom") LocalDateTime issuedAtFrom,
            Pageable pageable);

    // 마이페이지 "내 점검 이력" 요약의 issuedReportCount(#844) — 위와 동일 스코프의 FINALIZED 건수만.
    @Query("select count(r) from Report r join r.inspection i "
            + "where r.status = :status and i.facility.companyId = :companyId "
            + "and (i.assignedInspectorId = :userId or i.createdBy = :userId)")
    long countMyFinalizedReports(
            @Param("userId") Long userId, @Param("companyId") Long companyId, @Param("status") ReportStatus status);
}
