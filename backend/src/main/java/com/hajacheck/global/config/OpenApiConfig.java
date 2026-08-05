package com.hajacheck.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 문서 생성 커스터마이징(IT-05 API 계약 정합성). 여기 등록하는 것은 OpenAPI 스키마 생성
 * 파이프라인({@code /v3/api-docs})에만 관여하며, 실제 요청 바인딩·응답 직렬화(Jackson)와는 분리된
 * 코드 경로다 — 앱 동작에는 영향을 주지 않는다.
 */
@Configuration
public class OpenApiConfig {

    /**
     * 응답 record의 전 필드를 {@code required}로 표시한다.
     *
     * <p><b>왜 필요한가</b>: Jackson은 record의 모든 컴포넌트를 항상 JSON 키로 내보낸다(값이 null이어도
     * {@code "field": null} 로 키는 존재). 반면 swagger-core 는 {@code @NotNull} 같은 검증 애노테이션이
     * 붙은 필드만 required 로 표시하는데, 응답 DTO 에는 검증 애노테이션을 달지 않는 게 관례라 "실제로는
     * 항상 오는 필드가 계약서엔 optional 로" 표기되는 불일치가 광범위하게 생겼다(IT-05 diff 79건).
     * {@code openapi.yaml} 은 이 필드들을 required 로 적고 있으므로(예: {@code [success, data, error]},
     * {@code [content, page, totalElements]}) 실서버 문서를 계약서 표기에 맞춘다.
     *
     * <p><b>제외 대상</b> — required 로 적으면 틀리는 두 경우만 건너뛴다:
     * <ul>
     *   <li>{@code *Request} — 요청 DTO. 클라이언트가 키를 아예 생략할 수 있으므로 선택 입력 필드가
     *       필수로 잘못 표기된다(예: {@code PATCH /api/defects/{id}/status} 의 {@code reason}).</li>
     *   <li>{@code @JsonInclude} 로 조건부 직렬화를 쓰는 record — null 일 때 키 자체가 빠지므로
     *       required 가 성립하지 않는다(예: {@code ReportResponse}).</li>
     * </ul>
     *
     * <p>ponytail: 요청/응답 판별을 클래스명 접미사로 한다. 스키마는 요청·응답이 {@code components/schemas}
     * 를 공유해 구조적으로 구분할 방법이 없고, 이 레포는 DTO 네이밍이 일관되기 때문이다(record 178개 중
     * 요청은 전부 {@code *Request}, 나머지는 응답 또는 응답 내부 항목). 요청·응답이 같은 record 를
     * 공유하게 되면 이 규칙으로는 못 가르므로, 그때는 응답 전용 마커 애노테이션으로 승격할 것.
     *
     * <p>위 규칙과 그 한계는 {@code OpenApiConfigTest} 가 고정한다 — 공유 record 가 요청 바디에
     * 들어오는 순간 {@code requestBodiesDoNotShareTopLevelResponseRecords} 가 실패한다.
     */
    @Bean
    public ModelConverter responseRecordRequiredConverter(ObjectMapperProvider springDocObjectMapper) {
        return new ModelConverter() {
            @Override
            public Schema<?> resolve(
                    AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
                if (!chain.hasNext()) {
                    return null;
                }
                Schema<?> resolved = chain.next().resolve(type, context, chain);
                if (resolved == null) {
                    return null;
                }

                JavaType javaType = springDocObjectMapper.jsonMapper().constructType(type.getType());
                if (javaType == null) {
                    return resolved;
                }
                Class<?> recordClass = javaType.getRawClass();
                if (!recordClass.isRecord()
                        || isRequestType(recordClass)
                        || hasConditionalInclusion(recordClass)) {
                    return resolved;
                }

                // record/object 타입은 resolve() 가 $ref 껍데기만 돌려주고, 실제 필드를 담은 스키마는
                // context 의 정의된 모델 쪽에 등록돼 있다(springdoc 의 PropertyNamingStrategyConverter 와 동일 패턴).
                Schema<?> target = resolved;
                if (resolved.get$ref() != null) {
                    target = context.getDefinedModels()
                            .get(resolved.get$ref().substring(Components.COMPONENTS_SCHEMAS_REF.length()));
                }
                if (target == null || target.getProperties() == null) {
                    return resolved;
                }

                List<String> required = target.getRequired() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(target.getRequired());
                for (String propertyName : target.getProperties().keySet()) {
                    if (!required.contains(propertyName)) {
                        required.add(propertyName);
                    }
                }
                target.setRequired(required);
                return resolved;
            }
        };
    }

    /**
     * 요청 DTO 인지 — 바깥 클래스까지 거슬러 올라가며 본다. 요청 DTO 안에 중첩된 record 는 이름이
     * {@code Request} 로 끝나지 않아(예: {@code PlatformAdminPlanPolicyUpdateRequest.Entry},
     * {@code ReportRequest.ConfirmedDefect}) 자기 이름만 봐서는 요청인 줄 모르고 선택 입력 필드를
     * 필수로 잘못 표기하게 된다.
     */
    private static boolean isRequestType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (current.getSimpleName().endsWith("Request")) {
                return true;
            }
        }
        return false;
    }

    /**
     * null 일 때 JSON 키가 빠질 수 있는 record 인지 — 하나라도 조건부면 그 record 는 통째로 건드리지
     * 않는다(필드별로 가려내는 대신 보수적으로 제외 — 잘못 required 로 적는 것보다 안전하다).
     * record 컴포넌트에 붙인 {@code @JsonInclude} 는 대응 필드로 전파되므로 선언 필드를 본다.
     */
    private static boolean hasConditionalInclusion(Class<?> recordClass) {
        JsonInclude onClass = recordClass.getAnnotation(JsonInclude.class);
        if (onClass != null && onClass.value() != JsonInclude.Include.ALWAYS) {
            return true;
        }
        for (Field field : recordClass.getDeclaredFields()) {
            JsonInclude onField = field.getAnnotation(JsonInclude.class);
            if (onField != null && onField.value() != JsonInclude.Include.ALWAYS) {
                return true;
            }
        }
        return false;
    }
}
