package com.hajacheck.core.report.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.core.report.config.ReportPdfStorageProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class LocalReportPdfStorageTest {

    @TempDir
    Path tempDir;

    private LocalReportPdfStorage storage;

    @BeforeEach
    void setUp() {
        ReportPdfStorageProperties properties = new ReportPdfStorageProperties();
        properties.setBaseDir(tempDir.toString());
        properties.setMaxSizeBytes(10_000_000L);
        storage = new LocalReportPdfStorage(properties);
    }

    @Test
    void store_and_load_success_isolated_by_report_id() throws IOException {
        byte[] pdfBytes = "%PDF-1.4 sample content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);

        Long reportIdA = 10L;
        String storageKey = storage.store(reportIdA, file);

        assertThat(storageKey).endsWith(".pdf");

        // Load with correct reportId -> success
        Resource resource = storage.load(reportIdA, storageKey);
        assertThat(resource.exists()).isTrue();
        try (var is = resource.getInputStream()) {
            assertThat(is.readAllBytes()).isEqualTo(pdfBytes);
        }

        // Load with different reportId (IDOR attempt) -> FILE_NOT_FOUND (404)
        Long reportIdB = 20L;
        assertThatThrownBy(() -> storage.load(reportIdB, storageKey))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void load_path_traversal_attempt_throws_FILE_NOT_FOUND() {
        assertThatThrownBy(() -> storage.load(10L, "../secret.pdf"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);

        assertThatThrownBy(() -> storage.load(10L, "sub/dir.pdf"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }
}
