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

        Page<CounselTicket> page = ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
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

        Page<CounselTicket> page = ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
                LocalDateTime.of(2026, 7, 20, 0, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
    }

    /*
     * 타임존 회귀 고정 테스트는 이 클래스에 있었으나 삭제했다(#1263) — 리포지토리를 직접 호출해 이미
     * 확정된 LocalDateTime 경계를 넘기는 구조라, 경계를 만드는 쪽(서비스)이 ZoneId 환산을 넣어도 이
     * 테스트는 통과했다(= 실패할 수 없는 테스트). 계약을 실제로 지키는 회귀 테스트는 경계 계산 주체인
     * CounselTicketServiceTest#관리자_날짜별목록_조회경계는_JVM_기본타임존과_무관하다 로 옮겼다.
     */

    @Test
    void 접수일_같은날_여러건_최신순정렬() {
        Long userId = seedUser("boundary-order@haja.com");
        CounselTicket early = seedTicketAt(userId, LocalDateTime.of(2026, 7, 20, 9, 0, 0));
        CounselTicket late = seedTicketAt(userId, LocalDateTime.of(2026, 7, 20, 18, 0, 0));

        Page<CounselTicket> page = ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
                LocalDateTime.of(2026, 7, 20, 0, 0, 0),
                LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(CounselTicket::getId)
                .containsExactly(late.getId(), early.getId());
    }

    /**
     * 정렬 타이브레이커 회귀 고정(#1263) — createdAt 이 완전히 같은 티켓들이 페이지 경계에 걸려도 중복·누락이
     * 없어야 한다. createdAt 단일 키 정렬이면 동률 행의 순서가 쿼리마다 달라질 수 있어 같은 티켓이 두 페이지에
     * 나오거나 아예 빠질 수 있다. id DESC 타이브레이커가 전순서를 확정한다.
     */
    @Test
    void 접수시각이_동일해도_페이지경계에서_중복이나누락이_없다() {
        Long userId = seedUser("boundary-tie@haja.com");
        LocalDateTime sameInstant = LocalDateTime.of(2026, 7, 20, 13, 0, 0);
        CounselTicket first = seedTicketAt(userId, sameInstant);
        CounselTicket second = seedTicketAt(userId, sameInstant);
        CounselTicket third = seedTicketAt(userId, sameInstant);

        LocalDateTime start = LocalDateTime.of(2026, 7, 20, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000);
        Page<CounselTicket> page0 =
                ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(start, end, PageRequest.of(0, 2));
        Page<CounselTicket> page1 =
                ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(start, end, PageRequest.of(1, 2));

        assertThat(page0.getContent()).extracting(CounselTicket::getId)
                .containsExactly(third.getId(), second.getId());
        assertThat(page1.getContent()).extracting(CounselTicket::getId)
                .containsExactly(first.getId());
    }
}
