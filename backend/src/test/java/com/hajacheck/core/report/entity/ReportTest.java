package com.hajacheck.core.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportTest {

    @Test
    void draft_초안과버전을생성() {
        Report report = Report.draft(10L, 2, "{\"summary\":\"점검 결과\"}", 20L);

        assertThat(report.getInspectionId()).isEqualTo(10L);
        assertThat(report.getVersion()).isEqualTo(2);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(report.getCreatedBy()).isEqualTo(20L);
    }

    @Test
    void updateContent_근거검증결과와최종수정자를기록() {
        Report report = Report.draft(10L, 1, "{}", 20L);

        report.updateContent("{\"result\":true}", false, "[\"근거 확인 필요\"]", 30L);

        assertThat(report.getContentJson()).isEqualTo("{\"result\":true}");
        assertThat(report.getGroundingCheckPassed()).isFalse();
        assertThat(report.getGroundingWarnings()).isEqualTo("[\"근거 확인 필요\"]");
        assertThat(report.getEditedBy()).isEqualTo(30L);
    }

    @Test
    void finalizeReport_PDF와확정상태를기록() {
        Report report = Report.draft(10L, 1, "{}", 20L);

        report.finalizeReport("https://files.example/report.pdf", 30L);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(report.getPdfUrl()).isEqualTo("https://files.example/report.pdf");
        assertThat(report.getEditedBy()).isEqualTo(30L);
    }
}
