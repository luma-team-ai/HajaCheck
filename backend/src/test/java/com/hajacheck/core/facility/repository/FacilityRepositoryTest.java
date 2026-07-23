package com.hajacheck.core.facility.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(facilities) 대조를 위해 임베디드 교체를 끄고 Testcontainers PostgreSQL 사용 (리뷰 P2).
// facilities.company_id 는 companies(id) FK 이므로, 시설물 저장 전 회사를 먼저 시드한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class FacilityRepositoryTest extends PostgresTestSupport {

    @Autowired
    private FacilityRepository facilityRepository;

    @Autowired
    private TestEntityManager em;

    // FK(company_id → companies) 충족용 회사 시드 후 생성된 id 반환.
    private Long seedOwner(String email) {
        User owner = User.createCompanyOwner(email, "소유자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        Company company = Company.createPendingReview(
                owner.getId(),
                email + " 회사",
                "BRN-" + Math.abs(email.hashCode()),
                "대표자",
                "서울",
                null,
                "https://example.com/brn",
                "{\"source\":\"TEST\"}");
        em.persist(company);
        em.flush();
        return company.getId();
    }

    private Facility newFacility(Long ownerId, String name) {
        return Facility.builder()
                .companyId(ownerId)
                .name(name)
                .type("BUILDING")
                .address("서울시 강남구")
                .build();
    }

    private Facility newFacilityWithDueAt(Long ownerId, String name, LocalDate nextInspectionDueAt) {
        return Facility.builder()
                .companyId(ownerId)
                .name(name)
                .type("BUILDING")
                .address("서울시 강남구")
                .inspectionCycleMonths(6)
                .nextInspectionDueAt(nextInspectionDueAt)
                .build();
    }

    @Test
    void save_저장후_createdAt과id채워짐() {
        Long ownerId = seedOwner("owner-a@haja.com");

        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByCompanyId_소유자별목록_본인시설만반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long otherOwnerId = seedOwner("owner-b@haja.com");
        facilityRepository.save(newFacility(ownerId, "1번소유자시설A"));
        facilityRepository.save(newFacility(ownerId, "1번소유자시설B"));
        facilityRepository.save(newFacility(otherOwnerId, "2번소유자시설"));

        List<Facility> found = facilityRepository.findByCompanyId(ownerId);

        assertThat(found).hasSize(2)
                .extracting(Facility::getName)
                .containsExactlyInAnyOrder("1번소유자시설A", "1번소유자시설B");
    }

    // 시설물 목록 조회 상한(#484) — 상한 초과 데이터 시 limit(Pageable) 건수만 반환되는지 확인.
    @Test
    void findByCompanyIdOrderByIdAsc_상한초과시_limit건수만_id오름차순반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        for (int i = 0; i < 5; i++) {
            facilityRepository.save(newFacility(ownerId, "시설" + i));
        }

        List<Facility> found = facilityRepository.findByCompanyIdOrderByIdAsc(ownerId, PageRequest.of(0, 3));

        assertThat(found).hasSize(3)
                .extracting(Facility::getName)
                .containsExactly("시설0", "시설1", "시설2");
    }

    @Test
    void findByIdAndCompanyId_본인소유_반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdAndCompanyId(saved.getId(), ownerId);

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByIdAndCompanyId_타인소유_빈값() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long otherOwnerId = seedOwner("owner-b@haja.com");
        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdAndCompanyId(saved.getId(), otherOwnerId);

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndCompanyId_없는id_빈값() {
        Long ownerId = seedOwner("owner-a@haja.com");

        Optional<Facility> found = facilityRepository.findByIdAndCompanyId(999L, ownerId);

        assertThat(found).isEmpty();
    }

    // dev-05-02 점검 회차 채번 동시성 방지용 행 잠금 조회 — PESSIMISTIC_WRITE 쿼리가 실 PG 에서 정상 동작하는지 확인.
    @Test
    void findByIdForUpdate_존재하는시설_반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdForUpdate(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByIdForUpdate_없는id_빈값() {
        Optional<Facility> found = facilityRepository.findByIdForUpdate(999L);

        assertThat(found).isEmpty();
    }

    // 다가오는 점검 예정 조회(dev-03-02) — owner_id 단일 스코프, 범위 밖·null·타인소유 제외, 정렬·limit 확인.
    @Test
    void findUpcomingByCompanyId_범위내만_오름차순_반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "10일후", today.plusDays(10)));
        facilityRepository.save(newFacilityWithDueAt(ownerId, "3일후", today.plusDays(3)));
        facilityRepository.save(newFacilityWithDueAt(ownerId, "40일후_범위밖", today.plusDays(40)));
        facilityRepository.save(newFacility(ownerId, "예정일없음"));

        List<Facility> found = facilityRepository.findUpcomingByCompanyId(
                ownerId, today, today.plusDays(30), PageRequest.of(0, 10));

        assertThat(found).hasSize(2)
                .extracting(Facility::getName)
                .containsExactly("3일후", "10일후");
    }

    @Test
    void findUpcomingByCompanyId_타인소유는제외() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long otherOwnerId = seedOwner("owner-b@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "본인시설", today.plusDays(5)));
        facilityRepository.save(newFacilityWithDueAt(otherOwnerId, "타인시설", today.plusDays(5)));

        List<Facility> found = facilityRepository.findUpcomingByCompanyId(
                ownerId, today, today.plusDays(30), PageRequest.of(0, 10));

        assertThat(found).hasSize(1)
                .extracting(Facility::getName)
                .containsExactly("본인시설");
    }

    @Test
    void findUpcomingByCompanyId_limit건수만큼만_반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "1일후", today.plusDays(1)));
        facilityRepository.save(newFacilityWithDueAt(ownerId, "2일후", today.plusDays(2)));
        facilityRepository.save(newFacilityWithDueAt(ownerId, "3일후", today.plusDays(3)));

        List<Facility> found = facilityRepository.findUpcomingByCompanyId(
                ownerId, today, today.plusDays(30), PageRequest.of(0, 2));

        assertThat(found).hasSize(2)
                .extracting(Facility::getName)
                .containsExactly("1일후", "2일후");
    }

    @Test
    void findUpcomingByCompanyId_from경계값_오늘포함() {
        // from(오늘) 자체와 같은 nextInspectionDueAt 도 포함돼야 한다(inclusive, dDay=0).
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "오늘마감", today));

        List<Facility> found = facilityRepository.findUpcomingByCompanyId(
                ownerId, today, today.plusDays(30), PageRequest.of(0, 10));

        assertThat(found).hasSize(1)
                .extracting(Facility::getName)
                .containsExactly("오늘마감");
    }

    @Test
    void findUpcomingByCompanyId_to경계값_범위끝포함() {
        // to(오늘+days) 자체와 같은 nextInspectionDueAt 도 포함돼야 한다(inclusive).
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "범위끝마감", today.plusDays(30)));

        List<Facility> found = facilityRepository.findUpcomingByCompanyId(
                ownerId, today, today.plusDays(30), PageRequest.of(0, 10));

        assertThat(found).hasSize(1)
                .extracting(Facility::getName)
                .containsExactly("범위끝마감");
    }

    @Test
    void findUpcomingByCompanyId_소유시설없으면_빈목록() {
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();

        List<Facility> found = facilityRepository.findUpcomingByCompanyId(
                ownerId, today, today.plusDays(30), PageRequest.of(0, 10));

        assertThat(found).isEmpty();
    }

    // INSPECTION_DUE 알림 배치(NOTI-01) — owner 스코프 없는 전역 페이징 쿼리: 오늘 이하(overdue 포함)만, 미래 제외.
    @Test
    void findAllByNextInspectionDueAtLessThanEqual_오늘과overdue포함_미래제외() {
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "오늘마감", today));
        facilityRepository.save(newFacilityWithDueAt(ownerId, "어제마감_overdue", today.minusDays(1)));
        facilityRepository.save(newFacilityWithDueAt(ownerId, "내일마감_미래", today.plusDays(1)));
        facilityRepository.save(newFacility(ownerId, "예정일없음"));

        Slice<Facility> found = facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(
                today, PageRequest.of(0, 200));

        assertThat(found.getContent())
                .extracting(Facility::getName)
                .containsExactlyInAnyOrder("오늘마감", "어제마감_overdue");
    }

    @Test
    void findAllByNextInspectionDueAtLessThanEqual_owner스코프없이_모든owner반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long otherOwnerId = seedOwner("owner-b@haja.com");
        LocalDate today = LocalDate.now();
        facilityRepository.save(newFacilityWithDueAt(ownerId, "A소유_오늘마감", today));
        facilityRepository.save(newFacilityWithDueAt(otherOwnerId, "B소유_오늘마감", today));

        Slice<Facility> found = facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(
                today, PageRequest.of(0, 200));

        assertThat(found.getContent())
                .extracting(Facility::getName)
                .containsExactlyInAnyOrder("A소유_오늘마감", "B소유_오늘마감");
    }

    // PR머신 요청 회귀: 여러 페이지를 순회해도 중복·누락 없이 정확히 N건이 조회돼야 한다.
    // OrderByIdAsc 가 없으면 Postgres 는 페이지 간 행 순서를 보장하지 않아, 물리 저장 순서가 흔들릴 때
    // 같은 행이 두 페이지에 중복되거나 어느 페이지에도 안 나오는 누락이 발생한다. 그래서 삽입 후 update 로
    // 힙 저장 순서를 일부러 흔들어(MVCC: update 된 튜플이 새 힙 위치로 이동) 무정렬이었다면 실패하도록 구성한다.
    @Test
    void findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc_여러페이지_중복누락없이_전부조회() {
        Long ownerId = seedOwner("owner-a@haja.com");
        LocalDate today = LocalDate.now();
        int total = 7;
        List<Long> savedIds = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            Facility saved = facilityRepository.save(
                    newFacilityWithDueAt(ownerId, "마감시설" + i, today.minusDays(i)));
            savedIds.add(saved.getId());
        }
        em.flush();
        em.clear();

        // 짝수 인덱스 시설물을 재저장(update)해 물리 저장 순서를 id 순서와 어긋나게 흔든다.
        for (int i = 0; i < total; i += 2) {
            Facility f = facilityRepository.findById(savedIds.get(i)).orElseThrow();
            f.updateInfo(f.getName() + "_수정", f.getType(), f.getAddress(),
                    f.getLatitude(), f.getLongitude(), f.getBuiltYear(),
                    f.getScale(), f.getInspectionCycleMonths(), f.getNextInspectionDueAt());
            facilityRepository.save(f);
        }
        em.flush();
        em.clear();

        int pageSize = 2;
        List<Long> collectedIds = new java.util.ArrayList<>();
        int pageNumber = 0;
        Slice<Facility> page;
        do {
            page = facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(
                    today, PageRequest.of(pageNumber, pageSize));
            page.getContent().forEach(f -> collectedIds.add(f.getId()));
            pageNumber++;
        } while (page.hasNext());

        // 7건 / 페이지 2 → 4페이지(2+2+2+1). 중복·누락 없이 정확히 id 오름차순으로 전부 수집돼야 한다.
        assertThat(pageNumber).isEqualTo(4);
        assertThat(collectedIds)
                .hasSize(total)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(savedIds.stream().sorted().toList());
    }
}
