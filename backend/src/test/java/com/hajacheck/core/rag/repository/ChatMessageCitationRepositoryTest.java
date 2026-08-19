package com.hajacheck.core.rag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code findByMessageIdIn}의 {@code @EntityGraph(attributePaths = "document")} 검증(#1698).
 *
 * <p>이력 응답 {@code Citation.from()}이 인용마다 {@code citation.getDocument()}(title/collection)을
 * 참조하므로, EntityGraph 없이 LAZY 그대로 두면 인용 건수만큼 {@code rag_documents} 조회가 반복되는
 * N+1이 생긴다. 실 PG(Testcontainers)에서 서로 다른 문서를 인용하는 인용 2건을 조회한 뒤, Hibernate
 * 통계로 실행된 SELECT가 1건(문서까지 한 번에 join fetch)인지 확인해 N+1을 구조로 차단한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class ChatMessageCitationRepositoryTest extends PostgresTestSupport {

    @Autowired
    private ChatMessageCitationRepository chatMessageCitationRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void findByMessageIdIn_EntityGraph로문서를함께조회해N1이생기지않는다() {
        long unique = System.nanoTime();
        User user = User.builder()
                .email("citation-" + unique + "@haja.com").name("사용자").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        em.persist(user);

        ChatSession session = ChatSession.start(user.getId(), ChatSessionType.RAG);
        em.persist(session);

        ChatMessage botMessage1 = ChatMessage.createText(session.getId(), ChatSenderType.BOT, "답변1");
        ChatMessage botMessage2 = ChatMessage.createText(session.getId(), ChatSenderType.BOT, "답변2");
        em.persist(botMessage1);
        em.persist(botMessage2);

        RagDocument regulationDoc = RagDocument.upload(
                "시설물의 안전 및 유지관리에 관한 특별법", RagDocumentSourceType.LAW,
                RagTargetCollection.REGULATIONS, null, "국토교통부", null, null, "rag-documents/stub1.pdf");
        RagDocument defectKbDoc = RagDocument.upload(
                "결함 판정 기준", RagDocumentSourceType.LAW,
                RagTargetCollection.DEFECT_KB, null, "국토교통부", null, null, "rag-documents/stub2.pdf");
        em.persist(regulationDoc);
        em.persist(defectKbDoc);

        ChatMessageCitation citation1 = ChatMessageCitation.create(
                botMessage1.getId(), regulationDoc.getId(), "12_3", "제11조 ①", "시설물 안전 발췌");
        ChatMessageCitation citation2 = ChatMessageCitation.create(
                botMessage2.getId(), defectKbDoc.getId(), "20_1", "결함유형 3", "결함 판정 발췌");
        em.persist(citation1);
        em.persist(citation2);

        em.flush();
        em.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<ChatMessageCitation> found = chatMessageCitationRepository.findByMessageIdIn(
                List.of(botMessage1.getId(), botMessage2.getId()));

        // 인용 2건을 조회했지만 문서까지 join fetch로 한 번에 읽어 실행된 쿼리는 1건뿐이어야 한다
        // (EntityGraph 없이 LAZY만 썼다면 findByMessageIdIn 1건 + document 접근 시 지연로딩 2건 = 3건).
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(c -> c.getDocument().getTitle())
                .containsExactlyInAnyOrder("시설물의 안전 및 유지관리에 관한 특별법", "결함 판정 기준");
        assertThat(found).extracting(c -> c.getDocument().getTargetCollection())
                .containsExactlyInAnyOrder(RagTargetCollection.REGULATIONS, RagTargetCollection.DEFECT_KB);
        // 문서 접근 이후에도 추가 SELECT가 없어야 한다(위 join fetch가 실제로 lazy 초기화를 대체했는지 재확인).
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
