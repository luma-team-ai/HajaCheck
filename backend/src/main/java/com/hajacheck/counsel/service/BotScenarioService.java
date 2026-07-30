package com.hajacheck.counsel.service;

import com.hajacheck.counsel.dto.BotScenarioButtonResponse;
import com.hajacheck.counsel.dto.BotScenarioNodeResponse;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.repository.BotScenarioRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 버튼 선택형 챗봇 시나리오 조회(FR-7, #20/HAJA-33). 조회 전용 — 시나리오 트리는 관리자 시드로만 관리한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BotScenarioService {

    private final BotScenarioRepository botScenarioRepository;

    /** 최상위 시나리오 버튼 목록(parent_id IS NULL). */
    public List<BotScenarioButtonResponse> getRootButtons() {
        return botScenarioRepository.findByParentIdIsNullOrderBySortOrderAsc().stream()
                .map(BotScenarioButtonResponse::from)
                .toList();
    }

    /** 노드 상세 + 자식 버튼 목록. */
    public BotScenarioNodeResponse getNode(Long scenarioId) {
        BotScenario node = botScenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_SCENARIO_NOT_FOUND));
        List<BotScenario> children = botScenarioRepository.findByParentIdOrderBySortOrderAsc(node.getId());
        return BotScenarioNodeResponse.of(node, children);
    }
}
