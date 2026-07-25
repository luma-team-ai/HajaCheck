package com.hajacheck.counsel.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.repository.BotScenarioRepository;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 시나리오 챗봇 조회 MVC 통합 테스트(#20/HAJA-33). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BotScenarioControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BotScenarioRepository botScenarioRepository;

    private User saveUser() {
        return userRepository.save(User.builder()
                .email("scenario@haja.com").name("사용자").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 루트버튼조회_200() throws Exception {
        botScenarioRepository.save(BotScenario.create(null, "상담", "누수 문제", "누수 안내", true, 0));
        botScenarioRepository.save(BotScenario.create(null, "상담", "결로 문제", "결로 안내", false, 1));
        User user = saveUser();

        mockMvc.perform(get("/api/counsel/scenarios/roots").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].buttonLabel").value("누수 문제"))
                .andExpect(jsonPath("$.data[0].leadsToCounselor").value(true));
    }

    @Test
    void 노드상세조회_자식포함_200() throws Exception {
        BotScenario root = botScenarioRepository.save(
                BotScenario.create(null, "상담", "누수 문제", "누수 안내", false, 0));
        botScenarioRepository.save(
                BotScenario.create(root.getId(), "상담", "천장 누수", "천장 누수 안내", true, 0));
        User user = saveUser();

        mockMvc.perform(get("/api/counsel/scenarios/" + root.getId()).with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buttonLabel").value("누수 문제"))
                .andExpect(jsonPath("$.data.children.length()").value(1))
                .andExpect(jsonPath("$.data.children[0].buttonLabel").value("천장 누수"));
    }

    @Test
    void 노드상세_미존재_404_SCENARIO_NOT_FOUND() throws Exception {
        User user = saveUser();

        mockMvc.perform(get("/api/counsel/scenarios/999999").with(authentication(authOf(user))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_SCENARIO_NOT_FOUND"));
    }

    @Test
    void 루트버튼조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/counsel/scenarios/roots"))
                .andExpect(status().isUnauthorized());
    }
}
