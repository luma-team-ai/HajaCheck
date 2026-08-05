package com.hajacheck.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.defect.dto.DefectStatusUpdateRequest;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.menu.dto.MenuTreeItemResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanPolicyUpdateRequest;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link OpenApiConfig#responseRecordRequiredConverter} 의 요청/응답 판별 규칙을 고정한다.
 *
 * <p>이 컨버터는 "record 전 필드를 required 로 승격하되 요청 DTO 와 조건부 직렬화 record 는 제외"라는
 * 비자명한 규칙이라, 회귀하면 요청의 선택 입력이 필수로 잘못 표기되거나(계약 위반) 응답의 항상 존재하는
 * 필드가 optional 로 돌아가도 아무도 모른다. 스프링 컨텍스트 없이 swagger-core 로 스키마만 뽑아 단언한다.
 */
class OpenApiConfigTest {

    private static final String BASE_PACKAGE = "com.hajacheck";

    private ModelConverters converters;

    @BeforeEach
    void setUp() {
        converters = new ModelConverters();
        converters.addConverter(
                new OpenApiConfig()
                        .responseRecordRequiredConverter(
                                new ObjectMapperProvider(new SpringDocConfigProperties())));
    }

    @Test
    @DisplayName("응답 record 는 전 필드가 required 로 승격된다")
    void promotesEveryPropertyOfResponseRecord() {
        Schema<?> summary = resolve(ReportResponse.Summary.class);

        assertThat(summary.getProperties()).isNotEmpty();
        assertThat(summary.getRequired())
                .containsExactlyInAnyOrderElementsOf(summary.getProperties().keySet());
    }

    @Test
    @DisplayName("공통 페이징 envelope 도 계약서 표기(content·page·totalElements)대로 required 다")
    void promotesPageResponseEnvelope() {
        Schema<?> page = resolve(PageResponse.class);

        assertThat(page.getRequired()).contains("content", "page", "totalElements");
    }

    @Test
    @DisplayName("*Request 의 선택 입력은 required 로 승격되지 않는다")
    void skipsOptionalPropertyOfRequestRecord() {
        Schema<?> request = resolve(DefectStatusUpdateRequest.class);

        // status 는 @NotNull 이라 required(swagger-core 기본 동작), reason 은 @Size 뿐이라 선택 입력이다.
        assertThat(request.getRequired()).contains("status").doesNotContain("reason");
    }

    @Test
    @DisplayName("요청 DTO 안에 중첩된 record 도 이름이 Request 로 안 끝나지만 제외된다")
    void skipsNestedRecordOfRequestDto() {
        Schema<?> entry = resolve(PlatformAdminPlanPolicyUpdateRequest.Entry.class);

        // 코드 주석상 "null = 무제한" 인 세 필드 — 필수로 표기되면 계약이 틀어진다.
        assertThat(entry.getRequired())
                .doesNotContain("maxFacilities", "maxMonthlyAnalyses", "maxSeats");
    }

    @Test
    @DisplayName("자기 자신을 참조하는 record 도 재귀 필드까지 required 에 들어간다")
    void promotesSelfReferencingRecord() {
        Schema<?> menu = resolve(MenuTreeItemResponse.class);

        // children 은 List<MenuTreeItemResponse> — 재귀 해석 중 스키마가 아직 미완성이면 누락된다.
        assertThat(menu.getRequired())
                .containsExactlyInAnyOrderElementsOf(menu.getProperties().keySet());
        assertThat(menu.getRequired()).contains("children");
    }

    @Test
    @DisplayName("@JsonInclude 조건부 직렬화 record 는 통째로 승격 대상에서 빠진다")
    void skipsRecordWithConditionalInclusion() {
        Schema<?> report = resolve(ReportResponse.class);

        // null 이면 키 자체가 빠지는 필드가 있으므로 required 를 붙이면 안 된다.
        assertThat(report.getRequired()).isNullOrEmpty();
    }

    /**
     * 판별을 클래스명 접미사로 하는 데서 오는 알려진 한계의 가드 — 요청 바디가 {@code *Request} 로 끝나지
     * 않는 최상위 record 를 필드 타입으로 참조하면, 그 공유 스키마가 응답으로 오인돼 선택 입력까지 required
     * 로 승격된다. 현재 그런 record 가 없다는 것이 컨버터의 전제이므로, 전제가 깨지는 순간 여기서 실패한다
     * (그때는 응답 전용 마커 애노테이션으로 승격할 것 — {@code OpenApiConfig} 주석 참조).
     *
     * <p>ponytail: 출발점을 컨트롤러의 {@code @RequestBody}/{@code @RequestPart} 로 잡는다 — springdoc 이
     * 실제로 스키마를 만드는 표면이 거기라서다. 프로젝트 record 전체를 훑으면 FastAPI 로 나가는 아웃바운드
     * DTO({@code RagChatAiRequest} 등 문서에 안 실리는 것)까지 걸려 과탐한다.
     */
    @Test
    @DisplayName("요청 바디가 참조하는 record 는 전부 요청 계열이다(공유 record 도입 시 실패)")
    void requestBodiesDoNotShareTopLevelResponseRecords() throws ClassNotFoundException {
        Set<Class<?>> roots = requestBodyRecords();
        assertThat(roots).as("컨트롤러에서 수집한 요청 바디 record").isNotEmpty();

        List<String> violations = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        for (Class<?> root : roots) {
            collectViolations(root, visited, violations);
        }

        // 가드가 헛돌지 않는지(루트만 훑고 하위로 안 내려가는 상태로 통과하지 않는지) 확인 —
        // 이 중첩 record 는 PUT /api/platform-admin/plans 요청 바디 안에서만 닿을 수 있다.
        assertThat(visited).contains(PlatformAdminPlanPolicyUpdateRequest.Entry.class);
        assertThat(violations)
                .as("요청 바디가 참조하는 비-Request record (공유되면 선택 입력이 required 로 오표기됨)")
                .isEmpty();
    }

    private void collectViolations(Class<?> owner, Set<Class<?>> visited, List<String> violations) {
        if (!visited.add(owner)) {
            return;
        }
        if (!isRequestType(owner) && !hasConditionalInclusion(owner)) {
            violations.add(owner.getName());
        }
        for (RecordComponent component : owner.getRecordComponents()) {
            Set<Class<?>> referenced = new HashSet<>();
            collectRecords(component.getGenericType(), referenced);
            for (Class<?> next : referenced) {
                collectViolations(next, visited, violations);
            }
        }
    }

    /** 컨트롤러 요청 바디로 실제 문서화되는 record 들 — springdoc 이 스키마를 만드는 표면과 같다. */
    private Set<Class<?>> requestBodyRecords() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<Class<?>> roots = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            || parameter.isAnnotationPresent(RequestPart.class)) {
                        collectRecords(parameter.getParameterizedType(), roots);
                    }
                }
            }
        }
        return roots;
    }

    private void collectRecords(java.lang.reflect.Type type, Set<Class<?>> found) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isRecord() && clazz.getName().startsWith(BASE_PACKAGE)) {
                found.add(clazz);
            }
        } else if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            collectRecords(parameterized.getRawType(), found);
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                collectRecords(argument, found);
            }
        }
    }

    // 아래 둘은 OpenApiConfig 의 private 판별 규칙과 같은 정의 — 테스트가 규칙을 독립적으로 재진술해
    // 구현이 조용히 바뀌면 드러나게 한다.
    private boolean isRequestType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (current.getSimpleName().endsWith("Request")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConditionalInclusion(Class<?> recordClass) {
        JsonInclude onClass = recordClass.getAnnotation(JsonInclude.class);
        if (onClass != null && onClass.value() != JsonInclude.Include.ALWAYS) {
            return true;
        }
        for (java.lang.reflect.Field field : recordClass.getDeclaredFields()) {
            JsonInclude onField = field.getAnnotation(JsonInclude.class);
            if (onField != null && onField.value() != JsonInclude.Include.ALWAYS) {
                return true;
            }
        }
        return false;
    }

    private Schema<?> resolve(Class<?> type) {
        Map<String, Schema> models = converters.readAll(new AnnotatedType(type));
        String expected = type.getSimpleName();
        Schema<?> schema = models.get(expected);
        assertThat(schema).as("스키마 %s (실제 키: %s)", expected, models.keySet()).isNotNull();
        return schema;
    }
}
