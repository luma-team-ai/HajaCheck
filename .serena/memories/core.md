## HajaCheck — 시설물 안전점검 SaaS (모노레포)

**모듈**: `backend`(Spring Boot, Java) · `ai-server`(FastAPI, Python) · `ai-dl`(딥러닝 학습/트래킹, Python) · `frontend`(React, TS)
각 모듈에 자체 `CLAUDE.md`가 있고 `docs/conventions/{React,SpringBoot,AI}_개발_컨벤션.md`를 가리킴 — 모듈 작업 전 반드시 확인.

**Contract-First**: API 스펙 SOT는 `docs/openapi.yaml`(요약본 `docs/contract.md`). 신규 엔드포인트는 계약 문서 갱신이 선행/동반되어야 함.

**PR/브랜치 흐름**: 기능 PR base=`dev` (main 아님). PR머신이 자동 검수·머지. `dev→main` 승격은 사람 승인 필요(hold_promote). 브랜치명 `{역할}/{이슈}-{내용}` (예: `frontend/28-map-view`).

**진행 상황 스냅샷** (변하는 정보, 최신 확인은 `git log`/`docs/STATUS.md` 참조): `mem:progress`

**모듈별 세부**: `mem:backend/core` · `mem:frontend/core` · `mem:ai-server/core` · `mem:ai-dl/core`
공통: `mem:tech_stack` · `mem:suggested_commands` · `mem:conventions` · `mem:task_completion`
