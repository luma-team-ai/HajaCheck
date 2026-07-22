package com.hajacheck.core.report.dto;

import jakarta.validation.constraints.NotBlank;

public record FinalizeReportRequest(@NotBlank String pdfUrl) {
}
