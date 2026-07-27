package com.hajacheck.core.media.service;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.facility.service.FacilityService;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private static final String DETAIL_CATEGORY = "inspection-media-detail";
    private static final Set<String> THUMBNAIL_CONTENT_TYPES = Set.of("image/jpeg");
    // 썸네일은 thumbnailMaxDimension 으로 이미 축소되므로 이 상한은 순전히 방어적 상한선.
    private static final long THUMBNAIL_MAX_BYTES = 2_000_000L;
    // 상세 이미지는 썸네일(400px)보다 훨씬 큰 detailMaxDimension(기본 1600px)으로 인코딩되므로
    // 픽셀 수 비례로 상한도 크게 잡는다(방어적 상한선, 순정 사진 압축률 기준 여유 포함).
    private static final long DETAIL_MAX_BYTES = 8_000_000L;
    private static final String THUMBNAIL_MIME_TYPE = "image/jpeg";
    // 레거시(V13 이전) 행의 상세 이미지 즉석 생성 동시 실행 상한 — 배포 직후 레거시 인스펙션을 열면
    // 그리드 하자 수만큼 원본(최대 20MB) 디코딩이 한꺼번에 몰릴 수 있어 방어적으로 제한한다(#788/#789
    // PR머신 리뷰 P2). ponytail: 인스턴스 로컬 세마포어라 여러 앱 인스턴스 전체 합산은 상한×인스턴스수 —
    // 레거시 행이 시간이 지나면 자연 소멸하는 유한 집합이라 그 이상의 분산 제한(Redis 등)은 과함.
    private static final int MAX_CONCURRENT_LEGACY_DETAIL_GENERATION = 4;
    // PR머신 리뷰 P1(#789) — permit이 없을 때 무기한 대기하면 요청 스레드(Tomcat 워커)가 계속 점유돼
    // 레거시 행 대상 동시 요청 폭주 시 스레드풀 고갈로 번진다. 대기 상한을 두고 초과하면 즉시 503으로
    // 반환해 워커 스레드를 붙잡지 않는다("거부 없는 완화"가 아니라 "상한부 대기 후 거부"로 전환).
    // static final이 아닌 인스턴스 필드 — 테스트가 ReflectionTestUtils로 값을 줄여 5초 대기 없이
    // BUSY 분기를 검증할 수 있게 한다(static final 상수는 컴파일 타임에 인라인되어 리플렉션으로 못 바꿈).
    private long legacyDetailGenerationWaitSeconds = 5;
    private final Semaphore legacyDetailGenerationLimiter =
            new Semaphore(MAX_CONCURRENT_LEGACY_DETAIL_GENERATION);

    // 시설물 대표 사진(#632/#652, HAJA-377) — 시설물당 최대 등록 장수. 애플리케이션 레벨 카운트로 강제한다
    // (기존 보유분 + 이번 업로드 합계가 이 값을 넘으면 거부).
    private static final int MAX_FACILITY_PHOTOS = 4;

    private final MediaRepository mediaRepository;
    private final MediaWriter mediaWriter;
    private final InspectionService inspectionService;
    private final FacilityService facilityService;
    private final FileStorageService fileStorage;
    private final MediaUploadProperties properties;
    private final CompanyScopeGuard companyScopeGuard;

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
     * {@code Facility.companyId == companyId} 일치를 요구한다(FacilityService 클래스 문서:
     * "모든 조회/수정/삭제는 회사 스코프로 제한"). 즉 이 도메인엔 "읽기는 되지만 쓰기는 안 되는" 별도
     * 권한 계층이 아직 없어 조회 검증을 업로드(쓰기)에 재사용해도 권한 상승이 되지 않는다(리뷰 P2 확인).
     * assignedInspectorId 기반의 세분화된 역할 권한은 SecurityConfig 에 명시된 대로 후속 과제.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MediaResponse> uploadMedia(
            Long inspectionId, Long userId, Long companyId, List<MultipartFile> files) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        if (files.size() > properties.getMaxFilesPerRequest()) {
            throw new BusinessException(ErrorCode.MEDIA_COUNT_EXCEEDED);
        }

        // 소유권 검증 + 존재 확인 — FacilityService.get() 기반 IDOR 방지 로직을 그대로 재사용(중복 없음).
        inspectionService.getInspection(userId, companyId, inspectionId);

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
                mediaList.add(storeAndBuild(inspectionId, null, file, storedKeys));
            }
            return mediaWriter.saveAll(mediaList).stream().map(MediaResponse::from).toList();
        } catch (RuntimeException e) {
            // DB 저장 실패(또는 그 사이 어떤 예외든) — 이번 요청에서 저장한 파일을 전부 보상삭제해 고아 파일 방지.
            storedKeys.forEach(fileStorage::delete);
            throw e;
        }
    }

    /**
     * 시설물 대표 사진 업로드(#632/#652, HAJA-377) — Option B. 새 테이블/새 저장 파이프라인을 만들지 않고
     * {@link #uploadMedia}와 동일한 검증·저장·썸네일·보상삭제 로직을 그대로 재사용하되, 소유권 검증은
     * 점검이 아니라 시설물 회사 스코프({@link FacilityService#get})로 하고, 저장하는 Media 로우는
     * inspection_id 대신 facility_id 만 채운다(폴리모픽 XOR).
     *
     * <p>최대 {@value #MAX_FACILITY_PHOTOS}장 제한은 DB 제약이 아니라 애플리케이션 레벨 카운트로 강제한다 —
     * 기존 보유분({@link MediaRepository#countByFacilityId})과 이번 업로드 개수의 합이 상한을 넘으면
     * 파일을 하나도 저장하기 전에 거부한다(FACILITY_PHOTO_COUNT_EXCEEDED).
     *
     * <p>⚠️ {@link #uploadMedia}와 동일하게 NOT_SUPPORTED 로 클래스 레벨 readOnly=true 를 벗어난다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MediaResponse> uploadFacilityPhotos(
            Long facilityId, Long userId, Long companyId, List<MultipartFile> files) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }

        // 소유권 검증 + 존재 확인 — FacilityService.get() 의 회사 스코프 조회로 cross-company IDOR 방지.
        // companyId 는 인증 주체에서 유래하며(컨트롤러가 LoginUser 에서 전달) 클라이언트 파라미터가 아니다.
        facilityService.get(userId, companyId, facilityId);

        // 최대 4장 제한(애플리케이션 레벨) — 기존 보유분 + 이번 업로드 합계 검증. 파일 저장 전에 거부한다.
        long existing = mediaRepository.countByFacilityId(facilityId);
        if (existing + files.size() > MAX_FACILITY_PHOTOS) {
            throw new BusinessException(ErrorCode.FACILITY_PHOTO_COUNT_EXCEEDED);
        }

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
                mediaList.add(storeAndBuild(null, facilityId, file, storedKeys));
            }
            return mediaWriter.saveAll(mediaList).stream().map(MediaResponse::from).toList();
        } catch (RuntimeException e) {
            storedKeys.forEach(fileStorage::delete);
            throw e;
        }
    }

    /**
     * 시설물 대표 사진 목록 조회(#632/#652) — 소유권 검증 후 facility_id 만 채워진 로우를 id asc 로 반환한다.
     */
    @Transactional(readOnly = true)
    public List<MediaResponse> getFacilityPhotos(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityService.get(userId, companyId, facilityId);

        return mediaRepository.findByFacilityIdOrderByIdAsc(facilityId).stream()
                .map(MediaResponse::from)
                .toList();
    }

    private Media storeAndBuild(
            Long inspectionId, Long facilityId, MultipartFile file, List<String> storedKeys) {
        StoredFile original = fileStorage.store(file, ORIGINAL_CATEGORY,
                properties.getAllowedContentTypes(), properties.getMaxSizeBytes());
        storedKeys.add(original.storageKey());

        // byte[] 전체를 앱 힙에 올리지 않고 스트리밍으로 처리 — 각 유틸이 필요한 만큼만 읽는다
        // (최대 20개 파일 배치 업로드에서 힙 압박을 줄이기 위함). MultipartFile은 임시 저장소
        // 기반이라 getInputStream()을 여러 번 독립적으로 호출해도 매번 처음부터 읽힌다.
        // EXIF를 먼저 읽는 이유: Orientation 태그를 썸네일 재인코딩에 반영해야 한다(리뷰 P2) —
        // 대부분 스마트폰은 센서를 가로로 고정하고 촬영 방향만 Orientation으로 기록하므로, 이를
        // 무시하면 세로로 찍은 사진의 썸네일이 90° 눕혀진 채로 그리드에 노출된다.
        ExifData exif;
        try (InputStream in = file.getInputStream()) {
            exif = ExifGpsExtractor.extract(in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        byte[] thumbnailBytes;
        try (InputStream in = file.getInputStream()) {
            thumbnailBytes = ImageThumbnailGenerator.generate(
                    in, properties.getThumbnailMaxDimension(), exif.orientation());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        StoredFile thumbnail = fileStorage.storeBytes(thumbnailBytes, THUMBNAIL_MIME_TYPE, THUMBNAIL_CATEGORY,
                THUMBNAIL_CONTENT_TYPES, THUMBNAIL_MAX_BYTES);
        storedKeys.add(thumbnail.storageKey());

        // 상세 이미지(분석 결과 뷰어 전용, 썸네일보다 큰 해상도)도 업로드 시점에 1회 생성해 저장한다 —
        // 조회 시마다 원본을 재디코딩하던 성능 문제(PR머신 리뷰 P2, #789)를 썸네일과 동일한 패턴으로 해결.
        byte[] detailBytes;
        try (InputStream in = file.getInputStream()) {
            detailBytes = ImageThumbnailGenerator.generate(
                    in, properties.getDetailMaxDimension(), exif.orientation());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        StoredFile detail = fileStorage.storeBytes(detailBytes, THUMBNAIL_MIME_TYPE, DETAIL_CATEGORY,
                THUMBNAIL_CONTENT_TYPES, DETAIL_MAX_BYTES);
        storedKeys.add(detail.storageKey());

        return Media.builder()
                .inspectionId(inspectionId)
                .facilityId(facilityId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl(original.storageKey())
                .thumbnailUrl(thumbnail.storageKey())
                .detailUrl(detail.storageKey())
                .capturedAt(exif.capturedAt())
                .gpsLat(exif.gpsLat())
                .gpsLng(exif.gpsLng())
                .mimeSignatureVerified(true)
                .mimeType(file.getContentType())
                .build();
    }

    /**
     * 점검 회차별 미디어 목록 조회(#803 분석 결과 뷰어) — 업로드된 모든 미디어를 반환한다(하자 유무 무관).
     * 결과에는 각 미디어의 썸네일/상세이미지 URL이 포함되어 있어, 프론트가 이미지 갤러리를 구성할 수 있다.
     *
     * @param userId          요청 사용자 id
     * @param companyId       요청 사용자의 회사 id
     * @param inspectionId    점검 회차 id
     * @return 미디어 목록 (id 오름차순, 각 항목에 thumbnailUrl, detailUrl 포함)
     * @throws BusinessException 점검 회차 미존재 또는 타인 소유 (404 INSPECTION_NOT_FOUND via IDOR guard)
     */
    @Transactional(readOnly = true)
    public List<MediaResponse> getMediaByInspection(Long userId, Long companyId, Long inspectionId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        // 소유권 검증 — 타인 회차면 INSPECTION_NOT_FOUND(404)
        inspectionService.getInspection(userId, companyId, inspectionId);

        List<Media> medias = mediaRepository.findByInspectionIdOrderByIdAsc(inspectionId);
        return medias.stream().map(MediaResponse::from).toList();
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
    public ThumbnailFile getThumbnail(Long userId, Long companyId, Long mediaId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Media media = loadOwnedMedia(userId, companyId, mediaId);
        if (media.getThumbnailUrl() == null) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
        }
        return new ThumbnailFile(readOrMediaNotFound(media.getThumbnailUrl()), THUMBNAIL_MIME_TYPE);
    }

    /**
     * 분석 결과 뷰어(상세검수) 전용 상세 이미지 — 그리드·지도팝업용 썸네일(400px 상한)로는 크랙 폭 같은
     * 하자를 육안으로 판별하기 어려워(#788) 업로드 시 미리 생성해 둔 상세 이미지(detailUrl, V13)를
     * 그대로 읽어 반환한다(getThumbnail()과 동일 패턴 — 조회마다 재인코딩하지 않는다).
     *
     * <p>V13 이전에 업로드된 기존 행은 detailUrl이 없다(백필 안 함) — 그 경우 매 조회마다 원본에서
     * 즉석 생성하는 폴백을 탄다.
     *
     * <p>ponytail: write-through 캐시(생성 결과를 detailUrl에 저장)는 일부러 안 한다 — 캐시를 넣으면
     * 그 캐시 자체의 동시성(동일 mediaId 동시 최초조회 시 고아 파일)·부분실패 관측성 문제가 새로 생겨
     * PR머신 2라운드에 걸쳐 계속 지적됐다. 레거시 행은 시간이 지나면 자연히 사라지는 유한 집합(신규
     * 업로드는 전부 V13 경로로 처음부터 detailUrl을 가짐)이라 감수할 만한 트레이드오프로 판단.
     * 대신 {@link #legacyDetailGenerationLimiter}로 동시 재인코딩 수만 제한한다 — 배포 직후 레거시
     * 인스펙션을 열면 그리드 하자 수만큼 원본(최대 20MB) 디코딩이 한꺼번에 몰릴 수 있어서다. permit
     * 획득은 {@link #legacyDetailGenerationWaitSeconds} 상한부 대기이며, 초과 시 요청 스레드를
     * 계속 점유하지 않도록 즉시 503(MEDIA_DETAIL_GENERATION_BUSY)으로 거부한다(PR머신 리뷰 P1, #789).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ThumbnailFile getDetailImage(Long userId, Long companyId, Long mediaId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Media media = loadOwnedMedia(userId, companyId, mediaId);
        if (media.getDetailUrl() != null) {
            return new ThumbnailFile(readOrMediaNotFound(media.getDetailUrl()), THUMBNAIL_MIME_TYPE);
        }
        return generateLegacyDetailImage(media);
    }

    private ThumbnailFile generateLegacyDetailImage(Media media) {
        boolean acquired;
        try {
            acquired = legacyDetailGenerationLimiter.tryAcquire(
                    legacyDetailGenerationWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.MEDIA_DETAIL_GENERATION_BUSY);
        }
        try {
            byte[] originalBytes = readOrMediaNotFound(media.getOriginalUrl());
            int orientation = ExifGpsExtractor.extract(new ByteArrayInputStream(originalBytes)).orientation();
            byte[] detailBytes = ImageThumbnailGenerator.generate(
                    new ByteArrayInputStream(originalBytes), properties.getDetailMaxDimension(), orientation);
            return new ThumbnailFile(detailBytes, THUMBNAIL_MIME_TYPE);
        } finally {
            legacyDetailGenerationLimiter.release();
        }
    }

    // 소유권 검증 — 미디어 존재 + 그 미디어가 속한 점검 회차가 요청자 회사 소속인지. 존재 여부 열거
    // 방지(리뷰 P2) — 타인 소유 미디어(getInspection이 던지는 FACILITY_NOT_FOUND/INSPECTION_NOT_FOUND)와
    // 아예 없는 미디어(MEDIA_NOT_FOUND)를 error.code로 구분할 수 있으면 안 된다(openapi.yaml·클래스
    // 문서가 명시한 "존재 여부 열거 방지 통일 응답" 계약). 실패 사유와 무관하게 동일한
    // MEDIA_NOT_FOUND(404)로 통일한다. getThumbnail/getDetailImage 공용.
    private Media loadOwnedMedia(Long userId, Long companyId, Long mediaId) {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
        try {
            // 폴리모픽 소유(Option B, #632) — facility_id 만 채워진 시설물 대표 사진 로우는 inspection_id 가
            // null 이라 getInspection() 으로 인가하면 null FK 조회로 500/인가 누락이 난다. 소유 주체별로
            // 분기해 facility 전용 로우는 FacilityService.get() 의 회사 스코프 조회로 인가한다(리스크 감사
            // 필수처리 1). 두 조회 모두 companyId 는 인증 주체에서 유래한다(클라이언트 파라미터 아님).
            if (media.getFacilityId() != null) {
                facilityService.get(userId, companyId, media.getFacilityId());
            } else {
                inspectionService.getInspection(userId, companyId, media.getInspectionId());
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INSPECTION_NOT_FOUND
                    || e.getErrorCode() == ErrorCode.FACILITY_NOT_FOUND) {
                throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
            }
            throw e;
        }
        return media;
    }

    // DB 행(Media)은 있으나 디스크 파일이 유실된 경우(리뷰 P2, FileStorageService.read()가
    // FILE_NOT_FOUND로 구분)도 클라이언트 입장에선 "이 미디어를 찾을 수 없다"는 404와 동일하다 —
    // 저장소 구현 세부를 노출하지 않고 MEDIA_NOT_FOUND로 통일한다.
    private byte[] readOrMediaNotFound(String storageKey) {
        try {
            return fileStorage.read(storageKey);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.FILE_NOT_FOUND) {
                throw new BusinessException(ErrorCode.MEDIA_NOT_FOUND);
            }
            throw e;
        }
    }

    public record ThumbnailFile(byte[] content, String mimeType) {
    }
}
