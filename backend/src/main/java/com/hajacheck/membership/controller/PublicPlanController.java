package com.hajacheck.membership.controller;

import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.membership.dto.PublicPlanCatalogResponse;
import com.hajacheck.membership.service.PublicPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 요금제 API — 랜딩페이지 및 비회원도 접근 가능한 요금제 정보 조회(permitAll 대상).
 */
@Tag(name = "Membership", description = "요금제/멤버십 API")
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PublicPlanController {

    private final PublicPlanService publicPlanService;

    @Operation(summary = "공개 요금제 카탈로그 조회", description = "랜딩페이지 및 공개 화면에 표시할 전체 요금제 목록(가격, 한도, 기능)을 반환한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<PublicPlanCatalogResponse>> getPublicPlans() {
        return ResponseEntity.ok(ApiResponse.ok(publicPlanService.getPublicPlans()));
    }
}
