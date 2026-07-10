# hajaCheck — STATUS

> 마지막 갱신: 2026-07-10

## 인프라

| 항목 | 상태 | 비고 |
|---|---|---|
| GitHub 레포 | ✅ | luma-team-ai/HajaCheck (main + dev) |
| OCI A1 VM | ✅ 확보 | oci-arm1 (158.179.169.146, 3코어/16GB) |
| 도메인·HTTPS | ✅ | https://hajacheck.luma200ok.com — nginx `/`→8100(Spring)·`/ai/`→8101(FastAPI), certbot 자동갱신 크론 |
| 공용 개발 DB (PostgreSQL 16) | ✅ | DB/계정 `hajacheck`, localhost 전용 — 팀원 접근은 SSH 터널(`hajadev` 터널 전용 계정) |
| Redis | ✅ | 전용 컨테이너 `hajacheck-redis` 127.0.0.1:6380, requirepass·AOF |
| 팀원 DB 온보딩 | ✅ 완료 | `hajadev` 터널 전용 계정(authorized_keys, permitopen 5432(DB)·6380(Redis) 제약)에 팀원 7명 전원 공개키 등록 완료 |
| OAuth 앱 (Kakao/Google) | ✅ | Kakao(앱 1504012)·Google(hajacheck/hajacheck-web) 등록, Redirect URI=`/login/oauth2/code/{kakao\|google}` (localhost:8080 + dev 도메인). 크레덴셜=서버 `~/apps/hajacheck/.env`. 구글 테스트 사용자 8명(팀 전원 Gmail) 등록 완료 — 게시상태 Testing 유지(오픈 시 프로덕션 전환) |
| GitHub Actions CI | ✅ 그린 | PR 시 파트별 빌드/테스트 (backend·ai-server·frontend) |
| CD (서버 배포) | ⬜ | Sprint 1 앱 골격 후 — 배포 전용 SSH 키 + Secrets 예정 |

## 마지막 머지 PR

- #91 `chore: env-example-trim-unused-keys` (→ dev, 2026-07-09)
- #88 `ai/HAJA-20-llm-coach-defect-explain` — llm_client·prompts·structured output 우회·청킹·예시체인(defect-explain) 전부 포함 (→ dev, 2026-07-09)
- #87 `docs/HAJA-20-openapi-contract-draft` (→ dev, 2026-07-09)
- #86 `frontend/HAJA-41-result-viewer` (→ dev, 2026-07-09)

## 다음 작업

- **P0**
  - [ ] 착수 회의(7/9경): 담당자 배정, TS/JS·스타일 확정 — 안건 초안 `docs/_local/착수회의_안건_2026-07-05.md`
  - [ ] OpenAPI 스펙 우선 커밋 (Contract-First, PACA #10 — 유병현·김승현)
  - [ ] ERD 초안 + 데이터셋 확보 (PACA #10, #13 — 유병현·김승현)
  - [ ] 팀원 SSH 공개키 수집·등록 (2/7 완료, 나머지 **7/7(화)** 마감) + DB/Redis 비밀번호 개별 전달
- **P1**
  - [x] AI-LLM 코치: llm_client 구현 + structured output 버그 우회 (HAJA-20, PR #88, 2026-07-09 완료)
  - [ ] AI 온보딩 세션 자료에 HF `tool_choice` 400 트러블슈팅 반영 (HAJA-20 ⑥, 7/15 전)
  - [ ] 워킹 스켈레톤 관통 (7/18~19, HAJA-21)

## 알려진 이슈

| 이슈 | 상태 |
|---|---|
| — | — |
