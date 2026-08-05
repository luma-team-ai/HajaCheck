package com.hajacheck.core.inspection.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionListFilterRequest;
import com.hajacheck.core.inspection.dto.InspectionListItemResponse;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 점검(회차) 생성 — PRD §7 "🔍 점검 관리 A"(황승현 주담당) / dev-05-02.
 */
@Tag(name = "Inspection", description = "점검 회차 API")
@RestController
@RequestMapping("/api/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    @Operation(summary = "점검 회차 생성", description = "시설물 선택 + 점검일 + 담당자 지정으로 새 점검 회차 생성")
    @PostMapping
    public ResponseEntity<ApiResponse<InspectionResponse>> createInspection(
            @Valid @RequestBody InspectionCreateRequest request,
            @AuthenticationPrincipal LoginUser loginUser) {
        InspectionResponse response = inspectionService.createInspection(
                request, loginUser.getCompanyId(), loginUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "점검 목록 조회",
            description = "로그인 사용자 소유(회사 스코프) 시설물의 점검 회차를 상태/유형/점검일/회차/"
                    + "전체 하자 건수/하자 조건으로 필터링해 페이지 단위로 반환한다. "
                    + "defectType/defectGrade/defectStatus(#878, HAJA-452)는 자연어 하자조건 검색(POST /api/defects/nl-search)이 "
                    + "산출한 필터를 그대로 실어 재조회하는 용도 — 셋 중 1개 이상 주어지면 해당 조건을 전부 만족하는 하자가 "
                    + "하나라도 있는 점검만 반환한다(같은 하자 하나가 조건을 전부 만족해야 함, 서로 다른 하자로 나눠 만족하면 매칭 아님)")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<InspectionListItemResponse>>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @ModelAttribute InspectionListFilterRequest filters,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        PageResponse<InspectionListItemResponse> response = inspectionService.list(
                loginUser.getUserId(), loginUser.getCompanyId(), filters, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "점검 회차 단건 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InspectionResponse>> getInspection(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                inspectionService.getInspection(loginUser.getUserId(), loginUser.getCompanyId(), id)));
    }

    @Operation(summary = "점검 회차 검수 확정(ANALYZED → REVIEWED)",
            description = "결과 뷰어 '점검 요약' 버튼 클릭 시 호출 — 이미 REVIEWED/REPORTED면 멱등하게 무시된다.")
    @PostMapping("/{id}/confirm-review")
    public ResponseEntity<ApiResponse<Void>> confirmReview(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        inspectionService.confirmReview(loginUser.getUserId(), loginUser.getCompanyId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
