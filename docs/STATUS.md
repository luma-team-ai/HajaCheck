# hajaCheck — STATUS

> 마지막 갱신: 2026-07-13

## 인프라

| 항목 | 상태 | 비고 |
|---|---|---|
| GitHub 레포 | ✅ | luma-team-ai/HajaCheck (main + dev). **dev 브랜치 보호 활성화**(protected=true) — #106 승격 시 dev 자동삭제 재발 방지, 직접 push 차단·PR 전용 |
| OCI A1 VM | ✅ 확보 | oci-arm1 (158.179.169.146, 3코어/16GB) |
| 도메인·HTTPS | ✅ | https://hajacheck.luma200ok.com — **공유 host nginx**가 TLS 종료 `/`→8100(frontend 컨테이너)·`/ai/`→8101(fastapi 컨테이너), certbot 자동갱신 크론 |
| **배포 방식** | ✅ Docker | oci-arm1은 **공유 호스트**(nginx·postgres 공유) → `docker-compose.arm1.yml`로 **앱 컨테이너만**(spring 9100·fastapi 8101·frontend 8100, `network_mode: host`) 기동, 공유 nginx·postgres(hajacheck DB)·redis(6380) 재사용. base/override/prod은 전용 VM/로컬용 |
| 공용 개발 DB (PostgreSQL 16) | ✅ | DB/계정 `hajacheck`, localhost 전용 — 팀원 접근은 SSH 터널(`hajadev` 터널 전용 계정) |
| Redis | ✅ | 전용 컨테이너 `hajacheck-redis` 127.0.0.1:6380, requirepass·AOF |
| 팀원 DB 온보딩 | ✅ 완료 | `hajadev` 터널 전용 계정(authorized_keys, permitopen 5432(DB)·6380(Redis) 제약)에 팀원 7명 전원 공개키 등록 완료 |
| OAuth 앱 (Kakao/Google) | ✅ | Kakao(앱 1504012)·Google(hajacheck/hajacheck-web) 등록, Redirect URI=`/login/oauth2/code/{kakao\|google}` (localhost:8080 + dev 도메인). 크레덴셜=서버 `~/apps/hajacheck/.env`. 구글 테스트 사용자 8명(팀 전원 Gmail) 등록 완료 — 게시상태 Testing 유지(오픈 시 프로덕션 전환) |
| GitHub Actions CI | ✅ 그린 | PR 시 파트별 빌드/테스트 (backend·ai-server·frontend) |
| CD (서버 배포) | ✅ 가동 | `cd.yml`: main push → appleboy SSH(opc, **RSA 배포키**) → VM에서 `deploy.sh`(`DEPLOY_TARGET=arm1`) → arm1 compose 빌드·기동·헬스체크. Secrets: `OCI_HOST`·`OCI_USER`(opc)·`OCI_SSH_KEY`(RSA)·`OCI_HOST_FINGERPRINT`(**ECDSA** — appleboy가 ed25519보다 ECDSA 우선) |

## 버전 정합 (PRD ↔ 레포 ↔ OCI 실측, 2026-07-13 확인)

PRD v0.42 §7(L330·L371) "이미지 버전 = 레포 추종(단일 진실)" 원칙 기준으로 대조. **PRD 명시분(JDK 17·Gradle 8.14.3·Vite 6·React 18) 전부 일치**, PRD 미지정분(Python·Node·nginx·PG·Redis)은 레포 파일이 단일 진실 → OCI 실측과 일치. **불일치 없음.**

| 영역 | PRD 명시 | 레포(Dockerfile/compose·빌드파일) | OCI 실측 (arm1, `docker exec`) | 일치 |
|---|---|---|---|---|
| Backend JDK | JDK 17 | `eclipse-temurin:17` / `build.gradle` toolchain 17 | **OpenJDK 17.0.19** | ✅ |
| Gradle | 8.14.3 | wrapper `gradle-8.14.3` | (빌드타임) | ✅ |
| AI server Python | (미지정) | `python:3.11-slim` | **Python 3.11** (레포 추종, 패치 재측정 전) | ✅ |
| Frontend | Vite 6·React 18 | `package.json` Vite ^6.0.3 / React ^18.3.1 | (빌드타임) | ✅ |
| Frontend nginx | (미지정) | `nginx:1.27-alpine` | **nginx 1.27.5** | ✅ |
| PostgreSQL | (컨테이너, 메이저 미명시) | `postgres:16` | **PostgreSQL 16.13** (호스트) | ✅ |
| Redis | (requirepass·AOF) | `redis:7` (arm1 공유 컨테이너 `hajacheck-redis`=`redis:7-alpine`) | **Redis 7.4.9** | ✅ |
| OS/아키 | Ubuntu 22.04·ARM/aarch64 | multi-arch 이미지 | Ampere A1(aarch64) | ✅ |

> ⚠️ 지난 세션 이슈였던 JDK가 **PRD·`build.gradle`·Dockerfile·OCI 실측 네 곳 모두 17로 정합** 확인됨. (호스트 직접 `./gradlew build` 시 JDK 부재 문제는 컨테이너 빌드와 별개 — 아래 [알려진 이슈] 참조)

## 마지막 머지 PR

- **#134 `chore: dev→main 승격 — 문서 재정리·grounding 개선·map 리팩터 (중간 최신화)`** (→ **main**, 2026-07-13) — PR머신 수리 중이라 **메타 수동 검증**으로 승격(ai-server 39 passed·frontend build·R3/G6 clean). main→dev back-merge로 승격 충돌 해소 후 머지. CD 배포 트리거. 포함: #117·#120·#121·#123·#130·#132·#133 등
- **#133 `docs: DB 테이블 설계서(table_design.md)+실제 DDL 추가`** (→ dev, 2026-07-13) — `docs/design/db/`에 설계서·`hajaCheck_script.sql`. 리뷰 P2를 SQL에 확정 반영(user_consents RESTRICT·user_plans ACTIVE 부분유니크·updated_at 트리거). ⚠️ 이 SQL은 설계 참조용(Flyway 아님) — 배포 미실행
- **#132 `docs: design 문서 ai/ 하위 분리`** (→ dev, 2026-07-13) — `design/{dashboard_briefing,grounding_check}.md`→`design/ai/`(순수 rename). table_design은 P2 whack-a-mole로 #133 분리
- **#111 `docs: API 계약 문서 docs/api-contract/로 이동·정리`** (→ dev, 2026-07-13) — `contract.md`·`requirements_endpoints.{md,xlsx}`를 `docs/api-contract/`로 이동(내용 변경 0)
- **#108 `feat: 대시보드 AI 주간 브리핑`** (design-03-16, HAJA-118 → dev, 2026-07-13) — 현황데이터→자연어 요약 체인+`POST /ai/briefing`. code-reviewer P1 0, CI 20/20
- #107 `chore: dev→main 승격 — 문서 최신화` (→ **main**, 2026-07-12) — STATUS·컨벤션·README Docker 반영
- **#106 `chore: dev→main 승격 — Docker 전환 + 공유호스트 배포`** (→ **main**, 2026-07-12) — HajaCheck Docker 배포 LIVE. ⚠️ 이 승격 머지로 `delete_branch_on_merge=true` 때문에 dev가 자동삭제됐던 이슈는 **dev 브랜치 보호로 해소**
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
  - [ ] **#100 Grounding Check(HAJA-117) 재작업** — 작성자가 P1 2건 수정 + 최신 dev로 rebase 후 재검수
  - [ ] **dev→main 승격 대기** — #108·#111 등 dev 반영분, 사람 승인(hold_promote)
  - [ ] AI 온보딩 세션 자료에 HF `tool_choice` 400 트러블슈팅 반영 (HAJA-20 ⑥, 7/15 전)
  - [ ] 워킹 스켈레톤 관통 (7/18~19, HAJA-21)

## 알려진 이슈

| 이슈 | 상태 |
|---|---|
| **arm1 호스트 JDK 17 부재** (Docker 전환 부작용) — 앱 빌드가 컨테이너(`temurin:17`) 안으로 들어가며 호스트 JDK가 불필요→소실, 호스트에서 직접 `./gradlew build` 시 "Java 17 못 찾음" 실패 | arm1에 JDK 17 설치로 **해소**. 항구 대책(`settings.gradle` foojay-resolver로 JDK 자동 조달)은 별도 |
| **#100 Grounding Check** — 부가 텍스트 정규식 함수의 환각 오탐(P1)+`AI_개발_컨벤션 §4` 위반(P1)+dev 충돌 | Jira INSPECTION CHECK, 작성자 수정 대기 |
| ~~#73 P2 sync stale 중복본~~ | 폐기 완료(dev에 우수 버전 존재, 이슈 이미 CLOSED) |
