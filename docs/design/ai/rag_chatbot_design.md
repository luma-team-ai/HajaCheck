# 고객지원 RAG 챗봇 설계 — RetrievalQA·출처표시 규약 (design-03-20)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-19 · 이전 버전 `archive/`

> 담당: 이은석(주, 직접 구현) / 김승현(챕터7 RAG 코치) · 관련 WBS: `design-03-20`(설계)·`dev-08-01`(구현) · 관련 FR: FR-6
> 관련 이슈: GitHub #386 · Jira HAJA-122(에픽 HAJA-103 [AI 설계])
> 상태: **설계 진행(초안)** — 출처표시 스키마는 코드 확정분 채택, 파이프라인·표시·엔드포인트 규약 정리
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

## 3. 파이프라인 (RetrievalQA)

```
질의(query) → retriever(regulations[, defect_kb]) → 상위 k 청크 → context 조립
            → get_llm().with_structured_output(RagAnswerData) → 답변 + sources
```

- `defect_explain_chain.py` 패턴 동일: `_build_prompt()`(= `_system_base.md` + `prompts/rag_chat.md`) → `get_llm()` → structured output. 직접 `HuggingFaceEndpoint` 생성 금지(컨벤션 §2).
- Chroma 접근은 `ai/core/vectorstore.py` 팩토리(`get_vectorstore(collection)`)만 경유(컨벤션 §6). 직접 클라이언트 생성 금지.
- **답변은 structured output으로만 수신**(자유 텍스트 파싱 금지, 컨벤션 §4). `sources`는 LLM 창작이 아니라 **retriever가 반환한 청크 메타데이터에서 결정적으로 구성**한다(§4).

## 4. 검색·출처 구성 규약

### 4.1 컬렉션 선택
- 법규 Q&A 기본은 `regulations`. 하자 지식 질의가 섞이면 `defect_kb`도 포함(라우팅 기준·기본값은 dev-08-01 구현 시 확정 — 우선 `regulations` 단독으로 착수).
- top-k·score threshold·MMR 여부는 구현 시 튜닝값으로 확정(초안: k=4).

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

## 8. 공통 모듈 의존·선행 블로커

**의존(신규 모듈 불필요):** `vectorstore.py`(get_vectorstore)·`embeddings.py`·`chunking.py`(법조문 분리자)·`llm_client.get_llm()`·`prompts/`.

**⚠️ 선행 블로커(2026-07-19 확인):**
- `ai/core/vectorstore.py`가 현재 **`NotImplementedError` 스텁** → regulations 컬렉션 미생성. 본 챗봇(dev-08-01, 7/15~20 예정)과 report_chain(dev-07-01)이 **동일 의존**.
- regulations 적재는 `dev-11-05`(RAG 문서 관리, 김승현, **7/20~23** 예정)에 달림. **vectorstore 팩토리 자체 구현 주체·시점이 WBS에 미명시** → 김승현·오영석(공통 모듈)과 **`vectorstore 구현 → 임베딩 배치`를 dev-08-01·dev-07-01보다 앞당기는 순서 합의 필요**.
- 컨벤션 §6대로 검색 0건 시 "관련 근거 없음"으로 처리되므로 챗봇 골격 구현은 막히지 않으나, **실제 근거 인용은 regulations 적재(7/20 이후)부터** 가능.

## 9. 남은 확정 사항 (dev-08-01 이관)

- [ ] `prompts/rag_chat.md` 실파일 작성(골자: 질의+context→근거 기반 답변, 컨벤션 §6 출처 필수·0건 임의생성 금지)
- [ ] retrieval 파라미터 확정(컬렉션 라우팅 기본값·top-k·score threshold·MMR)
- [ ] `/ai/rag-chat` 요청 스키마 + `/api/chat-sessions` 연동(세션·이력)
- [ ] grounding 적용 여부 검토(답변-근거 정합, 공통 `ai.core.grounding` 재사용 가능성)
- [ ] report `legal_basis` 구조화 재사용 협의 결론 반영(§6, 김관영·유병현)
- [ ] **선행**: vectorstore 팩토리 구현·regulations 적재 순서 합의(§8)

→ 확정되는 대로 본 문서 갱신 + `ai-server/ai/chains/rag_chat_chain.py` 구현(dev-08-01).
