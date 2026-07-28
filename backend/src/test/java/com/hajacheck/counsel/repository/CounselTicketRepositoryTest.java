package com.hajacheck.counsel.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * CounselTicketRepository 신규 메서드(#1168 플랫폼 관리자 날짜별 상담 목록) — 접수일(createdAt) 경계값 테스트.
 * 실 PG(Testcontainers)에서 [start, end] BETWEEN 경계(자정 포함/제외)를 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class CounselTicketRepositoryTest extends PostgresTestSupport {

    @Autowired
    private CounselTicketRepository ticketRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedUser(String email) {
        User user = User.builder()
                .email(email).name("사용자").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        em.persist(user);
        em.flush();
        return user.getId();
    }

    private CounselTicket seedTicketAt(Long userId, LocalDateTime createdAt) {
        CounselTicket ticket = CounselTicket.request(userId, CounselType.USAGE, 1, "USAGE_GUIDE", "이용 방법");
        em.persist(ticket);
        em.flush();
        // @CreatedDate 는 persist 시점에 now()로 채워지므로, 테스트 대상 시각으로 직접 갱신 후 재조회한다.
        em.getEntityManager()
                .createQuery("update CounselTicket t set t.createdAt = :createdAt where t.id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", ticket.getId())
                .executeUpdate();
        em.flush();
        em.clear();
        return ticket;
    }

    @Test
    void 접수일_경계값_당일_00시00분00초_포함() {
        Long userId = seedUser("boundary-start@haja.com");
        LocalDateTime target = LocalDateTime.of(2026, 7, 20, 0, 0, 0);
        CounselTicket ticket = seedTicketAt(userId, target);

        Page<CounselTicket> page = ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                LocalDateTime.of(2026, 7, 20, 0, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(CounselTicket::getId).containsExactly(ticket.getId());
    }

    @Test
    void 접수일_경계값_다음날_00시00분00초_제외() {
        Long userId = seedUser("boundary-next@haja.com");
        // 다음날 자정 정각 접수 — 당일 조회 구간에서 제외돼야 한다.
        LocalDateTime nextDayMidnight = LocalDateTime.of(2026, 7, 21, 0, 0, 0);
        seedTicketAt(userId, nextDayMidnight);

        Page<CounselTicket> page = ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                LocalDateTime.of(2026, 7, 20, 0, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void 접수일_같은날_여러건_최신순정렬() {
        Long userId = seedUser("boundary-order@haja.com");
        CounselTicket early = seedTicketAt(userId, LocalDateTime.of(2026, 7, 20, 9, 0, 0));
        CounselTicket late = seedTicketAt(userId, LocalDateTime.of(2026, 7, 20, 18, 0, 0));

        Page<CounselTicket> page = ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                LocalDateTime.of(2026, 7, 20, 0, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(CounselTicket::getId)
                .containsExactly(late.getId(), early.getId());
    }
}
