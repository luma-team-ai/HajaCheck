# API 계약 (OpenAPI) — 초안

> **문서 버전:** v0.3 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

> Contract-First 원칙(PRD §6). 이 문서는 **ai-server(FastAPI) 파트만** 담고 있음 — Spring Boot 쪽 엔드포인트는 각 담당자가 이 문서에 이어서 추가.
> SOT는 `docs/api-contract/openapi.yaml` — 이 문서는 그 사람이 읽는 요약본. 구현된 엔드포인트는 서버 기동 후 `/docs`(Swagger UI) 또는 `/openapi.json`에서 실물 재확인 가능.

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

## POST /ai/rag-chat — ⏳ 미구현(설계만, 예: `docs/design/ai/rag_chroma_schema.md` 참조) — 계획 엔드포인트

점검 기준·법규 질의 전담 RAG 챗봇(FR-6). 성공 시 `AIResponse.data`는 `RagAnswerData` 형태이며, `sources[]`는 표시 라벨(`locator`)과 원문 발췌(`snippet`)를 분리한다.

> **내부 호출 계약**: PRD §6의 Spring Boot → FastAPI 구조를 따른다. 프론트엔드는 이 엔드포인트를 직접 호출하지 않는다. Spring Boot는 `session_id`가 인증 사용자 소유이고 `session_type='RAG'`인지 확인한 뒤에만 FastAPI를 호출한다. 세션이 존재하지 않거나 타인 소유이면 정보 노출을 막기 위해 모두 `404`로 처리하고 FastAPI 호출은 생략한다. 따라서 아래 `session_id`는 클라이언트가 임의 지정한 값이 아니라 Spring Boot가 검증한 서버 관리 식별자다.
>
> Spring Boot는 환경변수로 주입된 내부 서비스 토큰을 `X-Internal-Service-Token` 헤더에 담고, FastAPI는 일치하지 않거나 누락된 요청을 처리 전에 거부해야 한다. 토큰 값은 저장소·로그·OpenAPI에 기록하지 않는다. 운영 nginx는 `/ai/rag-chat`을 FastAPI로 직접 프록시하면 안 되며, 현재 `/ai/**` 직접 경로는 이 엔드포인트 구현 전에 Spring Boot 경유 또는 외부 차단으로 변경해야 한다. 이 인증·라우팅 구현은 후속 Spring/FastAPI 배포 작업의 선행조건이다.

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

**응답 실패**: `RAG_NO_RESULT`(검색 결과 0건) 또는 공통 LLM 오류 코드.

`sources[].doc_id`는 PostgreSQL `rag_documents.id`를 문자열화한 양의 정수 문자열(`^[1-9][0-9]*$`)이다. Spring Boot가 `chat_message_citations.document_id`에 저장할 때 패턴 검증을 통과한 값을 `int(doc_id)`로 변환한다.

---

## POST /ai/report — ⏳ 미구현(설계만, 예: `docs/design/ai/report-chain-design.md` 참조) — 계획 엔드포인트

AI 보고서 4개 섹션(개요·요약·상세·권고) 병렬 생성 및 Grounding Check (FR-5, 로그인/보고서 담당). **아래는 계획 스펙** — `ai-server/routers/ai_router.py`에 아직 라우트가 없다(실제 코드는 `ai/chains/report_chain.py`에 작성 예정, 설계는 완료).

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
  "on_mismatch": "regenerate"
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
          "legal_basis": "콘크리트 구조 설계기준 제X조"
        }
      ],
      "monitoring_points": ["지하주차장 균열 발생 부위"]
    },
    "grounding_ok": true
  },
  "usage": { "tokens": 850 },
  "error": null
}
```

**응답 실패** (`AIErrorCode`: `LLM_TIMEOUT` | `LLM_RATE_LIMIT` | `LLM_INVALID_OUTPUT` | `RAG_NO_RESULT`):
```json
{ "success": false, "data": null, "usage": null, "error": { "code": "LLM_INVALID_OUTPUT", "message": "..." } }
```

---

### 핵심 명세서 (P0 — 워킹 스켈레톤 관통용 최소 경로)

| 메서드 | 경로 | 기능 | 담당 |
|---|---|---|---|
| GET | `/api/auth/oauth2/{provider}` | 소셜 로그인(Kakao/Google) | 정재봉 |
| GET | `/api/users/me` | 내 정보 조회 | 정재봉/오영석 |
| POST | `/api/facilities` | 시설물 등록 | 김관영/유병현 |
| POST | `/api/inspections` | 점검(회차) 생성 | 황승현 |
| POST | `/api/inspections/{id}/media` | 촬영 데이터 업로드 | 황승현 |
| POST | `/api/inspections/{id}/analyze` | AI 분석 요청 | 황승현 |
| GET | `/api/inspections/{id}/defects` | 분석 결과(하자 목록) 조회 | 오영석 |
| PATCH | `/api/defects/{id}` | 검수(오탐 수정·등급 조정) | 오영석 |
| POST | `/ai/defect-explain` | AI 하자 설명 생성 | 오영석 ✅ 구현됨 |
| POST | `/ai/report` | AI 보고서 생성 및 Grounding | 김관영 ⏳ 미구현(설계만) |
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

> 하자 관리 메뉴(`/api/defects` 목록·검색, 공개 `/api/defects/nl-search` → 내부 `/ai/nl-search`)는 유병현/정재봉 담당(§7 표), 위 FR-4 검수 경로와 데이터는 공유하되 엔드포인트는 별도. 자연어 검색 공개 경로는 Spring Boot가 인증·점검자 권한·`has_ai_addon`을 검사한 뒤에만 내부 FastAPI로 전달한다. `GET /api/defects`는 클라이언트 필터와 별개로 인증 주체를 기준으로 시설물 소유자·배정 점검자 범위를 강제하며, 관리자는 전체 범위를 조회할 수 있다.

## 다음 추가 예정 (각 담당자)

- `/ai/rag-chat` — RAG 챗봇 (이은석)
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

**성공 201** `data` 필드: `companyId`(number), `maskedEmail`(string, 예 `haja***@check.com`), `status`(`PENDING_REVIEW`), `signupToken`(string). `signupToken`은 승인 대기 화면 상태조회에 쓰는 **불투명 랜덤 문자열**(서버 발급, PK 노출 금지) — 예시값은 문서에 싣지 않는다.

**실패**: `409 AUTH_EMAIL_DUPLICATED` · `409 AUTH_BUSINESS_NUMBER_DUPLICATED` · `400 FILE_REQUIRED|FILE_INVALID_TYPE|FILE_TOO_LARGE` · `400 INVALID_INPUT` · `500 FILE_UPLOAD_FAILED`

## GET /api/auth/email-availability?email={email} — 아이디(이메일) 중복확인
**성공 200** `data`: `{ "available": true }`

## POST /api/auth/id-inquiry — 아이디 찾기 (application/json)
**요청**: `{ "businessRegistrationNumber": "123-45-67890", "representativeName": "김민수", "companyName": "(주)하자체크" }` — repName·companyName 중 **최소 1개** 필수.
**성공 200** `data`: `{ "maskedEmail": "haja***@check.com" }`
**실패**: `404 AUTH_ACCOUNT_NOT_FOUND` (무매칭 통일 "일치하는 계정을 찾을 수 없습니다.")

## ~~비밀번호 찾기 1·2단계~~ — **이번 배포 제외, 후속(#194 / HAJA-172)**
> ⚠️ 최초 설계(이메일 + 사업자번호만으로 resetToken 반환)는 **계정 탈취 P1**(PR머신·security-reviewer 지적)으로 판정됨: 이메일·사업자번호 둘 다 준공개 정보라 out-of-band 소유 증명이 없으면 안전한 재설정 불가. SMTP 미사용 결정에 따라 **비밀번호 찾기·새 비밀번호 설정 2화면은 이번 범위에서 제외**하고, **보안질문/PIN(가입 시 비공개 비밀값 저장) 방식**으로 후속 구현한다(DB 컬럼 추가 동반). → **#194 / HAJA-172**.
> 관련 ErrorCode(`AUTH_VERIFICATION_FAILED`, `AUTH_RESET_TOKEN_INVALID`)와 password-reset 토큰 네임스페이스는 후속에서 사용.

## GET /api/auth/companies/status?token={signupToken} — 가입 상태 조회(승인 대기 새로고침)
**성공 200** `data`: `{ "status": "PENDING_REVIEW" , "companyName": "(주)하자체크", "rejectionReason": null }`
`status` ∈ `PENDING_REVIEW|APPROVED|REJECTED`. 스테퍼: PENDING_REVIEW=서류검토중, APPROVED=승인완료.
**실패**: `404 AUTH_SIGNUP_TOKEN_INVALID`

## POST /ai/business-license-ocr — 사업자등록증 OCR (AI서버, **stub**)
현재 stub. 백엔드 미배선(향후 실제 OCR 교체 seam).
**요청**: `{ "image_base64": "..." }` 또는 `{ "file_ref": "..." }`
**성공 200** (`AIResponse` envelope) `data`: `{ "businessRegistrationNumber": null, "companyName": null, "representativeName": null, "raw": {}, "stub": true }`

### 추가 ErrorCode (Spring `ErrorCode`)
이번 배포: `AUTH_EMAIL_DUPLICATED`(409) · `AUTH_BUSINESS_NUMBER_DUPLICATED`(409) · `AUTH_ACCOUNT_NOT_FOUND`(404) · `AUTH_SIGNUP_TOKEN_INVALID`(404) · `FILE_REQUIRED`(400) · `FILE_INVALID_TYPE`(400) · `FILE_TOO_LARGE`(400) · `FILE_UPLOAD_FAILED`(500)
후속(#194): `AUTH_VERIFICATION_FAILED`(400) · `AUTH_RESET_TOKEN_INVALID`(400)
