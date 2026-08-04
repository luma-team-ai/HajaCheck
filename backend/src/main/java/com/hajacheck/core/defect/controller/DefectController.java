package com.hajacheck.core.defect.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectActionLogResponse;
import com.hajacheck.core.defect.dto.DefectActionResultRequest;
import com.hajacheck.core.defect.dto.DefectLocationUpdateRequest;
import com.hajacheck.core.defect.dto.DefectPreviousDefectUpdateRequest;
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
import java.util.List;
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
            description = "신규→검수확정→조치중→조치완료 순서의 정방향 한 단계 전이는 사유 없이 허용한다. "
                    + "역행/건너뛰기 전이는 reason이 있어야 허용되며(없으면 400 INVALID_INPUT), "
                    + "조치완료(RESOLVED)에서의 이탈도 같은 규칙을 따른다 — 정방향 다음 단계가 없으므로 "
                    + "항상 사유가 필요하고, 사유가 있으면 되돌릴 수 있다(#1556). 현재 상태와 동일한 상태를 "
                    + "요청하면 409(INVALID_STATE_TRANSITION)로 거부된다. "
                    + "신규(DETECTED)에서 벗어나려면 등급이 먼저 확정돼 있어야 한다(없으면 400 INVALID_INPUT, #1397). "
                    + "같은 사진(mediaId)의 하자들은 한 그룹으로 함께 전이된다 — 단 앞으로 진행시키는 요청에서는 "
                    + "정방향 한 단계로 따라올 수 있는 멤버만 전이되고, 이미 목표 상태이거나 목표보다 앞서 있거나 "
                    + "두 단계 이상 뒤처진 멤버는 건드리지 않는다(#1583). 반대로 되돌리는 요청은 앞선 멤버도 함께 "
                    + "되돌린다(이미 목표 상태인 멤버는 제외, #1556)")
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
            description = "조치 후 사진(mediaId)/조치 내용/조치일/담당자를 등록하며 상태를 요청 바디의 "
                    + "targetStatus로 전이한다. targetStatus는 조치중(IN_PROGRESS)/조치완료(RESOLVED)만 "
                    + "허용하며(그 외는 400), 실제 전이 가능 여부는 정방향 한 단계 규칙을 따른다 — "
                    + "CONFIRMED에서는 IN_PROGRESS로, IN_PROGRESS에서는 RESOLVED로만 가능하고 순서를 건너뛴 "
                    + "전이(예: CONFIRMED→RESOLVED)는 400으로 거부된다"
                    + "(\"상태 저장\" 버튼 전용이라 사유 입력란이 없음, HAJA-393/#725, #1128)")
    @PatchMapping("/{id}/action")
    public ResponseEntity<ApiResponse<DefectResponse>> registerActionResult(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody DefectActionResultRequest request) {
        DefectResponse response =
                defectService.registerActionResult(loginUser.getUserId(), loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "하자 위치 사후 편집",
            description = "검수자가 하자 위치 텍스트를 사후에 편집한다(조치 등록과 별개). "
                    + "빈 문자열/공백은 null로 정규화된다.")
    @PatchMapping("/{id}/location")
    public ResponseEntity<ApiResponse<DefectResponse>> updateLocation(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody DefectLocationUpdateRequest request) {
        DefectResponse response = defectService.updateLocation(
                loginUser.getUserId(), loginUser.getCompanyId(), id, request.location());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "회차 간 대응 하자 확정",
            description = "검수자가 화면에서 확인한 이전 회차의 대응 하자 id를 확정 저장한다. "
                    + "같은 시설물의 더 이전 회차 하자만 지정할 수 있으며, 그 외에는 400(DEFECT_PREVIOUS_DEFECT_INVALID)으로 거부된다.")
    @PatchMapping("/{id}/previous-defect")
    public ResponseEntity<ApiResponse<DefectResponse>> confirmPreviousDefect(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody DefectPreviousDefectUpdateRequest request) {
        DefectResponse response = defectService.confirmPreviousDefect(
                loginUser.getUserId(), loginUser.getCompanyId(), id, request.previousDefectId());
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

    @Operation(summary = "조치 등록 이력 조회",
            description = "조치중(IN_PROGRESS)/조치완료(RESOLVED) 단계별로 등록된 조치 결과 제출 이력을 "
                    + "최신순으로 전체 반환한다(#1193/HAJA-569, 조치중 단계 다중 등록 지원). phase는 "
                    + "IN_PROGRESS/RESOLVED만 허용하며 그 외는 400으로 거부된다. 상세 모달 사진 탭이 항목 "
                    + "2개 이상일 때 등록일 select로 넘겨보는 용도.")
    @GetMapping("/{id}/action-logs")
    public ResponseEntity<ApiResponse<List<DefectActionLogResponse>>> getActionLogs(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @RequestParam DefectStatus phase) {
        List<DefectActionLogResponse> response =
                defectService.getActionLogs(loginUser.getUserId(), loginUser.getCompanyId(), id, phase);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
