package com.hajacheck.auth.support;

import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Collection;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 로컬 볼륨 파일 저장 구현(dev). 보안 원칙:
 * <ul>
 *   <li>원본 파일명 완전 무시 → 파일명은 UUID, 확장자는 <b>탐지된 contentType</b> 으로만 결정(사용자 입력 확장자 불신).</li>
 *   <li>MIME 화이트리스트 검증(호출부가 지정 — image/jpeg, image/png, application/pdf 등).</li>
 *   <li>저장 경로를 normalize 후 baseDir 하위인지 재확인(경로 트래버설 방지).</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorageService {

    // 탐지된 contentType → 확장자 매핑(화이트리스트와 1:1). 도메인이 늘어날 때 필요한 타입만 추가한다.
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
    public StoredFile store(MultipartFile file, String category,
                             Collection<String> allowedContentTypes, long maxSizeBytes) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        String contentType = validateContentType(file.getContentType(), allowedContentTypes);
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        try (InputStream in = file.getInputStream()) {
            return write(in, contentType, category);
        } catch (IOException e) {
            log.error("파일 저장 실패 category={}", category, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public StoredFile storeBytes(byte[] content, String contentType, String category,
                                  Collection<String> allowedContentTypes, long maxSizeBytes) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        String verifiedContentType = validateContentType(contentType, allowedContentTypes);
        if (content.length > maxSizeBytes) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        try (InputStream in = new ByteArrayInputStream(content)) {
            return write(in, verifiedContentType, category);
        } catch (IOException e) {
            log.error("파일 저장 실패 category={}", category, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private String validateContentType(String contentType, Collection<String> allowedContentTypes) {
        if (contentType == null || !allowedContentTypes.contains(contentType)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        // 화이트리스트에 있으나 확장자 매핑이 없으면(설정 확장 시 누락) 타입 거부로 안전측.
        if (!CONTENT_TYPE_EXTENSIONS.containsKey(contentType)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        return contentType;
    }

    private StoredFile write(InputStream in, String contentType, String category) throws IOException {
        String extension = CONTENT_TYPE_EXTENSIONS.get(contentType);

        // 저장키(상대경로): {category}/{uuid}{ext} — 원본 파일명은 사용하지 않는다.
        String safeCategory = sanitizeCategory(category);
        String fileName = UUID.randomUUID() + extension;
        String storageKey = safeCategory + "/" + fileName;

        Path target = baseDir.resolve(storageKey).normalize();
        // 경로 트래버설 방지: 최종 경로가 baseDir 하위인지 재확인.
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }

        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);

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

    @Override
    public byte[] read(String storageKey) {
        try {
            Path target = baseDir.resolve(storageKey).normalize();
            if (!target.startsWith(baseDir)) {
                throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
            }
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            // DB 행은 존재하나 디스크 파일이 유실/미생성된 경우(리뷰 P2) — 보상삭제 경합·스토리지
            // 정합성 깨짐 등. 조회 실패는 정상적인 최종 상태이지 서버 오류가 아니므로 500이 아닌
            // 404로 구분한다(썸네일은 그리드에서 병렬·빈번히 호출되어 500 스팸이 특히 부담스럽다).
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        } catch (IOException e) {
            log.error("파일 읽기 실패 storageKey={}", storageKey, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
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
