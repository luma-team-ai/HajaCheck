# hajaCheck — STATUS

> 마지막 갱신: 2026-07-07

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

- #38 `chore: application-local.yml.example 실제 인프라와 동기화 (터널 localhost·hajacheck·6380)` (→ dev, 2026-07-05)
- #32 `chore: CI 그린화 — gradle wrapper·pytest 스켈레톤 테스트·eslint 설정·lockfile 추가` (→ dev, 2026-07-04)

## 다음 작업

- **P0**
  - [ ] 착수 회의(7/9경): 담당자 배정, TS/JS·스타일 확정 — 안건 초안 `docs/_local/착수회의_안건_2026-07-05.md`
  - [ ] OpenAPI 스펙 우선 커밋 (Contract-First, PACA #10 — 유병현·김승현)
  - [ ] ERD 초안 + 데이터셋 확보 (PACA #10, #13 — 유병현·김승현)
  - [ ] 팀원 SSH 공개키 수집·등록 (2/7 완료, 나머지 **7/7(화)** 마감) + DB/Redis 비밀번호 개별 전달
- **P1**
  - [ ] AI-LLM 코치: llm_client 구현 + requirements 버전 확정 (7/15 온보딩 전, PACA #20)
  - [ ] 워킹 스켈레톤 관통 (7/18~19, PACA #21)

## 알려진 이슈

| 이슈 | 상태 |
|---|---|
| — | — |
