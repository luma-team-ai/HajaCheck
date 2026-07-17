package com.hajacheck.core.media.service;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.config.MediaUploadProperties;
import com.hajacheck.core.media.dto.MediaResponse;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.media.support.ExifGpsExtractor;
import com.hajacheck.core.media.support.ExifGpsExtractor.ExifData;
import com.hajacheck.core.media.support.ImageSignatureValidator;
import com.hajacheck.core.media.support.ImageThumbnailGenerator;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 촬영 데이터(이미지) 업로드(dev-05-03, PRD FR-2 이미지 핵심 범위). 파일 IO는 트랜잭션 밖에서 수행하고
 * DB 원자저장은 {@link MediaWriter}(별도 @Transactional 빈)에 위임한다 — CompanySignupService 와
 * 동일한 패턴(self-invocation 회피, 긴 IO 가 DB 커넥션을 점유하지 않도록).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MediaService {

    private static final String ORIGINAL_CATEGORY = "inspection-media";
    private static final String THUMBNAIL_CATEGORY = "inspection-media-thumb";
    private static final Set<String> THUMBNAIL_CONTENT_TYPES = Set.of("image/jpeg");
    // 썸네일은 thumbnailMaxDimension 으로 이미 축소되므로 이 상한은 순전히 방어적 상한선.
    private static final long THUMBNAIL_MAX_BYTES = 2_000_000L;
    private static final String THUMBNAIL_MIME_TYPE = "image/jpeg";

    private final MediaRepository mediaRepository;
    private final MediaWriter mediaWriter;
    private final InspectionService inspectionService;
    private final FileStorageService fileStorage;
    private final MediaUploadProperties properties;

    /**
     * ① 개수/소유권 검증 ② 전체 파일 매직바이트 검증(all-or-nothing) ③ 원본+썸네일 저장(트랜잭션 밖 IO)
     * + EXIF/GPS 추출 ④ DB 원자저장(writer) — 실패 시 저장한 파일 전부 보상삭제.
     *
     * <p>⚠️ NOT_SUPPORTED로 클래스 레벨 readOnly=true를 명시적으로 벗어난다 — 그렇지 않으면 파일 IO 내내
     * 읽기전용 트랜잭션이 열려 있는 채로 {@link MediaWriter#saveAll}이 REQUIRED로 같은 트랜잭션에 합류해
     * INSERT가 읽기전용 위반으로 실패한다(CompanySignupService와 동일하게 "트랜잭션 밖 IO, 별도 빈에서
     * 진짜 새 트랜잭션" 패턴을 따르되, 이 클래스는 getThumbnail()을 위해 클래스 레벨 readOnly=true를
     * 유지하므로 이 메서드에서만 명시적으로 무효화해야 한다).
     *
     * <p>⚠️ 소유권 검증(getInspection→FacilityService.get())은 "조회 가능한 사용자"가 아니라
     * {@code Facility.ownerId == requesterUserId} 단일 일치를 요구한다(FacilityService 클래스 문서:
     * "모든 조회/수정/삭제는 owner 스코프로 제한"). 즉 이 도메인엔 "읽기는 되지만 쓰기는 안 되는" 별도
     * 권한 계층이 아직 없어 조회 검증을 업로드(쓰기)에 재사용해도 권한 상승이 되지 않는다(리뷰 P2 확인).
     * assignedInspectorId 기반의 세분화된 역할 권한은 SecurityConfig 에 명시된 대로 후속 과제.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MediaResponse> uploadMedia(Long inspectionId, Long requesterUserId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        if (files.size() > properties.getMaxFilesPerRequest()) {
            throw new BusinessException(ErrorCode.MEDIA_COUNT_EXCEEDED);
        }

        // 소유권 검증 + 존재 확인 — FacilityService.get() 기반 IDOR 방지 로직을 그대로 재사용(중복 없음).
        inspectionService.getInspection(requesterUserId, inspectionId);

        // 전체 파일을 먼저 검증한다(all-or-nothing) — 하나라도 실패하면 아무것도 저장하지 않는다.
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new BusinessException(ErrorCode.FILE_REQUIRED);
            }
            ImageSignatureValidator.validate(file);
        }

        List<String> storedKeys = new ArrayList<>();
        try {
            List<Media> mediaList = new ArrayList<>();
            for (MultipartFile file : files) {
                mediaList.add(storeAndBuild(inspectionId, file, storedKeys));
            }
            return mediaWriter.saveAll(mediaList).stream().map(MediaResponse::from).toList();
        } catch (RuntimeException e) {
            // DB 저장 실패(또는 그 사이 어떤 예외든) — 이번 요청에서 저장한 파일을 전부 보상삭제해 고아 파일 방지.
            storedKeys.forEach(fileStorage::delete);
            throw e;
        }
    }

    private Media storeAndBuild(Long inspectionId, MultipartFile file, List<String> storedKeys) {
        StoredFile original = fileStorage.store(file, ORIGINAL_CATEGORY,
                properties.getAllowedContentTypes(), properties.getMaxSizeBytes());
        storedKeys.add(original.storageKey());

        // byte[] 전체를 앱 힙에 올리지 않고 스트리밍으로 처리 — 각 유틸이 필요한 만큼만 읽는다
        // (최대 20개 파일 배치 업로드에서 힙 압박을 줄이기 위함). MultipartFile은 임시 저장소
        // 기반이라 getInputStream()을 여러 번 독립적으로 호출해도 매번 처음부터 읽힌다.
        byte[] thumbnailBytes;
        try (InputStream in = file.getInputStream()) {
            thumbnailBytes = ImageThumbnailGenerator.generate(in, properties.getThumbnailMaxDimension());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        StoredFile thumbnail = fileStorage.storeBytes(thumbnailBytes, THUMBNAIL_MIME_TYPE, THUMBNAIL_CATEGORY,
                THUMBNAIL_CONTENT_TYPES, THUMBNAIL_MAX_BYTES);
        storedKeys.add(thumbnail.storageKey());

        ExifData exif;
        try (InputStream in = file.getInputStream()) {
            exif = ExifGpsExtractor.extract(in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        return Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl(original.storageKey())
                .thumbnailUrl(thumbnail.storageKey())
                .capturedAt(exif.capturedAt())
                .gpsLat(exif.gpsLat())
                .gpsLng(exif.gpsLng())
                .mimeSignatureVerified(true)
                .mimeType(file.getContentType())
                .build();
    }

    /**
     * 썸네일 조회(인가된 서빙 엔드포인트 전용) — 소유권 재검증 후 바이트를 반환한다.
     * 원본(originalUrl)은 어떤 경로로도 읽어 반환하지 않는다(PRD FR-2 원본 비공개 정책).
     *
     * <p>⚠️ uploadMedia()와 동일한 이유로 NOT_SUPPORTED — 클래스 레벨 readOnly=true 트랜잭션을 연 채로
     * fileStorage.read()의 블로킹 디스크 IO를 수행하면 DB 커넥션을 불필요하게 오래 점유한다. 조회는
     * 병렬·빈번하게 호출될 수 있어(썸네일 그리드) 커넥션 풀 고갈 위험이 업로드보다 오히려 크다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ThumbnailFile getThumbnail(Long requesterUserId, Long mediaId) {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
        inspectionService.getInspection(requesterUserId, media.getInspectionId());
        if (media.getThumbnailUrl() == null) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
        }
        try {
            return new ThumbnailFile(fileStorage.read(media.getThumbnailUrl()), THUMBNAIL_MIME_TYPE);
        } catch (BusinessException e) {
            // DB 행(Media)은 있으나 디스크 파일이 유실된 경우(리뷰 P2, FileStorageService.read()가
            // FILE_NOT_FOUND로 구분)도 클라이언트 입장에선 위 두 케이스와 동일한 "이 미디어의 썸네일을
            // 찾을 수 없다"는 404다 — 저장소 구현 세부를 노출하지 않고 MEDIA_NOT_FOUND로 통일한다.
            if (e.getErrorCode() == ErrorCode.FILE_NOT_FOUND) {
                throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
            }
            throw e;
        }
    }

    public record ThumbnailFile(byte[] content, String mimeType) {
    }
}
