package com.hajacheck.core.report.support;

import com.hajacheck.core.report.config.ReportPdfStorageProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 로컬 볼륨 PDF 저장 구현(dev) — auth.support.LocalFileStorage 와 동일한 보안 원칙을 따르되
 * report 도메인 전용으로 새로 작성한다(도메인 결합 방지, #446 handoff 지시):
 * <ul>
 *   <li>파일명은 UUID 고정, 확장자는 항상 .pdf(원본 파일명 무시).</li>
 *   <li>content-type이 application/pdf가 아니면 거부, 실제 바이트도 PDF 매직넘버(%PDF-)로
 *       시작하는지 검증한다(content-type 헤더 스푸핑 방지, #455 P2-3).</li>
 *   <li>저장 경로를 normalize 후 baseDir 하위인지 재확인(경로 트래버설 방지).</li>
 * </ul>
 *
 * <p>정적 리소스 핸들러로 직접 서빙하지 않는다(#455 P2-1) — {@link #load(String)}는 컨트롤러의
 * 소유권 검증(IDOR 방지)을 통과한 뒤에만 호출되고, storageKey 는 단일 경로 세그먼트(UUID 파일명)만
 * 허용해 baseDir 밖 경로 트래버설을 차단한다.
 */
@Slf4j
@Component
public class LocalReportPdfStorage implements ReportPdfStorage {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final byte[] PDF_MAGIC_NUMBER = {'%', 'P', 'D', 'F', '-'};
    private static final String CATEGORY = "reports";

    private final ReportPdfStorageProperties properties;
    private final Path baseDir;

    public LocalReportPdfStorage(ReportPdfStorageProperties properties) {
        this.properties = properties;
        this.baseDir = Paths.get(properties.getBaseDir()).toAbsolutePath().normalize().resolve(CATEGORY);
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

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("보고서 PDF 저장 실패", e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        if (!startsWithPdfMagicNumber(bytes)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }

        String storageKey = UUID.randomUUID() + ".pdf";
        Path target = resolveWithinBaseDir(storageKey);

        try {
            Files.createDirectories(baseDir);
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("보고서 PDF 저장 실패", e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        return storageKey;
    }

    @Override
    public Resource load(String storageKey) {
        // storageKey 는 단일 경로 세그먼트만 허용 — "/", ".." 포함 시 트래버설 시도로 간주한다.
        if (storageKey == null || storageKey.isBlank()
                || storageKey.contains("/") || storageKey.contains("\\") || storageKey.contains("..")) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        Path target = resolveWithinBaseDir(storageKey);
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    private Path resolveWithinBaseDir(String storageKey) {
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return target;
    }

    private static boolean startsWithPdfMagicNumber(byte[] bytes) {
        if (bytes.length < PDF_MAGIC_NUMBER.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC_NUMBER.length; i++) {
            if (bytes[i] != PDF_MAGIC_NUMBER[i]) {
                return false;
            }
        }
        return true;
    }
}
