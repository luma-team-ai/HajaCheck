## ai-server (FastAPI) — 세부

- 라우터: `routers/ai_router.py` — 모든 `/ai/**` 응답은 공통 `AIResponse` envelope(`{success, data, usage, error}`), 에러코드는 `AIErrorCode`(`LLM_TIMEOUT`/`LLM_RATE_LIMIT`/`LLM_INVALID_OUTPUT`/`RAG_NO_RESULT`).
- LLM 클라이언트: `ai/core/llm_client.py` — HF structured output 우회 구현(`_StructuredLLM`, 세부는 `mem:tech_stack` 참고). 신규 체인 작성 시 `get_llm().with_structured_output(Schema).invoke(...)` 패턴 그대로 재사용.
- 체인 예시: `ai/chains/defect_explain_chain.py`(구현됨) · `ai/chains/_example_chain.py`(템플릿).
- RAG 인프라: `ai/core/{embeddings,vectorstore,chunking}.py` — chromadb 기반, 아직 `/ai/rag-chat`(FR-6) 엔드포인트로는 노출 안 됨.
- requirements.txt 버전 고정 — 임의 변경 금지(`mem:tech_stack` 참고).
- 코드 컨벤션 SOT: `docs/conventions/AI_개발_컨벤션.md`.
