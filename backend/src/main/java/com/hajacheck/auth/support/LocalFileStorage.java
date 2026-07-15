package com.hajacheck.auth.support;

import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 로컬 볼륨 파일 저장 구현(dev). 보안 원칙:
 * <ul>
 *   <li>원본 파일명 완전 무시 → 파일명은 UUID, 확장자는 <b>탐지된 contentType</b> 으로만 결정(사용자 입력 확장자 불신).</li>
 *   <li>MIME 화이트리스트 검증(image/jpeg, image/png, application/pdf).</li>
 *   <li>저장 경로를 normalize 후 baseDir 하위인지 재확인(경로 트래버설 방지).</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorageService {

    // 탐지된 contentType → 확장자 매핑(화이트리스트와 1:1).
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "application/pdf", ".pdf");

    private final FileStorageProperties properties;
    private final Path baseDir;

    public LocalFileStorage(FileStorageProperties properties) {
        this.properties = properties;
        this.baseDir = Paths.get(properties.getBaseDir()).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(MultipartFile file, String category) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }

        String contentType = file.getContentType();
        if (contentType == null || !properties.getAllowedContentTypes().contains(contentType)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        // 화이트리스트에 있으나 확장자 매핑이 없으면(설정 확장 시 누락) 타입 거부로 안전측.
        String extension = CONTENT_TYPE_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }

        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        // 저장키(상대경로): {category}/{uuid}{ext} — 원본 파일명은 사용하지 않는다.
        String safeCategory = sanitizeCategory(category);
        String fileName = UUID.randomUUID() + extension;
        String storageKey = safeCategory + "/" + fileName;

        Path target = baseDir.resolve(storageKey).normalize();
        // 경로 트래버설 방지: 최종 경로가 baseDir 하위인지 재확인.
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }

        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("파일 저장 실패 storageKey={}", storageKey, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        String url = joinUrl(properties.getBaseUrlPath(), storageKey);
        return new StoredFile(url, storageKey);
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            Path target = baseDir.resolve(storageKey).normalize();
            if (!target.startsWith(baseDir)) {
                log.warn("보상삭제 경로 이탈 감지 — 삭제 스킵 storageKey={}", storageKey);
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // 보상삭제는 best-effort — 실패해도 상위 흐름을 막지 않는다(로깅만).
            log.warn("파일 보상삭제 실패 storageKey={}", storageKey, e);
        }
    }

    private String sanitizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "misc";
        }
        // 영문/숫자/하이픈만 허용 — 경로 조작 문자 제거.
        String cleaned = category.replaceAll("[^a-zA-Z0-9-]", "");
        return cleaned.isBlank() ? "misc" : cleaned;
    }

    private String joinUrl(String base, String storageKey) {
        String prefix = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return prefix + "/" + storageKey;
    }
}
