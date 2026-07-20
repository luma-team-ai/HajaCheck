package com.hajacheck.core.report.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateReportContentRequest(@NotBlank String contentJson) {
}
