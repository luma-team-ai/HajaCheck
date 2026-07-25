package com.hajacheck.counsel.controller;

import com.hajacheck.counsel.dto.BotScenarioButtonResponse;
import com.hajacheck.counsel.dto.BotScenarioNodeResponse;
import com.hajacheck.counsel.service.BotScenarioService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시나리오 챗봇 조회 API(FR-7, #20/HAJA-33). 로그인 사용자면 접근 가능(anyRequest().authenticated()).
 */
@Tag(name = "Counsel Scenario", description = "시나리오 챗봇 조회 API")
@RestController
@RequestMapping("/api/counsel/scenarios")
@RequiredArgsConstructor
public class BotScenarioController {

    private final BotScenarioService botScenarioService;

    @Operation(summary = "최상위 시나리오 버튼 목록", description = "챗봇 첫 화면의 최상위 버튼 목록을 반환한다.")
    @GetMapping("/roots")
    public ResponseEntity<ApiResponse<List<BotScenarioButtonResponse>>> getRoots() {
        return ResponseEntity.ok(ApiResponse.ok(botScenarioService.getRootButtons()));
    }

    @Operation(summary = "시나리오 노드 상세", description = "노드의 응답 텍스트와 자식 버튼 목록을 반환한다.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BotScenarioNodeResponse>> getNode(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(botScenarioService.getNode(id)));
    }
}
