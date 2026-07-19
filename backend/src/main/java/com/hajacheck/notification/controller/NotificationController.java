package com.hajacheck.notification.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 목록 조회 API(AP-020, HAJA-25 FR-9). 수신자(userId)는 인증 사용자
 * (@AuthenticationPrincipal)에서만 취득 — 요청 바디/파라미터로 userId를 받지 않는다
 * (cross-owner IDOR 방지, DashboardController/FacilityController와 동일 원칙).
 *
 * <p>이벤트 발행(트리거)·읽음처리 PATCH는 이 PR 범위 밖이다.
 */
@Tag(name = "Notification", description = "알림 API")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 목록 조회",
            description = "로그인 사용자에게 온 알림을 읽음/미읽음 모두 포함해 최신순으로 반환한다")
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getNotifications(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getNotifications(loginUser.getUserId())));
    }
}
