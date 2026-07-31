package com.hajacheck.core.facility.controller;

import com.hajacheck.auth.dto.AssignableUserResponse;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityInspectionOverviewResponse;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityStatusResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingRequest;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingResponse;
import com.hajacheck.core.facility.service.FacilityComparisonService;
import com.hajacheck.core.facility.service.FacilityInspectionOverviewService;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.facility.service.InspectionNotificationSettingService;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AuthService authService;
    private final InspectionNotificationSettingService inspectionNotificationSettingService;
    private final FacilityComparisonService facilityComparisonService;
    private final FacilityInspectionOverviewService facilityInspectionOverviewService;

    @Operation(summary = "시설물 등록", description = "로그인 사용자의 회사 소유로 시설물을 신규 등록한다")
    @PostMapping
    public ResponseEntity<ApiResponse<FacilityResponse>> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody FacilityCreateRequest request) {
        FacilityResponse response =
                facilityService.create(loginUser.getUserId(), loginUser.getCompanyId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "회사 시설물 목록 조회", description = "로그인 사용자의 회사가 소유한 시설물 목록을 반환한다")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FacilityResponse>>> list(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                facilityService.list(loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "시설물 현황 목록 조회", description = "로그인 사용자의 회사가 소유한 시설물의 현황(상태·D-day·담당자·최근점검일)을 반환한다"
            + "(#540 ⑥, HAJA-378) — 대시보드 스타일 현황 테이블 화면 전용")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<List<FacilityStatusResponse>>> listStatus(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                facilityService.listStatus(loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "시설물 상세 조회", description = "로그인 사용자의 회사가 소유한 시설물 단건을 조회한다")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FacilityResponse>> get(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(
                facilityService.get(loginUser.getUserId(), loginUser.getCompanyId(), id)));
    }

    @Operation(summary = "시설물 수정", description = "로그인 사용자의 회사가 소유한 시설물 정보를 전체 수정한다")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FacilityResponse>> update(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody FacilityUpdateRequest request) {
        FacilityResponse response =
                facilityService.update(loginUser.getUserId(), loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "시설물 삭제", description = "로그인 사용자의 회사가 소유한 시설물을 삭제한다")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        facilityService.delete(loginUser.getUserId(), loginUser.getCompanyId(), id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @Operation(summary = "시설물 점검주기 설정", description = "로그인 사용자의 회사가 소유한 시설물에 점검 주기를 설정하고 다음 점검일(nextInspectionDueAt)을 산출·저장한다")
    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<FacilityResponse>> setSchedule(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody FacilityScheduleRequest request) {
        FacilityResponse response =
                facilityService.setSchedule(loginUser.getUserId(), loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "점검 알림 설정 조회",
            description = "로그인 사용자의 시설물별 점검 알림 설정을 조회한다(#540 ③). 설정을 저장한 적이 없으면"
                    + " DB 컬럼 기본값(사전알림 사용/7일전/경과알림 사용, HAJA-498/V21)을 반환한다")
    @GetMapping("/{id}/notification-settings")
    public ResponseEntity<ApiResponse<InspectionNotificationSettingResponse>> getNotificationSettings(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(
                inspectionNotificationSettingService.get(loginUser.getUserId(), loginUser.getCompanyId(), id)));
    }

    @Operation(summary = "점검 알림 설정 저장",
            description = "로그인 사용자의 시설물별 점검 알림 설정을 생성하거나 갱신한다(upsert, #540 ③)")
    @PutMapping("/{id}/notification-settings")
    public ResponseEntity<ApiResponse<InspectionNotificationSettingResponse>> saveNotificationSettings(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            @Valid @RequestBody InspectionNotificationSettingRequest request) {
        InspectionNotificationSettingResponse response = inspectionNotificationSettingService.save(
                loginUser.getUserId(), loginUser.getCompanyId(), id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "회차 간 비교 조회",
            description = "로그인 사용자의 회사가 소유한 시설물의 두 점검 회차(before/after)를 비교해 "
                    + "KPI(신규/진행중/개선완료/등급상승 4종)와 하자 변화 목록을 반환한다(HAJA-531/#1112). "
                    + "previous_defect_id로 확정된 회차 간 대응(HAJA-437)을 기준으로 분류하며, "
                    + "\"재발생\"(이전 회차 RESOLVED가 이후 회차에 재연결된 경우)은 recurring으로 별도 "
                    + "구분한다(HAJA-532/#1119).")
    @GetMapping("/{id}/compare")
    public ResponseEntity<ApiResponse<FacilityComparisonResponse>> compare(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id,
            // #1157 — 생략 시 서비스가 이 시설물의 실제 최근 2개 회차로 자동 대체한다.
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer after) {
        return ResponseEntity.ok(ApiResponse.ok(facilityComparisonService.compare(
                loginUser.getUserId(), loginUser.getCompanyId(), id, before, after)));
    }

    @Operation(summary = "시설물 점검 이력 조회",
            description = "로그인 사용자의 회사가 소유한 시설물의 전체 점검 회차 이력과 집계(전체 등급/회차수/누적 하자/"
                    + "미조치 건수)를 반환한다(#1359/HAJA-616). 최신 회차에는 이전 회차 대비 변화 메모(changeNote)가"
                    + " 함께 내려간다(비교 가능한 상태일 때만).")
    @GetMapping("/{id}/inspections")
    public ResponseEntity<ApiResponse<FacilityInspectionOverviewResponse>> getInspectionOverview(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(
                facilityInspectionOverviewService.get(loginUser.getUserId(), loginUser.getCompanyId(), id)));
    }

    @Operation(summary = "배정 가능한 담당자 목록 조회",
            description = "로그인 사용자의 회사 소속 사용자 중 시설물 담당자로 배정 가능한(활성·INSPECTOR/ADMIN) 목록을 반환한다")
    @GetMapping("/assignable-users")
    public ResponseEntity<ApiResponse<List<AssignableUserResponse>>> listAssignableUsers(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.listAssignableUsers(loginUser.getCompanyId(), loginUser.getUserId())));
    }
}
