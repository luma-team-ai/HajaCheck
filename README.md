# hajaCheck — AI 기반 시설물 외관 하자 점검 플랫폼

이미지/영상을 업로드하면 AI(YOLOv8-seg)가 균열·박리·누수 등 하자를 자동 탐지하고,
LLM(LangChain + RAG)이 점검 보고서 초안을 자동 생성하는 시설물 안전점검 지원 플랫폼.

- 기간: 2026-07-09 ~ 2026-08-07 (4주)
- 팀: 8명 (메뉴 담당제 — 전원 화면+API+AI 연동 직접 구현)

## 구성

| 디렉토리 | 스택 | 설명 |
|---|---|---|
| `backend/` | JDK 17 · Spring Boot 3.x · Gradle | 모듈러 모놀리스 (auth / core / counsel / admin / global) |
| `ai-server/` | Python 3.11 · FastAPI · LangChain | YOLO 추론 + LLM 체인 + Chroma RAG (내부 네트워크 전용) |
| `frontend/` | React 18 · Vite · TypeScript | feature 기반 구조 (메뉴 담당제와 1:1) |
| `docs/` | — | PRD · 컨벤션 문서 · handoff |

## 아키텍처

```
[React SPA] → [Nginx 80/443] → [Spring Boot :8080] → [FastAPI :8000 (내부)]
                                     ├ PostgreSQL          ├ YOLOv8-seg (ONNX)
                                     └ Redis (세션·잡·캐시)  ├ LangChain + Qwen3-8B (HF API)
                                                            └ Chroma (임베디드)
```

## 시작하기

```bash
# 로컬 인프라 (예비용 — 기본은 공용 개발 DB 접속)
docker compose up -d

# backend
cd backend && ./gradlew bootRun

# ai-server
cd ai-server && uv venv && uv pip install -r requirements.txt && uvicorn main:app --port 8000

# frontend
cd frontend && npm install && npm run dev
```

## 컨벤션 문서 (필독)

- `docs/PRD_hajaCheck.md` — 요구사항·아키텍처·일정
- `docs/SpringBoot_코드_컨벤션.md` — 백엔드 규약
- `docs/React_코드_컨벤션.md` — 프론트 규약
- `docs/AI_개발_컨벤션.md` — AI 체인 개발 규약

## Git 규칙

- 브랜치: `main`(배포) ← `develop`(통합) ← `feature/{도메인}-{작업}`
- 커밋: `feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `chore:` + 한글 요약
- main·develop 직접 푸시 금지 — PR + 부담당 승인 + CI 통과 필수
