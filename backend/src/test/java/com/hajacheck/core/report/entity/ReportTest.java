package com.hajacheck.core.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReportTest {

    @Test
    void draft_createsDraftReport() {
        Report report = Report.draft(10L, 2, "{\"summary\":\"result\"}", 20L);

        assertThat(report.getInspectionId()).isEqualTo(10L);
        assertThat(report.getVersion()).isEqualTo(2);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(report.getCreatedBy()).isEqualTo(20L);
    }

    @Test
    void updateContent_recordsGroundingResultAndEditor() {
        Report report = Report.draft(10L, 1, "{}", 20L);

        report.updateContent("{\"result\":true}", false, "[\"warning\"]", 30L);

        assertThat(report.getContentJson()).isEqualTo("{\"result\":true}");
        assertThat(report.getGroundingCheckPassed()).isFalse();
        assertThat(report.getGroundingWarnings()).isEqualTo("[\"warning\"]");
        assertThat(report.getEditedBy()).isEqualTo(30L);
    }

    @Test
    void finalizedReport_cannotBeFinalizedOrUpdatedAgain() {
        Report report = Report.draft(10L, 1, "{}", 20L);
        report.finalizeReport("https://files.example/report.pdf", 30L);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(report.getPdfUrl()).isEqualTo("https://files.example/report.pdf");
        assertThat(report.getEditedBy()).isEqualTo(30L);
        assertThatThrownBy(() -> report.finalizeReport("https://files.example/other.pdf", 31L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.updateContent("{\"changed\":true}", true, null, 31L))
                .isInstanceOf(IllegalStateException.class);
    }
}
