package com.hajacheck.counsel.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.dto.CounselAttachmentResponse;
import com.hajacheck.counsel.service.CounselAttachmentService;
import com.hajacheck.counsel.service.CounselAttachmentService.AttachmentFile;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 상담 채팅 이미지 첨부 API(FR-7, #20/HAJA-33) — 업로드(저장키 반환)/서빙(인가 후 바이트). 업로드/서빙 모두
 * 티켓 당사자만(서비스에서 검증). 원본을 정적 URL 로 노출하지 않고 서빙 엔드포인트로만 인가 접근한다.
 */
@Tag(name = "Counsel Attachment", description = "상담 채팅 이미지 첨부 API")
@RestController
@RequiredArgsConstructor
public class CounselAttachmentController {

    private final CounselAttachmentService counselAttachmentService;

    @Operation(summary = "상담 첨부 업로드", description = "이미지(JPG/PNG) 1건 업로드 후 저장키 반환(당사자만). 프론트는 이 저장키를 WS 메시지에 실어 보낸다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨")
    // consumes 미명시 시 springdoc 이 요청 바디를 기본값(application/json)으로 문서화한다 —
    // 이 엔드포인트는 multipart 만 받으므로 선언이 사실과 일치한다(MediaController 업로드와 동일).
    @PostMapping(value = "/api/counsel/tickets/{ticketId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CounselAttachmentResponse>> upload(
            @PathVariable Long ticketId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal LoginUser loginUser) {
        CounselAttachmentResponse response =
                counselAttachmentService.upload(ticketId, loginUser.getUserId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "상담 첨부 조회", description = "메시지 첨부 이미지 바이트 반환(당사자만, 개인 대화라 공유 캐시 금지).")
    // 업로드 허용 타입이 JPG/PNG 뿐이라 응답도 둘 중 하나다. 문서 전용 표기 —
    // 전역 default-produces-media-type(JSON)이 이미지 응답을 JSON으로 오문서화하는 걸 덮는다.
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "원본 이미지 바이트",
            headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "no-store, private (공유 캐시 금지)",
                    schema = @Schema(type = "string")),
            content = {
                @Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary"))
            })
    @GetMapping("/api/counsel/tickets/{ticketId}/messages/{messageId}/attachment")
    public ResponseEntity<byte[]> getAttachment(
            @PathVariable Long ticketId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal LoginUser loginUser) {
        AttachmentFile attachment =
                counselAttachmentService.read(ticketId, messageId, loginUser.getUserId());
        return ResponseEntity.ok()
                // 개인 대화 첨부 — 공유 캐시(프록시/CDN)·로그아웃 후 노출 방지(Media 썸네일과 동일 이유).
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(attachment.mimeType()))
                .body(attachment.content());
    }
}
