package com.hajacheck.auth.support;

import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.core.media.support.DetectedFileType;
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
 *   <li><b>매직바이트 검증</b> — 선언된 Content-Type 과 실제 파일 시그니처가 일치해야 저장한다.</li>
 *   <li>저장 경로를 normalize 후 baseDir 하위인지 재확인(경로 트래버설 방지).</li>
 * </ul>
 *
 * <p><b>왜 매직바이트까지 보는가(#1488)</b>: 멀티파트 파트 헤더의 {@code Content-Type} 은
 * 클라이언트가 한 줄로 바꿀 수 있다. 화이트리스트만 대조하면 <b>{@code .pdf} 로 저장된 파일의 내용이
 * 임의 바이트</b>일 수 있어, ①확장자와 실제 내용이 어긋난 파일이 볼륨에 쌓이고 ②나중에 사람이 증빙
 * (사업자등록증 등)을 열람할 때 그 내용을 신뢰할 수 없으며 ③"형식을 속여 상위 검사를 건너뛰는" 우회의
 * 발판이 된다. 저장 계층에서 실제 바이트를 확인하는 것이 마지막 방어선이다.
 *
 * <p>일부 호출부는 이미 상위에서 {@code ImageSignatureValidator} 로 같은 검사를 한다
 * ({@code CounselAttachmentService} 등) — 중복이지만 <b>의도된 다층 방어</b>다. 상위 검사가 빠진 경로가
 * 하나라도 생기면 그 즉시 구멍이 되기 때문에, 저장 계층은 호출부를 신뢰하지 않는다.
 *
 * <p>⚠️ <b>허용목록에 새 MIME 타입을 추가할 때는 {@link DetectedFileType} 에 시그니처를,
 * {@code CONTENT_TYPE_EXTENSIONS} 에 확장자를 반드시 함께 추가할 것.</b> 빠뜨리면 그 타입의 업로드가
 * 전부 {@code FILE_INVALID_TYPE} 로 실패한다(예: {@code video/mp4} 를 media-upload 허용목록에만 넣는
 * 경우 — {@code application.yml} 이 "영상은 후속 PR 범위"라고 예고해 둔 지뢰다). 그 실수는
 * {@code UploadContentTypeStartupValidator} 가 <b>기동 실패</b>로 잡아준다 — 런타임에 사용자 업로드가
 * 조용히 깨지는 것보다 배포 시점에 크게 터지는 편이 낫다.
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
        validateSignature(readHeader(file), contentType);
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
        validateSignature(content, verifiedContentType);
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

    /**
     * 선언된(그리고 화이트리스트를 통과한) contentType 이 <b>실제 파일 시그니처</b>와 일치하는지 확인한다.
     * 불일치하면 저장 자체를 거부한다({@link ErrorCode#FILE_INVALID_TYPE}) — 확장자와 내용이 어긋난
     * 파일을 볼륨에 남기지 않는다.
     */
    private void validateSignature(byte[] header, String contentType) {
        boolean matches = DetectedFileType.detect(header)
                .map(detected -> detected.mimeType().equals(contentType))
                .orElse(false);
        if (!matches) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
    }

    /** 시그니처 판별에 필요한 앞부분만 읽는다(전체 적재 없음). 읽기 실패는 형식 불량으로 간주한다. */
    private byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(DetectedFileType.HEADER_LENGTH);
        } catch (IOException e) {
            log.warn("업로드 파일 헤더 읽기 실패 — 형식 불량으로 처리. exception={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
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
