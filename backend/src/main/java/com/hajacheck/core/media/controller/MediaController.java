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

    @Operation(summary = "촬영 데이터 업로드", description = "점검 회차에 이미지(JPG/PNG) 다중 업로드")
    @PostMapping("/api/inspections/{inspectionId}/media")
    public ResponseEntity<ApiResponse<List<MediaResponse>>> uploadMedia(
            @PathVariable Long inspectionId,
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal LoginUser loginUser) {
        List<MediaResponse> response = mediaService.uploadMedia(inspectionId, loginUser.getCompanyId(), files);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "미디어 썸네일 조회", description = "원본은 서빙하지 않고 재인코딩된 썸네일만 반환")
    @GetMapping("/api/media/{id}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        ThumbnailFile thumbnail = mediaService.getThumbnail(loginUser.getCompanyId(), id);
        // 사용자별로 소유권 검증을 거쳐 다른 콘텐츠를 반환하는 사적(private) 이미지라(현장 GPS 결부),
        // 동일 URL이 공유 캐시(프록시/CDN)나 브라우저 캐시에 남아 다른 사용자·로그아웃 후 노출되면
        // 안 된다(리뷰 P2).
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(thumbnail.mimeType()))
                .body(thumbnail.content());
    }
}
