package com.hajacheck.core.report.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.core.report.config.ReportPdfStorageProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "sample.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4 sample content".getBytes());
    }

    @Test
    void deleteAll_보고서디렉터리전체를제거한다() throws IOException {
        Long reportId = 30L;
        String storageKey = storage.store(reportId, pdfFile());
        assertThat(storage.load(reportId, storageKey).exists()).isTrue();

        storage.deleteAll(reportId);

        assertThatThrownBy(() -> storage.load(reportId, storageKey))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void deleteAll_존재하지않는reportId는아무일도하지않는다() {
        assertThatCode(() -> storage.deleteAll(999L)).doesNotThrowAnyException();
    }

    @Test
    void listReportIdsWithStoredFiles_파일이있는reportId만나열() throws IOException {
        Long reportIdA = 41L;
        Long reportIdB = 42L;
        storage.store(reportIdA, pdfFile());
        storage.store(reportIdB, pdfFile());

        List<Long> reportIds = storage.listReportIdsWithStoredFiles();

        assertThat(reportIds).containsExactlyInAnyOrder(reportIdA, reportIdB);
    }

    @Test
    void listReportIdsWithStoredFiles_저장된파일이없으면빈목록() {
        assertThat(storage.listReportIdsWithStoredFiles()).isEmpty();
    }

    @Test
    void deleteOrphans_keepStorageKey는보존하고오래된나머지만삭제() throws IOException {
        Long reportId = 50L;
        String keepKey = storage.store(reportId, pdfFile());
        String staleKey = storage.store(reportId, pdfFile());
        // 저장 직후엔 둘 다 새 파일이라 mtime을 과거로 되돌려 "N일 경과"를 재현한다.
        Path reportDir = tempDir.resolve("reports").resolve(String.valueOf(reportId));
        Files.setLastModifiedTime(reportDir.resolve(staleKey),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        int removed = storage.deleteOrphans(reportId, keepKey, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(removed).isEqualTo(1);
        assertThat(storage.load(reportId, keepKey).exists()).isTrue();
        assertThatThrownBy(() -> storage.load(reportId, staleKey))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void deleteOrphans_유예기간내파일은삭제하지않는다() throws IOException {
        Long reportId = 51L;
        String recentKey = storage.store(reportId, pdfFile());

        int removed = storage.deleteOrphans(reportId, null, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(removed).isEqualTo(0);
        assertThat(storage.load(reportId, recentKey).exists()).isTrue();
    }

    @Test
    void deleteOrphans_존재하지않는reportId는0을반환한다() {
        assertThat(storage.deleteOrphans(999L, null, Instant.now())).isZero();
    }
}
