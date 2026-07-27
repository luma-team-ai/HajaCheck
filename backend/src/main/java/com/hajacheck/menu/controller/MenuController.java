package com.hajacheck.menu.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.menu.dto.MenuTreeItemResponse;
import com.hajacheck.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사이드바/관리자 메뉴 트리 조회 API(#1003). role은 인증 사용자(@AuthenticationPrincipal)에서만 취득한다. */
@Tag(name = "Menu", description = "메뉴 API")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @Operation(summary = "메뉴 트리 조회", description = "로그인 사용자의 role 기준으로 노출 가능한 메뉴 트리를 반환한다")
    @GetMapping
    public ResponseEntity<ApiResponse<List<MenuTreeItemResponse>>> getMenuTree(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getMenuTree(loginUser.getRole())));
    }
}
