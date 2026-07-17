package com.hajacheck.core.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.core.ai.dto.ReportResponse;
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
    void updateContent후_내부AI근거검증결과와최종수정자를기록() {
        Report report = Report.draft(10L, 1, "{}", 20L);

        report.updateContent("{\"result\":true}", 30L);
        report.recordGroundingResult(grounding(false, "[\"근거 확인 필요\"]"), 30L);

        assertThat(report.getContentJson()).isEqualTo("{\"result\":true}");
        assertThat(report.getGroundingCheckPassed()).isFalse();
        assertThat(report.getGroundingWarnings()).isEqualTo("[\"근거 확인 필요\"]");
        assertThat(report.getEditedBy()).isEqualTo(30L);
    }

    @Test
    void finalizeReport_근거검증통과후PDF와확정상태를기록() {
        Report report = Report.draft(10L, 1, "{}", 20L);
        report.recordGroundingResult(grounding(true, null), 30L);

        report.finalizeReport("https://files.example/report.pdf", 30L);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(report.getPdfUrl()).isEqualTo("https://files.example/report.pdf");
        assertThat(report.getEditedBy()).isEqualTo(30L);
    }

    @Test
    void finalizeReport_확정후재확정하거나수정하면예외() {
        Report report = Report.draft(10L, 1, "{}", 20L);
        report.recordGroundingResult(grounding(true, null), 30L);
        report.finalizeReport("https://files.example/report.pdf", 30L);

        assertThatThrownBy(() -> report.finalizeReport("https://files.example/other.pdf", 31L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> report.updateContent("{\"changed\":true}", 31L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalizeReport_근거검증미통과또는미수행이면예외() {
        Report unchecked = Report.draft(10L, 1, "{}", 20L);
        Report failed = Report.draft(10L, 2, "{}", 20L);
        failed.recordGroundingResult(grounding(false, "[\"근거 확인 필요\"]"), 30L);

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
    void draft_버전이1보다작으면예외() {
        assertThatThrownBy(() -> Report.draft(10L, 0, "{}", 20L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Report.draft(10L, -1, "{}", 20L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalizeReport_PDF주소가없으면초안상태를유지하고예외() {
        Report nullUrl = Report.draft(10L, 1, "{}", 20L);
        nullUrl.recordGroundingResult(grounding(true, null), 30L);
        Report blankUrl = Report.draft(10L, 2, "{}", 20L);
        blankUrl.recordGroundingResult(grounding(true, null), 30L);

        assertThatThrownBy(() -> nullUrl.finalizeReport(null, 30L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> blankUrl.finalizeReport("  ", 30L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(nullUrl.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(blankUrl.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(nullUrl.getPdfUrl()).isNull();
        assertThat(blankUrl.getPdfUrl()).isNull();
    }

    @Test
    void updateContent_본문이없으면기존내용을유지하고예외() {
        Report report = Report.draft(10L, 1, "{\"original\":true}", 20L);

        assertThatThrownBy(() -> report.updateContent(" ", 30L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(report.getContentJson()).isEqualTo("{\"original\":true}");
        assertThat(report.getGroundingCheckPassed()).isNull();
        assertThat(report.getEditedBy()).isNull();
    }

    @Test
    void draft_본문이유효한JSON이아니면예외() {
        assertThatThrownBy(() -> Report.draft(10L, 1, "{invalid", 20L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundingResult_근거경고가유효한JSON이아니면예외() {
        assertThatThrownBy(() -> grounding(false, "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundingResult_통과와동시에불일치경고가있으면예외() {
        assertThatThrownBy(() -> grounding(true, "[\"근거 확인 필요\"]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groundingResult_공백경고는null로정규화() {
        Report report = Report.draft(10L, 1, "{}", 20L);

        report.recordGroundingResult(grounding(true, "   "), 30L);

        assertThat(report.getGroundingWarnings()).isNull();
    }

    @Test
    void groundingResult_통과와빈배열경고는허용() {
        Report report = Report.draft(10L, 1, "{}", 20L);

        report.recordGroundingResult(grounding(true, "[]"), 30L);

        assertThat(report.getGroundingCheckPassed()).isTrue();
        assertThat(report.getGroundingWarnings()).isEqualTo("[]");
    }

    @Test
    void updateContent_기존grounding판정을무효화() {
        Report report = Report.draft(10L, 1, "{}", 20L);
        report.recordGroundingResult(grounding(true, null), 20L);

        report.updateContent("{\"changed\":true}", 30L);

        assertThat(report.getGroundingCheckPassed()).isNull();
        assertThat(report.getGroundingWarnings()).isNull();
        assertThatThrownBy(() -> report.finalizeReport("https://files.example/report.pdf", 30L))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GroundingCheckResult grounding(boolean passed, String warnings) {
        ReportResponse aiReport = new ReportResponse(null, null, null, null, passed);
        return GroundingCheckResult.fromAiReport(aiReport, warnings);
    }
}
