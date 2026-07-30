package com.hajacheck.counsel.controller;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.dto.CounselTicketCreateRequest;
import com.hajacheck.counsel.dto.CounselTicketNoteResponse;
import com.hajacheck.counsel.dto.CounselTicketNoteUpdateRequest;
import com.hajacheck.counsel.dto.CounselTicketResponse;
import com.hajacheck.counsel.dto.CounselTicketSummaryResponse;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.service.CounselTicketNoteService;
import com.hajacheck.counsel.service.CounselTicketService;
import com.hajacheck.counsel.service.CounselTicketService.Transcript;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final CounselTicketNoteService counselTicketNoteService;

    @Operation(summary = "상담 티켓 생성",
            description = "시나리오 리프(leadsToCounselor=true)에서 상담원 연결을 요청한다(WAITING). "
                    + "has_counselor_access 활성 플랜만 허용, category/title 은 시나리오 트리에서 스냅샷.")
    @PostMapping
    public ResponseEntity<ApiResponse<CounselTicketResponse>> createTicket(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CounselTicketCreateRequest request) {
        CounselTicketResponse response =
                counselTicketService.createTicket(loginUser.getUserId(), request.scenarioId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "내 상담 이력 조회",
            description = "요청자 본인의 상담 티켓 목록(최신순). status=ALL(기본) 또는 특정 상태 필터. userId 는 세션에서만.")
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<PageResponse<CounselTicketSummaryResponse>>> getMyTickets(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        CounselTicketStatus statusFilter = parseStatusFilter(status);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                counselTicketService.getMyTickets(loginUser.getUserId(), statusFilter, pageable))));
    }

    /** "ALL"은 필터 없음(null), 그 외 값은 enum 파싱 — 유효하지 않은 값은 500이 아닌 400으로 표면화한다. */
    private CounselTicketStatus parseStatusFilter(String status) {
        if ("ALL".equalsIgnoreCase(status)) {
            return null;
        }
        try {
            return CounselTicketStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    @Operation(summary = "상담 대화 조회",
            description = "티켓의 전체 대화 이력(시간순). 당사자(사용자 본인/담당 상담원) 또는 PLATFORM_ADMIN.")
    @GetMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessages(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        boolean platformAdmin = loginUser.getRole() == Role.PLATFORM_ADMIN;
        List<ChatMessageResponse> messages =
                counselTicketService.getMessages(id, loginUser.getUserId(), platformAdmin);
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    @Operation(summary = "플랫폼 관리자 날짜별 상담 목록",
            description = "접수일(createdAt) 기준 특정 날짜에 접수된 상담 티켓 전체(최신순, 고객 프로필 포함). PLATFORM_ADMIN 전용.")
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<PageResponse<CounselTicketSummaryResponse>>> getAdminTicketsByDate(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (loginUser.getRole() != Role.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        LocalDate targetDate = parseDate(date);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                counselTicketService.getAdminTicketsByDate(targetDate, pageable))));
    }

    /** date 파라미터(YYYY-MM-DD) 파싱 — 누락·형식 오류 모두 500이 아닌 400(INVALID_INPUT)으로 표면화한다. */
    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    @Operation(summary = "대화 내보내기", description = "티켓의 전체 대화를 평문 텍스트(.txt)로 다운로드한다. 당사자만.")
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportTranscript(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        Transcript transcript = counselTicketService.exportTranscript(id, loginUser.getUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + transcript.fileName() + "\"")
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(transcript.content());
    }

    @Operation(summary = "상담 대기열 조회",
            description = "상태별 티켓 목록(생성순, 기본 WAITING). COUNSELOR/PLATFORM_ADMIN 전용. "
                    + "COUNSELOR 는 본인이 보유한 상담 유형(counselor_skills)으로만 필터링되고, "
                    + "PLATFORM_ADMIN 은 운영 모니터링 목적으로 전체 대기열을 그대로 본다.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CounselTicketSummaryResponse>>> getQueue(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "WAITING") CounselTicketStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        boolean platformAdmin = loginUser.getRole() == Role.PLATFORM_ADMIN;
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                counselTicketService.getQueue(status, pageable, loginUser.getUserId(), platformAdmin))));
    }

    @Operation(summary = "상담 티켓 셀프-클레임 배정", description = "대기 티켓에 자기 자신을 배정한다. COUNSELOR/PLATFORM_ADMIN 전용.")
    @PostMapping("/{id}/assign")
    public ResponseEntity<ApiResponse<CounselTicketResponse>> assign(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        CounselTicketResponse response = counselTicketService.assignToCounselor(id, loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "상담 종료", description = "담당 상담원 본인·티켓 소유 고객 본인·PLATFORM_ADMIN 이 상담을 종료한다.")
    @PostMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<CounselTicketResponse>> resolve(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        boolean platformAdmin = loginUser.getRole() == Role.PLATFORM_ADMIN;
        CounselTicketResponse response =
                counselTicketService.resolve(id, loginUser.getUserId(), platformAdmin);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "고객 상담 이력", description = "이 티켓의 담당 상담원 본인 또는 PLATFORM_ADMIN이 그 고객의 과거 상담 이력(현재 티켓 제외, 최신순 20건)을 조회한다.")
    @GetMapping("/{id}/customer-history")
    public ResponseEntity<ApiResponse<List<CounselTicketSummaryResponse>>> getCustomerHistory(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        boolean platformAdmin = loginUser.getRole() == Role.PLATFORM_ADMIN;
        List<CounselTicketSummaryResponse> history =
                counselTicketService.getCustomerHistory(id, loginUser.getUserId(), platformAdmin);
        return ResponseEntity.ok(ApiResponse.ok(history));
    }

    @Operation(summary = "고객 이력 티켓 대화 조회", description = "customer-history 목록에 나온 과거 티켓의 대화 내용을 조회한다. 담당 상담원이 요청자와 달라도 ticketId(현재 담당 중)와 같은 고객이면 허용.")
    @GetMapping("/{id}/customer-history/{historyId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getCustomerHistoryMessages(
            @PathVariable Long id,
            @PathVariable Long historyId,
            @AuthenticationPrincipal LoginUser loginUser) {
        boolean platformAdmin = loginUser.getRole() == Role.PLATFORM_ADMIN;
        List<ChatMessageResponse> messages =
                counselTicketService.getCustomerHistoryMessages(id, historyId, loginUser.getUserId(), platformAdmin);
        return ResponseEntity.ok(ApiResponse.ok(messages));
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

    @Operation(summary = "상담원 비공개 메모 조회",
            description = "고객에게 노출되지 않는 상담원 전용 메모(티켓당 1개)를 조회한다. 담당 상담원 본인만.")
    @GetMapping("/{id}/note")
    public ResponseEntity<ApiResponse<CounselTicketNoteResponse>> getNote(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        CounselTicketNoteResponse response =
                counselTicketNoteService.getNote(id, loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "상담원 비공개 메모 저장",
            description = "상담원 전용 메모를 저장한다(upsert — 없으면 생성, 있으면 갱신). 담당 상담원 본인만.")
    @PutMapping("/{id}/note")
    public ResponseEntity<ApiResponse<CounselTicketNoteResponse>> saveNote(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CounselTicketNoteUpdateRequest request) {
        CounselTicketNoteResponse response =
                counselTicketNoteService.saveNote(id, loginUser.getUserId(), request.content());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
