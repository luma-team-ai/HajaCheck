package com.hajacheck.core.ai.controller;

import com.hajacheck.core.ai.dto.DefectExplainRequest;
import com.hajacheck.core.ai.dto.DefectExplainResponse;
import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 서버(FastAPI) 인증 프록시 — 모든 AI 호출은 스프링(세션 인증)을 강제 경유한다(#228 / HAJA-188).
 * SecurityConfig 의 anyRequest().authenticated() 로 이미 보호되므로 별도 permitAll/인가 설정 불필요.
 */
@Tag(name = "AI Proxy", description = "AI 서버 인증 프록시 API")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiProxyController {

    private final AiProxyService aiProxyService;

    @Operation(summary = "하자 원인·조치방안 설명", description = "로그인 사용자 요청을 인증 프록시로 AI 서버에 전달해 원인·위험·조치방안을 반환한다")
    @PostMapping("/defect-explain")
    public ResponseEntity<ApiResponse<DefectExplainResponse>> defectExplain(
            @Valid @RequestBody DefectExplainRequest request) {
        return ResponseEntity.ok(aiProxyService.explainDefect(request));
    }

    @Operation(summary = "AI 보고서 생성", description = "확정된 하자 목록을 인증 프록시로 AI 서버에 전달해 개요·요약·상세·권고 보고서를 반환한다")
    @PostMapping("/report")
    public ResponseEntity<ApiResponse<ReportResponse>> report(
            @Valid @RequestBody ReportRequest request) {
        return ResponseEntity.ok(aiProxyService.generateReport(request));
    }
}
