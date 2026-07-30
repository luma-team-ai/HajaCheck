# API 계약 (OpenAPI) — 초안

> **문서 버전:** v0.7 · **최종 수정:** 2026-07-23 · 이전 버전 `archive/`

> Contract-First 원칙(PRD §6). 이 문서는 **ai-server(FastAPI) 파트만** 담고 있음 — Spring Boot 쪽 엔드포인트는 각 담당자가 이 문서에 이어서 추가.
> SOT는 `docs/api-contract/openapi.yaml` — 이 문서는 그 사람이 읽는 요약본. 구현된 엔드포인트는 서버 기동 후 `/docs`(Swagger UI) 또는 `/openapi.json`에서 실물 재확인 가능.

## 접근 모델 — `/ai/*`는 내부 전용 (2026-07-16, #229·#234·#236)

**외부(브라우저)는 FastAPI `/ai/*`를 직접 호출하지 않는다.** 무인증 공개 구멍 폐쇄(A안, 스프링 강제 경유):
- 프론트 → 스프링 `POST /api/ai/*`(**세션+CSRF 인증**, 미인증 401) → 스프링이 `X-Internal-Key` 헤더를 부착해 FastAPI `POST /ai/*` 호출 → 결과를 표준 `ApiResponse{success,data,error}`로 변환해 반환.
- **nginx는 공개 `/ai/`를 더 이상 프록시하지 않는다**(default/arm1/tls.conf에서 제거). FastAPI는 내부망/loopback으로만 도달 가능.
- FastAPI는 `AI_INTERNAL_KEY` 설정 시 `X-Internal-Key` 불일치 요청을 **401**로 거부(`/health` 제외, `hmac.compare_digest` 상수시간 비교).
- 공유키 = env `AI_INTERNAL_KEY`(스프링은 `AI_SERVER_INTERNAL_KEY`로 주입받아 `ai.server.internal-key`에 바인딩, 동일 단일 소스). 운영(arm1/prod) compose는 `:?`로 강제.
- ⚠️ 라이브 arm1의 실제 공개 엣지는 **공유 host nginx**(레포 밖, `443 /ai/ → 8101`)라 별도 ops로 `/ai/` 제거 필요 — 그 전엔 내부키가 심층방어.

### POST /api/ai/defect-explain (스프링 인증 프록시 · #229)
프론트가 실제 호출하는 엔드포인트. 세션 인증 필요. 바디는 FastAPI `/ai/defect-explain`과 동일(snake_case, 각 필드 `@NotBlank`+`@Size`). 스프링이 FastAPI를 호출해 표준 envelope으로 반환:
- 성공 → `data: { cause, risk, action }`
- FastAPI LLM 실패(`success=false`) → FastAPI 코드/메시지 그대로 전파(HTTP 200)
- AI 서버 장애 → `AI_SERVER_UNREACHABLE`(503) · `AI_SERVER_TIMEOUT`(504) · `AI_INVALID_RESPONSE`(502)

> `briefing` 등 다른 `/ai/*`도 향후 동일 패턴의 스프링 프록시(`/api/ai/*`)로만 노출한다 — 프론트의 FastAPI 직접 호출 금지.

## GET /health

헬스체크. 응답: `{"status": "ok"}`

## GET /ai/ping

공통 envelope 동작 확인용.

**응답** (`AIResponse` envelope, 모든 `/ai/**` 공통):
```json
{ "success": true, "data": {"message": "pong"}, "usage": null, "error": null }
```

## POST /ai/defect-explain

하자 원인·조치방안 설명 (AI 하자 설명 패널, FR-4 P1, 점검관리B 담당).

**요청**:
```json
{
  "defect_type": "철근 노출",
  "severity_grade": "D",
  "location": "교각 하부",
  "facility_type": "교량"
}
```

**응답 성공**:
```json
{
  "success": true,
  "data": { "cause": "...", "risk": "...", "action": "..." },
  "usage": { "tokens": 123 },
  "error": null
}
```

**응답 실패** (`AIErrorCode`: `LLM_TIMEOUT` | `LLM_RATE_LIMIT` | `LLM_INVALID_OUTPUT` | `RAG_NO_RESULT` | `VALIDATION_ERROR`):
```json
{ "success": false, "data": null, "usage": null, "error": { "code": "LLM_INVALID_OUTPUT", "message": "..." } }
```

**`VALIDATION_ERROR`** = 비-LLM 코드 경로(입력·대조 검증) 실패용 코드. `POST /ai/grounding-check`(환각 방어 게이트)처럼 LLM 호출이 없는 순수 코드 대조 엔드포인트가 예외 폴백 시 사용한다(#122, PR #120). 프론트/백엔드 소비처는 이 코드를 `error.code` 분기에 포함해야 한다 — LLM 계열 코드와 달리 재시도가 아니라 입력 재검토가 필요하다.

> ✅ **해결됨 (2026-07-09, PR #88)**: HF Serverless Inference는 langchain 표준 `with_structured_output()`이 강제하는 `tool_choice="any"`를 지원하지 않아 `400 INVALID_TOOL_CHOICE`가 발생하는 구조적 제약이었다(`langchain-ai/langchain#29569`, upstream "not planned" — 버전 업그레이드로는 해결 안 됨). `get_llm().with_structured_output(schema)`는 내부적으로 `PydanticOutputParser`로 프롬프트에 JSON 스키마를 지시하고 응답을 직접 파싱하는 방식으로 우회 구현됨(`ai-server/ai/core/llm_client.py`의 `_StructuredLLM`). 호출부 시그니처는 동일하게 유지되므로 각 체인 담당자는 그대로 `get_llm().with_structured_output(Schema).invoke(...)`만 쓰면 된다 — 실제 HF 토큰으로 `/ai/defect-explain` e2e 검증 완료.

---

## POST /ai/grounding-check

생성물이 주장하는 하자 수치·등급을 실측 `defects`와 코드로 대조하는 환각 방어 게이트(HAJA-117). **LLM 호출 없음 — 결정론적**. `ai-server/routers/ai_router.py`(약 58~65행) 구현됨.

**요청**:
```json
{
  "defects": [ { "defect_type": "균열", "grade": "B" } ],
  "claims": {
    "total_count": 1,
    "count_by_grade": { "B": 1 },
    "count_by_type": { "균열": 1 },
    "mentioned_grades": ["B"]
  },
  "on_mismatch": "regenerate"
}
```

**응답 성공** `data`: `{ grounded, action, ground_truth, checks, mismatches, unverifiable }` — `action` ∈ `PASS|REGENERATE|WARN`. 상세 스키마는 `docs/api-contract/openapi.yaml`(`GroundingCheckRequest`/`GroundingResult`) 참조.

**응답 실패**: `VALIDATION_ERROR`(위 각주 참조).

---

## POST /ai/briefing

대시보드 AI 주간 브리핑 — 현황 데이터(코드 집계) → 자연어 요약 카드(대시보드 P1). `ai-server/routers/ai_router.py`(약 68~75행) 구현됨. 수치(전주 대비 변화율·추세)는 코드로 계산해 프롬프트에 주입하고, LLM은 자연어만 생성한다(수치 환각 방지).

**요청** (`DashboardStats`): `total_facilities`·`monthly_analysis`·`pending_review`·`pending_action`·`this_week_defects`·`last_week_defects`·`top_defect_type`·`critical_defects`·`grade_distribution`(선택).

**응답 성공** `data`: `{ briefing, recommendation, facts: { this_week_defects, last_week_defects, change_pct, trend, top_defect_type, critical_defects } }`. 상세 스키마는 `openapi.yaml`(`BriefingRequest`/`WeeklyBriefing`) 참조.

**응답 실패** (`AIErrorCode`: `LLM_TIMEOUT` | `LLM_RATE_LIMIT` | `LLM_INVALID_OUTPUT` | `RAG_NO_RESULT`).

---

## POST /ai/rag-chat — ✅ 구현됨(내부 전용, Spring 프록시는 후속 이슈)

점검 기준·법규 질의 전담 RAG 챗봇(FR-6). FastAPI 라우트는 `ai-server/routers/ai_router.py`에 구현되어 있다(체인: `ai-server/ai/chains/rag_chat_chain.py`, GitHub #19/HAJA-28). 성공 시 `AIResponse.data`는 `RagAnswerData` 형태이며, `sources[]`는 표시 라벨(`locator`)과 원문 발췌(`snippet`)를 분리한다.

> **내부 호출 계약**: PRD §6의 Spring Boot → FastAPI 구조를 따른다. 프론트엔드는 이 엔드포인트를 직접 호출하지 않는다. **Spring `/api/ai/rag-chat` 프록시(요청자의 `session_id` 소유·`session_type='RAG'` 검증 포함)는 이번 범위에 포함되지 않고 후속 이슈로 분리됐다** — `/api/chat-sessions` 자체가 아직 미구현이기 때문이다(`docs/design/ai/rag_chatbot_design.md` §9 참조). FastAPI 요청 스키마는 `session_id`(선택, 양의 정수)를 받지만 **현재 파이프라인은 이 값을 사용하지 않는다**(세션·대화 이력 연동 전 상태) — 아래 `session_id`는 향후 Spring이 검증해 넘길 서버 관리 식별자를 위한 선점 필드다.
>
> Spring Boot는 환경변수로 주입된 내부키를 **`X-Internal-Key`** 헤더에 담고(`AI_INTERNAL_KEY`, 다른 `/ai/*`와 공유 단일 소스), FastAPI는 일치하지 않거나 누락된 요청을 처리 전에 거부한다(`deps.py::verify_internal_key`, `hmac.compare_digest` 상수시간 비교 — 위 "접근 모델" 절과 동일 메커니즘). 토큰 값은 저장소·로그·OpenAPI에 기록하지 않는다. 운영 nginx는 다른 `/ai/*`와 동일하게 공개 프록시 대상에서 이미 제외돼 있다(2026-07-16 확정, 위 "접근 모델" 절 참조).

**요청**:
```json
{
  "question": "균열 보수 기준은 무엇인가요?",
  "session_id": 42
}
```

**응답 성공**:
```json
{
  "success": true,
  "data": {
    "answer": "균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다.",
    "sources": [
      {
        "doc_id": "42",
        "title": "시설물의 안전 및 유지관리에 관한 특별법",
        "collection": "regulations",
        "locator": "제12조",
        "snippet": "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.",
        "chunk_ref": "42_3"
      }
    ]
  },
  "usage": { "tokens": 320 },
  "error": null
}
```

**응답 실패**: `RAG_NO_RESULT`(검색 결과 0건, 캐시 저장 안 함) · `VALIDATION_ERROR`(비-LLM 검증 실패) · 공통 LLM 오류 코드(`LLM_INVALID_OUTPUT` 등).

정상 응답은 Redis에 `ai:cache:rag-chat:{sha256(question)[:16]}` 키로 캐시된다(TTL 1일, `llm_client.CACHE_TTL_SECONDS` 공유). 캐시 히트 시 Chroma·LLM 호출 없이 저장된 `RagAnswerData`를 그대로 반환한다.

`sources[].doc_id`는 PostgreSQL `rag_documents.id`를 문자열화한 양의 정수 문자열(`^[1-9][0-9]*$`)이다. Spring Boot가 `chat_message_citations.document_id`에 저장할 때 패턴 검증을 통과한 값을 `int(doc_id)`로 변환한다.

---

## POST /ai/report — ✅ 구현됨(내부 전용, Spring `/api/ai/report` 프록시 경유)

AI 보고서 4개 섹션(개요·요약·상세·권고)을 병렬 생성하고 Grounding Check를 수행한다
(FR-5, 로그인/보고서 담당). FastAPI 라우트는 `ai-server/routers/ai_router.py`에 구현되어 있으며,
외부 클라이언트는 직접 호출하지 않고 인증된 Spring `/api/ai/report` 프록시를 경유한다.

현재 correlation 필드에는 두 호환 모드가 있다.

- `grounding_request_id`, `inspection_id`, `report_version`을 **모두 생략**하면 기존 호환 경로로 처리하며,
  응답에도 correlation 필드와 `content_hash`를 포함하지 않는다.
- 세 필드를 **모두 제공**하면 AI 서버가 응답에 같은 값을 되돌리고, correlation 필드를 제외한 보고서 본문을
  canonical JSON(키 정렬, 공백 없는 구분자, UTF-8)으로 직렬화한 SHA-256 `content_hash`를 함께 반환한다.
- 세 필드 중 일부만 제공하면 FastAPI 요청 모델 검증 단계에서 HTTP 422를 반환한다.

⚠️ 현재 Spring 프록시는 correlation 값을 서버에서 생성·조회해 강제하지 않고 요청 DTO의 값을 그대로 전달한다.
따라서 이 필드들은 응답 상관관계와 본문 무결성 대조를 위한 **선택적 호환 계약**이며, “인증 사용자의 점검과
서버가 발급한 요청만 저장된다”는 권한·귀속 보장은 아니다. 서버 강제 배선과 공개·내부 DTO 분리는 후속 범위다.

**요청**:
```json
{
  "facility_info": {
    "name": "Haja APT",
    "location": "서울시"
  },
  "confirmed_defects": [
    {
      "defect_type": "균열",
      "location": "1동 1층 기둥",
      "severity_grade": "B",
      "description": "기둥 표면 수평 균열"
    }
  ],
  "on_mismatch": "regenerate",
  "grounding_request_id": "rpt-01J2Y8A7M6",
  "inspection_id": 42,
  "report_version": 3
}
```

**응답 성공**:
```json
{
  "success": true,
  "data": {
    "overview": {
      "purpose": "하자의 발생 현황을 체계적으로 조사...",
      "facility_summary": "Haja APT 101동...",
      "scope": "전체 외벽 및 지하주차장..."
    },
    "summary": {
      "overall_opinion": "전반적으로 양호하나 일부 결함...",
      "total_count": 1,
      "count_by_grade": { "A": 0, "B": 1, "C": 0, "D": 0, "E": 0 },
      "key_findings": ["1동 기둥 균열 발생"]
    },
    "detail": {
      "items": [
        {
          "defect_type": "균열",
          "location": "1동 1층 기둥",
          "severity_grade": "B",
          "description": "기둥 표면 수평 균열",
          "cause": "건조 수축에 의한 미세 균열"
        }
      ]
    },
    "recommendation": {
      "items": [
        {
          "target": "균열",
          "method": "에폭시 수지 주입 공법",
          "priority": "중",
          "legal_basis": "콘크리트 구조 설계기준 제X조",
          "legal_basis_verified": true
        }
      ],
      "monitoring_points": ["지하주차장 균열 발생 부위"]
    },
    "grounding_ok": true,
    "grounding_request_id": "rpt-01J2Y8A7M6",
    "inspection_id": 42,
    "report_version": 3,
    "content_hash": "64자리 소문자 SHA-256 hex"
  },
  "usage": { "tokens": 850 },
  "error": null
}
```

**응답 실패(HTTP 200 envelope)** (`AIErrorCode`: `LLM_TIMEOUT` | `LLM_RATE_LIMIT` |
`LLM_INVALID_OUTPUT` | `RAG_NO_RESULT` | `VALIDATION_ERROR`):
```json
{ "success": false, "data": null, "usage": null, "error": { "code": "LLM_INVALID_OUTPUT", "message": "..." } }
```

**요청 모델 검증 실패(HTTP 422)**: correlation 일부 입력, 빈 `grounding_request_id`, 1 미만의
`inspection_id`/`report_version` 등 FastAPI 요청 모델 자체가 거부하는 경우에는 공통 AI envelope가 아니라
FastAPI validation error(`detail[]`)를 반환한다.

---

### 핵심 명세서 (P0 — 워킹 스켈레톤 관통용 최소 경로)

| 메서드 | 경로 | 기능 | 담당 |
|---|---|---|---|
| GET | `/api/auth/oauth2/{provider}` | 소셜 로그인(Kakao/Google) | 정재봉 |
| GET | `/api/users/me` | 내 정보 조회 | 정재봉/오영석 |
| POST | `/api/facilities` | 시설물 등록 | 허남/김관영 |
| GET | `/api/facilities` | 내 시설물 목록 조회 | 허남/김관영 |
| GET | `/api/facilities/{id}` | 시설물 상세 조회(점검이력 포함) | 허남/김관영 |
| PUT | `/api/facilities/{id}` | 시설물 수정 | 허남/김관영 |
| DELETE | `/api/facilities/{id}` | 시설물 삭제 | 허남/김관영 |
| POST | `/api/facilities/{id}/schedule` | 시설물 점검주기 설정(dev-04-03) | 허남/김관영 |
| POST | `/api/inspections` | 점검(회차) 생성 | 황승현 |
| POST | `/api/inspections/{id}/media` | 촬영 데이터 업로드 | 황승현 |
| POST | `/api/inspections/{id}/analyze` | AI 분석 요청 | 황승현 |
| GET | `/api/inspections/{id}/defects` | 분석 결과(하자 목록) 조회 | 오영석 |
| PATCH | `/api/defects/{id}` | 검수(오탐 수정·등급 조정) | 오영석 |
| POST | `/ai/defect-explain` | AI 하자 설명 생성 | 오영석 ✅ 구현됨 |
| POST | `/ai/report` | AI 보고서 생성 및 Grounding(내부 전용) | 김관영 ✅ 구현됨 |
| POST | `/api/reports` | 보고서 생성 요청 (비동기 잡 발족) | 김관영 |
| GET | `/api/reports/{id}/pdf` | 보고서 PDF 다운로드 | 김관영 |

### 전체 명세서 (FR-1~FR-9 전 범위)

| FR | 메서드 | 경로(안) | 기능 | 담당 |
|---|---|---|---|---|
| FR-1 | GET/POST | `/api/auth/**`, `/api/users/me` | 로그인·마이페이지·권한 | 정재봉/오영석 |
| FR-2 | POST | `/api/inspections/{id}/media` | 업로드·프레임 추출 | 황승현/허남 |
| FR-3 | POST | `/api/inspections/{id}/analyze`, `/api/jobs/{id}` | AI 하자 탐지·잡 상태 | 황승현 |
| FR-4 | GET/PATCH | `/api/defects/**`, `/ai/defect-explain` | 시각화·검수·AI 설명 | 오영석 |
| FR-5 | POST/GET | `/api/reports/**`, `/ai/report` | LLM 보고서 생성 | 김관영 |
| FR-6 | POST | `/ai/rag-chat`, `/api/chat-sessions` | RAG 챗봇 | 이은석 |
| FR-7 | POST/WS | `/api/counsel/**`, `/ws/counsel` | 상담 챗봇·상담원 연결 | 이은석/김승현 |
| FR-8 | GET/POST | `/api/admin/**` | 관리자(사용자·기준·모니터링) | 이은석/김승현 |
| FR-9 | GET | `/api/notifications` (P1) | 알림 센터 | 미배정 |
| 공통 | GET | `/actuator/health`, `/health` | 헬스체크 | 인프라 |

> 하자 관리 메뉴(`/api/defects` 목록·검색, 공개 `/api/defects/nl-search` → 내부 `/ai/nl-search`)는 유병현/정재봉 담당(§7 표), 위 FR-4 검수 경로와 데이터는 공유하되 엔드포인트는 별도. 시설과 시설 경유 점검·하자·미디어·보고서·AI 브리핑의 데이터 범위는 `facilities.company_id = 인증 주체 companyId`인 **단일 회사 스코프**다. 요청 시마다 사용자가 ACTIVE이고 `users.company_id`가 일치하며, 회사가 APPROVED+VERIFIED이고 멤버십이 APPROVED·승인시각 존재·미회수·미만료인지 현재 DB에서 검증한다. 관리자도 타회사 전체 조회 예외가 없고, 담당 점검자(`assigned_inspector_id`)라는 이유만으로 타회사 시설 범위를 확장하지 않는다. 유효 소속이 아니면 `403 FORBIDDEN`, 유효 소속이지만 범위 내 결과가 없으면 `200` 빈 페이지이며 모든 역할에서 `is_deleted=false`만 페이지 응답한다. 상세 필드·페이지 규약은 OpenAPI SOT를 따른다.

## 다음 추가 예정 (각 담당자)

- `/api/ai/rag-chat` — Spring 프록시(session_id 소유·session_type='RAG' 검증) + `/api/chat-sessions`, `chat_message_citations` 영속화 (이은석, 후속 이슈)
- `/api/defects/nl-search` — 자연어 검색 공개 게이트웨이(Spring Boot, 인증·플랜 검사) → 내부 `/ai/nl-search`(FastAPI, 외부 직접 노출 금지)
- Spring Boot REST 엔드포인트 전체 (백엔드 담당)

---

# 기업 인증 플로우 (회원가입·아이디/비밀번호 찾기) — Contract v1 (2026-07-14)

> Figma 5개 화면(기업 회원가입/가입 승인 대기/아이디 찾기/비밀번호 찾기/새 비밀번호 설정) 풀스택 대응. 백엔드(Spring)·프론트(React)·AI(stub) 정렬용 단일 계약. **모든 엔드포인트는 `/api/auth/**`**(기존 SecurityConfig permitAll 커버). 응답은 공통 `ApiResponse` envelope `{success, data, error{code,message}}`.
>
> **결정**: OCR=stub+수동입력 · 파일저장=dev 로컬볼륨 · 주소=다음(카카오) 우편번호 · 개인가입=소셜 위임(범위 외) · 관리자 승인 화면=범위 외(가입은 company.status=PENDING_REVIEW 생성만).
>
> **공통 규약(소비처 필수 준수)**:
> - 검증 실패는 **절대 401 금지**(프론트 axios가 401을 로그인 강제 리다이렉트로 처리) → 400/404/409만 사용.
> - CSRF: double-submit 쿠키. 비로그인 POST도 `XSRF-TOKEN` 쿠키를 먼저 받아 `X-XSRF-TOKEN` 헤더로 재전송해야 통과 → 프론트는 인증 폼 마운트 시 GET(`email-availability` 등) 1회로 쿠키 프라이밍.
> - 계정 열거 방지: 찾기류 실패 메시지 통일.

## POST /api/auth/companies — 기업 회원가입 (multipart/form-data)
가입 신청. User(passwordHash·ACTIVE) + Company(PENDING_REVIEW) + UserConsent(약관 2건) 원자 생성.

**요청** (multipart 폼 필드 + 파일):
| 필드 | 타입 | 검증 |
|---|---|---|
| `email` | text | @NotBlank @Email (= 로그인 아이디) |
| `password` | text | @NotBlank @Size(min=8), 영문+숫자 포함 |
| `companyName` | text | @NotBlank ≤200 |
| `businessRegistrationNumber` | text | @NotBlank `\d{3}-?\d{2}-?\d{5}` (수동입력) |
| `representativeName` | text | @NotBlank ≤100 (수동입력) |
| `address` | text | @NotBlank ≤300 (다음 우편번호 결과) |
| `addressDetail` | text | ≤200 (직접입력, nullable) |
| `agreeTermsOfService` | text(bool) | @AssertTrue |
| `agreePrivacyPolicy` | text(bool) | @AssertTrue |
| `businessRegistrationFile` | file | 필수, MIME∈{image/jpeg,image/png,application/pdf}, ≤10MB |

> 약관 버전은 클라이언트가 보내지 않음(서버 소유). OCR 필드(사업자번호/상호/대표)는 현재 사용자가 수동 입력한 값을 그대로 저장.

**성공 201** `data` 필드: `companyId`(number), `maskedEmail`(string, 예 `h***@c***.com`), `status`(`PENDING_REVIEW`), `signupToken`(string). `signupToken`은 승인 대기 화면 상태조회에 쓰는 **불투명 랜덤 문자열**(서버 발급, PK 노출 금지) — 예시값은 문서에 싣지 않는다.

**실패**: `409 AUTH_EMAIL_DUPLICATED` · `409 AUTH_BUSINESS_NUMBER_DUPLICATED` · `400 FILE_REQUIRED|FILE_INVALID_TYPE|FILE_TOO_LARGE` · `400 INVALID_INPUT` · `500 FILE_UPLOAD_FAILED`

## GET /api/auth/email-availability?email={email} — 아이디(이메일) 중복확인
**성공 200** `data`: `{ "available": true }`

## POST /api/auth/id-inquiry — 아이디 찾기 (application/json)
**요청**: `{ "businessRegistrationNumber": "123-45-67890", "representativeName": "김민수", "companyName": "(주)하자체크" }` — repName·companyName 중 **최소 1개** 필수.
**성공 200** `data`: `{ "maskedEmail": "h***@c***.com" }`
**실패**: `404 AUTH_ACCOUNT_NOT_FOUND` (무매칭 통일 "일치하는 계정을 찾을 수 없습니다.")

## 비밀번호 찾기 1·2단계 — **이메일 링크 방식** (2026-07-17 확정, #194 / HAJA-172)
> **이력**: 최초 설계(이메일 + 사업자번호만으로 resetToken 반환)는 **계정 탈취 P1**(PR머신·security-reviewer 지적)으로 판정 — 이메일·사업자번호 둘 다 준공개 정보라 out-of-band 소유 증명이 없으면 안전한 재설정 불가. 당시 SMTP 미사용 결정에 따라 보안질문/PIN 방식으로 후속 예정이었으나, **팀이 SMTP 확보를 결정하면서 이메일 링크 방식으로 전환**한다(원 결정이 "향후 SMTP/SMS 확보 시 이메일 링크로 대체 가능"으로 열어둔 경로). **보안질문/PIN 방식은 폐기** → 가입 화면 비밀값 필드·DB 컬럼 추가 **불필요**.
>
> **핵심 보안 요건**: 이메일 링크 방식의 안전성은 **"메일함 소유 증명"**에서 나온다. 따라서 ①resetToken은 **메일로만** 전달하고 **응답 바디·로그·에러에 절대 포함하지 않는다**(최초 P1의 재발 방지) ②1단계는 계정 존재 여부와 무관하게 **동일 응답 + 동일 응답시간**(계정 열거 방지) ③토큰은 1회용·단기·추측 불가.

### POST /api/auth/password-reset-request — 1단계: 재설정 링크 발송 요청 (application/json)
**요청**: `{ "email": "haja@check.com" }` — @NotBlank @Email

**성공 200** `data`: `{ "requested": true }`
- 계정 존재 여부와 **무관하게 항상 200 + 동일 바디**(계정 열거 방지). 존재할 때만 실제 메일을 발송한다.
- **응답에 resetToken을 포함하지 않는다**(포함 시 최초 P1 재현 — 준공개 정보만으로 토큰 획득 가능해짐).
- ⚠️ **메일 발송은 비동기로 처리하고 응답은 즉시 반환한다.** 동기 발송하면 존재하는 계정만 SMTP 왕복(수백 ms~초)만큼 느려져 **바디가 같아도 응답시간 차이로 계정 열거가 가능**하다. 발송 실패도 응답에 반영하지 않는다(반영하면 그 자체가 존재 단서).
- 토큰: **전용 `PasswordResetTokenStore`**(재설정 전용 인터페이스, Redis 구현은 `RedisPasswordResetTokenStore`). TTL은 `AuthProperties.passwordResetTtl`(기본 10분, `app.auth.password-reset-ttl`로 조정).
  > **왜 `TokenStore`를 재사용하지 않는가** (2026-07-17 정정): 초안은 "`TokenStore.issue(PASSWORD_RESET, …)` 재사용"과 "발급 ①②③을 Lua로 원자화"를 **동시에** 요구했는데 **두 조항은 양립 불가능**하다 — `issue`로 ①을 하면 별도 왕복이라 ①②③을 한 스크립트에 넣을 수 없다. 원자성(보안·정합성)을 택해 재설정 경로를 전용 인터페이스로 분리했다. `TokenStore`는 **가입 상태 토큰 전용**으로 남고 시그니처·동작 모두 불변이다(원문 키 유지 — TTL 30일 in-flight 토큰 보호).
  - **Redis 저장 키는 토큰 원문이 아니라 `sha256(token)`을 쓴다** — Redis 덤프·스냅샷 유출 시 토큰 평문이 곧 계정 탈취가 되는 것을 막는 심층방어. 이 해시는 **재설정 경로에만** 적용된다(가입 상태 토큰은 원문 키 그대로).
- **재발급 시 해당 사용자의 이전 토큰 무효화**(동시 다발 링크 방지). `key=토큰해시 → value=userId`는 단방향이라 역추적이 불가능하므로, **보조 인덱스 `auth:password-reset:user:{userId} → 현재 토큰해시`를 둔다.** ⚠️ `KEYS`/`SCAN` 순회 금지(운영 Redis 블로킹).
  - **인덱스 TTL = 토큰 TTL과 동일하게** 건다. 안 걸면 토큰은 10분 뒤 사라지는데 인덱스만 영구 잔존해 Redis 키가 샌다.
  - **발급은 단일 Lua로 원자화한다** — ①이전 토큰 삭제 ②새 토큰 저장 ③인덱스 갱신을 **한 스크립트**에서. 쪼개면 동시 요청 인터리브 시 이전 토큰이 살아남거나(무효화 실패) **방금 발급한 토큰이 지워진다** — 후자는 "**나중에 보낸 메일의 링크가 죽고 먼저 온 링크가 사는**" 사용자 체감 버그다(발송 버튼 더블클릭으로 재현). 단일 스크립트면 마지막에 실행된 요청이 이기고 그 요청이 메일도 나중에 보내므로 **"최신 메일 = 유효한 링크"가 구조적으로 보장**된다.
  - **consume도 단일 Lua** — 조회·삭제 + 인덱스 정리를 한 스크립트에서. 인덱스 정리는 **compare-and-delete**: 인덱스가 가리키는 값이 **방금 소비한 토큰해시일 때만** 삭제한다. 무조건 지우면, 아직 안 지워진 구토큰을 소비할 때 현재 유효 토큰의 인덱스가 날아가 다음 발급의 무효화가 실패한다.
  - Lua가 여러 키를 다루므로 **단일 노드 Redis 전제**다(현 compose `redis` 서비스). 클러스터 전환 시 hash tag로 같은 슬롯에 묶어야 한다.
- 메일 링크: `{FRONTEND_BASE_URL}/reset-password?token={resetToken}`
  - ⚠️ **링크 base는 설정값 `FRONTEND_BASE_URL`만 쓴다. 요청에서 유도 금지**(`ServletUriComponentsBuilder.fromCurrentRequest()`, `Host`/`X-Forwarded-Host` 헤더 등). nginx가 `Host`를 그대로 통과시키므로, 요청에서 유도하면 공격자가 Host를 조작해 **피해자 메일에 공격자 도메인 링크**를 심는 password-reset poisoning이 성립한다.
  - 메일 제목·본문에 **사용자 입력을 삽입하지 않는다**(헤더 인젝션 표면 제거).

**실패**: `429 AUTH_TOO_MANY_REQUESTS` (rate-limit 초과 — 아래 §Rate-limit)

### POST /api/auth/password-reset — 2단계: 새 비밀번호 설정 (application/json)
**요청**: `{ "token": "...", "newPassword": "..." }` — `newPassword`는 가입과 **동일 정책**(@NotBlank @Size(min=8), 영문+숫자 포함)

**성공 200** `data`: `{ "reset": true }`
- `PasswordResetTokenStore.consume(token)` — 조회·삭제·**보조 인덱스 CAD**를 **단일 Lua로 원자 처리**(1회용). 성공 후 재사용 불가. 동시 소비 2건도 Redis 단일 스레드 직렬화로 **1건만 성공**한다.
  > ⚠️ `TokenStore.consume(PASSWORD_RESET, …)`를 쓰면 **안 된다.** `TokenStore`는 원문 키(`auth:password-reset:{원문}`)를 조회하는데 재설정 토큰은 `auth:password-reset:{sha256}`에 저장되므로 **영원히 empty를 반환해 2단계가 100% 실패**한다. 위 §토큰의 전용 인터페이스 분리와 세트다.

**실패**: `400 AUTH_RESET_TOKEN_INVALID` (토큰 무효·만료·사용됨 — 세 경우 **메시지 통일**, 어느 쪽인지 노출 금지) · `400 INVALID_INPUT` (비밀번호 정책 위반)

> **세션 무효화는 이번 범위에서 제외한다** — 현 설정은 non-indexed 세션(`repository-type: indexed`·`@EnableRedisIndexedHttpSession` 부재)이라 `FindByIndexNameSessionRepository` 빈이 없고, 주입하면 기동이 실패한다. indexed 전환은 Redis `notify-keyspace-events` 설정 변경 + 배포 시 기존 로그인 세션 일괄 무효화 위험을 동반하므로 **별도 이슈**로 분리한다. → 후속. 그때까지는 "비밀번호를 바꿔도 기존 세션은 살아있다"가 알려진 한계다.

### Rate-limit
전역 rate-limit은 미도입(#185 P2 후속)이므로 **1단계에만 최소 구현**한다. Redis 카운터 기반.

- **대상 이메일 기준**: 예 이메일당 5분 3회 — 특정 피해자 메일 폭탄 방어. **사용자 단위 실질 방어는 이 축이 담당한다.** 값은 설정으로.
- **전역 상한**: 전체 합쳐 분당 N건 — 발송 남용(발신 도메인 평판 훼손·SMTP 제공자 차단)에 천장. 값은 설정으로.
  - ⚠️ **N의 성격**: **"SMTP 제공자 한도·발신 평판을 보호하는 천장"**이지 사용자 단위 방어가 아니다. **정상 트래픽이 절대 닿지 않을 만큼 크게** 잡고, 제공자 쿼터를 기준으로 산정한다. 정상 트래픽 수준에 맞춰 작게 잡으면 안 된다.
- 초과 시 `429 AUTH_TOO_MANY_REQUESTS`. **1단계의 429는 계정 열거 단서가 되지 않도록** 이메일 존재 여부와 무관하게 동일 조건으로 건다.

> ⚠️ **알려진 한계 — 전역 상한은 DoS로 역이용될 수 있다.** IP 축을 쓰지 않으므로 공격자와 정상 사용자를 구분할 수단이 없다. 공격자가 임의 이메일로 전역 상한을 채우면 그 시간 창의 **정상 사용자 재설정 요청도 429로 막힌다**(비인증 엔드포인트라 비용이 사실상 0). N을 크게 잡는 것은 이 역이용의 실익을 줄이기 위함이기도 하다. 근본 해결(XFF 신뢰경계 정리 후 IP 축 도입, 또는 CAPTCHA)은 **후속 이슈**다.

> **IP 기준 rate-limit은 쓰지 않는다**(2026-07-17 결정). nginx가 `X-Forwarded-For $proxy_add_x_forwarded_for`로 **클라이언트 제공값에 덧붙이고**(`frontend/nginx/arm1.conf:33`) 스프링이 `forward-headers-strategy: framework`로 **첫 항목을 클라 IP로 채택**하므로, 헤더 위조로 IP 카운터가 무력화된다. 실제 외부 엣지가 레포 밖 host nginx라 레포만 고쳐선 완결되지 않아 **IP 축 자체를 채택하지 않고** 이메일 축 + 전역 상한으로 간다. → XFF 신뢰경계 정리는 후속(ops 동반).
> ⚠️ 따라서 **감사 로그의 IP는 위조 가능한 값**이다. 추적 근거로 신뢰하지 말 것.

- 감사 로그: 대상 이메일 **해시**·성공/실패·시각. **이메일 원문·토큰 평문 로깅 금지.**

> **2단계에는 rate-limit을 걸지 않는다.** 토큰 기준 카운터는 공격자가 매 시도 다른 토큰을 쓰므로 항상 1이라 무의미하고, IP 기준은 위 사유로 무력하다. 2단계의 실제 방어는 **토큰 엔트로피**(32바이트 `SecureRandom`, `RedisPasswordResetTokenStore.generateToken()`)이며 이는 rate-limit으로 보강할 성질이 아니다. 애플리케이션 레벨 요청 폭주(리소스 소모)는 인프라(nginx `limit_req` 등) 몫으로 남긴다.

### SMTP 설정 (env)
| env | 용도 |
|---|---|
| `SMTP_HOST` · `SMTP_PORT` · `SMTP_USERNAME` · `SMTP_PASSWORD` | 발송 서버 |
| `MAIL_FROM` | 발신 주소 |
| `FRONTEND_BASE_URL` | 재설정 링크 base (`{BASE}/reset-password?token=...`) |

- **운영 강제는 compose `:?` 가드로 한다** — `docker-compose.arm1.yml`에서 `${SMTP_HOST:?}` 형태로 미설정 시 컨테이너 기동을 막는다. **`AI_INTERNAL_KEY`(`docker-compose.arm1.yml:95`)와 동일한 방식**이며, 앱 레벨 프로파일 분기는 쓰지 않는다 — **arm1(실운영)과 로컬이 둘 다 `SPRING_PROFILES_ACTIVE: docker`라 프로파일로 운영/로컬을 구분할 수 없다.**
- **로컬/dev**: env 미설정 시 발송 대신 **재설정 링크를 로그로 출력**하는 구현체로 폴백해 개발 가능하게 한다(인터페이스 + 구현체 2개, 설정 유무로 선택).
- `.env.example`에 **키 이름만** 추가(값 금지).
- ✅ **아웃바운드 확인 완료**(2026-07-17): arm1은 **587/465 열림**(OCI가 인프라 레벨에서 막는 건 25번뿐). SMTP 방식 그대로 간다 — HTTPS API(SES/SendGrid) 전환은 **불필요**.
- ⚠️ **승격 순서**: 서버 `.env`에 SMTP 값 주입 → 그 다음 main 승격. (`:?` 가드는 의도적으로 배포를 멈추므로, 순서를 어기면 기동 실패한다. `AI_INTERNAL_KEY`와 동일 유형의 전제조건.)

### ErrorCode
`AUTH_RESET_TOKEN_INVALID`(기존 예약분 사용) · `AUTH_TOO_MANY_REQUESTS`(신규). `AUTH_VERIFICATION_FAILED`는 보안질문 방식 폐기로 **참조 0건** → 제거 대상(후속 정리).

### 프론트 규약 (별도 PR이지만 계약에 고정)
토큰이 URL 쿼리(`?token=`)에 실리므로 프론트는 아래를 지킨다:
- 재설정 페이지에 `Referrer-Policy: no-referrer` — 외부 리소스 요청 시 Referer로 토큰이 새는 것 방지.
- 토큰 소비 후 `history.replaceState`로 URL에서 토큰 제거(브라우저 히스토리·공유 유출 방지).

## GET /api/auth/companies/status?token={signupToken} — 가입 상태 조회(승인 대기 새로고침)
**성공 200** `data`: `{ "status": "PENDING_REVIEW" , "companyName": "(주)하자체크", "rejectionReason": null }`
`status` ∈ `PENDING_REVIEW|APPROVED|REJECTED`. 스테퍼: PENDING_REVIEW=서류검토중, APPROVED=승인완료.
**실패**: `404 AUTH_SIGNUP_TOKEN_INVALID`

## POST /ai/business-license-ocr — 사업자등록증 OCR (AI서버, 실구현 #552/#598)
RapidOCR(한국어)+LLM 파싱. 백엔드 프록시(`/api/auth/business-license/ocr`, #557) 경유. 인식 실패 필드는 null(FE 수동입력 폴백).
**요청**: `{ "image_base64": "..." }` (필수)
**성공 200** (`AIResponse` envelope) `data`: `{ "businessRegistrationNumber": "123-45-67890", "companyName": "하자첵 주식회사", "representativeName": "홍길동", "businessStartDate": "2020-01-15", "raw": { "lineCount": 12, "avgConfidence": 0.93 }, "stub": false }` (#598 businessStartDate 추가)

### 추가 ErrorCode (Spring `ErrorCode`)
이번 배포: `AUTH_EMAIL_DUPLICATED`(409) · `AUTH_BUSINESS_NUMBER_DUPLICATED`(409) · `AUTH_ACCOUNT_NOT_FOUND`(404) · `AUTH_SIGNUP_TOKEN_INVALID`(404) · `FILE_REQUIRED`(400) · `FILE_INVALID_TYPE`(400) · `FILE_TOO_LARGE`(400) · `FILE_UPLOAD_FAILED`(500)
비밀번호 찾기(#194, 이메일 링크 방식): `AUTH_RESET_TOKEN_INVALID`(400, 기존 예약분) · `AUTH_TOO_MANY_REQUESTS`(429, 신규)
> `AUTH_VERIFICATION_FAILED`(400)는 보안질문 방식 전용으로 예약됐으나 그 방식이 폐기돼 **참조 0건** — 제거 대상(후속 정리). 이번 PR에서는 건드리지 않는다.
