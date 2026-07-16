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

        byte[] originalBytes = readBytes(file);
        byte[] thumbnailBytes = ImageThumbnailGenerator.generate(originalBytes, properties.getThumbnailMaxDimension());
        StoredFile thumbnail = fileStorage.storeBytes(thumbnailBytes, THUMBNAIL_MIME_TYPE, THUMBNAIL_CATEGORY,
                THUMBNAIL_CONTENT_TYPES, THUMBNAIL_MAX_BYTES);
        storedKeys.add(thumbnail.storageKey());

        ExifData exif = ExifGpsExtractor.extract(originalBytes);

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
     */
    public ThumbnailFile getThumbnail(Long requesterUserId, Long mediaId) {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
        inspectionService.getInspection(requesterUserId, media.getInspectionId());
        if (media.getThumbnailUrl() == null) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
        }
        return new ThumbnailFile(fileStorage.read(media.getThumbnailUrl()), THUMBNAIL_MIME_TYPE);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    public record ThumbnailFile(byte[] content, String mimeType) {
    }
}
