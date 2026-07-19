package com.hajacheck.notification.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/notifications MVC 통합 테스트(AP-020, HAJA-25 FR-9).
 * FacilityControllerTest/MediaControllerTest 와 동일하게 전역 시큐리티 필터체인이
 * ClientRegistrationRepository 를 요구해 @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    private User saveUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("알림수신자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 알림목록조회_인증사용자_200_읽음미읽음모두_최신순() throws Exception {
        User user = saveUser("notif-owner@haja.com");
        Notification older = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.REVIEW_PENDING, null));
        Notification newer = notificationRepository.saveAndFlush(
                Notification.create(user.getId(), NotificationType.ANALYSIS_DONE, "{\"inspectionId\":1}"));
        newer.markAsRead();
        notificationRepository.saveAndFlush(newer);

        mockMvc.perform(get("/api/notifications").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.data[0].type").value("ANALYSIS_DONE"))
                .andExpect(jsonPath("$.data[0].payload.inspectionId").value(1))
                .andExpect(jsonPath("$.data[0].isRead").value(true))
                .andExpect(jsonPath("$.data[1].id").value(older.getId()))
                .andExpect(jsonPath("$.data[1].type").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$.data[1].payload").doesNotExist())
                .andExpect(jsonPath("$.data[1].isRead").value(false));
    }

    @Test
    void 알림목록조회_타인알림은제외() throws Exception {
        User owner = saveUser("notif-owner2@haja.com");
        User stranger = saveUser("notif-stranger@haja.com");
        notificationRepository.save(Notification.create(stranger.getId(), NotificationType.COUNSEL_REPLIED, null));

        mockMvc.perform(get("/api/notifications").with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 알림목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }
}
