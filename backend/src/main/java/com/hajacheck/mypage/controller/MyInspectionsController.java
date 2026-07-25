package com.hajacheck.mypage.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.mypage.dto.MyInspectionRowResponse;
import com.hajacheck.mypage.dto.MyInspectionsSummaryResponse;
import com.hajacheck.mypage.dto.MyReportRowResponse;
import com.hajacheck.mypage.service.MyInspectionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이페이지 — 내 점검 이력 / 보고서(#844, HAJA-442). 프론트 /mypage/inspections 화면(#668)이
 * mock으로만 선구현돼 실 서버에서 전 구간 404였던 것을 메운다. MembershipController와 같은 "/api/me"
 * 하위 리소스지만 도메인(점검/보고서 이력)이 달라 컨트롤러를 분리한다.
 */
@Tag(name = "MyInspections", description = "마이페이지 — 내 점검 이력/보고서 API")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MyInspectionsController {

    private final MyInspectionsService myInspectionsService;

    @Operation(summary = "내 점검 이력 요약",
            description = "내가 참여한(담당자 또는 등록자) 점검 회차의 참여/검수확정/발급보고서/진행중 건수를 반환한다")
    @GetMapping("/inspections/summary")
    public ResponseEntity<ApiResponse<MyInspectionsSummaryResponse>> getSummary(
            @AuthenticationPrincipal LoginUser loginUser) {
        MyInspectionsSummaryResponse response =
                myInspectionsService.getSummary(loginUser.getUserId(), loginUser.getCompanyId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "내 점검 이력 목록",
            description = "assignedInspectorId 또는 createdBy가 본인인 점검 회차를 조회 기간(period=1M/3M/6M/1Y/ALL) "
                    + "필터와 함께 페이지 단위로 반환한다")
    @GetMapping("/inspections")
    public ResponseEntity<ApiResponse<PageResponse<MyInspectionRowResponse>>> getInspections(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "ALL") String period,
            @PageableDefault(size = 8) Pageable pageable) {
        PageResponse<MyInspectionRowResponse> response = myInspectionsService.getInspections(
                loginUser.getUserId(), loginUser.getCompanyId(), period, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "내 발급 보고서 목록",
            description = "FINALIZED 상태 보고서를 조회 기간(period=1M/3M/6M/1Y/ALL) 필터와 함께 최신순으로 "
                    + "반환한다(페이지네이션 없음, 상한 있음)")
    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<MyReportRowResponse>>> getReports(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "ALL") String period) {
        List<MyReportRowResponse> response =
                myInspectionsService.getReports(loginUser.getUserId(), loginUser.getCompanyId(), period);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
