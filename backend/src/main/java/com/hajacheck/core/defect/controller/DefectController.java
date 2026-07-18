package com.hajacheck.core.defect.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.service.DefectService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 하자 목록·상세 조회 API(HAJA-30, 1단계 read only). 소유자(owner)는 인증 사용자
 * (@AuthenticationPrincipal)로부터만 취득 — 요청 파라미터로 ownerId를 받지 않는다(cross-owner IDOR 방지).
 * 상태 전이(PATCH) 엔드포인트는 이번 범위 밖 — 별도 이슈에서 추가한다.
 */
@Tag(name = "Defect", description = "하자(결함) API")
@RestController
@RequestMapping("/api/defects")
@RequiredArgsConstructor
public class DefectController {

    private final DefectService defectService;

    @Operation(summary = "내 하자 목록 조회",
            description = "로그인 사용자가 소유한 시설물의 하자 목록을 유형/등급/상태로 필터링해 페이지 단위로 반환한다")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DefectResponse>>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) DefectType type,
            @RequestParam(required = false) DefectGrade grade,
            @RequestParam(required = false) DefectStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<DefectResponse> response =
                defectService.list(loginUser.getUserId(), type, grade, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "하자 상세 조회", description = "로그인 사용자 소유 시설물의 하자 단건을 조회한다")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DefectResponse>> get(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(defectService.get(loginUser.getUserId(), id)));
    }
}
