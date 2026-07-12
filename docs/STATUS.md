# hajaCheck — STATUS

> 마지막 갱신: 2026-07-12

## 인프라

| 항목 | 상태 | 비고 |
|---|---|---|
| GitHub 레포 | ✅ | luma-team-ai/HajaCheck (main + dev) |
| OCI A1 VM | ✅ 확보 | oci-arm1 (158.179.169.146, 3코어/16GB) |
| 도메인·HTTPS | ✅ | https://hajacheck.luma200ok.com — **공유 host nginx**가 TLS 종료 `/`→8100(frontend 컨테이너)·`/ai/`→8101(fastapi 컨테이너), certbot 자동갱신 크론 |
| **배포 방식** | ✅ Docker | oci-arm1은 **공유 호스트**(nginx·postgres 공유) → `docker-compose.arm1.yml`로 **앱 컨테이너만**(spring 9100·fastapi 8101·frontend 8100, `network_mode: host`) 기동, 공유 nginx·postgres(hajacheck DB)·redis(6380) 재사용. base/override/prod은 전용 VM/로컬용 |
| 공용 개발 DB (PostgreSQL 16) | ✅ | DB/계정 `hajacheck`, localhost 전용 — 팀원 접근은 SSH 터널(`hajadev` 터널 전용 계정) |
| Redis | ✅ | 전용 컨테이너 `hajacheck-redis` 127.0.0.1:6380, requirepass·AOF |
| 팀원 DB 온보딩 | ✅ 완료 | `hajadev` 터널 전용 계정(authorized_keys, permitopen 5432(DB)·6380(Redis) 제약)에 팀원 7명 전원 공개키 등록 완료 |
| OAuth 앱 (Kakao/Google) | ✅ | Kakao(앱 1504012)·Google(hajacheck/hajacheck-web) 등록, Redirect URI=`/login/oauth2/code/{kakao\|google}` (localhost:8080 + dev 도메인). 크레덴셜=서버 `~/apps/hajacheck/.env`. 구글 테스트 사용자 8명(팀 전원 Gmail) 등록 완료 — 게시상태 Testing 유지(오픈 시 프로덕션 전환) |
| GitHub Actions CI | ✅ 그린 | PR 시 파트별 빌드/테스트 (backend·ai-server·frontend) |
| CD (서버 배포) | ✅ 가동 | `cd.yml`: main push → appleboy SSH(opc, **RSA 배포키**) → VM에서 `deploy.sh`(`DEPLOY_TARGET=arm1`) → arm1 compose 빌드·기동·헬스체크. Secrets: `OCI_HOST`·`OCI_USER`(opc)·`OCI_SSH_KEY`(RSA)·`OCI_HOST_FINGERPRINT`(**ECDSA** — appleboy가 ed25519보다 ECDSA 우선) |

## 마지막 머지 PR

- **#106 `chore: dev→main 승격 — Docker 전환 + 공유호스트 배포`** (→ **main**, 2026-07-12) — HajaCheck Docker 배포 LIVE. ⚠️ 이 승격 머지로 `delete_branch_on_merge=true` 때문에 dev가 자동삭제돼 재생성함(향후 승격 전 dev 브랜치 보호 필요)
- #105 `fix: 공유 호스트(arm1) 배포 변형` (Closes #104, HAJA-125 → dev, 2026-07-12)
- #103 `chore: Docker Compose 전환` (Closes #101, HAJA-124 → dev, 2026-07-12)
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
