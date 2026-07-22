package com.hajacheck.core.report.dto;

/** PDF 업로드 결과 — 저장된 접근 URL만 반환한다. */
public record ReportPdfResponse(String pdfUrl) {
}
