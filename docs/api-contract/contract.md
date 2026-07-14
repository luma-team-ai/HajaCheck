# API 계약 (OpenAPI) — 초안

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

---

## POST /ai/report

AI 보고서 4개 섹션(개요·요약·상세·권고) 병렬 생성 및 Grounding Check (FR-5, 로그인/보고서 담당).

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

> ✅ **해결됨 (2026-07-09, PR #88)**: HF Serverless Inference는 langchain 표준 `with_structured_output()`이 강제하는 `tool_choice="any"`를 지원하지 않아 `400 INVALID_TOOL_CHOICE`가 발생하는 구조적 제약이었다(`langchain-ai/langchain#29569`, upstream "not planned" — 버전 업그레이드로는 해결 안 됨). `get_llm().with_structured_output(schema)`는 내부적으로 `PydanticOutputParser`로 프롬프트에 JSON 스키마를 지시하고 응답을 직접 파싱하는 방식으로 우회 구현됨(`ai-server/ai/core/llm_client.py`의 `_StructuredLLM`). 호출부 시그니처는 동일하게 유지되므로 각 체인 담당자는 그대로 `get_llm().with_structured_output(Schema).invoke(...)`만 쓰면 된다 — 실제 HF 토큰으로 `/ai/defect-explain` e2e 검증 완료.

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
| POST | `/ai/report` | AI 보고서 생성 및 Grounding | 김관영 |
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

> 하자 관리 메뉴(`/api/defects` 목록·검색, `/ai/nl-search`)는 유병현/정재봉 담당(§7 표), 위 FR-4 검수 경로와 데이터는 공유하되 엔드포인트는 별도.

## 다음 추가 예정 (각 담당자)

- `/ai/rag-chat` — RAG 챗봇 (이은석)
- `/ai/nl-search` — 자연어 검색 (하자 관리 담당)
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

**성공 201** `data`: `{ "companyId": 12, "maskedEmail": "haja***@check.com", "status": "PENDING_REVIEW", "signupToken": "<opaque>" }`
`signupToken`은 승인 대기 화면 상태조회에 사용(불투명 랜덤, PK 노출 금지).

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
