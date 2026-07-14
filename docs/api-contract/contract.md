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
