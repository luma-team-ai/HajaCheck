package com.hajacheck.auth.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.BusinessLicenseFileService;
import com.hajacheck.auth.service.BusinessLicenseFileService.LicenseFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 회사 소속을 재검증한 뒤 사업자등록증을 반환하는 비정적 파일 엔드포인트. */
@Tag(name = "BusinessLicense", description = "사업자등록증 보호 파일 API")
@RestController
@RequiredArgsConstructor
public class BusinessLicenseFileController {

    private final BusinessLicenseFileService businessLicenseFileService;

    @Operation(summary = "사업자등록증 조회", description = "유효한 회사 소속과 저장키를 검증한 뒤 파일을 반환한다")
    // Content-Type은 저장된 파일 확장자로 결정된다(업로드 허용 타입 기준). 문서 전용 표기 —
    // 전역 default-produces-media-type(JSON)이 바이너리 응답을 JSON으로 오문서화하는 걸 덮는다.
    @ApiResponse(responseCode = "200", description = "파일 바이너리(Content-Type은 확장자로 결정)",
            content = {
                @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                        schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        schema = @Schema(type = "string", format = "binary"))
            })
    @GetMapping("/api/companies/{companyId}/business-license/{*storageKey}")
    public ResponseEntity<byte[]> download(
            @PathVariable Long companyId,
            @PathVariable String storageKey,
            @AuthenticationPrincipal LoginUser loginUser) {
        LicenseFile file = businessLicenseFileService.load(companyId, loginUser.getUserId(), storageKey);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename("business-license" + file.extension())
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
