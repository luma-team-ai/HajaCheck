package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.PasswordChangeRequest;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.PasswordChangeService;
import com.hajacheck.auth.support.SessionTerminator;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.invitecode.dto.InviteCodeRedeemRequest;
import com.hajacheck.invitecode.service.InviteCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final PasswordChangeService passwordChangeService;
    private final SessionTerminator sessionTerminator;

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

    /**
     * 로그인 후 비밀번호 변경(#1315 / HAJA-601).
     *
     * <p>대상 사용자는 <b>세션 principal 로만</b> 식별한다 — 바디·경로·쿼리 어디로도 userId 를 받지
     * 않으므로 타인 비밀번호를 지정할 수단 자체가 없다(IDOR 차단).
     *
     * <p>성공 시 <b>현재 세션을 종료</b>한다(A안): 비밀번호가 바뀌었는데 예전 자격증명으로 발급된 세션이
     * 그대로 살아 있으면 "비밀번호를 바꿨다 = 접근을 회수했다"는 사용자 기대가 깨진다. 순서가 중요하다 —
     * 서비스 호출이 반환(= 쓰기 트랜잭션 커밋)된 <b>뒤에</b> 세션을 정리해야, 변경이 롤백됐는데 세션만
     * 날아가는 상태 불일치가 생기지 않는다.
     *
     * <p>⚠️ 종료되는 것은 <b>이 요청의 세션뿐</b>이다 — 다른 기기 세션 무효화는 범위 밖이며 후속 이슈
     * <b>#1318</b> 이다(사유는 SessionTerminator javadoc). 프론트는 200 을 받으면 로그인 화면으로
     * 보내야 한다(#1316).
     */
    @Operation(summary = "비밀번호 변경",
            description = "현재 비밀번호 확인 후 새 비밀번호로 변경한다. 성공 시 현재 세션이 종료되므로 재로그인이 필요하다.")
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        passwordChangeService.changePassword(loginUser.getUserId(), request);
        sessionTerminator.terminate(httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
