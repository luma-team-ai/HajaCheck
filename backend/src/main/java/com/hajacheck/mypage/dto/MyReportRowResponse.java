package com.hajacheck.mypage.dto;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.report.entity.Report;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GET /api/me/reports 목록 항목(#844, handoff §2-3).
 *
 * <p>title은 반환하지 않는다 — Report 엔티티에 제목 컬럼이 없어 화면 제목({@code [{yy-RR}] {시설물명}
 * 점검 보고서})은 프론트가 조립한다. issuedAt은 {@link Report#getUpdatedAt()}(finalize 시점 근사) —
 * Report에 별도 발급 timestamp 컬럼이 없어 finalizeReport() 호출 시 갱신되는 BaseTimeEntity.updatedAt을
 * 대신 쓴다. fileSizeBytes는 PDF 조회 실패 시 예외 대신 null(서비스 계층에서 산출).
 */
public record MyReportRowResponse(
        Long id,
        Long inspectionId,
        String facilityName,
        Integer roundNo,
        LocalDateTime issuedAt,
        Long fileSizeBytes,
        List<ReportGradeDotColor> gradeDots,
        String pdfUrl) {

    // 심각도 순(RED → ORANGE → GREEN) 고정 — 존재하는 색만 중복 없이 이 순서로 반환한다(handoff §2-3).
    private static final List<ReportGradeDotColor> SEVERITY_ORDER =
            List.of(ReportGradeDotColor.RED, ReportGradeDotColor.ORANGE, ReportGradeDotColor.GREEN);

    public static MyReportRowResponse from(
            Report report, String facilityName, Integer roundNo, Set<DefectGrade> grades, Long fileSizeBytes) {
        return new MyReportRowResponse(
                report.getId(),
                report.getInspectionId(),
                facilityName,
                roundNo,
                report.getUpdatedAt(),
                fileSizeBytes,
                toGradeDots(grades),
                report.getPdfUrl());
    }

    private static List<ReportGradeDotColor> toGradeDots(Set<DefectGrade> grades) {
        Set<ReportGradeDotColor> colors = new LinkedHashSet<>();
        for (DefectGrade grade : grades) {
            colors.add(ReportGradeDotColor.from(grade));
        }
        return SEVERITY_ORDER.stream().filter(colors::contains).toList();
    }
}
