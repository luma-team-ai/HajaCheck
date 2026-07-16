package com.hajacheck.core.inspection.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
        InspectionResponse response = inspectionService.createInspection(request, loginUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "점검 회차 단건 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InspectionResponse>> getInspection(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(inspectionService.getInspection(loginUser.getUserId(), id)));
    }
}
