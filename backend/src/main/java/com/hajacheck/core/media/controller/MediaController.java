package com.hajacheck.core.media.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.media.dto.MediaResponse;
import com.hajacheck.core.media.service.MediaService;
import com.hajacheck.core.media.service.MediaService.ThumbnailFile;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 촬영 데이터(이미지) 업로드/썸네일 조회 — PRD §7 "🔍 점검 관리 A"(황승현 주담당) / dev-05-03.
 * 업로드는 점검 회차 하위 경로, 썸네일 조회는 미디어 단건 경로(클라이언트가 업로드 응답의 id로만 접근).
 */
@Tag(name = "Media", description = "촬영 데이터(미디어) API")
@RestController
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @Operation(
            summary = "점검 회차별 촬영 데이터 목록 조회",
            description = "점검 회차에 업로드된 모든 미디어를 반환한다(분석 결과 뷰어 용, #803). "
                    + "하자 등급이 0개인 이미지도 포함되며, 각 항목에 thumbnailUrl/detailUrl이 포함된다."
    )
    @GetMapping("/api/inspections/{inspectionId}/media")
    public ResponseEntity<ApiResponse<List<MediaResponse>>> getMediaByInspection(
            @PathVariable Long inspectionId,
            @AuthenticationPrincipal LoginUser loginUser) {
        List<MediaResponse> response = mediaService.getMediaByInspection(
                loginUser.getUserId(), loginUser.getCompanyId(), inspectionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "촬영 데이터 업로드", description = "점검 회차에 이미지(JPG/PNG) 다중 업로드")
    @PostMapping("/api/inspections/{inspectionId}/media")
    public ResponseEntity<ApiResponse<List<MediaResponse>>> uploadMedia(
            @PathVariable Long inspectionId,
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal LoginUser loginUser) {
        List<MediaResponse> response = mediaService.uploadMedia(
                inspectionId, loginUser.getUserId(), loginUser.getCompanyId(), files);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(
            summary = "시설물 대표 사진 목록 조회",
            description = "시설물에 등록된 대표 사진(#632/#652)을 반환한다. 각 항목에 thumbnailUrl/detailUrl 포함."
    )
    @GetMapping("/api/facilities/{facilityId}/media")
    public ResponseEntity<ApiResponse<List<MediaResponse>>> getFacilityPhotos(
            @PathVariable Long facilityId,
            @AuthenticationPrincipal LoginUser loginUser) {
        List<MediaResponse> response = mediaService.getFacilityPhotos(
                loginUser.getUserId(), loginUser.getCompanyId(), facilityId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(
            summary = "시설물 대표 사진 업로드",
            description = "시설물 대표 사진(JPG/PNG) 다중 업로드 — 시설물당 최대 4장(#632/#652)"
    )
    @PostMapping("/api/facilities/{facilityId}/media")
    public ResponseEntity<ApiResponse<List<MediaResponse>>> uploadFacilityPhotos(
            @PathVariable Long facilityId,
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal LoginUser loginUser) {
        List<MediaResponse> response = mediaService.uploadFacilityPhotos(
                facilityId, loginUser.getUserId(), loginUser.getCompanyId(), files);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "미디어 썸네일 조회", description = "원본은 서빙하지 않고 재인코딩된 썸네일만 반환")
    @GetMapping("/api/media/{id}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        ThumbnailFile thumbnail =
                mediaService.getThumbnail(loginUser.getUserId(), loginUser.getCompanyId(), id);
        // 사용자별로 소유권 검증을 거쳐 다른 콘텐츠를 반환하는 사적(private) 이미지라(현장 GPS 결부),
        // 동일 URL이 공유 캐시(프록시/CDN)나 브라우저 캐시에 남아 다른 사용자·로그아웃 후 노출되면
        // 안 된다(리뷰 P2).
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(thumbnail.mimeType()))
                .body(thumbnail.content());
    }

    @Operation(summary = "미디어 상세 이미지 조회",
            description = "분석 결과 뷰어 전용 — 그리드용 썸네일보다 큰 해상도로 원본에서 재인코딩해 반환(원본 직접 서빙 안 함)")
    @GetMapping("/api/media/{id}/detail")
    public ResponseEntity<byte[]> getDetailImage(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        ThumbnailFile detail =
                mediaService.getDetailImage(loginUser.getUserId(), loginUser.getCompanyId(), id);
        // getThumbnail()과 동일한 이유로 no-store — 소유권 검증을 거치는 사적 이미지라 공유 캐시 금지.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(detail.mimeType()))
                .body(detail.content());
    }
}
