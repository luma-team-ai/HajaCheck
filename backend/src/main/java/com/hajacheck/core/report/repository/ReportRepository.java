package com.hajacheck.core.report.repository;

import com.hajacheck.core.report.entity.Report;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // 보고서 버전 목록(최신순) — #446.
    List<Report> findByInspectionIdOrderByVersionDesc(Long inspectionId);

    // 다음 버전 계산용 — 최신 버전 1건만 조회해 서비스에서 +1 한다.
    Optional<Report> findFirstByInspectionIdOrderByVersionDesc(Long inspectionId);
}
