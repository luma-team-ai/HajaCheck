package com.hajacheck.core.defect.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.NlSearchRequest;
import com.hajacheck.core.defect.dto.NlSearchResult;
import com.hajacheck.core.defect.service.NlSearchService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 하자 자연어 검색 공개 게이트웨이(HAJA-120/179~183, FR-013). 점검자 역할·has_ai_addon 플랜 게이트는
 * SecurityConfig(hasRole INSPECTOR)와 NlSearchService(플랜/회사 멤버십 검증)가 나눠 담당한다 — 이 컨트롤러는
 * 인증 사용자 식별(IDOR 방지, DefectController 규약과 동일)과 요청 위임만 수행한다.
 */
@Tag(name = "Defect", description = "하자(결함) API")
@RestController
@RequestMapping("/api/defects")
@RequiredArgsConstructor
public class DefectSearchController {

    private final NlSearchService nlSearchService;

    @Operation(summary = "하자 자연어 검색",
            description = "자연어 질의를 하자 필터 조건(유형/등급/상태/신뢰도 하한)으로 변환한다. "
                    + "점검자 역할과 AI 부가 기능이 있는 활성 플랜만 허용하며, 게이트 실패 시 내부 AI 서버를 호출하지 않는다")
    @PostMapping("/nl-search")
    public ResponseEntity<ApiResponse<NlSearchResult>> nlSearch(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestBody NlSearchRequest request) {
        return ResponseEntity.ok(nlSearchService.search(loginUser.getUserId(), request.query()));
    }
}
