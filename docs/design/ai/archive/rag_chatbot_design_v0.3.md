# 고객지원 RAG 챗봇 설계 — RetrievalQA·출처표시 규약 (design-03-20)

> **문서 버전:** v0.3 · **최종 수정:** 2026-08-04 · 이전 버전 `archive/`

> 담당: 이은석(주, 직접 구현) / 김승현(챕터7 RAG 코치) · 관련 WBS: `design-03-20`(설계)·`dev-08-01`(구현) · 관련 FR: FR-6
> 관련 이슈: GitHub #386(설계) · #19(구현, HAJA-28) · Jira HAJA-122(설계 에픽 HAJA-103 [AI 설계])·HAJA-32(구현)
> 상태: **파이프라인 구현 완료 + 검색·캐시 리모델링 완료**(HAJA-28 → #1410/#1462) — v0.3에서 **하이브리드 검색(§3.1)·시맨틱 캐시(§3.2)** 반영
> 상태(구): **파이프라인 구현 완료(HAJA-28)** — Chroma 검색·RetrievalQA·citation·Redis 캐시는 `ai-server/ai/chains/rag_chat_chain.py`로 구현됨. Spring 프록시·세션 연동(§7·§9)은 후속 이슈로 분리.
> 컨벤션 근거: `docs/conventions/AI_개발_컨벤션.md` §3(프롬프트)·§4(structured output)·§6(RAG 규약)·§8(체인 절차)
> 연관 문서: `docs/design/ai/rag_chroma_schema.md`(Chroma 메타데이터·SourceCitation SoT), `docs/design/ai/report-chain-design.md` §6.4(report `legal_basis` 정합), `docs/api-contract/contract.md`(`/ai/rag-chat`)
> 참조 구현: `ai-server/ai/core/schemas.py`(`SourceCitation`/`RagAnswerData`/`AIResponse`, HAJA-145), `ai-server/ai/chains/defect_explain_chain.py`(체인 패턴), `ai-server/ai/core/chunking.py`(법조문 분리자)

---

## 1. 목적·범위

고객지원 메뉴의 RAG 챗봇(법규 Q&A 전담)이 사용자 질의를 받아 Chroma에서 근거 청크를 검색하고, **출처를 반드시 표기하여** 답변한다(KPI: 출처 표기율 100%). 본 설계는 ① 검색·생성 파이프라인(RetrievalQA) ② 출처(`sources`) 구성·표시 규약 ③ 엔드포인트 계약을 확정한다.

**본 문서가 정의하지 않는 것(SoT 위임):**
- 출처 **스키마 정의**(`SourceCitation` 필드·타입) → `rag_chroma_schema.md` §6 + `schemas.py`가 SoT. 여기서 재정의하지 않는다.
- Chroma 컬렉션 메타데이터·청킹 분리자 → `rag_chroma_schema.md` §3~5, `AI_개발_컨벤션.md` §6.

## 2. SoT 경계 — 출처표시 스키마는 재정의 금지

출처표시 스키마는 **이미 코드로 확정**돼 있으므로(HAJA-145, `ai/core/schemas.py`) 본 챗봇은 이를 **그대로 채택**한다:

```python
class SourceCitation(BaseModel):
    doc_id: str          # ^[1-9][0-9]*$ (양의 정수 문자열)
    title: str           # Chroma metadata `source` → API 경계에서 title
    collection: Literal["regulations", "defect_kb"]
    locator: str         # 렌더링 완료 표시 문구 ("제12조" / "제12조 ①" / "12페이지")
    chunk_ref: str       # Chroma document id ({doc_id}_{chunk_index})

class RagAnswerData(BaseModel):
    answer: str
    sources: list[SourceCitation]
```

- 응답은 공통 envelope `AIResponse.ok(data=RagAnswerData(...))`로 감싼다.
- `locator` **렌더링 정책은 `rag_chroma_schema.md` §7을 그대로 준수**: 답변 생성 시점 **1회** 렌더링(조문 있으면 `"제12조"`/`"제12조 ①"`, 법조문 정보 없는 지침류는 `"{page}페이지"`), 화면 표시 시 Chroma 재조회 금지.

## 3. 파이프라인 (LangGraph)

```
질의(query)
  → ① cache_check(Redis 완전일치)        ──히트─→ 응답 반환
  → ② semantic_cache_check(임베딩 유사도) ──히트─→ 응답 반환
  → ③ retrieve = hybrid_search(벡터 top-10 + BM25 top-10 → RRF) → 상위 k 청크
  → ④ context 조립 → get_llm().with_structured_output(RagAnswerData) → 답변 + sources
  → ⑤ cache_write(Redis) → semantic_cache_write(Chroma) → END
```

구현 = `ai/chains/rag_chat_chain.py`(LangGraph StateGraph). 근거 없음(`grounded=false`) 경로는 `no_result` 노드로 빠져 **양쪽 캐시에 저장되지 않는다**.

### 3.1 검색 = 벡터 + BM25 하이브리드 (RRF) — #1410, 2026-08-03

법규 질의는 **의미형**("정기점검 주기는?")과 **키워드형**("제12조", "별표 3")이 섞인다. 후자는 숫자·기호라 임베딩 공간에서 변별력이 낮아 벡터 단독으로는 top-10에서 완전히 놓치는 사례가 있었다.

- `ai/core/hybrid_search.py` — 벡터 top-10 + BM25 top-10을 **RRF(Reciprocal Rank Fusion)** 로 결합. 반환 타입은 `list[Document]` 그대로라 후속 노드는 무수정.
- `ai/core/bm25_index.py` — `regulations` 전용 **인메모리 BM25 캐시**(Chroma 파생). Chroma가 유일한 SoT이고, `rag_ingest`의 ingest/delete 3경로에서 `invalidate()`만 호출해 정합성을 유지한다(캐시를 직접 add/remove 하지 않음).
- **토큰화 = kiwipiepy 형태소 분석**(체언 N\* · 용언 V\* · 외국어 SL · 숫자 SN만, 조사·어미 제외). 정규식(공백·음절) 토큰화는 한국어 조사·어미가 붙은 채 토큰이 갈려(`안전점검을` ≠ `안전점검`) **BM25가 22개 질의 중 14개에서 실패**했고, 그 부실한 결과가 RRF로 섞여 벡터 순위까지 끌어내렸다(실측 회귀).
- **RRF 상수 `k=1`**(관용값 60 아님) — `1/(60+rank)`는 rank 1과 10의 점수 차가 거의 없어 **두 리스트에 모두 뜬 오답**이 **한 리스트 1위 정답**을 눌렀다.
- 실측(질의 22개): MRR 0.737 → **0.767**, nDCG@10 0.791 → **0.813**, Recall 동률. 평가 하네스는 `ai/eval/`(`python -m ai.eval.run_eval`).

### 3.2 응답 재사용 = 2계층 캐시 — #1462, 2026-08-04

| 계층 | 판정 기준 | 비고 |
|---|---|---|
| ① 완전일치 | 질문 SHA-256 해시 → Redis(TTL) | 같은 문장 반복 호출 차단 |
| ② 시맨틱 | 질문 임베딩(bge-m3) → `semantic_cache` 컬렉션 top-1, `distance ≤ 1 − threshold` | `hnsw:space=cosine`이라 **distance = 1 − 유사도**. threshold 기본 **0.95**(env `SEMANTIC_CACHE_THRESHOLD`) |

- 저장 형태: 질문 원문 = `page_content`, 답변 직렬화 = `metadata["answer_json"]`.
- 캐시 파싱 실패 시 **miss로 폴백**(캐시 오염이 요청을 죽이지 않는다).
- ⚠️ **미해결**: ② 계층은 TTL·무효화가 없어 법규 개정 재색인 후에도 옛 답변이 히트할 수 있다(**#1478**). threshold 실측 튜닝("제12조" vs "제13조" 오탐)도 후속.

- `defect_explain_chain.py` 패턴 동일: `_build_prompt()`(= `_system_base.md` + `prompts/rag_chat.md`) → `get_llm()` → structured output. 직접 `HuggingFaceEndpoint` 생성 금지(컨벤션 §2).
- Chroma 접근은 `ai/core/vectorstore.py` 팩토리(`get_vectorstore(collection)`)만 경유(컨벤션 §6). 직접 클라이언트 생성 금지.
- **답변은 structured output으로만 수신**(자유 텍스트 파싱 금지, 컨벤션 §4). `sources`는 LLM 창작이 아니라 **retriever가 반환한 청크 메타데이터에서 결정적으로 구성**한다(§4).

## 4. 검색·출처 구성 규약

### 4.1 컬렉션 선택
- 법규 Q&A 기본은 `regulations`. 하자 지식 질의가 섞이면 `defect_kb`도 포함(라우팅 기준·기본값은 dev-08-01 구현 시 확정 — 우선 `regulations` 단독으로 착수).
- 최종 파라미터: 벡터 top-10 + BM25 top-10 → RRF(k=1) → **반환 top-k = 4**(`hybrid_search.DEFAULT_TOP_K`). MMR 미사용.

### 4.2 청크 → `SourceCitation` 매핑 (결정적)
retriever가 준 청크 metadata를 아래로 매핑한다(`rag_chroma_schema.md` §4~6 필드명 기준):

| SourceCitation | 소스 | 규칙 |
|---|---|---|
| `doc_id` | metadata `doc_id` | 그대로 |
| `title` | metadata `source` | API 경계에서 `title`로 매핑 |
| `collection` | 검색한 컬렉션명 | `"regulations"` / `"defect_kb"` |
| `locator` | `article`(+`clause`) 또는 `page` | §7 정책으로 렌더링(있으면 조문, 없으면 페이지) |
| `chunk_ref` | Chroma document id | `{doc_id}_{chunk_index}` 그대로 |

### 4.3 검색 0건 처리
- 컨벤션 §6·§5 준수: **임의 생성 금지**. `AIErrorCode.RAG_NO_RESULT`를 그대로 사용(신규 에러코드 불필요)하고, 답변 문구는 "관련 근거를 찾지 못했습니다".
- **확정(#431): envelope는 `success:false`.** `AIResponse.fail(AIErrorCode.RAG_NO_RESULT, "관련 근거를 찾지 못했습니다")` → `{success: false, error: {code: "RAG_NO_RESULT", message: "..."}}`. (`AIResponse.ok()`는 에러코드를 실을 방법이 없어 `success:true`+빈 `sources` 형태는 구조적으로 불가능 — `.fail()` 경로만 유효.)
- Spring 프록시(`AiProxyService`)는 기존 defect-explain/report/briefing 프록시와 동일 패턴으로 `error`를 그대로 `ApiResponse.fail(error.code(), error.message())`에 패스스루한다. **신규 매핑 불필요.**

## 5. 화면 표시·이력 저장 규약 (FE 협업)

- 답변 하단에 `sources`를 **출처 칩/각주** 형태로 노출(`title` + `locator`). 표시 문구는 `locator`를 그대로 사용(FE 재조립 금지 — Chroma 재조회 불필요).
- 채팅 이력 저장: `chat_message_citations`(`message_id`, `document_id`, `chunk_ref`, `locator`, `snippet`)에 매핑(`rag_chroma_schema.md` §6). `doc_id`는 검증 후 `int()`로 `document_id`에 저장, `collection`은 `rag_documents.target_collection` 조인으로 복원(중복 저장 안 함).

## 6. report_chain `legal_basis`와의 정합 (Q2 대응)

`report-chain-design.md` §6.4의 `RecommendationItem.legal_basis: str`는 현재 **"문서명+조문"을 한 문자열로 합친** 형태다. 본 챗봇의 출처 규약(`SourceCitation` 구조화)과 어긋난다.

- **권고**: 보고서 `recommendation`도 근거를 `SourceCitation`(또는 그 부분집합 `title`+`locator`)으로 **구조화 재사용**해 citation 표현을 프로젝트 전역에서 일원화한다. 최소한 `legal_basis` 렌더링을 §7 `locator` 정책과 동일 규칙으로 맞춘다.
- 이는 계약(`schemas.py`/`openapi.yaml`)에 영향 → 김관영(FR-5)·유병현(챕터2 계약)과 합의 후 확정. 본 문서는 챗봇 측 규약만 확정하고, 보고서 측 채택은 협의 사항으로 남긴다.

## 7. 엔드포인트 계약

- `POST /ai/rag-chat` (FR-6, `contract.md` "다음 추가 예정"). 요청: `{query: str, session_id?: ...}`(세부는 dev-08-01·`/api/chat-sessions`와 함께 확정). 응답: `AIResponse.ok(RagAnswerData)`.
- **외부 직접 노출 금지 — Spring 강제 경유**(HAJA-188/190/191 보안 반영): 공개 경로는 Spring Boot(`/api/...`)가 인증·플랜(`has_ai_addon` 등) 검사 후 내부 `/ai/rag-chat` 호출, FastAPI는 `X-Internal-Key` 검증. nginx 공개 `/ai/` 미노출.

## 8. 공통 모듈 의존 (2026-07-22 갱신 — 구현 완료, 블로커 해소)

**의존(신규 모듈 불필요):** `vectorstore.py`(get_vectorstore)·`embeddings.py`·`chunking.py`(법조문 분리자)·`llm_client.get_llm()`/`get_redis_client()`·`prompts/rag_chat.md`.

`ai/core/vectorstore.py`는 더 이상 스텁이 아니다 — `get_vectorstore(collection)` 팩토리가 완전히 구현돼 있고(§7 이전 버전이 우려했던 `NotImplementedError` 블로커는 해소됨), `rag_chat_chain.py`는 이를 그대로 경유해 `regulations` 컬렉션을 검색한다. 다만 **regulations 컬렉션의 실제 데이터 적재**(문서 등록·임베딩 배치, dev-11-05 담당)는 이 설계·구현 범위 밖이며 별도로 진행된다 — 적재 전에는 모든 질의가 검색 0건(`RAG_NO_RESULT`)으로 응답하는 것이 정상 동작이다(§4.3 그대로 유효).

## 9. 확정 사항 (2026-07-22, HAJA-28 구현 완료)

- [x] `prompts/rag_chat.md` 실파일 작성(`ai-server/ai/prompts/rag_chat.md`) — `{context}`+`{question_text}`(wrap_untrusted) 변수, 근거 없으면 "관련 근거를 찾지 못했습니다" 유도, LLM은 answer만 생성.
- [x] retrieval 파라미터 확정: 컬렉션은 `regulations` 단독 착수(§4.1 그대로), top-k=4(초안값 그대로 채택), score threshold·MMR·`defect_kb` 병행 라우팅은 이번 범위 밖(필요 시 후속).
- [x] Redis 캐시 방식 확정: 키 `ai:cache:rag-chat:{sha256(question)[:16]}`, TTL은 `llm_client.CACHE_TTL_SECONDS`(1일) 재사용, 검색 0건은 캐시 저장 안 함.
- [ ] `/ai/rag-chat` 요청 스키마의 `session_id`는 필드만 선점(현재 미사용) — Spring `/api/ai/rag-chat` 프록시(소유·`session_type='RAG'` 검증) + `/api/chat-sessions` 연동(세션·이력)은 **후속 이슈로 분리**(`/api/chat-sessions` 자체 미구현).
- [ ] `chat_message_citations` 실사용(대화 이력 영속화, LangChain Memory)도 후속 이슈로 분리.
- [ ] grounding 적용 여부 검토(답변-근거 정합, 공통 `ai.core.grounding` 재사용 가능성) — 미착수.
- [ ] report `legal_basis` 구조화 재사용 협의 결론 반영(§6, 김관영·유병현) — 미착수.

구현: `ai-server/ai/chains/rag_chat_chain.py`(GitHub #19, HAJA-28) · 계약: `docs/api-contract/contract.md`("POST /ai/rag-chat") · 테스트: `ai-server/tests/test_rag_chat_chain.py`.

---

## 변경 이력
- **v0.3 (2026-08-04)**: §3 파이프라인을 LangGraph 현행으로 교체 — **하이브리드 검색(벡터+BM25 RRF, kiwipiepy 토큰화, #1410)**·**2계층 시맨틱 캐시(#1462)** 신설, 최종 파라미터 확정. 벡터 단독 시절 설명이 남아 있던 것을 정정(2026-08-04 공개문서 stale 감사).
- v0.2 (2026-07-22): HAJA-28 구현 완료 반영.
