package com.hajacheck.core.rag.controller;

import com.hajacheck.core.rag.dto.RagDocumentResponse;
import com.hajacheck.core.rag.dto.RagDocumentUploadRequest;
import com.hajacheck.core.rag.service.RagDocumentService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 콘솔 — RAG 문서 관리(#22/HAJA-35, PRD FR-8-B). ADMIN 인가는 SecurityConfig 의 URL 매처
 * ("/api/admin/**" → hasRole(ADMIN)) 가 필터 단계에서 강제한다 — 다른 관리자 컨트롤러(AdminPlanController/
 * AdminUserController)와 동일한 가드를 그대로 재사용한다. company 스코핑은 두지 않는다(법규·지침 문서는
 * 회사 소유 리소스가 아니라 플랫폼 전체가 공유하는 지식베이스 원본).
 */
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/rag-documents")
@RequiredArgsConstructor
@Validated
public class RagDocumentController {

    private final RagDocumentService ragDocumentService;

    @Operation(summary = "RAG 문서 목록 조회", description = "법규·지침 PDF 문서와 임베딩 상태를 최신 등록순으로 반환한다(ADMIN 전용).")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RagDocumentResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(ragDocumentService.list()));
    }

    @Operation(summary = "RAG 문서 업로드",
            description = "법규·지침 PDF를 업로드해 텍스트를 추출하고 AI 서버 임베딩 파이프라인을 실행한다(ADMIN 전용). "
                    + "AI 서버 임베딩 실패는 업로드 자체를 실패시키지 않는다 — FAILED 상태로 남고 재임베딩으로 복구한다.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RagDocumentResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @Valid @ModelAttribute RagDocumentUploadRequest request) {
        RagDocumentResponse response = ragDocumentService.upload(file, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "RAG 문서 재임베딩",
            description = "기존 상태(대기/완료/실패)와 무관하게 임베딩을 재실행한다(ADMIN 전용, 명시적 관리자 액션으로만 "
                    + "트리거 — 자동 재임베딩 없음). AI 서버가 동일 문서의 기존 청크를 삭제 후 재삽입하는 idempotent 설계다.")
    @PostMapping("/{id}/re-embed")
    public ResponseEntity<ApiResponse<RagDocumentResponse>> reEmbed(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(ragDocumentService.reEmbed(id)));
    }
}
