package com.hajacheck.core.report.support;

import com.hajacheck.core.report.config.ReportPdfStorageProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 로컬 볼륨 PDF 저장 구현(dev) — auth.support.LocalFileStorage 와 동일한 보안 원칙을 따르되
 * report 도메인 전용으로 새로 작성한다(도메인 결합 방지, #446 handoff 지시):
 * <ul>
 *   <li>파일명은 UUID 고정, 확장자는 항상 .pdf(원본 파일명 무시).</li>
 *   <li>content-type이 application/pdf가 아니면 거부.</li>
 *   <li>저장 경로를 normalize 후 baseDir 하위인지 재확인(경로 트래버설 방지).</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalReportPdfStorage implements ReportPdfStorage {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String CATEGORY = "reports";

    private final ReportPdfStorageProperties properties;
    private final Path baseDir;

    public LocalReportPdfStorage(ReportPdfStorageProperties properties) {
        this.properties = properties;
        this.baseDir = Paths.get(properties.getBaseDir()).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        if (!PDF_CONTENT_TYPE.equals(file.getContentType())) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String storageKey = CATEGORY + "/" + UUID.randomUUID() + ".pdf";
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }

        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("보고서 PDF 저장 실패", e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        String base = properties.getBaseUrlPath();
        String prefix = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return prefix + "/" + storageKey;
    }
}
