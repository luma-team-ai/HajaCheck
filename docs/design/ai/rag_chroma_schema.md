# Chroma 컬렉션 메타데이터 & RAG 출처(sources) 응답 스키마 — HAJA-113

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

> 에픽: HAJA-103 [AI 설계] · 담당: 유병현(챕터2 데이터·API 계약 오너) · 관련 요구: `PRD_hajaCheck_v0.43.md` §5 FR-6, §6.3 (v0.41 대비 RAG·데이터 모델 요구사항 변경 없음)
> 구현 연계: `ai-server/ai/core/vectorstore.py`(Chroma 컬렉션 팩토리, RAG 코치 구현 예정) · `ai-server/ai/core/schemas.py`(`SourceCitation`/`RagAnswerData`) · DB: `docs/design/db/table_design.md` §5.5 (`rag_documents`, `chat_message_citations`)

## 1. 목적

FR-6 RAG 챗봇이 `regulations`(법규·지침)/`defect_kb`(하자 지식) 두 Chroma 컬렉션에서 검색한 청크를 답변에 인용할 때, ① 어떤 메타데이터를 청크에 붙일지 ② 답변 API가 그 출처를 어떤 구조로 반환할지를 확정한다. KPI "출처 표기율 100%"를 기계적으로 검증 가능하게 만드는 것이 목표다.

## 2. SoT 경계 — PostgreSQL `rag_documents` vs Chroma 메타데이터

문서 원본 관리는 PostgreSQL `rag_documents`가 SoT이고, Chroma는 청크 텍스트 + 검색/출처 표시에 필요한 최소 메타데이터만 보관한다(임베딩 시점에 1회 복제, read-only 스냅샷).

| `rag_documents` 컬럼 | Chroma 복제 | 비고 |
|---|---|---|
| `id` | → `doc_id`(문자열화) | 접두사 없는 양의 정수 문자열(`"42"`, `^[1-9][0-9]*$`) |
| `title` | → `source` | citation 표시용 문서명 |
| `source_type`(`LAW`/`GUIDELINE`) | → `doc_type` | 법률/지침 구분 라벨. **컬렉션 라우팅과는 무관** |
| `target_collection`(`REGULATIONS`/`DEFECT_KB`, 신규) | 라우팅 근거(메타데이터 필드로 복제 안 함) | 등록 시 반드시 명시하며 임베딩 후에는 변경하지 않는 불변값. 임베딩 파이프라인이 이 값으로 `regulations`/`defect_kb` 컬렉션을 결정 — `source_type`과 분리된 축이라 `defect_kb` 문서도 `source_type=GUIDELINE`을 유지하면서 라우팅 가능 |
| `effective_date`(date, nullable, 신규) | → `effective_date`(ISO 문자열) | LAW만 채움, 그 외 NULL → 값 없으면 Chroma 키 자체 생략 |
| `publisher`(varchar, nullable, §2.8 신규) | → `publisher`(문자열) | regulations 대상 — 발행 기관/부처명. 값 없으면 키 생략 |
| `authored_at`(date, nullable, §2.8 신규) | → `authored_at`(ISO 문자열) | defect_kb 대상 — 문서 작성일(`effective_date`와 별개). 값 없으면 키 생략 |
| `verification_status`(enum, nullable, §2.8 신규) | → `verification_status`(문자열, `"UNVERIFIED"`/`"VERIFIED"`) | defect_kb 대상 — 전문가 검토 통과 여부. 값 없으면 키 생략 |
| `file_url` / `embedding_status` / `chunk_count` / `created_at` | ❌ | 검색·citation에 불필요(관리 목적) |
| `embedded_at` | → `embedded_at` | 임베딩 처리 시각 |

> `target_collection`/`effective_date`는 `table_design.md` §2.7 마이그레이션으로, `publisher`/`authored_at`/`verification_status`는 §2.8 마이그레이션(HAJA-143/144 필드 누락 보완)으로 `rag_documents`에 추가된 컬럼이다(`rag_target_collection_type`/`rag_doc_verification_status_type` enum 신규). 기존 행의 `target_collection`은 현재 Chroma 컬렉션의 실제 `doc_id` 소속으로 명시적 백필하며 `source_type`으로 추론하지 않는다.

`article`/`clause`/`page`/`chunk_index`/`chunk_hash`/`defect_category`/`severity_ref`는 Postgres에 대응 컬럼이 없다 — 청킹·분류 단계에서 청크 단위로 새로 채워진다.

## 3. Chroma 제약 및 표준화 규칙

- 메타데이터 값은 `str | int | float | bool`만 허용 — 리스트·딕셔너리·`None` 저장 불가.
- **네이밍**: 전부 `snake_case`, 두 컬렉션 공통 키(`doc_id`, `source`, `doc_type`, `page`, `chunk_index`, `chunk_hash`, `embedding_model`, `embedded_at`) 고정.
- **다중값 평탄화**: 청크가 여러 조문에 걸치면 `"제12조,제13조"`처럼 콤마 구분 문자열(파싱은 조회 측에서 `split(",")`).
- **결측값**: 값이 없는 필드는 키 자체를 생성하지 않는다(`None`/빈 문자열 저장 금지).
- **날짜**: `effective_date`, `embedded_at`은 ISO 8601 문자열(`"2026-01-01"`) 고정.
- **Chroma document id**: `{doc_id}_{chunk_index}` — 재임베딩 시 동일 청크는 동일 id로 upsert.

## 4. 필드 정의서 — `regulations` 컬렉션

| 필드명 | 타입 | 필수 | 채워지는 시점 | 용도 |
|---|---|---|---|---|
| doc_id | str | ✅ | 임베딩 시(`rag_documents.id` 복제) | 관리 |
| source | str | ✅ | 임베딩 시(`rag_documents.title` 복제) | 출처 |
| doc_type | str | ✅ | 임베딩 시(`rag_documents.source_type` 복제, `"LAW"`) | 검색/출처 |
| article | str | 조문 있는 경우만 | 청킹 시(분리자 기준 자동 추출, 예: `"제12조"`) | 출처 |
| clause | str | 항 있는 경우만 | 청킹 시(예: `"①"`) | 출처 |
| page | int | PDF 원본만 | 청킹 시 | 출처 |
| effective_date | str (ISO) | LAW만 | 임베딩 시(`rag_documents.effective_date` 복제) | 검색/출처 |
| publisher | str | 있는 경우만 | 임베딩 시(`rag_documents.publisher` 복제, §2.8 신규) | 출처 |
| chunk_index | int | ✅ | 청킹 시 | 관리 |
| chunk_hash | str | ✅ | 청킹 시(SHA256, 재임베딩 변경 감지) | 관리 |
| embedding_model | str | ✅ | 임베딩 시(예: `"BAAI/bge-m3"`) | 관리 |
| embedded_at | str (ISO) | ✅ | 임베딩 시 | 관리 |

## 5. 필드 정의서 — `defect_kb` 컬렉션

| 필드명 | 타입 | 필수 | 채워지는 시점 | 용도 |
|---|---|---|---|---|
| doc_id | str | ✅ | 임베딩 시 | 관리 |
| source | str | ✅ | 임베딩 시 | 출처 |
| doc_type | str | ✅ | 임베딩 시(`rag_documents.source_type` 복제, 예: `"GUIDELINE"`) — 컬렉션 라우팅은 `target_collection`이 전담하므로 필터링/표시용 자유 라벨로만 사용 | 검색/출처 |
| defect_category | str | ✅ | 문서 업로드 시 관리자 태깅(`defects.type` enum 코드값 그대로: `CRACK`/`SPALLING`/`LEAK_EFFLORESCENCE`/`REBAR_EXPOSURE`/`PAINT_DAMAGE`) | 검색 |
| severity_ref | str | 해당하는 경우만 | 문서 업로드 시 관리자 태깅(`defects.grade` enum 코드값, `A`~`E`) | 검색 |
| authored_at | str (ISO) | 있는 경우만 | 임베딩 시(`rag_documents.authored_at` 복제, §2.8 신규) | 출처 |
| verification_status | str | 있는 경우만 | 임베딩 시(`rag_documents.verification_status` 복제, §2.8 신규, `"UNVERIFIED"`/`"VERIFIED"`) | 검색/출처 |
| page | int | PDF 원본만 | 청킹 시 | 출처 |
| chunk_index / chunk_hash / embedding_model / embedded_at | — | ✅ | `regulations`와 동일 | 관리 |

> `article`/`clause`/`effective_date`는 `defect_kb`에 없음 — 법조문 구조가 아니므로 강제하지 않는다.

## 6. `sources` 응답 스키마 (`ai/core/schemas.py`, HAJA-145)

```python
class SourceCitation(BaseModel):
    doc_id: str       # 양의 정수 문자열(^[1-9][0-9]*$)
    title: str       # Chroma metadata `source`를 API 경계에서 `title`로 매핑
    collection: Literal["regulations", "defect_kb"]
    locator: str      # "제12조" / "제12조 ①" / "12페이지" — 렌더링 완료된 표시 문구
    chunk_ref: str     # Chroma document id ({doc_id}_{chunk_index}), chat_message_citations.chunk_ref에 그대로 저장

class RagAnswerData(BaseModel):
    answer: str
    sources: list[SourceCitation]
```

`AIResponse.ok(data=RagAnswerData(...))` 형태로 공통 envelope에 담는다. 검색 결과 0건은 기존 `AIErrorCode.RAG_NO_RESULT`를 그대로 사용한다(신규 에러 코드 불필요).

`chat_message_citations`(Postgres: `message_id`, `document_id`, `chunk_ref`, `locator`, `snippet`)와 필드를 맞췄다 — `SourceCitation.doc_id`는 양의 정수 문자열 검증 후 `int(doc_id)`로 변환해 `document_id`에 저장하고, `chunk_ref`→`chunk_ref`(그대로), `locator`→`locator`, `snippet`→`snippet`에 저장한다. `collection`은 중복 저장하지 않고 이력 복원 시 불변인 `rag_documents.target_collection`을 조인해 `REGULATIONS`→`regulations`, `DEFECT_KB`→`defect_kb`로 변환한다. 문서를 다른 컬렉션으로 재분류할 때는 기존 행을 수정하지 않고 새 `rag_documents` 행을 생성해 재임베딩한다.

## 7. 인용(citation) 렌더링 정책

RAG 체인이 **답변 생성 시점에 1회** `locator`를 렌더링한다 — article/clause 유무에 따라 `"제12조"`/`"제12조 ①"`(있으면) 또는 `"{page}페이지"`(법조문 정보가 없는 지침류)로 조립하고, 결과를 그대로 `chat_message_citations.snippet`에 저장한다. 화면 표시 시점마다 Chroma를 재조회하지 않는다 — `snippet` 컬럼의 설계 의도("Chroma 재조회 없이 UI 노출")와 일치하며, 채팅 이력 로드 시 Chroma 가용성에 의존하지 않는다는 장점이 있다.

## 8. 연관 문서

- `docs/design/db/table_design_v0.4.md` §5.5 (`rag_documents`, `chat_message_citations` 확정 스키마)
- `docs/conventions/AI_개발_컨벤션.md` §6 (RAG 규약 — 컬렉션 네이밍, 청킹 분리자)
- `ai-server/ai/core/chunking.py` (법조문 경계 우선 분리 구현)
- `ai-server/ai/core/vectorstore.py` (Chroma 컬렉션 팩토리 — 온보딩 세션 이후 RAG 코치 구현)
