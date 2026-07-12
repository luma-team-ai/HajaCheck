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

> 운영 배포는 **Docker Compose(공유 호스트 oci-arm1)** 기반 — 앱만 컨테이너로 격리, 공유 nginx·PostgreSQL·Redis 재사용. 상세: `docs/prd/PRD_hajaCheck_v0.41.md §6.1`.

## 시작하기

로컬 개발은 두 방식 중 선택.

**① 전체 스택을 Docker로** (override 자동 적용 · 핫리로드)
```bash
docker compose up --build   # nginx·spring·fastapi·postgres·redis 전체 기동
```

**② 서비스 개별 실행 + 공용 개발 DB(SSH 터널)**
```bash
cd backend  && ./gradlew bootRun
cd ai-server && uv venv && uv pip install -r requirements.txt && uvicorn main:app --port 8000
cd frontend && npm install && npm run dev
```

> ⚠️ `docker-compose.arm1.yml`은 **운영 서버(공유 호스트) 전용** — 로컬에서 실행 금지.

## 컨벤션 문서 (필독)

- `docs/prd/PRD_hajaCheck_v0.41.md` — 요구사항·아키텍처·일정(배포 §6.1)
- `docs/conventions/SpringBoot_코드_컨벤션.md` — 백엔드 규약
- `docs/conventions/React_코드_컨벤션.md` — 프론트 규약
- `docs/conventions/AI_개발_컨벤션.md` — AI 체인 개발 규약

## Git 규칙

- 브랜치: `main`(운영) ← `dev`(통합) ← `feature/{도메인}-{작업}`
- 커밋: `feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `chore:` + 한글 요약
- main·dev **직접 푸시 금지(브랜치 보호 적용)** — PR + CI 통과 필수. `dev` PR은 PR머신이 자동 검수·머지, `main`은 승격(운영자 승인) 시 CD 자동배포
