package com.hajacheck.invitecode.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.invitecode.dto.InviteCodeIssueResponse;
import com.hajacheck.invitecode.dto.InviteCodeRevokeRequest;
import com.hajacheck.invitecode.service.InviteCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔 — 사용자 초대 코드 발급(#794). ADMIN 인가는 SecurityConfig의 URL 매처
 * ("/api/admin/**" → hasRole(ADMIN))가 필터 단계에서 강제한다. 회사 스코프는 loginUser.companyId만
 * 사용하고 요청 파라미터로 받지 않아 cross-company 발급/폐기를 원천 차단한다.
 *
 * <p>폐기 응답이 {@code ResponseEntity<Void>}+204가 아니라 {@code ApiResponse} 봉투로 200을 내리는 이유:
 * 프론트 axios 인터셉터(shared/api/axios.ts)가 모든 응답에서 response.data.data를 읽는다 — 204(본문 없음)를
 * 내리면 response.data가 undefined가 되어 그 접근이 그대로 크래시한다(AuthController.logout과 동일 패턴).
 *
 * <p>폐기가 {@code DELETE /{code}}(경로 변수)가 아니라 {@code POST /revoke}(바디)인 이유(PR머신 리뷰 P3):
 * 초대 코드는 회사 배선 권한을 주는 1회용 크레덴셜이라, URL 경로에 실으면 nginx/서블릿 액세스 로그·
 * 브라우저 히스토리에 평문으로 남는다. 발급 응답·redeem 요청은 이미 바디로만 오가므로 폐기도 맞춘다.
 */
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/invite-codes")
@RequiredArgsConstructor
public class AdminInviteCodeController {

    private final InviteCodeService inviteCodeService;

    @Operation(summary = "초대 코드 발급", description = "요청 관리자 소속 회사로 스코프된 1회용 초대 코드를 발급한다(ADMIN 전용). TTL 경과 시 자동 만료(app.auth.invite-code-ttl).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨")
    @PostMapping
    public ResponseEntity<ApiResponse<InviteCodeIssueResponse>> issue(
            @AuthenticationPrincipal LoginUser loginUser) {
        InviteCodeIssueResponse response = inviteCodeService.issue(loginUser.getCompanyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "초대 코드 폐기", description = "발급 모달을 닫을 때 호출한다(ADMIN 전용). 요청 관리자 소속 회사가 발급한 코드만 삭제되고, 그 외(미존재·만료·타 회사)는 조용히 무시한다.")
    @PostMapping("/revoke")
    public ResponseEntity<ApiResponse<Void>> revoke(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody InviteCodeRevokeRequest request) {
        inviteCodeService.revoke(request.code(), loginUser.getCompanyId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
