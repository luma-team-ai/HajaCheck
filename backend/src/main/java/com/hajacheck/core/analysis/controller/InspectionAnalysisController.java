package com.hajacheck.core.analysis.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.service.InspectionAnalysisService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 분석 실행/상태(dev-05-04, API 명세서 v0.3 AP-006 연장) — {@code Profile("!test")} 이유는
 * {@link com.hajacheck.core.analysis.support.AnalysisProgressStore} 문서 참고.
 */
@Tag(name = "Inspection Analysis", description = "AI 분석 실행/상태 API")
@RestController
@RequestMapping("/api/inspections/{id}/analyze")
@Profile("!test")
@RequiredArgsConstructor
public class InspectionAnalysisController {

    private final InspectionAnalysisService analysisService;

    @Operation(summary = "AI 분석 시작", description = "점검 회차에 업로드된 이미지 전체에 대해 AI 하자 탐지를 비동기로 시작한다")
    @PostMapping
    public ResponseEntity<Void> analyze(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        analysisService.startAnalysis(loginUser.getUserId(), loginUser.getCompanyId(), id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @Operation(summary = "AI 분석 진행 상태 조회", description = "진행률·파일별 상태·탐지 요약을 폴링용으로 반환한다")
    @GetMapping
    public ResponseEntity<ApiResponse<AnalysisStatusResponse>> status(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                analysisService.getStatus(loginUser.getUserId(), loginUser.getCompanyId(), id)));
    }
}
