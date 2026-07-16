package com.hajacheck.core.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void finalizeReport_근거검증통과후PDF와확정상태를기록() {
        Report report = Report.draft(10L, 1, "{}", 20L);
        report.updateContent("{}", true, null, 30L);

        report.finalizeReport("https://files.example/report.pdf", 30L);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(report.getPdfUrl()).isEqualTo("https://files.example/report.pdf");
        assertThat(report.getEditedBy()).isEqualTo(30L);
    }

    @Test
    void finalizeReport_확정후재확정하거나수정하면예외() {
        Report report = Report.draft(10L, 1, "{}", 20L);
        report.updateContent("{}", true, null, 30L);
        report.finalizeReport("https://files.example/report.pdf", 30L);

        assertThatThrownBy(() -> report.finalizeReport("https://files.example/other.pdf", 31L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.updateContent("{\"changed\":true}", true, null, 31L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalizeReport_근거검증미통과또는미수행이면예외() {
        Report unchecked = Report.draft(10L, 1, "{}", 20L);
        Report failed = Report.draft(10L, 2, "{}", 20L);
        failed.updateContent("{}", false, "[\"근거 확인 필요\"]", 30L);

        assertThatThrownBy(() -> unchecked.finalizeReport("https://files.example/unchecked.pdf", 30L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.finalizeReport("https://files.example/failed.pdf", 30L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(unchecked.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(failed.getStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    @Test
    void draft_본문이없으면예외() {
        assertThatThrownBy(() -> Report.draft(10L, 1, null, 20L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Report.draft(10L, 1, "  ", 20L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateContent_본문이없으면기존내용을유지하고예외() {
        Report report = Report.draft(10L, 1, "{\"original\":true}", 20L);

        assertThatThrownBy(() -> report.updateContent(" ", true, null, 30L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(report.getContentJson()).isEqualTo("{\"original\":true}");
        assertThat(report.getGroundingCheckPassed()).isNull();
        assertThat(report.getEditedBy()).isNull();
    }
}
