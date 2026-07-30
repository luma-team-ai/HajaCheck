package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Flyway 마이그레이션 번호가 <b>V1 부터 결번 없이 연속</b>인지 고정한다(#988 PR머신 검수 요청 항목).
 *
 * <p><b>왜 필요한가</b>: {@code spring.flyway.out-of-order} 는 기본값이 {@code false} 다. 결번 상태로 더
 * 높은 번호가 먼저 적용된 DB 에 나중에 낮은 번호 파일이 도착하면, Flyway 는 그것을 "이미 지나간 순번에
 * 끼어든 마이그레이션"으로 보고 <b>validate 실패로 앱 기동을 거부</b>한다 — 배포가 통째로 멈추는 #531
 * 형태의 장애다. 스키마가 최종적으로 같아지느냐와 무관하게 <b>번호 순서</b>만으로 터진다.
 *
 * <p>이 레포는 실제로 그 상태를 만들었다가 되돌린 이력이 있다: #988(결제 원장)이 V19 를 쓰려다 팀원의
 * #632 가 같은 번호를 선점해 V20 으로 옮겼고, 그 사이 브랜치에는 <b>V19 가 비어 있는 V20</b>이 존재했다.
 * 두 PR 의 머지 순서를 조율(#632 선행 → #988 후행)해 해소했지만, 다음에 같은 상황이 오면 사람이 알아채기
 * 전에 dev 에 들어갈 수 있다. 그 회귀선을 여기서 자동으로 잡는다.
 *
 * <p>런타임 의존이 없는 순수 단위 테스트다 — Spring 컨텍스트도 Testcontainers 도 띄우지 않으므로 CI 에서
 * 사실상 공짜로 돌고, 번호 규칙이 깨진 PR 을 가장 이른 단계에서 되돌려준다.
 */
class FlywayMigrationVersionSequenceTest {

    /** 운영/로컬이 실제로 읽는 위치와 동일해야 한다(application.yml 기본 {@code classpath:db/migration}). */
    private static final String MIGRATION_LOCATION_PATTERN = "classpath*:db/migration/V*__*.sql";

    /** Flyway 버전 파일 명명 규칙 — {@code V{번호}__{설명}.sql}. 이 레포는 정수 단일 번호만 쓴다. */
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void 마이그레이션_번호는_V1부터_결번없이_연속이어야_한다() throws IOException {
        Set<Integer> versions = loadMigrationVersions();

        assertThat(versions)
                .as("classpath 에서 마이그레이션 파일을 하나도 찾지 못했다 — 경로 패턴이 실제 위치와 어긋났는지 확인할 것")
                .isNotEmpty();
        assertThat(versions.iterator().next())
                .as("마이그레이션 번호는 V1 부터 시작해야 한다")
                .isEqualTo(1);

        int max = versions.stream().mapToInt(Integer::intValue).max().orElseThrow();
        List<Integer> missing = IntStream.rangeClosed(1, max)
                .filter(version -> !versions.contains(version))
                .boxed()
                .toList();

        // 실패 메시지에 "빠진 번호"를 그대로 찍는다 — 원인(어느 PR 이 번호를 건너뛰었는지)이 바로 보이도록.
        assertThat(missing)
                .as("""
                        Flyway 마이그레이션 번호에 결번이 있다: %s (현재 최대 번호 V%d)
                        spring.flyway.out-of-order 가 기본 false 라, 결번 상태로 배포되면 나중에 그 번호의
                        마이그레이션이 도착하는 순간 validate 실패로 앱 기동이 거부된다(#531 형태).
                        선행 PR 의 번호가 아직 dev 에 없다면 그 PR 을 먼저 머지하거나, 이 PR 의 번호를
                        빈자리로 당겨서 연속을 회복할 것.""".formatted(missing, max))
                .isEmpty();
    }

    @Test
    void 같은_번호를_쓰는_마이그레이션이_둘_이상_있으면_안된다() throws IOException {
        // 번호 중복은 Flyway 가 기동 시점에 'Found more than one migration with version N' 으로 거부한다.
        // 결번과 같은 뿌리(번호 조율 실패)에서 나오므로 같은 자리에서 함께 막는다.
        List<Integer> allVersions = new ArrayList<>();
        for (Resource resource : resolveMigrationResources()) {
            matchVersion(resource).ifPresent(allVersions::add);
        }

        List<Integer> duplicated = allVersions.stream()
                .collect(Collectors.groupingBy(version -> version, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();

        assertThat(duplicated)
                .as("같은 번호를 쓰는 마이그레이션 파일이 둘 이상이다: %s — Flyway 가 기동을 거부한다", duplicated)
                .isEmpty();
    }

    private Set<Integer> loadMigrationVersions() throws IOException {
        Set<Integer> versions = new TreeSet<>();
        for (Resource resource : resolveMigrationResources()) {
            matchVersion(resource).ifPresent(versions::add);
        }
        return versions;
    }

    private Resource[] resolveMigrationResources() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources(MIGRATION_LOCATION_PATTERN);
    }

    private java.util.Optional<Integer> matchVersion(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return java.util.Optional.empty();
        }
        Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
        // 반복 마이그레이션(R__*.sql)이나 규칙 밖 파일은 순번 대상이 아니라 조용히 건너뛴다.
        return matcher.matches()
                ? java.util.Optional.of(Integer.parseInt(matcher.group(1)))
                : java.util.Optional.empty();
    }
}
