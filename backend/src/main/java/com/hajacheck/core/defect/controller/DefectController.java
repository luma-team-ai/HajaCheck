package com.hajacheck.core.defect.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectActionResultRequest;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.dto.DefectRevisionResponse;
import com.hajacheck.core.defect.dto.DefectStatusUpdateRequest;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.service.DefectService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 하자 목록·상세 조회 및 상태 전이 API(HAJA-30). 회사 스코프는 인증 사용자
 * (@AuthenticationPrincipal)로부터만 취득 — 요청 파라미터로 companyId를 받지 않는다(cross-company IDOR 방지).
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
                defectService.list(
                        loginUser.getUserId(), loginUser.getCompanyId(), type, grade, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "하자 상세 조회", description = "로그인 사용자 소유 시설물의 하자 단건을 조회한다")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DefectResponse>> get(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(
                defectService.get(loginUser.getUserId(), loginUser.getCompanyId(), id)));
    }

    @Operation(summary = "하자 상태 전이",
            description = "신규→검수확정→조치대기→조치중→조치완료 순서의 정방향 한 단계 전이는 사유 없이 허용한다. "
                    + "역행/건너뛰기 전이는 reason이 있어야 허용되며(없으면 400 INVALID_INPUT), "
                    + "조치완료(RESOLVED) 상태에서의 이탈은 사유 유무와 무관하게 409(INVALID_STATE_TRANSITION)로 거부된다")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<DefectResponse>> updateStatus(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody DefectStatusUpdateRequest request) {
        DefectResponse response =
                defectService.updateStatus(
                        loginUser.getUserId(), loginUser.getCompanyId(), id, request.status(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "조치 결과 등록",
            description = "조치 후 사진(mediaId)/조치 내용/조치일/담당자를 등록하며 상태를 조치완료(RESOLVED)로 전이한다. "
                    + "IN_PROGRESS 상태에서만 호출 가능 — 순서를 건너뛴 완료 처리는 400으로 거부된다("
                    + "\"조치 완료 등록\" 버튼 전용이라 사유 입력란이 없음, HAJA-393/#725)")
    @PatchMapping("/{id}/action")
    public ResponseEntity<ApiResponse<DefectResponse>> registerActionResult(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody DefectActionResultRequest request) {
        DefectResponse response =
                defectService.registerActionResult(loginUser.getUserId(), loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "하자 활동 기록 조회",
            description = "로그인 사용자 소유 시설물의 하자 상태 변경 이력을 최신순으로 페이지 단위 반환한다")
    @GetMapping("/{id}/revisions")
    public ResponseEntity<ApiResponse<PageResponse<DefectRevisionResponse>>> getRevisions(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<DefectRevisionResponse> response =
                defectService.getRevisions(
                        loginUser.getUserId(), loginUser.getCompanyId(), id, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
