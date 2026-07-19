# hajaCheck — Spring Boot(Java) 코드 컨벤션

> **문서 버전:** v0.3 · **최종 수정:** 2026-07-18 · 이전 버전 `archive/`

> 대상: 백엔드 코드를 작성하는 전체 팀원 (메뉴 담당제 — 전원이 API를 직접 구현)
> 관리: Backend 리드 (프로젝트 구조·API 계약 총괄, 리뷰 시 준수 점검)
> 기준: JDK 17 / Spring Boot 3.x / Gradle / 모듈러 모놀리스
> 연관 문서: PRD_hajaCheck.md §6, AI_개발_컨벤션.md

---

## 0. 원칙

- 도메인 패키지 간 직접 의존 금지 — 다른 도메인 기능이 필요하면 해당 도메인의 Service 인터페이스를 통해 호출
- Controller는 얇게, 비즈니스 로직은 Service에, 데이터 접근은 Repository에
- Entity를 API 응답으로 직접 반환 금지 — 반드시 DTO 변환
- 이 문서와 다른 방식이 필요하면 Backend 리드와 협의 후 문서를 먼저 수정

---

## 1. 프로젝트 구조 (모듈러 모놀리스)

도메인 우선(package-by-feature), 도메인 내부에서 레이어 분리.

```
com.hajacheck/
├─ global/                     # 도메인 무관 공통
│  ├─ config/                  # Security, Redis, WebSocket, Swagger 설정
│  ├─ common/                  # ApiResponse, 공통 DTO, 상수
│  ├─ exception/               # GlobalExceptionHandler, ErrorCode, BusinessException
│  └─ util/
├─ auth/                       # 로그인·회원·마이페이지
│  ├─ controller/  service/  repository/  entity/  dto/
├─ core/                       # 시설물·점검·하자·보고서·업로드
│  ├─ facility/    ├─ inspection/    ├─ defect/    ├─ report/    ├─ media/
│  │   (각각 controller/ service/ repository/ entity/ dto/)
│  └─ ai/                      # FastAPI 연동 클라이언트 (분석 잡 요청·폴링)
├─ counsel/                    # 시나리오 챗봇·상담(WebSocket)·티켓
│  ├─ controller/  service/  repository/  entity/  dto/  websocket/
└─ admin/                      # 관리자 (사용자·기준·통계·모니터링)
   ├─ controller/  service/  repository/  dto/
```

**도메인 간 경계 규칙**

- `auth` ↔ `core` ↔ `counsel` ↔ `admin` 간 Repository/Entity 직접 참조 금지 (Service 경유)
- `admin`은 각 도메인의 admin용 Service를 호출하는 조합 계층 — 자체 Entity 최소화
- `global`은 모든 도메인이 참조 가능, 역방향(global→도메인) 참조 금지

## 2. 네이밍

| 대상 | 규칙 | 예 |
|---|---|---|
| 클래스 | PascalCase, 역할 접미사 | `DefectController`, `DefectService`, `DefectRepository` |
| 메서드 | camelCase, 동사 시작 | `findDefectsByGrade()`, `updateDefectStatus()` |
| DTO | 용도 접미사 | 요청 `DefectUpdateRequest`, 응답 `DefectResponse` |
| Entity | 도메인 명사 단수 | `Defect`, `Inspection` (테이블: `defects`, `inspections` — snake_case 복수) |
| 상수/enum | UPPER_SNAKE_CASE | `DefectStatus.PENDING_REVIEW` |
| 패키지 | 소문자 단수 | `defect`, `inspection` |

- boolean은 `is`/`has` 접두: `isReviewed`, `hasAttachment`
- 축약어 금지: `insp` ✗ → `inspection` ✓ (관용 축약 `id`, `url`, `dto`는 허용)

## 3. 레이어 규칙

**Controller**
- `@RestController` + 클래스 레벨 `@RequestMapping("/api/{도메인}")`
- 하는 일: 요청 검증(`@Valid`) → Service 호출 → `ApiResponse` 래핑 반환. 그 외 로직 금지
- 인증 사용자 정보는 `@AuthenticationPrincipal`로 주입 (SecurityContext 직접 접근 금지)

**Service**
- 인터페이스 없이 구현 클래스 단일 작성 (규모상 인터페이스 분리 불필요)
- `@Transactional`은 Service 메서드 단위. 조회 전용은 `@Transactional(readOnly = true)`
- 다른 도메인 호출은 상대 도메인의 Service를 주입받아 사용

**Repository**
- Spring Data JPA 인터페이스. 복잡한 조회는 `@Query`(JPQL) 사용, 네이티브 쿼리는 리뷰 필수
- N+1 주의: 연관 로딩은 기본 `LAZY`, 목록 조회는 `fetch join` 또는 DTO 프로젝션

## 4. API 규약

**URL**: `/api/{도메인 복수형}` — kebab-case, 리소스 중심

```
GET    /api/defects?grade=C&status=OPEN     # 목록 (필터는 쿼리 파라미터)
GET    /api/defects/{id}                    # 단건
POST   /api/inspections                     # 생성
PATCH  /api/defects/{id}/status             # 부분 수정 (상태 등)
DELETE /api/media/{id}                      # 삭제
POST   /api/inspections/{id}/analysis       # 행위성 리소스는 명사로
```

- 관리자 API: `/api/admin/**` (Security에서 ROLE_ADMIN 일괄 제한)
- WebSocket: `/ws` 엔드포인트, STOMP destination은 `/topic/counsel/{roomId}`, `/app/counsel/**`

**응답 공통 envelope** (`global/common/ApiResponse` — AI 컨벤션 §5와 동일 구조)

```json
// 성공
{ "success": true, "data": { ... } }
// 실패
{ "success": false, "error": { "code": "DEFECT_NOT_FOUND", "message": "..." } }
```

- 목록은 `data` 안에 `{ "content": [...], "page": 0, "totalElements": 42 }` 페이징 구조 통일
- HTTP 상태코드: 200(조회·수정), 201(생성), 400(검증 실패), 401/403(인증·인가), 404, 500

## 5. 예외 처리

- 비즈니스 예외는 `BusinessException(ErrorCode)` 단일 예외 + `ErrorCode` enum으로 관리
- `ErrorCode` 네이밍: `{도메인}_{원인}` — `DEFECT_NOT_FOUND`, `COUNSEL_QUEUE_FULL`, `AI_JOB_TIMEOUT`
- `GlobalExceptionHandler`(`@RestControllerAdvice`)에서 일괄 변환 — Controller/Service에서 try-catch로 응답 만드는 것 금지
- 검증 실패(`MethodArgumentNotValidException`)도 핸들러에서 공통 포맷으로 변환

## 6. Entity / DTO

- Entity: `@Setter` 금지 — 상태 변경은 의도가 드러나는 메서드로 (`defect.confirmReview(grade)`)
- `created_at`과 `updated_at`이 모두 있는 테이블만 `BaseTimeEntity`를 상속한다. `created_at`만 있으면
  해당 Entity에 `@CreatedDate` 필드를 선언한다.
- 감사 시각(`createdAt`, `updatedAt`)은 기존 규약대로 `LocalDateTime`, 그 밖의 `timestamptz` 업무 시각은
  `Instant`, 날짜 컬럼은 `LocalDate`를 사용한다.
- FK는 `Long` 식별자 필드를 영속화의 쓰기 소스로 사용한다. 같은 도메인 내부에서 읽기 탐색이 필요하면
  동일 FK 컬럼에 `insertable = false, updatable = false`를 지정한 읽기 전용 JPA 관계를 병행하고 기본
  fetch는 `LAZY`로 한다. 도메인 경계를 넘는 FK는 `Long` 식별자만 유지하고 Entity를 직접 참조하지 않는다.
- 읽기 전용 관계는 FK 식별자 필드와 메모리에서 자동 동기화되지 않는다. 신규 Entity에 FK 식별자만 넣어
  `persist`/`flush`한 직후 같은 영속성 컨텍스트에서 관계 getter를 호출하면 `null`일 수 있으므로, 해당 경로는
  FK 식별자를 사용한다. 관계 객체가 필요하면 `flush` 후 영속성 컨텍스트를 비우고 다시 조회한 Entity에서
  접근하며, 생성 직후 읽기 전용 관계를 역참조하는 코드를 작성하지 않는다.
- 목록 조회에서 읽기 전용 연관관계에 접근해야 하면 Repository 쿼리에 `fetch join`, `@EntityGraph`, 또는
  DTO 프로젝션을 명시한다. 식별자만 필요한 경로는 `Long` FK 필드를 사용하고, 로딩 전략 없이 컬렉션을
  순회하며 LAZY 연관관계에 접근하지 않는다.
- PostgreSQL named enum은 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`과 실제 타입명의 `columnDefinition`을
  함께 사용하며 Java enum 라벨과 순서를 DDL에 맞춘다.
- `jsonb`는 `@JdbcTypeCode(SqlTypes.JSON)`과 `columnDefinition = "jsonb"`로 매핑한다. 현재 JSON 표현은
  기존 Entity와 동일하게 유효한 JSON 문자열을 사용한다.
- 부분 인덱스와 복잡한 CHECK 등 JPA annotation으로 정확히 표현할 수 없는 제약은
  `docs/design/db/HajaCheck_script.sql`에서 관리한다. Hibernate는 스키마를 생성하지 않고 `validate`만 수행한다.
- 현재 상태를 검사한 뒤 다음 상태로 전이하는 Entity는 `@Version`과 `lock_version` 컬럼으로 동시 갱신을
  감지한다. 외부 API 호출·파일 생성·메시지 발행 같은 부작용은 낙관적 락이 반영되는 flush/commit 성공 뒤에
  실행하거나, transactional outbox·멱등성 키로 재실행 안전성을 보장한다.
- DTO 변환은 DTO 클래스의 정적 팩토리 `from(entity)` / `toEntity()` 사용 (MapStruct 도입 안 함)
- record 사용 권장: 요청/응답 DTO는 Java record로 작성

## 6-1. 배정 정책 — 점검자 배정 범위

- 점검자 배정은 요청자·배정 대상 모두 APPROVED+VERIFIED 상태인 회사의 APPROVED 멤버십을 가진
  사용자로 한정한다(무소속/개인 배정 불가). 애플리케이션 레벨(`AuthService.validateAssignableInspector`)과
  DB 트리거(`trg_inspections_check_assigned_inspector_company`) 양쪽에서 강제한다.
- 근거: PRD §2.4 조직 모델(계정 소유자 = 플랜 보유자, 소유자가 점검자를 좌석 한도 내 초대) —
  무소속 개인 점검자 배정은 설계 범위 밖이다.
- 관련 테스트: `InspectionAssignedInspectorCompanyBoundaryTest`

## 7. Lombok 허용 범위

- 허용: `@Getter`, `@RequiredArgsConstructor`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`(Entity)
- 금지: `@Data`, `@Setter`(Entity), `@AllArgsConstructor`(public), `@ToString`(연관관계 포함 Entity — 순환 참조)

## 8. Redis 키 규약

`{도메인}:{용도}:{식별자}` 콜론 구분 — DB 담당이 키 설계서 관리, 신규 키는 협의 후 추가

```
session:*                  # Spring Session 기본 (설정으로 자동)
ai:job:{jobId}             # 분석 잡 상태·진행률 (TTL 24h)
ai:usage:{yyyyMMdd}        # LLM 토큰 사용량 일별 집계
chat:memory:{sessionId}    # 챗봇 대화 메모리 (TTL 2h)
counsel:queue              # 상담 대기열 (Sorted Set)
cache:dashboard:{userId}   # 대시보드 통계 캐시 (TTL 10m)
oauth:state:{state}        # OAuth 인가 state (TTL 5m)
```

- TTL 없는 키 생성 금지 (세션·대기열 등 프레임워크/설계상 예외만 허용)

## 9. 설정·시크릿

- 프로파일: `local` / `prod` 2종 (`application-local.yml`, `application-prod.yml`)
- 시크릿(OAuth 클라이언트 시크릿, DB 비밀번호, HF 토큰)은 **yml에 커밋 금지** — 환경변수 주입(`${KAKAO_CLIENT_SECRET}`). 서버·로컬 모두 **Docker Compose가 `.env`(gitignore)에서 주입**(운영 서버는 `~/apps/hajacheck/.env`)
- 매직넘버 금지 — 설정값은 `@ConfigurationProperties` 클래스로 바인딩

## 10. 로깅

- `@Slf4j`, 로그 메시지는 플레이스홀더 사용: `log.info("분석 잡 생성 jobId={}", jobId)` (문자열 연결 금지)
- 레벨: `ERROR`(장애·5xx), `WARN`(재시도 가능 실패), `INFO`(주요 비즈니스 이벤트), `DEBUG`(개발용)
- 개인정보(이메일, 토큰)는 로그 금지 — 필요 시 마스킹

## 11. 테스트

- 필수: Service 단위 테스트 (Mockito) — 핵심 비즈니스 로직 위주, 커버리지 수치 강제 없음
- Repository 테스트: `@DataJpaTest` (복잡한 쿼리만)
- PostgreSQL named enum·jsonb·DB 제약을 사용하는 Repository 테스트는 `PostgresTestSupport`를 상속한다.
  Testcontainers는 Gradle이 기준 DDL을 테스트 classpath에 복사한 `db/HajaCheck_script.sql`로 초기화한다.
  별도의 축약 DDL 사본을 만들지 않는다.
- Docker를 사용할 수 없는 로컬·CI 환경은 `TEST_POSTGRES_URL`, `TEST_POSTGRES_USERNAME`,
  `TEST_POSTGRES_PASSWORD`로 이미 초기화된 격리 PostgreSQL을 지정할 수 있다.
- 통합 테스트: 시연 시나리오 경로(업로드→분석→검수→보고서) 위주로 중간보고(7/31) 전 작성
- 테스트 네이밍: `메서드명_조건_기대결과()` 한글 허용 — `하자검수_없는하자ID_예외발생()`

## 12. Git / PR

- 브랜치: `main`(운영) ← `dev`(통합) ← `feature/{도메인}-{작업}` (예: `feature/defect-nl-search`)
- 커밋 메시지: `타입: 요약` — `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:` (한글 요약 허용)
- PR 규칙: main·dev **직접 푸시 금지(브랜치 보호)**, CI 통과 필수, PR은 500라인 이하 권장(리뷰 가능 크기). `dev` PR은 PR머신이 자동 검수·머지
- PR 템플릿: 작업 내용 / 테스트 방법 / 스크린샷(화면 변경 시)

## 13. 코드 스타일

- 포맷터: IntelliJ 기본 + `.editorconfig` 저장소 커밋 (인덴트 4칸, 라인 120자)
- import 와일드카드 금지, 미사용 import 제거 (IDE 저장 시 자동 정리 설정)
- `Optional`은 반환 타입에만 사용 (필드·파라미터 금지)
- Stream은 가독성이 좋아질 때만 — 3단 이상 중첩 시 for문 고려

## 14. 리뷰 체크리스트 (리뷰어용)

- [ ] 도메인 패키지 경계 준수 (타 도메인 Repository/Entity 직접 참조 없음)
- [ ] Entity 직접 반환 없음 (DTO 변환)
- [ ] ApiResponse envelope + ErrorCode 사용
- [ ] `@Transactional` 위치·readOnly 적절
- [ ] N+1 가능성 (목록 조회 fetch join/프로젝션)
- [ ] Redis 키 규약·TTL 준수
- [ ] 시크릿 하드코딩 없음
- [ ] 테스트 포함 (Service 로직 변경 시)
