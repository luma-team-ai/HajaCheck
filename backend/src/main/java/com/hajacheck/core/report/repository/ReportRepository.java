package com.hajacheck.core.report.repository;

import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // #1702 — 회차 필터는 점검의 현재 회차(i.roundNo)가 아니라 보고서가 발급 시점에 스냅샷한
    // r.roundNo 를 기준으로 한다. 목록에 표시되는 회차 자체가 스냅샷(CompanyReportListItemResponse)
    // 이므로, 필터가 실시간 회차를 보면 "3회차로 필터했는데 목록엔 2회차로 표시"되는 모순이 난다.
    @Query(value = "select r from Report r join fetch r.inspection i join fetch i.facility f "
            + "where f.companyId = :companyId and r.status in :statuses "
            + "and r.deletedAt is null "
            + "and (:facilityId = -1 or f.id = :facilityId) "
            + "and (:roundNo = -1 or r.roundNo = :roundNo) "
            + "and (:query = '' or lower(f.name) like lower(concat('%', :query, '%'))) "
            + "and r.updatedAt >= :updatedAtFrom",
            countQuery = "select count(r) from Report r join r.inspection i join i.facility f "
                    + "where f.companyId = :companyId and r.status in :statuses "
                    + "and r.deletedAt is null "
                    + "and (:facilityId = -1 or f.id = :facilityId) "
                    + "and (:roundNo = -1 or r.roundNo = :roundNo) "
                    + "and (:query = '' or lower(f.name) like lower(concat('%', :query, '%'))) "
                    + "and r.updatedAt >= :updatedAtFrom")
    Page<Report> findCompanyPage(@Param("companyId") Long companyId,
            @Param("statuses") List<ReportStatus> statuses, @Param("facilityId") Long facilityId,
            @Param("roundNo") Integer roundNo,
            @Param("query") String query, @Param("updatedAtFrom") LocalDateTime updatedAtFrom,
            Pageable pageable);

    @Query("select count(r) as totalCount, "
            + "coalesce(sum(case when r.status = :finalized then 1 else 0 end), 0) as finalizedCount, "
            + "coalesce(sum(case when r.status = :draft then 1 else 0 end), 0) as draftCount, "
            + "coalesce(sum(case when r.status = :finalized and r.updatedAt >= :monthStart then 1 else 0 end), 0) "
            + "as issuedThisMonthCount from Report r join r.inspection i join i.facility f "
            + "where f.companyId = :companyId and r.deletedAt is null")
    CompanyReportSummaryProjection summarizeCompany(@Param("companyId") Long companyId,
            @Param("finalized") ReportStatus finalized, @Param("draft") ReportStatus draft,
            @Param("monthStart") LocalDateTime monthStart);

    // 보고서 버전 목록(최신순) — #446.
    List<Report> findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc(Long inspectionId);

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
            + "and r.deletedAt is null "
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
            + "and r.deletedAt is null "
            + "and (i.assignedInspectorId = :userId or i.createdBy = :userId)")
    long countMyFinalizedReports(
            @Param("userId") Long userId, @Param("companyId") Long companyId, @Param("status") ReportStatus status);

    /**
     * 회차 재정렬(#1702) 후 해당 시설물의 <b>DRAFT</b> 보고서 회차 스냅샷을 점검의 현재 회차에 다시
     * 맞춘다. {@code InspectionService#reserveRoundNo}가 시프트 직후 같은 트랜잭션에서 호출한다.
     *
     * <p>FINALIZED를 제외하는 것이 이 컬럼의 존재 이유다 — 확정 보고서는 발급 시점에 회차가 동결되어야
     * 이미 인쇄·제출된 PDF 표지의 "제N회차"와 영구히 일치한다. 반대로 DRAFT는 아직 발급 전 살아있는
     * 문서라 현재 회차를 따라가야 한다.
     *
     * <p>절대값 대입({@code = i.round_no})이라 멱등하고, 스냅샷이 어떤 이유로든 어긋난 DRAFT까지 함께
     * 복구한다(+1 증분이었다면 어긋난 값은 어긋난 채로 밀렸을 것). soft delete된 초안은 다시 노출되지
     * 않으므로 건드리지 않는다.
     *
     * <p>{@code updated_at}은 일부러 갱신하지 않는다 — 시스템 재번호는 사용자의 편집이 아니라서,
     * 갱신하면 손대지도 않은 초안이 "최근 수정" 목록·기간 필터(reportPeriodStart) 맨 앞으로 튀어오른다.
     *
     * <p>JPQL은 UPDATE의 FROM 조인을 표현하지 못해 네이티브로 둔다. status는 PG named enum
     * ({@code report_status_type})이라 문자열 파라미터를 명시적으로 캐스팅해 바인딩한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update reports r set round_no = i.round_no from inspections i "
            + "where r.inspection_id = i.id and i.facility_id = :facilityId "
            + "and r.status = cast(:draftStatus as report_status_type) "
            + "and r.deleted_at is null and r.round_no <> i.round_no",
            nativeQuery = true)
    int syncDraftRoundNoToInspection(
            @Param("facilityId") Long facilityId, @Param("draftStatus") String draftStatus);
}
