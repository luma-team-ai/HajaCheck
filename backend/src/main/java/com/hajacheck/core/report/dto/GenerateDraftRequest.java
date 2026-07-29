package com.hajacheck.core.report.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record GenerateDraftRequest(
        @NotEmpty Set<String> sections,
        Boolean includePhoto
) {
}
