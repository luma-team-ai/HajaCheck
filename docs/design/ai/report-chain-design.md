# LLM 보고서 생성 체인 설계 — 섹션 병렬생성 구조 (design-03-19)

> **문서 버전:** v0.2 · **최종 수정:** 2026-07-16 · 이전 버전 `archive/`

> 담당: 김관영(주, 직접 구현) / 오영석(코치, 리뷰·페어링) · 관련 WBS: `design-03-19` · 관련 FR: FR-5-01~FR-5-07, NFR-20
> 상태: **AI 서버 측 구현 완료(dev-07-01, PR 대기)** — `ai-server/ai/chains/report_chain.py` + `POST /ai/report` 배선·테스트(pytest 그린) 완료. `recommendation` 섹션의 실 RAG 연동은 `ai/core/vectorstore.py::get_vectorstore()` 미구현으로 보류 중(§5-1 참고). 근거 양식: 국토안전관리원 「공동주택 정밀안전점검 서식 자료집」(2023.04, 최신) 보고서 참고서식
> 컨벤션 근거: `docs/conventions/AI_개발_컨벤션.md`, `ai-server/ai/chains/defect_explain_chain.py`(기존 체인 예시)
> 참고 자료: `AI 심화 과정 FInal-Project/정밀안전점검 표준서식 자료/` (공동주택·교량·절토사면 표준서식 hwpx), `OTKCEC230761.pdf`(정밀안전점검 실시결과 평가사례집 2019~2020 — 품질 기준·프롬프트 지시문 참고용)

---

## 1. 진행 범위 안내

| 구분 | 범위 | 상태 |
|---|---|---|
| A. 체인 아키텍처(병렬 구조) | §2, §3 | ✅ 완료 |
| B. 공통 모듈 연동 방식 | §4 | ✅ 완료 |
| C. Grounding Check 흐름 | §5 | ✅ 완료 |
| D. 섹션별 스키마·프롬프트 골자 | §6 | ✅ 완료 (2023.04 공동주택 표준서식 기준) |
| E. 최종 조립·제출 포맷(PDF, FR-5-07) | §7 | ✅ 완료 (초안 — 프롬프트 세부 문구는 구현 시 확정) |

실제 코드(`ai/chains/report_chain.py`)는 dev-07-01에서 작성하며, 이 설계 문서가 그 구현의 골격이다.

**dev-07-01 구현 완료 기록(2026-07-16)**: 위 A~E 골격 그대로 구현, `pytest` 전체 그린(회귀 0). 코드리뷰(P1 0건) 반영분 포함 — `ReportSummary.count_by_grade`/`key_findings` 필수화(빈 값 시 파싱 재시도), `recommendation` RAG 조회 실패 로깅 추가, 비-LLM 검증 실패를 `VALIDATION_ERROR`로 분리. §5-1·§8 참고.

### 양식 근거·일관성 검증 요약 (2026-07-13)

- **채택 기준 양식**: 국토안전관리원 「공동주택 정밀안전점검 서식 자료집」(2023.04) — 3종 서식 중 최신이며 HajaCheck 대상(공동주택)과 일치
- **교차 검증**: 교량·절토사면 표준서식과 보고서 골격 동일(결과표 → 현황표 → 위치도·사진 → 실시결과 요약문·종합의견 → 상태평가·보수보강 → 안전성평가). 시설물 유형별 부재 목록만 상이 → 상충 없음
- **평가사례집(OTKCEC230761, 2020)**: 양식이 아닌 품질 기준 자료. 서식(2023)과 조항 번호가 어긋나면 서식 우선
- **도메인 주의**: 표준서식은 시특법상 "정밀안전점검"(구조 안전) 양식, HajaCheck는 "하자 점검" 도메인 → 서식을 그대로 복제하지 않고 **섹션 구조·표 형식·기재 항목을 차용**한다
- **등급 체계 정정(2026-07-13)**: 이전 초안은 "안전등급 A~E 미채용, 자체 체계(긴급/중대/일반) 유지"로 서술했으나 오류 — PRD §9 미결이슈 #3에서 "시설물 안전등급 A~E 준용"이 이미 권장·채택되었고, `docs/api-contract/contract.md`(당시 경로 `docs/contract.md`, PR #111로 이동)·`docs/api-contract/openapi.yaml`(`severity_grade: enum[A,B,C,D,E]`)·`defect_explain_chain` 테스트에 이미 A~E로 구현까지 완료된 상태였다. §6.2·§6.3을 A~E 기준으로 정정
- **FR 간 정합성**: FR-5-06(LLM 4섹션: 개요/요약/상세/권고)과 FR-5-07(보고서 5요소: 점검개요·하자현황요약표·유형별상세·조치권고·종합의견)은 어긋나지 않는다 — **하자현황요약표는 확정 하자 통계로 만드는 결정적(비-LLM) 표**이고, 나머지 4요소가 LLM 4섹션에 1:1 대응(개요→점검개요, 상세→유형별상세, 권고→조치권고, 요약→종합의견)

---

## 2. 섹션 정의 (FR-5-06 기준, 이미 확정된 스펙)

독립적으로 생성 가능한 4개 섹션을 `RunnableParallel`로 동시 호출한다.

| 섹션 키 | 명칭 | 입력 데이터(초안) | 비고 |
|---|---|---|---|
| `overview` | 개요 | 시설물 정보(유형·위치·점검일자) | |
| `summary` | 요약 | 확정 하자 통계(등급별 개수) | |
| `detail` | 상세 | 확정 하자 목록(유형·위치·등급·설명) | |
| `recommendation` | 권고 | 상세 하자 목록 + RAG 근거(FR-5-04) | Chroma Retriever 호출 포함 |

각 섹션은 독립 실행되므로 서로의 출력에 의존하지 않는다(동시성 전제 조건).

## 3. 체인 아키텍처

```
                     ┌─ overview_chain ─┐
facility + defects → ├─ summary_chain ──┤ → RunnableParallel → merge → GroundingCheck → 조립
                     ├─ detail_chain ───┤
                     └─ recommendation_chain (+ RAG retriever) ─┘
```

- 입력: `facility_info`(시설물 정보) + `confirmed_defects`(검수 확정된 하자 목록·통계) — AP-010 요청 바디 기준
- `RunnableParallel({"overview": ..., "summary": ..., "detail": ..., "recommendation": ...})` 로 4개 sub-chain을 동시 invoke
- 각 sub-chain은 `defect_explain_chain.py` 패턴과 동일하게: `_build_prompt()` → `get_llm().with_structured_output(SectionSchema)` → 반환
- 병렬 실행 결과(dict)를 그대로 최종 조립 단계(§7)로 전달
- **병렬 생성은 내부 최적화일 뿐 산출물 분리가 아님** — 4개 sub-chain 출력은 §7에서 즉시 merge되어 단일 보고서 객체(document)가 된다. 섹션별 개별 파일이 만들어지는 지점은 파이프라인 어디에도 없다(§7 참고)

## 4. 공통 모듈 연동 방식

기존 컨벤션을 그대로 따른다(신규 모듈 불필요):

- LLM 호출: `ai.core.llm_client.get_llm()` — 섹션별 체인 4개 모두 이 함수 하나만 경유. 직접 `HuggingFaceEndpoint` 생성 금지(컨벤션 §2)
- 프롬프트 파일: `ai/prompts/report_{overview,summary,detail,recommendation}.md` 4개 파일로 분리(컨벤션 §3). 골자는 §6 확정, 세부 문구는 구현 시(§8)
- 출력 형식: 섹션별 Pydantic 스키마 + `with_structured_output()` (컨벤션 §4) — §6에 확정
- 엔드포인트: `/ai/report` (AP-040) — `ai_router.py` 등록은 구현 단계(dev-07-01)에서
- **동기 응답 예외 사유(2026-07-16 추가)**: `AI_개발_컨벤션.md` §5는 "장시간 작업(보고서 생성 등)은 동기 응답 금지 — 비동기 잡 패턴"을 원칙으로 두지만, `/ai/report`는 PR #123에서 이미 **동기**로 계약 확정됨(`docs/api-contract/contract.md`). 비동기 경계는 AI 서버가 아니라 **백엔드 `/api/reports`(dev-07-02, 잡 ID→폴링)**가 담당하는 구조라 컨벤션 위반이 아님 — AI 서버의 `/ai/report`는 백엔드가 내부적으로 호출하는 동기 서비스 콜로 취급한다. 이 문단이 그 예외의 근거 기록.
- **백엔드 프록시 필요(2026-07-16 추가)**: HAJA-188/190/191(보안 3/3, dev 반영 2026-07-15~16)로 프론트/백엔드가 FastAPI `/ai/*`를 직접 호출할 수 없게 됨 — 모든 AI 호출은 Spring `/api/ai/*` 내부키 프록시를 경유해야 한다. `/ai/report`도 예외 없음. 이 요건은 설계 시점(2026-07-13)엔 존재하지 않았고, 실구현 중 발견되어 **별도 이슈 #239/HAJA-192**(백엔드, `AiProxyController`/`AiProxyService`에 `/api/ai/report` 추가)로 분리 처리함 — 스택 경계상 이 파일이 다루는 dev-07-01(AI 서버) PR과는 별개 PR.

## 5. Grounding Check 흐름 (FR-5-05)

**정정(2026-07-13)**: 이 절은 원래 자체 대조 로직을 서술했으나, 이미 공통 모듈 `ai-server/ai/core/grounding.py`(HAJA-117, 담당 허남, `docs/design/ai/grounding_check.md`)가 구현·리뷰(PR #100) 완료된 상태로 확인됨. `AI_개발_컨벤션.md` §0("공통 기반 위에서만 개발")에 따라 report_chain은 이 모듈을 **그대로 재사용**해야 하며, 자체 대조 로직을 새로 만들지 않는다. **PR #100은 2026-07-13 05:11 UTC dev 머지 완료(이후 #118로 main 승격) — 선행조건 해소, report_chain 실 구현(dev-07-01) 착수 가능.**

1. 병렬 생성 결과에서 `summary`(§6.2) 필드로 `GroundingClaims`를 구성 — `GroundingClaims(total_count=summary.total_count, count_by_grade=summary.count_by_grade)`
2. `ai.core.grounding.check_grounding(confirmed_defects, claims, on_mismatch=MismatchPolicy.REGENERATE)` 호출 — 실측 대조는 이 함수가 수행(자체 구현 금지)
3. 반환된 `GroundingResult.action`에 따라 분기:
   - `PASS` → 그대로 확정
   - `REGENERATE` → 해당 섹션 재생성(최대 재시도 횟수는 `llm_client.MAX_RETRIES` 컨벤션 따름)
   - 재생성 후도 불일치 → `WARN` 정책으로 전환, 응답에 `grounding_ok: bool` 배지 플래그 포함(`GroundingResult.grounded` 값 그대로 사용), 보고서는 그대로 반환(생성 자체를 막지 않음 — 컨벤션 §5 "AI 실패가 비-AI 기능을 막으면 안 됨"과 동일 원칙)
4. `detail`(§6.3) 섹션의 `len(items)` 대조는 공통 모듈의 현재 범위(수치·등급 대조) 밖이므로, 이 항목만 report_chain에서 별도로 `confirmed_defects` 개수와 직접 비교(공통 모듈 확장 여지는 `docs/design/ai/grounding_check.md` §7 참고)

## 6. 섹션별 출력 스키마·프롬프트 골자 (2023.04 공동주택 표준서식 기준)

표준서식 보고서 목차(제1장 개요 ~ 제7장 종합결론·건의)에서 하자 점검 도메인에 맞는 항목만 차용했다.

### 6.1 `overview` — 점검 개요 (서식 제1장 차용: 점검 목적·시설물 개요·점검 범위)

```python
class ReportOverview(BaseModel):
    purpose: str          # 점검 목적 (하자 조사·조치방안 제안 취지, 서식 1.1 차용)
    facility_summary: str # 시설물 개요 — 명칭·위치·규모·준공일 서술 (서식 1.2 + 건축물 현황표 차용)
    scope: str            # 점검 범위·대상 부위 (서식 1.3 차용)
```
프롬프트(`report_overview.md`) 골자: `facility_info`(명칭/위치/유형/규모/점검일자)를 주고 3개 필드를 사실 기반 서술로 생성. 입력에 없는 수치·이력 창작 금지(_system_base.md 근거 원칙).

### 6.2 `summary` — 종합 의견 (서식 "실시결과 요약문 — 책임기술자 종합의견" + 제7장 종합결론 차용)

```python
class ReportSummary(BaseModel):
    overall_opinion: str        # 종합 의견 — 전반 상태 총평
    total_count: int            # 언급하는 총 하자 수 (ai.core.grounding.GroundingClaims.total_count와 동일 필드명, §5)
    count_by_grade: dict[str, int]  # 등급별 개수 {"A": n, "B": n, "C": n, "D": n, "E": n} (GroundingClaims.count_by_grade와 동일 필드명, §5)
    key_findings: list[str]     # 주요 발견사항 3~5개 (서식 "나. 정밀안전점검 주요 결과" 차용)
```
프롬프트(`report_summary.md`) 골자: `confirmed_defects` 통계(등급별 개수·유형 분포)를 주고 총평 생성. **`total_count`·`count_by_grade`는 입력 통계를 그대로 옮겨 적도록 지시**(§5 공통 Grounding Check 모듈에 그대로 전달할 필드이므로 필드명을 `ai.core.grounding.GroundingClaims`와 맞춤 — 정정 2026-07-13).

### 6.3 `detail` — 유형별 상세 (서식 제3장 차용: 부재별 외관조사·결함 항목별 발생원인 분석)

```python
class DefectDetailItem(BaseModel):
    defect_type: str      # 하자 유형
    location: str         # 발생 위치 (동·층·부위)
    severity_grade: str   # 하자 등급 A~E (`docs/api-contract/openapi.yaml`의 `severity_grade` enum과 동일)
    description: str      # 상태 서술 (외관조사 결과 상당)
    cause: str            # 추정 원인 (서식 3.2 "결함 항목별 발생원인 분석" 차용)

class ReportDetail(BaseModel):
    items: list[DefectDetailItem]   # confirmed_defects와 1:1 — 개수 일치가 Grounding Check 대상
```
프롬프트(`report_detail.md`) 골자: 확정 하자 목록을 주고 **입력 하자마다 정확히 1개 항목** 생성(추가·누락 금지 명시). cause는 기존 `defect_explain_chain.py`의 원인 서술 톤 재사용.

### 6.4 `recommendation` — 조치 권고 (서식 제6장 보수·보강방안 + 제7장 건의 + "라. 주요 감시대상 부위" 차용)

```python
class RecommendationItem(BaseModel):
    target: str           # 대상 하자 유형/부위
    method: str           # 권고 조치·보수 방안 (서식 6.1 차용)
    priority: str         # 조치 우선순위
    legal_basis: str      # RAG 근거 인용 — 문서명+조문 (FR-5-04, 컨벤션 §6 출처 필수)

class ReportRecommendation(BaseModel):
    items: list[RecommendationItem]
    monitoring_points: list[str]  # 지속 관찰 필요 부위 (서식 "라. 주요 감시대상 부재 및 부위" 차용)
```
프롬프트(`report_recommendation.md`) 골자: 하자 목록 + Chroma Retriever 검색 결과(점검 지침·법규)를 주고 조치 방안 생성. 검색 0건 시 legal_basis에 임의 생성 금지(컨벤션 §6) — "관련 근거 없음" 명시.

**§5-1. RAG 벡터스토어 미구현에 따른 임시 처리(2026-07-16 추가)**: 구현 시점 기준 `ai.core.vectorstore.py::get_vectorstore()`가 아직 `NotImplementedError` 스텁(AI-LLM 코치 담당, 미착수). `recommendation` 섹션은 이 함수 호출(및 그 이후 검색)을 넓게 `try/except`로 감싸 실패 시 위에서 정의한 "검색 0건" 케이스와 동일하게 취급 — `legal_basis`를 코드에서 강제로 "관련 근거 없음"으로 덮어써 LLM이 그럴듯한 인용을 지어내도 결과에 반영되지 않게 막는다. `get_vectorstore()`가 실구현되면 `report_chain.py` 코드 변경 없이 같은 성공 경로로 실제 검색 결과가 흐르는 구조. 코드리뷰에서 이 예외 처리에 로깅이 없어 향후 실제 버그도 "근거 없음"으로 조용히 가려질 수 있다는 P2 지적이 있었고, 로깅 추가로 반영 완료.

## 7. 최종 조립·내보내기 레이아웃 (FR-5-07)

조립 순서는 FR-5-07의 5요소를 따르며, 표준서식의 앞부분 구조(결과표→현황표→요약문)를 차용한 배치다:

| # | 보고서 구성 | 데이터 소스 | LLM 여부 |
|---|---|---|---|
| 0 | 표지·기본정보 (시설물명·점검일·점검자) | `facility_info` | ✕ (템플릿) |
| 1 | 점검 개요 | `overview` 섹션 | ○ |
| 2 | **하자 현황 요약표** (유형×등급 매트릭스, 서식 "결과표" 상당) | `confirmed_defects` 통계 직접 렌더링 | **✕ (결정적 표 — LLM 미경유)** |
| 3 | 유형별 상세 (하자별 사진 슬롯 포함, 서식 "부위별 사진" 차용) | `detail` 섹션 + `media`/`defects` 테이블에 저장된 실제 점검 촬영 이미지(§7.3) | ○ |
| 4 | 조치 권고 (+ 지속 관찰 부위) | `recommendation` SECTION | ○ |
| 5 | 종합 의견 (작성자 서명란 — 서식 "책임기술자 종합의견" 차용) | `summary` 섹션 | ○ |

- 요약표(#2)를 LLM에 맡기지 않는 것이 서식 정합·Grounding 리스크를 동시에 줄이는 선택 — LLM 산출물(#5 summary)의 수치는 §5에서 이 표와 대조된다
- 렌더링은 FR-5-07 담당 구간(김관영 주담당, dev-07-02)에서 이 표 순서대로 구현. 편집 화면(AP-028 `PATCH /api/reports/{id}`)은 섹션 단위 편집을 전제로 이 6블록 구조를 따른다 — **단, 이는 편집 단계에만 적용**(§7.1 참고)

### 7.1 제출 포맷 확정 (2026-07-13)

- **PDF = 규정상 확정 포맷**: `시설물의 안전 및 유지관리 실시 등에 관한 지침` 제36조② — "안전점검등 결과보고서는 ... e-보고서(규칙 별지 제4호 서식의 첨부자료로 제출하는 보고서, **PDF파일**)로 작성 및 제출". 부록만 "PDF파일 외 파일형식 제출 가능"(HWP 언급 없음). → FR-5-07·AP-011·`GET /api/reports/{id}/pdf`의 PDF 단일 포맷 제출 설계로 최종 확정합니다.

#### 7.1.1 PDF 렌더링 라이브러리 선정

- **선정 라이브러리**: **`OpenHTMLtoPDF`** (Apache-2.0, Thymeleaf 템플릿 엔진 결합)
- **선정 근거**:
  1. **Low-level API (iText / OpenPDF) 한계**: Java 코드로 표(Table) 좌표 및 라인을 일일이 프로그래밍해야 하므로 생산성이 낮고 유지보수가 어려움.
  2. **HTML/CSS 기반 변환 (OpenHTMLtoPDF) 장점**: HTML5/CSS3 Page Media 규격(`@page { size: A4; margin: 20mm; }`, 페이지 번호 `counter(page)`)을 지원하므로 서식 레이아웃(테두리, 음영, 6블록)을 CSS만으로 동일하게 재현하기에 가장 유리함. Thymeleaf와 결합하여 'HTML 템플릿 채움' 방식으로 구조 통일.

### 7.2 모듈 생성 vs 단일 제출 (2026-07-13 확정)

병렬 섹션 생성(§3)·섹션 단위 편집(AP-028)은 내부/편집 단계의 모듈 구조이고, **제출·다운로드 인터페이스는 항상 전체 문서 단일 산출물 1개(PDF)**만 노출한다. 섹션별 개별 파일 제출은 만들지 않는다.

- **근거**: ① 규정 제36조가 "보고서"(부록 제외 본문)를 e-보고서 1건으로 제출하도록 규정 — 섹션별 분리 제출 개념 자체가 없음. ② 기존 스펙 `GET /api/reports/{id}/pdf`(FR-011, `docs/api-contract/openapi.yaml`)도 이미 "보고서 1건 → 파일 1개" 단일 다운로드로 확정돼 있어, 섹션별 export를 추가하면 기존 계약과 어긋남
- **편집(모듈)과 제출(단일)의 경계**: 검수자가 AP-028로 섹션별 초안을 수정하는 것은 계속 허용 — 그 결과가 저장된 보고서 엔티티(단일 레코드)에서 §7 순서대로 조립된 **하나의 문서**를 내보낼 뿐, "수정된 섹션만 부분 제출"하는 기능은 두지 않는다

### 7.3 이미지 첨부·외부 참고 문서 런타임 의존성 금지 (2026-07-13 확정)

**이미지 첨부**: §7 표 #3(유형별 상세)의 사진 슬롯은 하자 탐지·업로드 파이프라인이 이미 적재한 데이터(PRD §6.3 데이터 모델의 `media`/`defects` 테이블 — 실제 점검 촬영 이미지)를 그대로 삽입한다. 이 이미지는 report_chain의 산출물이 아니라 **입력**(`confirmed_defects`와 함께 조립 단계에 전달)이며, PDF 라이브러리의 이미지 삽입 API로 바이트를 직접 넣는 방식 — LLM은 이미지에 관여하지 않는다(사진 슬롯 배치만 템플릿에 정의).

**원본 vs 축소본 (2026-07-14 확정, 오영석·허남 협의)**: PDF 사진 슬롯에는 **축소본(리사이즈된 이미지)만 삽입**한다. 원본 이미지는 `media`/`defects` 테이블에 그대로 보존되어 별도 조회(웹 UI 등)로만 접근 가능 — PDF는 원본을 참조·임베드하지 않는다. 근거: PDF 용량·렌더링(OpenHTMLtoPDF) 성능 확보, 증빙이 필요할 경우 원본은 시스템 내 별도 경로로 언제든 조회 가능하므로 PDF 자체에 원본 해상도를 실을 필요가 없음.

**외부 참고 문서 의존성 금지 — 근거**: 지금 설계에 참고 중인 자료(`OTKCEC230761.pdf`, `시설물의+안전+및+유지관리+...고시.PDF`)는 **설계 시점(design-time) 구조 참고용**이며, 런타임 코드가 이 원본 파일을 직접 열람·파싱하는 경로는 이미 설계상 존재하지 않는다 — 기존 컨벤션(`AI_개발_컨벤션.md` §6 RAG 규약)이 이를 이미 강제하고 있음을 이번에 재확인했다:

- **법규·지침(고시 PDF, 평가사례집 PDF)**: RAG 추천 섹션(§6.4 `recommendation`)은 원본 PDF가 아니라 **Chroma `regulations` 컬렉션**만 조회(`AI_개발_컨벤션.md` §6 "Chroma 접근은 `vectorstore.py` 팩토리만 사용"). 원본 PDF는 관리자 RAG 문서 관리 화면에서 **1회 업로드→명시적 배치 임베딩**(PRD FR-8, §6.2 §6)으로만 시스템에 들어오고, 그 이후 report_chain·rag_chat_chain 등 어떤 체인도 raw PDF/md 파일을 직접 읽지 않는다. 즉 "외부 PDF·md 파일에 런타임 의존성이 있으면 안 된다"는 요구는 **이미 이 파이프라인 구조로 충족**되어 있음 — 신규 코드에서 이 원칙을 어기고 PDF를 직접 `open()`/파싱하는 우회 경로를 만들지 않는 것만 리뷰에서 확인하면 된다
- **PDF 렌더링**: HTML/템플릿 엔진 기반 자체 템플릿(§8 "PDF 렌더링 라이브러리 선정"에서 확정)만 사용하며, 외부 문서 파일에 의존하지 않음(원래부터 이 설계였고 이번에 원칙만 명문화)

## 8. 남은 확정 사항 (구현 단계 이관)

- [x] **선행조건 — PR #100(`ai/HAJA-117-grounding-check` → dev) 머지** — 2026-07-13 05:11 UTC dev 머지 완료(이후 #118로 main 승격). `ai.core.grounding`(공통 모듈) 사용 가능. report_chain 실 구현(dev-07-01) 착수 가능 상태로 전환
- [x] 섹션별 프롬프트 세부 문구 (`ai/prompts/report_*.md` 4개 실파일 작성 완료, 2026-07-16)
- [x] 하자 등급 라벨 확정 — A~E (`docs/api-contract/openapi.yaml` `severity_grade` enum과 일치, 2026-07-13 정정; `docs/conventions/하자_심각도_등급_규칙.md`(HAJA-109, 2026-07-13)와도 정합 확인)
- [x] 제출 포맷 확정 — PDF 전용(규정 제36조 근거, 기존)
- [x] PDF 렌더링 라이브러리 선정 — `OpenHTMLtoPDF` + `Thymeleaf` 조합 (§7.1.1)
- [x] 이미지 삽입 방식 확정 — 축소본만 PDF 삽입, 원본은 별도 저장/조회(2026-07-14, §7.3). 실 구현(`media`/`defects` 이미지 바이트 삽입)은 dev-07-02(백엔드 PDF 조립 단계)에서 진행 — dev-07-01(AI 서버 체인)은 이미지를 다루지 않음(2026-07-16 정정: 이전 버전이 dev-07-01 담당으로 잘못 표기했었음)
- [x] `ai-server/ai/chains/report_chain.py` 구현 + `POST /ai/report` 배선 + `pytest` 그린(dev-07-01, 2026-07-16, PR 대기 — GitHub #18)
- [ ] `ai.core.vectorstore.py::get_vectorstore()` 실구현 — **블로커, AI-LLM 코치 담당**. 완료 전까지 `recommendation`은 §5-1 폴백(관련 근거 없음)으로 동작
- [x] 백엔드 `/api/ai/report` 프록시 (HAJA-188/190/191 보안 요건, 2026-07-16 신규 발견 → GitHub #239/HAJA-192로 분리, PR 대기)
- [ ] dev-07-02: 백엔드 `/api/reports`(비동기 잡)·`/api/reports/{id}/pdf`·PDF 조립·이미지 바이트 삽입 — 미착수

→ AI 서버 측(dev-07-01) 완료. 남은 블로커는 벡터스토어 실구현(타 담당자) 뿐이며, 그 외 dev-07-02(백엔드 PDF 조립)로 이관.
