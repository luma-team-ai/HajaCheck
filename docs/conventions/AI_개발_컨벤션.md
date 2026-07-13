# hajaCheck — AI 개발 컨벤션

> 대상: 전체 팀원 (각 메뉴 담당이 자기 메뉴의 AI 기능을 직접 구현하는 체제)
> 관리: AI-LLM 코치 (공통 모듈·예시 체인 제공, 코드 리뷰 시 준수 점검)
> 배포: 7/15(수) AI 온보딩 세션 / 점검: 7/17(금) 코드 리뷰 ①
> 연관 문서: PRD_hajaCheck.md §6.2

---

## 0. 원칙

- 모든 AI 기능은 **공통 기반(llm_client, prompts/, 공통 스키마) 위에서만** 개발한다
- LLM 호출 코드를 각자 새로 만들지 않는다 — 만들고 싶은 게 있으면 공통 모듈에 PR
- 이 문서와 다른 방식이 필요하면 AI-LLM 코치와 협의 후 문서를 먼저 수정한다

---

## 1. 디렉토리 구조 (FastAPI)

```
ai-server/
├─ ai/
│  ├─ core/
│  │  ├─ llm_client.py      # 공통 LLM 클라이언트 (★유일한 LLM 호출 지점)
│  │  ├─ embeddings.py      # 임베딩 모델 설정 (ko-sbert/BGE-m3)
│  │  ├─ vectorstore.py     # Chroma PersistentClient 설정
│  │  └─ schemas.py         # 공통 요청/응답 Pydantic 모델
│  ├─ chains/               # 메뉴별 체인 (담당자별 파일)
│  │  ├─ report_chain.py    # 보고서 생성 (로그인/보고서 담당)
│  │  ├─ rag_chat_chain.py  # RAG 챗봇 (고객지원 담당)
│  │  ├─ briefing_chain.py  # 대시보드 브리핑 (대시보드 담당)
│  │  ├─ defect_explain_chain.py  # 하자 설명 (점검B 담당)
│  │  └─ nl_search_chain.py # 자연어 검색 (하자 관리 담당)
│  └─ prompts/              # 프롬프트 파일 (하드코딩 금지)
│     ├─ _system_base.md    # 공통 시스템 프롬프트
│     ├─ report_summary.md
│     ├─ defect_explain.md
│     └─ ...
├─ routers/
│  └─ ai_router.py          # /ai/** 엔드포인트
└─ requirements.txt         # 버전 고정 (수정은 코치 승인)
```

---

## 2. 공통 LLM 클라이언트 (`ai/core/llm_client.py`)

- 모델명, HF 엔드포인트, API 토큰, 타임아웃, 재시도(2회), 토큰 사용량 로깅을 **한 곳에서** 관리
- 체인에서는 `get_llm()`만 호출한다. `HuggingFaceEndpoint`를 직접 생성하지 않는다
- 모델 교체(HF Serverless ↔ Ollama)는 이 파일 + 환경변수만 수정하면 되도록 유지 — `LLM_PROVIDER=hf|ollama`(기본 `hf`), Ollama 사용 시 `OLLAMA_MODEL`(기본 `qwen3:8b`)·`OLLAMA_BASE_URL`(기본 `http://localhost:11434`) 추가 설정(`.env.example` 참고, HAJA-114)

```python
# 사용 예 — 모든 체인의 시작점
from ai.core.llm_client import get_llm

llm = get_llm()          # 기본: Qwen3-8B via HF Serverless API
llm = get_llm(temperature=0.2)  # 파라미터만 오버라이드 가능
```

- 토큰 사용량은 클라이언트가 자동으로 Redis에 집계 (`ai:usage:{date}` — 관리자 모니터링 연동)
- **응답 캐시 내장**: 캐시 키 `ai:cache:{provider}:{model}:{temperature}:{hash}` Redis 캐시를 클라이언트가 자동 적용 — 개발 중 동일 질의 반복으로 인한 크레딧 소진 방지. provider/모델/temperature별로 네임스페이스 분리(HAJA-114 P1 픽스 — provider 전환 시 이전 provider 캐시가 잘못 재사용되는 정합성 버그 방지). 캐시 우회가 필요하면 `get_llm(cache=False)`

## 3. 프롬프트 규약

- 위치: `ai/prompts/` — **코드에 프롬프트 문자열 하드코딩 금지**
- 파일명: `{기능}_{동작}.md` 소문자 스네이크 (예: `report_summary.md`, `nl_search_convert.md`)
- 모든 프롬프트는 `_system_base.md`(공통 시스템 프롬프트: 역할 정의, 한국어 응답, 근거 없는 내용 생성 금지)를 상단에 결합해 사용
- 변수는 `{변수명}` 표기, 파일 상단 주석에 입력 변수 목록 명시
- 프롬프트 수정도 코드와 동일하게 PR 리뷰 대상

## 4. 출력 형식 — Structured Output 필수

- LLM 응답은 **JSON 스키마 지정(Pydantic 모델 + `with_structured_output`)** 으로만 받는다
- 자유 텍스트를 받아서 직접 파싱(정규식, split 등)하는 코드는 리뷰에서 반려
- 챗봇처럼 자유 서술이 필요한 경우에도 `{ "answer": str, "sources": list[str] }` 형태의 최소 스키마를 적용

```python
class DefectExplain(BaseModel):
    cause: str          # 추정 원인
    risk: str           # 방치 시 위험
    action: str         # 권고 조치

result = get_llm().with_structured_output(DefectExplain).invoke(prompt)
```
(실제 예시: `ai/chains/defect_explain_chain.py`)

> ⚠️ **트러블슈팅 — `400 INVALID_TOOL_CHOICE`가 나면**: HF Serverless Inference는 langchain 표준 구현이 강제하는 `tool_choice="any"`를 지원하지 않아, `.with_structured_output()`을 직접 새로 구현/변형하면 이 에러가 재현될 수 있다(`langchain-ai/langchain#29569`, upstream "not planned" — 라이브러리 버전을 올려도 안 고쳐짐). `get_llm().with_structured_output(schema)`는 이미 프롬프트에 JSON 스키마를 지시하고 `PydanticOutputParser`로 직접 파싱하는 우회 방식(`ai/core/llm_client.py`의 `_StructuredLLM`)이라 **이 함수를 그대로 쓰면 이 문제를 안 만난다** — 같은 문제를 각자 다시 겪지 말고 반드시 `get_llm()` 경유로만 구조화 출력을 받을 것.

## 5. 엔드포인트·에러 규약

**네이밍**: `/ai/{기능}` — `/ai/report`, `/ai/chat`, `/ai/briefing`, `/ai/defect-explain`, `/ai/nl-search`

**요청/응답 공통 envelope** (`ai/core/schemas.py`의 `AIResponse` 사용):

```json
// 성공
{ "success": true, "data": { ... }, "usage": { "tokens": 1234 } }
// 실패
{ "success": false, "error": { "code": "LLM_TIMEOUT", "message": "..." } }
```

**에러 코드**: `LLM_TIMEOUT`, `LLM_RATE_LIMIT`, `LLM_INVALID_OUTPUT`(스키마 파싱 실패), `RAG_NO_RESULT`, `VALIDATION_ERROR`(비-LLM 코드 경로의 입력·대조 검증 실패 — grounding-check 등, #122)

**프론트 폴백 표준**: AI 기능 실패 시 화면이 깨지지 않아야 한다 — 표준 문구("AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.") + 재시도 버튼. AI 실패가 비-AI 기능(목록 조회 등)을 막으면 안 됨

**장시간 작업**(보고서 생성 등)은 동기 응답 금지 — 기존 비동기 잡 패턴(잡 ID → 폴링) 사용

## 6. RAG 규약 (해당 담당만)

- Chroma 접근은 `ai/core/vectorstore.py`의 팩토리만 사용, 컬렉션 네이밍: `regulations`(법규·지침), `defect_kb`(하자 지식)
- 청킹: 법규 문서는 조문 경계 우선 분리 — `RecursiveCharacterTextSplitter(separators=["\n제", "\n①", "\n\n", "\n"])`, 그 외 일반 문서는 500자/오버랩 50자 기준(변경 시 코치 협의)
- 재임베딩(관리자 기능)은 명시적 배치 잡으로만 실행 — 서비스 중 챗봇 질의와 쓰기 락 충돌 방지
- 답변에는 반드시 출처(`sources`) 포함 — 문서명 + 조문/페이지
- 검색 결과 0건 시 "관련 근거를 찾지 못했습니다"로 응답하고 임의 생성 금지

## 7. 버전 고정

- `requirements.txt`의 `langchain`, `langchain-huggingface`, `chromadb`, `sentence-transformers` 버전은 고정하며 **수정은 AI-LLM 코치 승인 필수**
- 버전 결정: 온보딩 세션(7/15) 전까지 코치가 확정·공지
- 로컬 개발 시 반드시 가상환경(venv) + requirements 설치로 통일

## 8. 체인 개발 절차 (각 메뉴 담당)

1. 코치가 제공한 예시 체인(`chains/_example_chain.py`) 복제
2. 프롬프트 파일 작성 (`prompts/`)
3. 출력 Pydantic 스키마 정의
4. 체인 구현 → `ai_router.py`에 엔드포인트 등록 (공통 envelope 적용)
5. 자체 테스트: 정상 케이스 + 타임아웃 + 스키마 파싱 실패 케이스
6. PR 생성 → 부담당 + AI-LLM 코치 리뷰

## 9. 리뷰 체크리스트 (리뷰어용)

- [ ] `get_llm()` 사용 (직접 클라이언트 생성 없음)
- [ ] 프롬프트가 `prompts/` 파일로 분리됨
- [ ] Structured output 적용 (자유 텍스트 파싱 없음)
- [ ] 공통 envelope + 에러 코드 사용
- [ ] 프론트 폴백 처리 확인
- [ ] (RAG) 출처 포함, 0건 처리
- [ ] requirements 변경 없음 (있다면 코치 승인 여부)
