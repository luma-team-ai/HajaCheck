package com.hajacheck.core.facility.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시설물 CRUD API. 회사 스코프는 인증 사용자(@AuthenticationPrincipal)로부터만 취득 —
 * 요청 바디/파라미터로 companyId 를 받지 않는다(cross-company IDOR 방지).
 */
@Tag(name = "Facility", description = "시설물 API")
@RestController
@RequestMapping("/api/facilities")
@RequiredArgsConstructor
public class FacilityController {

    private final FacilityService facilityService;

    @Operation(summary = "시설물 등록", description = "로그인 사용자의 회사 소유로 시설물을 신규 등록한다")
    @PostMapping
    public ResponseEntity<ApiResponse<FacilityResponse>> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody FacilityCreateRequest request) {
        FacilityResponse response = facilityService.create(loginUser.getCompanyId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "회사 시설물 목록 조회", description = "로그인 사용자의 회사가 소유한 시설물 목록을 반환한다")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FacilityResponse>>> list(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(facilityService.list(loginUser.getCompanyId())));
    }

    @Operation(summary = "시설물 상세 조회", description = "로그인 사용자의 회사가 소유한 시설물 단건을 조회한다")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FacilityResponse>> get(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(facilityService.get(loginUser.getCompanyId(), id)));
    }

    @Operation(summary = "시설물 수정", description = "로그인 사용자의 회사가 소유한 시설물 정보를 전체 수정한다")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FacilityResponse>> update(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody FacilityUpdateRequest request) {
        FacilityResponse response = facilityService.update(loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "시설물 삭제", description = "로그인 사용자의 회사가 소유한 시설물을 삭제한다")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        facilityService.delete(loginUser.getCompanyId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @Operation(summary = "시설물 점검주기 설정", description = "로그인 사용자의 회사가 소유한 시설물에 점검 주기를 설정하고 다음 점검일(nextInspectionDueAt)을 산출·저장한다")
    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<FacilityResponse>> setSchedule(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody FacilityScheduleRequest request) {
        FacilityResponse response = facilityService.setSchedule(loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
