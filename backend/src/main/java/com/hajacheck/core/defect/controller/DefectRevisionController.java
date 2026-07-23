package com.hajacheck.core.defect.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.defect.dto.DefectDetailItem;
import com.hajacheck.core.defect.dto.DefectRevisionRequest;
import com.hajacheck.core.defect.service.DefectRevisionService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검수 API — 하자 등급 조정·오탐 수정. 소유권은 점검 회차(inspection)를 통해 검증하며,
 * 미존재와 타인 소유를 구분하지 않고 404로 통일한다(IDOR 방지).
 */
@Tag(name = "Defect", description = "하자 검수 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DefectRevisionController {

    private final DefectRevisionService defectRevisionService;

    @Operation(
            summary = "분석 결과(하자 목록) 조회",
            description = "점검 회차에 속한 하자 전체를 반환한다(뷰어·검수 공용, FR-4). "
                    + "defects.is_deleted=false만 반환하고 id 오름차순 정렬."
    )
    @GetMapping("/inspections/{id}/defects")
    public ResponseEntity<ApiResponse<List<DefectDetailItem>>> getDefectsByInspection(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable("id") Long inspectionId) {
        List<DefectDetailItem> defects = defectRevisionService.getDefectsByInspection(
                loginUser.getUserId(), loginUser.getCompanyId(), inspectionId);
        return ResponseEntity.ok(ApiResponse.ok(defects));
    }

    @Operation(
            summary = "검수 — 오탐 수정·등급 조정",
            description = "점검자가 하자를 검수한다(FR-4, 휴먼 인 더 루프). "
                    + "grade(등급 조정) 또는 isDeleted(오탐 삭제) 중 정확히 하나만 지정. "
                    + "각 변경은 defect_revisions에 append-only 이력으로 기록된다."
    )
    @PatchMapping("/defects/{id}")
    public ResponseEntity<ApiResponse<DefectDetailItem>> reviewDefect(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable("id") Long defectId,
            @Valid @RequestBody DefectRevisionRequest request) {
        DefectDetailItem defect = defectRevisionService.reviewDefect(
                loginUser.getCompanyId(), loginUser.getUserId(), defectId, request);
        return ResponseEntity.ok(ApiResponse.ok(defect));
    }
}
