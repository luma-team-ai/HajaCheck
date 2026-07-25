package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.BotScenario;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotScenarioRepository extends JpaRepository<BotScenario, Long> {

    // 최상위 시나리오 버튼(parent_id IS NULL) — sort_order 오름차순.
    List<BotScenario> findByParentIdIsNullOrderBySortOrderAsc();

    // 특정 노드의 자식 버튼 목록 — sort_order 오름차순.
    List<BotScenario> findByParentIdOrderBySortOrderAsc(Long parentId);
}
