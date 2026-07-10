## Tech stack per module

- **backend**: Java 17 (toolchain — 전역 CLAUDE.md 컨벤션은 Java 21 명시하지만 이 프로젝트 `build.gradle`은 17로 고정, 임의 변경 금지), Spring Boot 3.3.5, JPA, Security+OAuth2 client, WebSocket, Redis(spring-session-data-redis), springdoc-openapi 2.6.0, PostgreSQL, Lombok. Gradle(gradle wrapper 미확인 — `build.gradle` 루트 `backend/`).
- **ai-server**: Python, FastAPI 0.115.6, pydantic 2.10.4, langchain 0.3.13 + langchain-huggingface 0.1.2, chromadb 0.5.23(RAG 벡터스토어), sentence-transformers, redis. **requirements.txt 버전 고정 — 임의 수정 금지, AI-LLM 코치 승인 필요**(`AI_개발_컨벤션.md §7`).
- **ai-dl**: Python, MLflow(실험 추적 헬퍼 `tracking.py`) — Colab 세션 유실 대비 가이드 있음(`tracking.py` 상세 주석 참고).
- **frontend**: React 18.3 + TypeScript 5.7 + Vite 6, react-router-dom 6, @tanstack/react-query 5, zustand 5, axios, MSW(mock service worker, `src/mocks/`), vitest 4 + jsdom.

HF(HuggingFace) Serverless Inference는 langchain 표준 `with_structured_output()`의 `tool_choice="any"` 강제를 지원 안 함(`langchain-ai/langchain#29569`, upstream not planned) → `ai-server/ai/core/llm_client.py`의 `_StructuredLLM`이 `PydanticOutputParser` 기반으로 우회 구현. 호출부는 동일 시그니처(`get_llm().with_structured_output(Schema).invoke(...)`) 그대로 사용.
