# 하자 자연어 검색 필터 변환 스키마 설계 — WBS design-03-18

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

> 메뉴: 하자 관리 (담당: 유병현) · 관련 요구: PRD §4 메뉴 구성(IA) "자연어 검색(P1)", FR-013 · 관련 Jira: HAJA-120(설계) — 하위 HAJA-179~183
> 구현 예정(후속): `ai-server/ai/chains/nl_search_chain.py` · 프롬프트: `ai/prompts/nl_search_convert.md` · 공개 엔드포인트(Spring Boot): `POST /api/defects/nl-search` · 내부 엔드포인트(FastAPI): `POST /ai/nl-search`
> **이 문서는 설계 확정 범위다. Spring 게이트웨이·`/ai/nl-search`·`/api/defects` 실제 구현, 프론트 연동 코드는 후속 티켓에서 진행한다.**

## 0. 계약 범위

- v1 필터 대상은 **하자 유형·등급·상태·confidence** 4개로 제한한다.
- LLM은 DB를 조회하지 않고 자연어 질의를 **필터 조건(JSON)으로만 변환**한다. 실제 하자 목록 조회는 Spring Boot `GET /api/defects`가 담당한다.
- 프론트는 자연어 변환 결과를 **기존 수동 필터 상태에 반영**하고, AI 실패 시 수동 필터 기능은 그대로 유지한다.

---

## 1. 필터 대상 필드·enum 매핑 (HAJA-179)

### 1.1 필터 대상 필드

DB 설계(`docs/design/db/table_design.md` §4)의 `defects` 테이블 컬럼 중 4개만 v1 자연어 검색 대상으로 한다.

| 필드 | DB 컬럼 | DB enum 타입 | 값 |
|---|---|---|---|
| 하자 유형 | `defects.type` | `defect_type` | `CRACK`, `SPALLING`, `LEAK_EFFLORESCENCE`, `REBAR_EXPOSURE`, `PAINT_DAMAGE` |
| 등급 | `defects.grade` | `defect_grade_type` | `A`, `B`, `C`, `D`, `E` |
| 상태 | `defects.status` | `defect_status_type` | `DETECTED`, `CONFIRMED`, `ACTION_PENDING`, `IN_PROGRESS`, `RESOLVED` |
| AI 탐지 신뢰도 | `defects.confidence` | `double precision` (0~1) | 연속값 — enum 아님, 임계값(`confidenceMin`)으로만 필터 |

> 필터 값 표현은 **영문 DB enum 코드로 통일**한다(HAJA-182 §4.1 참고). 프론트 표시용 한국어 라벨 매핑은 프론트가 별도로 소유하며 이 계약에는 포함하지 않는다.

### 1.2 한국어 표현·동의어 매핑 (초안)

사용자가 자연어로 입력할 것으로 예상되는 한국어 표현 → DB 코드 매핑. LLM 프롬프트(HAJA-181)의 few-shot/지시문에 그대로 반영한다.

| DB 코드 | 대표 표현 | 동의어·변형 |
|---|---|---|
| `CRACK` | 균열 | 크랙, 금 감, 갈라짐 |
| `SPALLING` | 박리박락 | 박리, 박락, 콘크리트 박리 |
| `LEAK_EFFLORESCENCE` | 누수백태 | 누수, 백태, 물샘, 누수 흔적 |
| `REBAR_EXPOSURE` | 철근노출 | 철근 노출, 철근 드러남 |
| `PAINT_DAMAGE` | 도장손상 | 도장 손상, 페인트 손상, 도장 벗겨짐 |
| `DETECTED` | 신규 | 미확인, 신규 탐지, AI 탐지 |
| `CONFIRMED` | 검수확정 | 검수 완료, 확정 |
| `ACTION_PENDING` | 조치대기 | 조치 대기, 대기중, 조치 필요 |
| `IN_PROGRESS` | 조치중 | 조치 진행중, 진행중 |
| `RESOLVED` | 조치완료 | 완료, 해결됨, 조치 끝 |

### 1.3 등급(grade) 비교 표현 처리 방향

`하자_심각도_등급_규칙.md` 기준 **A=우수(경미) ~ E=불량(심각)** — 등급 문자가 뒤로 갈수록(A→E) 심각도가 높다.

"D등급 이상"처럼 **비교 표현**이 들어오면, LLM이 이를 등급 **단일 값이 아니라 포함 집합**으로 확장해서 반환한다. 순서는 `A(1) < B(2) < C(3) < D(4) < E(5)`이며, "~등급 이상"은 "심각도가 그 등급과 같거나 더 심각한 쪽"(문자 순서로 뒤쪽 전부)을 의미한다.

- "D등급 이상" → `grade: ["D", "E"]`
- "A등급만" → `grade: ["A"]`
- "B등급 이하" → `grade: ["A", "B"]`

이 확장 로직은 **API 계약에 별도 연산자(`gradeMin`/`gradeMax`)를 두지 않고 LLM 프롬프트 단계에서 집합으로 풀어서 반환**한다 — 이유는 §4(HAJA-182)에서 프론트 기존 수동 필터가 `DefectGrade[]`(포함 목록) 형태를 이미 쓰고 있어, 응답을 그대로 그 형태에 대입할 수 있게 하기 위함이다.

> **범위 한정**: 이 확장 규칙은 등급 문자(A~E)나 "~등급 이상/이하"처럼 **명시적 비교 표현**이 있을 때만 적용한다. "심각한", "위험한" 같은 모호한 형용사만으로 등급을 추측하지 않는다 — 그 경우는 §2.3 "애매한 질의"로 처리한다(§3.2 프롬프트에 동일 규칙 반영, §5.2 테스트 케이스 참고).

### 1.4 완료 기준 반영

- 필터 가능 필드·enum 매핑표: 위 1.1
- 후속 S2(HAJA-180) 스키마 설계에서 그대로 참조 가능: `filters.type: string[]`, `filters.grade: string[]`, `filters.status: string[]`, `filters.confidenceMin: number | null`

---

## 2. `/ai/nl-search` request/response 스키마 (HAJA-180)

`AI_개발_컨벤션.md` §5 공통 envelope(`AIResponse`)을 그대로 따른다. LLM은 필터 조건만 반환하고 DB를 조회하지 않는다(§0).

### 2.1 Request

```python
class NlSearchRequest(BaseModel):
    query: str  # 사용자가 입력한 자연어 질의 원문 — 라우터에서 trim 후 1~500자 검증
```

- `query`는 Spring 공개 게이트웨이와 FastAPI 내부 라우터 양쪽에서 양끝 공백을 제거한 뒤 **1~500자**만 허용한다. 빈 문자열/공백만 있거나 500자를 초과하면 **LLM 호출 이전에 코드로 검증**해 `VALIDATION_ERROR`로 즉시 실패시킨다(§5.4 입력 검증 케이스, 모델 컨텍스트 초과·LLM 호출 비용 남용 방지). 공개 요청은 Spring에서 먼저 거부해 FastAPI 호출도 발생시키지 않는다.
- FastAPI의 위 검증은 기본 422 응답으로 흘려보내지 않고 공통 `AIResponse.fail(AIErrorCode.VALIDATION_ERROR, ...)` envelope으로 반환한다. Pydantic의 요청 파싱 자체가 실패한 경우에 대한 엔드포인트 예외 처리도 구현 단계에서 동일 envelope을 보장한다.
- 현재 프론트의 수동 필터 상태는 요청에 포함하지 않는다 — 자연어 검색은 매 질의마다 **독립적으로 새 필터 조건을 만든다**(기존 수동 필터와의 병합 여부는 프론트가 응답을 받은 뒤 결정, HAJA-182 §4.3).

### 2.2 Response — `NlSearchResult` (`AIResponse.data`)

```python
from typing import Literal, Optional

from pydantic import BaseModel, Field

DefectTypeCode = Literal["CRACK", "SPALLING", "LEAK_EFFLORESCENCE", "REBAR_EXPOSURE", "PAINT_DAMAGE"]
DefectGradeCode = Literal["A", "B", "C", "D", "E"]
DefectStatusCode = Literal["DETECTED", "CONFIRMED", "ACTION_PENDING", "IN_PROGRESS", "RESOLVED"]

class NlSearchFilters(BaseModel):
    type: list[DefectTypeCode] = Field(default_factory=list)        # defect_type 코드 목록
    grade: list[DefectGradeCode] = Field(default_factory=list)      # defect_grade_type 코드 목록 (§1.3 집합 확장 결과)
    status: list[DefectStatusCode] = Field(default_factory=list)    # defect_status_type 코드 목록
    confidenceMin: Optional[float] = Field(default=None, ge=0.0, le=1.0)

class NlSearchResult(BaseModel):
    filters: NlSearchFilters
    unsupported_terms: list[str] = Field(default_factory=list)   # 필터 대상 밖이라 무시한 표현
    clarifying_question: Optional[str] = None                     # 질의가 애매해 되물어야 할 때만 채움
    interpretation_confidence: float = Field(ge=0.0, le=1.0)      # LLM이 질의를 얼마나 명확히 해석했는지 — filters.confidenceMin(하자 자체 신뢰도)와는 다른 값
```

- `type`/`grade`/`status`는 `Literal[...]`로 값 자체를 §1.1 enum으로 제한한다 — LLM이 목록에 없는 값을 반환하면 structured output 파싱 단계에서 `LLM_INVALID_OUTPUT`으로 걸러진다.
- `confidenceMin`/`interpretation_confidence`는 `Field(ge=0.0, le=1.0)`으로 0~1 범위를 모델 레벨에서 강제한다.

**성공 응답 예시** ("D등급 이상 조치 대기 하자"):

```jsonc
{
  "success": true,
  "data": {
    "filters": { "type": [], "grade": ["D", "E"], "status": ["ACTION_PENDING"], "confidenceMin": null },
    "unsupported_terms": [],
    "clarifying_question": null,
    "interpretation_confidence": 0.92
  },
  "usage": { "tokens": 180 }
}
```

**실패 응답** (LLM 타임아웃 등, AI_개발_컨벤션 §5 에러 코드 그대로 사용):

```jsonc
{ "success": false, "error": { "code": "LLM_TIMEOUT", "message": "..." } }
```

### 2.3 필드별 처리 원칙

| 상황 | `filters` | `unsupported_terms` | `clarifying_question` | `interpretation_confidence` |
|---|---|---|---|---|
| 정상 질의 | 인식된 조건 채움 | `[]` | `null` | 높음(대략 ≥0.7) |
| 애매한 질의 | 확실한 부분만(비거나 부분) | `[]` | 되묻는 질문 채움 | 낮음(<0.7) |
| 지원하지 않는 조건 포함 | 인식 가능한 나머지만 | 못 다룬 표현 나열 | 보통 `null` | 인식된 부분 기준 |
| 입력 길이 검증 실패 | (LLM 호출 안 함) | — | — | — → 빈 값 또는 500자 초과 시 `VALIDATION_ERROR`로 실패 처리 |

- `clarifying_question`이 채워진 경우 프론트는 `filters`를 기존 상태에 적용하지 않고 질문만 노출한다(HAJA-182 §4.3).
- `unsupported_terms`가 있는 경우 나머지 `filters`는 정상 적용하되, 무시된 표현을 배너/토스트로 안내한다.

---

## 3. 프롬프트·Structured Output 설계 (HAJA-181)

`AI_개발_컨벤션.md` §4 원칙대로 자유 텍스트 파싱 없이 `get_llm().with_structured_output(NlSearchResult)`만 사용한다. 구조화 출력 스키마는 §2.2의 `NlSearchResult`를 그대로 재사용한다 — API 응답 모델과 LLM 출력 모델을 분리하지 않는다(불일치 리스크 제거).

### 3.1 체인 (`ai/chains/nl_search_chain.py`, 후속 구현 시)

```python
from ai.core.llm_client import get_llm
from ai.core.schemas import NlSearchResult  # 또는 nl_search_chain.py 자체 정의, §2.2와 동일 필드

def run_nl_search_chain(query: str) -> NlSearchResult:
    prompt = _build_prompt(query)  # _system_base.md + nl_search_convert.md
    llm = get_llm().with_structured_output(NlSearchResult)
    return llm.invoke(prompt)
```

- 질의 trim·길이 검증은 체인 진입 전 라우터(`ai_router.py`)에서 처리 — `run_nl_search_chain`은 trim 기준 1~500자의 `query`만 받는다.

### 3.2 프롬프트 초안 (`ai/prompts/nl_search_convert.md`)

```markdown
<!-- 입력 변수: {query} -->
사용자가 하자 관리 화면에서 입력한 자연어 검색 질의를 필터 조건 JSON으로 변환하라.

## 필터 대상 필드 (이 4개 외에는 unsupported_terms로 분류)
- type (하자 유형): CRACK, SPALLING, LEAK_EFFLORESCENCE, REBAR_EXPOSURE, PAINT_DAMAGE
- grade (등급, A=경미~E=심각): A, B, C, D, E
- status (조치 상태): DETECTED, CONFIRMED, ACTION_PENDING, IN_PROGRESS, RESOLVED
- confidenceMin (AI 탐지 신뢰도 하한, 0~1)

## 한국어 표현 매핑
(§1.2 표를 그대로 삽입 — 균열→CRACK, 박리박락→SPALLING, 누수백태→LEAK_EFFLORESCENCE, 철근노출→REBAR_EXPOSURE,
 도장손상→PAINT_DAMAGE, 신규→DETECTED, 검수확정→CONFIRMED, 조치대기→ACTION_PENDING, 조치중→IN_PROGRESS, 조치완료→RESOLVED)

## 등급 비교 표현 규칙
등급 순서는 A(1) < B(2) < C(3) < D(4) < E(5), 뒤로 갈수록 심각.
"~등급 이상"은 그 등급부터 E까지 전부 포함하는 집합으로, "~등급 이하"는 A부터 그 등급까지 포함하는 집합으로 변환하라.
예: "D등급 이상" → grade: ["D", "E"]
**단, "심각한", "위험한", "큰" 같은 모호한 형용사만으로는 등급을 추측하지 마라.** 질의에 등급 문자(A~E)나 "~등급 이상/이하" 같은 명시적 비교 표현이 없으면 grade는 비워두고, 대신 clarifying_question으로 몇 등급부터를 원하는지 되물어라.
예: "심각한 거 보여줘" → grade: [](추측 금지), clarifying_question: "몇 등급 이상을 심각하다고 볼까요?"

## confidence(신뢰도) 퍼센트 변환 규칙
v1은 신뢰도 **하한(`confidenceMin`)만** 지원한다. 사용자가 하한을 퍼센트로 표현하면("80% 이상", "80퍼센트 이상", "신뢰도 80 이상" 등) **100으로 나눠 0~1 소수로 변환**해서 confidenceMin에 넣어라.
예: "신뢰도 80% 이상" → confidenceMin: 0.8 / "90퍼센트 이상 확실한" → confidenceMin: 0.9
이미 0~1 사이 소수로 말한 경우("신뢰도 0.8 이상")는 그대로 사용한다.
"80% 이하", "80 미만", "신뢰도 낮은"처럼 **상한 또는 낮은 신뢰도**를 요구하는 표현은 confidenceMin으로 변환하지 마라. v1에서 표현할 수 없는 조건이므로 해당 구절을 원문 그대로 unsupported_terms에 넣고 confidenceMin은 null로 둬라. 다른 유형·등급·상태 조건이 함께 있으면 그 조건만 정상 변환한다.

## 애매하거나 지원하지 않는 표현 처리
- 4개 필터 대상에 속하지 않는 조건(위치, 날짜, 담당자 등)은 filters에 넣지 말고 unsupported_terms에 원문 그대로 나열하라.
- 질의가 너무 짧거나 모호해 필터를 특정할 수 없으면(등급 추측 금지 규칙 포함) filters는 비워두고 clarifying_question에 되물을 질문을 한국어로 작성하라.
- 정상적으로 해석했으면 clarifying_question은 null로 두고 interpretation_confidence를 0.7 이상으로 반환하라.

질의: {query}
```

> `_system_base.md`(공통 시스템 프롬프트: 한국어 응답, 근거 없는 내용 생성 금지)를 상단에 결합해 사용한다(AI_개발_컨벤션 §3).

### 3.3 완료 기준 반영

- `get_llm().with_structured_output(...)` 패턴 그대로 적용 — 구현 단계에서 프롬프트·Pydantic 모델을 그대로 옮길 수 있다.

---

## 4. 프론트 ↔ Spring Boot ↔ `/ai/nl-search` ↔ `/api/defects` 연동 계약 (HAJA-182)

자연어 검색은 PRD §2.3/§2.4의 인증·플랜 집행 원칙과 §6 내부 네트워크 아키텍처를 따른다.

- **공개 호출 경로**: 프론트는 Spring Boot의 `POST /api/defects/nl-search`만 호출한다. Spring Security 세션 인증을 통과한 점검자 요청만 허용한다.
- **플랜 게이트**: Spring Boot는 개인 활성 플랜 또는 회사 활성 플랜을 조회해 `plans.has_ai_addon = true`인지 검사한다. 회사 플랜 상속은 회사가 `status = APPROVED`이면서 `verification_status = VERIFIED`이고, 요청 사용자가 해당 회사에 **유효하게 소속된 승인 멤버십**을 보유한 경우에만 허용한다. `users.company_id` 존재만으로 상속하지 않는다(`table_design.md` §2.6 cross-tenant IDOR 방지 규칙). Free 등 AI 부가 기능이 없는 플랜과 회사·멤버십 검증 실패 요청은 FastAPI를 호출하지 않고 `403 AI_ADDON_REQUIRED`로 거부한다. 프론트에서 버튼을 숨기는 것은 보조 UX일 뿐 서버 검사를 대체하지 않는다.
- **내부 호출 경로**: 위 검사를 통과한 경우에만 Spring Boot가 내부 네트워크의 FastAPI `POST /ai/nl-search`를 호출한다. 환경변수로 주입한 `X-Internal-Service-Token`을 전달하고 FastAPI는 누락·불일치 요청을 처리 전에 거부한다.
- **외부 노출 차단**: 운영 nginx는 `/ai/nl-search`를 FastAPI로 직접 프록시하지 않는다. 현재의 포괄적인 `/ai/**` 프록시는 이 기능 배포 전에 Spring 경유 또는 외부 차단으로 변경하며, 프론트도 `aiClient`가 아니라 인증·CSRF 처리가 적용되는 Spring API 클라이언트를 사용한다.
- **응답 변환**: FastAPI 내부 `AIResponse.data`의 `NlSearchResult`를 Spring 공통 `ApiResponse.ok(data)`에 담아 반환한다. FastAPI의 LLM 에러 코드는 Spring `ApiResponse.fail(code, message)`로 보존하되 내부 예외·토큰 값은 노출하지 않는다.

### 4.1 필터 값 표현 원칙

- **배경**: DB(`table_design.md`)의 `defect_type`/`defect_status_type`은 영문 enum 코드(`CRACK`, `ACTION_PENDING` 등)인데, 프론트 `inspection/types.ts`의 `DefectType`/`DefectStatus`는 한국어 문자열 리터럴(`'균열'`, `'조치대기'` 등)로 정의돼 있어 서로 어긋난다(등급 `A`~`E`만 우연히 일치). `GET /api/defects`가 아직 미구현이라 지금까지는 드러나지 않은 잠재 불일치였다.
- **결정**: 필터 값 표현은 **영문 DB enum 코드로 통일**한다. 내부 `/ai/nl-search` 응답, 공개 `POST /api/defects/nl-search` 응답, `GET /api/defects` 쿼리 파라미터·응답 모두 코드값(`CRACK`, `ACTION_PENDING` 등)을 사용한다.
- **프론트 표시 라벨**: 한국어 라벨 매핑은 프론트가 자체 소유한다. `frontend/src/features/map/constants.ts`의 `Record<코드, 라벨>` + fallback 라벨 패턴(`GRADE_COLOR`/`GRADE_LABEL`/`FALLBACK_GRADE_LABEL`)을 그대로 재사용해 `inspection/constants.ts`에 `DEFECT_TYPE_LABEL`/`DEFECT_STATUS_LABEL`을 정의한다. 이 매핑은 API 계약에 포함하지 않는다.
- **범위 경계**: `inspection/types.ts`의 타입을 한국어 리터럴에서 영문 코드로 바꾸는 실제 마이그레이션과 `ResultViewerPage.tsx` 등 소비 컴포넌트 수정은 이번 설계 PR 범위 밖이다. `GET /api/defects` 백엔드 구현과 함께 묶이는 후속 프론트 구현 티켓에서 처리한다.

### 4.2 자연어 변환 출력 → `GET /api/defects` 파라미터 매핑

`GET /api/defects`는 아직 미구현이라 이 매핑이 곧 그 쿼리 파라미터 설계의 기준이 된다.

**소유권 범위는 아래 클라이언트 필터보다 먼저 서버에서 강제한다.** Spring Boot는 인증 주체로부터 조회 가능한 점검 회차를 계산해 시설물 소유자는 본인 소유 시설물, 점검자는 자신에게 배정된 시설물·점검 회차, 관리자는 전체 하자만 조회할 수 있게 제한한다. 클라이언트가 `facilityId`·`inspectionId`·회사 식별자 등으로 이 범위를 확장할 수 없으며, 범위 밖 `defects`는 다른 필터 조건과 일치하더라도 결과에 포함하지 않는다. 역할 검사는 `@PreAuthorize`, 데이터 범위는 repository/service의 소유권 조건으로 함께 적용한다(PRD §5 FR-1).

| 자연어 변환 응답 필드 | `/api/defects` 쿼리 파라미터(안) | 비고 |
|---|---|---|
| `filters.type` (string[]) | `type=CRACK,SPALLING` (콤마 구분) | 복수 선택 지원 |
| `filters.grade` (string[]) | `grade=D,E` | §1.3 비교 표현 확장 결과 그대로 전달 |
| `filters.status` (string[]) | `status=ACTION_PENDING` | 복수 선택 지원 |
| `filters.confidenceMin` (number\|null) | `confidenceMin=0.8` | 미지정 시 파라미터 생략 |

빈 배열(`[]`)인 필드는 쿼리 파라미터에서 생략한다(= 해당 축은 필터링 안 함).

현재 DB 설계상 `defects.confidence`는 NOT NULL이다(`table_design.md` §5.4). 따라서 `confidenceMin`이 지정되면 `confidence >= confidenceMin` 조건으로 비교한다. 향후 confidence가 nullable로 완화될 경우 NULL 행 처리 정책은 해당 DB 변경 티켓에서 별도로 확정한다.

### 4.3 프론트 수동 필터 ↔ 자연어 필터 동기화 방식

1. 사용자가 자연어 질의 입력 → 프론트가 Spring Boot `POST /api/defects/nl-search` 호출.
2. Spring Boot가 세션·점검자 권한·`has_ai_addon`을 검사하고, 통과한 요청만 내부 FastAPI `POST /ai/nl-search`로 전달.
3. 응답 성공 + `clarifying_question`이 `null`이면, 응답의 `filters`로 **기존 수동 필터 상태를 통째로 교체**한다(부분 병합 아님 — 자연어 질의 결과가 새 필터 기준이 된다).
4. `clarifying_question`이 있으면 필터 상태는 그대로 두고 질문만 노출한다(§2.3).
5. `unsupported_terms`가 있으면 인식된 나머지 필터는 적용하되, 무시된 표현을 안내한다(§2.3).
6. 필터 상태가 갱신되면 프론트는 동일한 `GET /api/defects` 조회 경로를 그대로 사용한다 — 수동 필터로 조회하든 자연어로 조회하든 최종 진입점은 하나다.

### 4.4 AI 실패 시 fallback 원칙

Spring 게이트웨이가 전달한 자연어 변환 요청이 실패(`LLM_TIMEOUT`/`LLM_RATE_LIMIT`/`LLM_INVALID_OUTPUT`)하면:

- 화면이 깨지지 않아야 한다 — AI_개발_컨벤션 §5 표준 문구("AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.") + 재시도 버튼.
- **기존 수동 필터 기능은 그대로 유지**한다 — 자연어 검색 실패가 `GET /api/defects` 수동 필터 조회(유형·등급·상태 체크박스 등)를 막지 않는다.
- 직전에 자연어 검색으로 적용된 필터가 있었다면 그대로 유지하고(롤백하지 않음), 사용자가 수동으로 다시 조정할 수 있게 한다.
- `401`(미인증)과 `403 AI_ADDON_REQUIRED`(플랜 제한)는 LLM 장애 문구로 처리하지 않는다. 각각 로그인 유도와 업그레이드 안내로 분기하며, 두 경우 모두 FastAPI 호출이 발생하지 않아야 한다.

### 4.5 완료 기준 반영

- AI/백엔드/프론트가 위 4.1~4.4 계약을 기준으로 각자 구현 가능.
- HAJA-120 후속 구현 티켓 범위: ① AI 서버 내부 `/ai/nl-search` + 내부 토큰 검증 구현, ② Spring Boot 공개 `POST /api/defects/nl-search` 게이트웨이(인증·점검자 권한·`has_ai_addon`)와 `GET /api/defects` 구현(§4.2 파라미터 기준), ③ 운영 nginx 직접 노출 차단, ④ 프론트 `inspection/types.ts` 영문 코드 마이그레이션 + Spring API 기반 자연어 검색 UI(§4.3 동기화 로직).

---

## 5. 예시 질의·테스트 시나리오 (HAJA-183)

후속 구현 단계에서 AI 서버 단위 테스트, 백엔드 필터 테스트, 프론트 mock 테스트가 **동일 fixture**를 재사용한다.

### 5.1 정상 질의

| 질의 | 기대 `filters` | `unsupported_terms` | `clarifying_question` |
|---|---|---|---|
| "균열만 보여줘" | `{ "type": ["CRACK"] }` | `[]` | `null` |
| "D등급 이상 하자" | `{ "grade": ["D", "E"] }` | `[]` | `null` |
| "조치 대기 중인 누수" | `{ "type": ["LEAK_EFFLORESCENCE"], "status": ["ACTION_PENDING"] }` | `[]` | `null` |
| "신뢰도 80% 이상 철근노출" | `{ "type": ["REBAR_EXPOSURE"], "confidenceMin": 0.8 }` | `[]` | `null` |
| "B등급 이하 조치완료된 도장손상" | `{ "type": ["PAINT_DAMAGE"], "grade": ["A", "B"], "status": ["RESOLVED"] }` | `[]` | `null` |

### 5.2 애매한 질의 (되묻기)

"애매한 질의"는 필터 값을 특정할 근거(유형·등급·상태·신뢰도 중 명시적 언급)가 없는 경우로 한정한다. 모호한 형용사만으로 등급·유형을 추측하지 않는다 — 추측이 아니라 `clarifying_question`으로 되묻는 쪽이 기본값이다.

| 질의 | 기대 `filters` | 기대 `clarifying_question` |
|---|---|---|
| "하자 좀 보여줘" | 전부 빈 값(`type: []`, `grade: []`, `status: []`, `confidenceMin: null`) | non-null(예: "어떤 유형·등급·상태의 하자를 찾으시나요?") |
| "심각한 거" | 전부 빈 값 — "심각한"은 등급 D/E를 명시한 표현이 아니므로 §1.3 확장 대상에 포함하지 않는다 | non-null(예: "몇 등급 이상을 심각하다고 볼까요?") |

애매한 질의 케이스는 `clarifying_question`의 **문구까지 정확히 일치**시키지 않는다 — AI 서버 테스트는 `filters`가 표에 명시된 값(전부 빈 값)과 정확히 일치하는지, `clarifying_question`이 `null`이 아닌지(구조적 검증)만 확인한다(§5.5).

### 5.3 지원하지 않는 조건 포함

| 질의 | 기대 처리 |
|---|---|
| "3층에 있는 균열 보여줘" | `filters: { "type": ["CRACK"] }`, `unsupported_terms: ["3층"]` (위치는 v1 필터 대상 아님, §1.1) |
| "지난주에 발견된 철근노출" | `filters: { "type": ["REBAR_EXPOSURE"] }`, `unsupported_terms: ["지난주"]` (날짜는 v1 필터 대상 아님) |
| "신뢰도 80% 이하 균열" | `filters: { "type": ["CRACK"], "confidenceMin": null }`, `unsupported_terms: ["신뢰도 80% 이하"]` (v1은 신뢰도 하한만 지원, §3.2) |

### 5.4 입력 길이 검증

| 질의 | 처리 |
|---|---|
| `""`, `"   "` | LLM 호출 전 코드 검증에서 `VALIDATION_ERROR`로 즉시 실패(§2.1). `filters`/`unsupported_terms`/`clarifying_question` 생성 안 함 |
| trim 후 501자 이상 | LLM 호출 전 코드 검증에서 `VALIDATION_ERROR`로 즉시 실패(§2.1). FastAPI 호출·토큰 사용 없음 |

### 5.5 스택별 재사용 기준

- **AI 서버**: 5.1~5.4를 `test_nl_search_chain.py`의 케이스로 사용. 5.1(정상)·5.3(미지원 조건 포함)은 `filters`/`unsupported_terms`가 표에 명시된 값과 **정확히 일치**하는지 검증한다. 5.2(애매한 질의)는 `clarifying_question` 문구까지 고정하지 않고 `filters == 표의 빈 값` && `clarifying_question is not None`라는 **구조적 조건**만 검증한다(§5.2 참고). 5.4는 LLM mock이 호출되지 않고 `VALIDATION_ERROR` envelope이 반환되는지 검증한다.
- **Spring 백엔드 — 인증·플랜 게이트**: 미인증 요청은 `401`, AI 부가 기능이 없는 Free 플랜은 `403 AI_ADDON_REQUIRED`이며 두 경우 FastAPI 클라이언트가 호출되지 않는지 검증한다. 개인 활성 플랜 또는 `APPROVED`+`VERIFIED` 회사와 유효한 승인 멤버십을 모두 만족하는 회사 활성 플랜이 `has_ai_addon=true`인 점검자 요청만 내부 토큰을 포함해 전달되는지 검증한다. `users.company_id`만 일치하거나 회사/멤버십이 대기·반려·만료된 요청은 `403`이고 FastAPI가 호출되지 않아야 한다.
- **Spring 백엔드 — 조회 소유권**: 통과 케이스에서는 5.1의 기대 `filters`를 §4.2 매핑표로 변환한 쿼리 파라미터가 `GET /api/defects`에서 올바른 결과를 반환하는지 검증한다. 동일 필터에 대해 시설물 소유자는 본인 소유, 점검자는 배정된 시설물·점검 회차, 관리자는 전체 결과만 받는지 확인하고, 타 회사·타 소유자·미배정 점검자의 하자가 섞이지 않는 cross-tenant 부정 테스트를 필수로 둔다.
- **AI 서버 호출 경계**: `X-Internal-Service-Token` 누락·불일치 요청을 LLM 호출 전에 거부하고, 올바른 내부 호출만 허용하는지 검증한다.
- **프론트**: 5.1~5.4를 MSW mock 핸들러 fixture로 사용 — 공개 Spring 경로(`/api/defects/nl-search`)만 호출하며 `clarifying_question`/`unsupported_terms` 분기 UI, `401` 로그인 유도, `403 AI_ADDON_REQUIRED` 업그레이드 안내가 각각 올바르게 렌더링되는지 검증한다.

### 5.6 완료 기준 반영

- 정상/애매함/지원불가/입력 길이 검증/인증·플랜 게이트/소유권 격리 케이스 전부 포함.
- 후속 구현 티켓(AI/백엔드/프론트)이 동일 표를 fixture로 재사용 가능.

---

## 변경 이력
- v0.1 (2026-07-15): 최초 작성 — HAJA-179~183 설계 확정(필터 필드·enum 매핑, request/response 스키마, 프롬프트·structured output, 연동 계약, 테스트 시나리오). HAJA-120.
