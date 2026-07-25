package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.invitecode.dto.InviteCodeRedeemRequest;
import com.hajacheck.invitecode.service.InviteCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 정보 조회. 미인증은 SecurityConfig 의 EntryPoint 에서 401 처리.
 */
@Tag(name = "User", description = "사용자 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final InviteCodeService inviteCodeService;

    @Operation(summary = "내 정보 조회", description = "세션의 인증 사용자 정보 반환")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getMe(loginUser.getUserId())));
    }

    @Operation(summary = "초대 코드 적용", description = "발급받은 초대 코드를 입력해 회사 소속으로 전환한다(WAITING 상태 전용, #794).")
    @PostMapping("/me/invite-code")
    public ResponseEntity<ApiResponse<UserResponse>> redeemInviteCode(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody InviteCodeRedeemRequest request) {
        UserResponse response = inviteCodeService.redeem(request.code(), loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
