package com.hajacheck.counsel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hajacheck.counsel.dto.BotScenarioButtonResponse;
import com.hajacheck.counsel.dto.BotScenarioNodeResponse;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.repository.BotScenarioRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** BotScenarioService 단위테스트 — 루트 버튼 목록/노드 상세/미존재 노드 404(#20/HAJA-33). */
@ExtendWith(MockitoExtension.class)
class BotScenarioServiceTest {

    @Mock
    private BotScenarioRepository botScenarioRepository;

    private BotScenarioService service;

    @BeforeEach
    void setUp() {
        service = new BotScenarioService(botScenarioRepository);
    }

    @Test
    void 루트버튼목록_반환() {
        when(botScenarioRepository.findByParentIdIsNullOrderBySortOrderAsc())
                .thenReturn(List.of(scenario(1L, null, "누수", true), scenario(2L, null, "결로", false)));

        List<BotScenarioButtonResponse> roots = service.getRootButtons();

        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).buttonLabel()).isEqualTo("누수");
        assertThat(roots.get(0).leadsToCounselor()).isTrue();
    }

    @Test
    void 노드상세_자식버튼포함() {
        BotScenario node = scenario(1L, null, "누수", false);
        when(botScenarioRepository.findById(1L)).thenReturn(Optional.of(node));
        when(botScenarioRepository.findByParentIdOrderBySortOrderAsc(1L))
                .thenReturn(List.of(scenario(3L, 1L, "천장 누수", true)));

        BotScenarioNodeResponse response = service.getNode(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.children()).hasSize(1);
        assertThat(response.children().get(0).buttonLabel()).isEqualTo("천장 누수");
    }

    @Test
    void 노드상세_미존재_404_SCENARIO_NOT_FOUND() {
        when(botScenarioRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNode(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_SCENARIO_NOT_FOUND);
    }

    private BotScenario scenario(Long id, Long parentId, String label, boolean leadsToCounselor) {
        BotScenario scenario = BotScenario.create(parentId, "상담", label, "응답", leadsToCounselor, 0);
        ReflectionTestUtils.setField(scenario, "id", id);
        return scenario;
    }
}
