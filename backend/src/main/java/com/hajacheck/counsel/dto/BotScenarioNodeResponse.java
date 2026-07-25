package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.BotScenario;
import java.util.List;

/** 시나리오 노드 상세 — 응답 텍스트 + 자식 버튼 목록. */
public record BotScenarioNodeResponse(
        Long id,
        Long parentId,
        String category,
        String buttonLabel,
        String responseText,
        boolean leadsToCounselor,
        List<BotScenarioButtonResponse> children) {

    public static BotScenarioNodeResponse of(BotScenario node, List<BotScenario> children) {
        return new BotScenarioNodeResponse(
                node.getId(),
                node.getParentId(),
                node.getCategory(),
                node.getButtonLabel(),
                node.getResponseText(),
                node.isLeadsToCounselor(),
                children.stream().map(BotScenarioButtonResponse::from).toList());
    }
}
