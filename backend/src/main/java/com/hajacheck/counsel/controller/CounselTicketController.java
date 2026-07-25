package com.hajacheck.counsel.controller;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.dto.CounselTicketResponse;
import com.hajacheck.counsel.dto.CounselTicketSummaryResponse;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.service.CounselTicketService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전문 상담 티켓 API(FR-7, #20/HAJA-33).
 *
 * <p>인가 경계: 대기열 조회·배정(셀프-클레임)은 COUNSELOR/PLATFORM_ADMIN 만(SecurityConfig requestMatchers).
 * 종료(담당 상담원 본인/PLATFORM_ADMIN)·오프라인 이탈(티켓 소유자 본인)의 세부 소유권은 서비스가 검증한다.
 */
@Tag(name = "Counsel Ticket", description = "전문 상담 티켓 API")
@RestController
@RequestMapping("/api/counsel/tickets")
@RequiredArgsConstructor
public class CounselTicketController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CounselTicketService counselTicketService;

    @Operation(summary = "상담 티켓 생성", description = "상담원 연결을 요청한다(WAITING). has_counselor_access 활성 플랜만 허용.")
    @PostMapping
    public ResponseEntity<ApiResponse<CounselTicketResponse>> createTicket(
            @AuthenticationPrincipal LoginUser loginUser) {
        CounselTicketResponse response = counselTicketService.createTicket(loginUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "상담 대기열 조회", description = "상태별 티켓 목록(생성순, 기본 WAITING). COUNSELOR/PLATFORM_ADMIN 전용.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CounselTicketSummaryResponse>>> getQueue(
            @RequestParam(defaultValue = "WAITING") CounselTicketStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return ResponseEntity.ok(
                ApiResponse.ok(PageResponse.from(counselTicketService.getQueue(status, pageable))));
    }

    @Operation(summary = "상담 티켓 셀프-클레임 배정", description = "대기 티켓에 자기 자신을 배정한다. COUNSELOR/PLATFORM_ADMIN 전용.")
    @PostMapping("/{id}/assign")
    public ResponseEntity<ApiResponse<CounselTicketResponse>> assign(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        CounselTicketResponse response = counselTicketService.assignToCounselor(id, loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "상담 종료", description = "담당 상담원 본인 또는 PLATFORM_ADMIN 이 상담을 종료한다.")
    @PostMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<CounselTicketResponse>> resolve(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        boolean platformAdmin = loginUser.getRole() == Role.PLATFORM_ADMIN;
        CounselTicketResponse response =
                counselTicketService.resolve(id, loginUser.getUserId(), platformAdmin);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "오프라인 이탈", description = "티켓 소유 사용자 본인이 상담을 이탈 처리한다(OFFLINE_LEFT).")
    @PostMapping("/{id}/leave-offline")
    public ResponseEntity<ApiResponse<CounselTicketResponse>> leaveOffline(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        CounselTicketResponse response =
                counselTicketService.leaveOffline(id, loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
