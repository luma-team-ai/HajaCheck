package com.hajacheck.core.defect.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.service.DefectService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 하자 조회 API. 소유자(owner) 검증은 요청 사용자(@AuthenticationPrincipal)를 통해 점검 소속 시설물의
 * 소유권으로 검증된다(IDOR 방지).
 */
@Tag(name = "Defect", description = "하자 API")
@RestController
@RequestMapping("/api/defects")
@RequiredArgsConstructor
public class DefectController {

    private final DefectService defectService;

    @Operation(summary = "하자 단건 조회", description = "로그인 사용자 소유의 시설물에 속한 하자를 조회한다")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DefectResponse>> getDefect(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(defectService.getDefect(loginUser.getUserId(), id)));
    }
}
