# hajaCheck — STATUS

> 마지막 갱신: 2026-07-18

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
| OAuth 앱 (Kakao/Google) | ✅ | Kakao(앱 1504012)·Google(hajacheck/hajacheck-web) 등록. **Redirect URI = `{오리진}/login/oauth2/code/{kakao\|google}`** — 오리진은 **로컬 `http://localhost`(80) + 운영 `https://hajacheck.luma200ok.com`** (2026-07-18 콘솔 갱신, 카카오·구글 양쪽). ⚠️ 로컬은 nginx 80 단일 오리진으로 전환됨(#302·#311·#313) — 옛 `localhost:8080`은 현 compose가 주입하지 않는다(`docker-compose.oci-db.yml`은 `http://localhost/...` 주입). 콘솔에 8080 잔존은 무해하나 현행 아님. **카카오는 플랫폼 Web 사이트 도메인**(오리진: `http://localhost`·운영 도메인)이 별도 등록돼야 로그인 활성. 크레덴셜=서버 `~/apps/hajacheck/.env`. 구글 테스트 사용자 8명(팀 전원 Gmail) 등록 완료 — 게시상태 Testing 유지(오픈 시 프로덕션 전환) |
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

- **미디어(이미지) 업로드/썸네일 API (→ dev, 2026-07-18, dev-05-03)** — **#263** `feat: 촬영 데이터(이미지) 업로드 API` (squash `f443ba9`). `POST /api/inspections/{id}/media`(다중 이미지, 매직바이트 검증·EXIF GPS/촬영시각 추출·썸네일 재인코딩·픽셀폭탄 방어) + `GET /api/media/{id}/thumbnail`(인가된 썸네일만·원본 비서빙·`no-store,private`). 소유권=InspectionService IDOR 로직 재사용, 트랜잭션 밖 IO(`NOT_SUPPORTED`)+별도 `MediaWriter` 트랜잭션. **메타 드레인 머지**: 며칠 막혀 있던 CI 실패 2건(테스트 결함 — GlobalExceptionHandlerTest 유니코드 리터럴 손상·MediaControllerTest NOT_SUPPORTED 미커밋 가시성)을 메타가 수정(`ee9d7be`) → CI 그린·머신 P2 과다 해소·`ai:needs-human`. **G6 PASS**(dev 파일 겹침 0·merge-tree 충돌 0·파괴적 SQL 0). 영상/프레임추출은 범위 밖. ⚠️ **후속 이슈**: 배정 점검자 업로드 권한(**#364**/HAJA-245)·회차별 총량 상한(**#365**/HAJA-244)·마감회차 업로드 차단(**#374**)·P3 묶음(**#375**: nosniff·고아파일·타이밍 사이드채널·FILE_TOO_LARGE 문구·OpenAPI maxItems·ImageIO.write). **배포 전제**: 운영 `media` 테이블·`media_file_type` enum은 `HajaCheck_script.sql`에 이미 반영(드리프트 없음), 업로드 볼륨 마운트는 #323/HAJA-229로 반영됨. (Jira dev-pr-check 전환 + `awaiting-promotion` 라벨은 별도 세션에서 처리 예정)
- **AI 무인증 공개 폐쇄 — FastAPI 스프링 강제 경유 (A안, 3PR → dev, 2026-07-16, HAJA-188·190·191)** — nginx가 `/ai/*`를 공개 프록시 + FastAPI 무인증이라 인터넷 누구나 LLM 엔드포인트 직접 호출 가능하던 **보안 구멍 폐쇄**. **#229**(HAJA-188) 백엔드 `POST /api/ai/defect-explain` 인증 프록시(RestClient+`AiServerProperties`+`AI_SERVER_*` ErrorCode, code-reviewer P1[타임아웃 오분류]→픽스, security P1 0). **#234**(HAJA-190) 프론트 `aiClient` `/ai`→`/api/ai` 전환. **#236**(HAJA-191, Critical) FastAPI `/ai` 라우터 `X-Internal-Key` 검증(hmac, 미설정 시 비활성)+nginx 공개 `/ai/` 제거(default/arm1/tls.conf)+compose 공유키(spring `AI_SERVER_INTERNAL_KEY`/fastapi `AI_INTERNAL_KEY`=동일 `${AI_INTERNAL_KEY}`); code-reviewer P1[deps.py Dockerfile COPY 누락]→픽스·실도커빌드 검증, security-reviewer opus **"구멍 실제로 닫힘" 확정**. 계약: `contract.md` §접근모델(v0.3 in-place). ⚠️ **배포 전제(↓알려진 이슈)**: ① 서버 `.env` `AI_INTERNAL_KEY` ② 운영 host nginx `/ai/` ops 제거. 후속: rate limiting(#230)·`/docs` prod 비활성(#242)·briefing 스프링 프록시.
- **마이페이지(내 플랜) 풀스택 (→ dev, 2026-07-15, HAJA-177·HAJA-185)** — PRD §2.4 멤버십 모델 코드 이관. **#211** `feat: 마이페이지 내 플랜 API` (HAJA-177, squash `da1d115`): `com.hajacheck.membership.*` 신설 — Plan/UserPlan/UsageCounter 엔티티(DDL v0.3 매핑) + `GET /api/me/plan`·`GET /api/me/seats`·`POST /api/me/plan/upgrade-inquiry` + ErrorCode(PLAN_NOT_FOUND·PLAN_FORBIDDEN·USER_NOT_FOUND·PLAN_DATA_INVALID). **#213** `feat: 마이페이지 내 플랜 화면` (HAJA-185, squash `2e0e16d`): 사이드바 마이페이지 활성화 + `/mypage/plan`(요금제 카드·사용량 바·좌석 목록·결제이력 placeholder·업그레이드 문의 상태전환) + 앱 셸(Sidebar/DashboardLayout/TopBar 등) `shared/` 승격 + MSW mock. **PR머신 오프 → 메타 검수 직접 머지**: 백엔드 code-reviewer + security-reviewer **P1 0**, 프론트 typescript-reviewer **P1 0(대시보드 회귀 0)**. CI backend/frontend PASS(실 PG 리포지토리 테스트 포함). 리뷰 픽스: 좌석 ACTIVE 필터·예외 패턴 통일(BusinessException)·타임존 Asia/Seoul 고정·NPE 방어. dev 병합 시 ErrorCode·test-init SQL 충돌 합집합 해소. 로컬 로그인→마이페이지 E2E 확인. ⚠️ **후속**: 결제이력·좌석 초대·QuotaInterceptor·읽기권한 비대칭(**#210/HAJA-184**), contract.md 마이페이지 § dev 재반영(리베이스 중 분리, 백업 보관 — 문서 버전규칙 따라 별도 반영). **배포 전제**: dev PG에 plans/user_plans/usage_counters 필요(↓알려진 이슈).
- **#176 `[HAJA-17] feat: 시설물 CRUD 백엔드 API` (dev-04-01, FR-11-01 → dev, 2026-07-15)** — `com.hajacheck.core.facility.*` Facility 엔티티(Inspection·Defect·대시보드 집계 의존성 루트)+Repository(owner 스코프)+DTO+Service+Controller(`/api/facilities` CRUD)+`FACILITY_NOT_FOUND`. IDOR 방지(`findByIdAndOwnerId`, ownerId 바디 미수신, 미존재/타소유 동일 응답). **PR머신 오프 → 사용자 승인 직접 머지**(merge `441d2e14`), 머신 검수 P1 0·CI backend PASS. 하드삭제(facilities `is_deleted` 없음 → @SQLDelete 미적용). ⚠️ **후속 P2**: PUT 낙관적 락(@Version) 부재(스키마 v0.3 필요, PL 협의)·**P3**: builtYear 범위/목록 페이징/type 화이트리스트 → 후속 이슈. FE 대시보드 뼈대는 HAJA-17 잔여 범위(후속 PR — 대시보드 집계 API가 PR #222로 진행 중, 동일 HAJA-17 우산). ✅ **Jira 동기화 완료**: HAJA-17 → dev-pr-check.
- **#206 `chore: dev→main 승격 — 문서 내용 최신화 phase 2` (→ main, 2026-07-15)** — docs-only. `/ai/report` 미구현 정정(거짓 '구현완료(PR#88)' 제거)·`/ai/grounding-check`·`/ai/briefing`·기업인증 4개 엔드포인트 openapi.yaml(SOT) 반영·stale PRD 참조 3파일(README·PR템플릿·MODEL_CARD) 정정. **`user_consents` 삭제정책 라이브 DB 실측**(`sudo -u postgres psql`, read-only) → 실제 `ON DELETE CASCADE`(문서의 'RESTRICT 확정'은 거짓이라 정정), `table_design.md` **v0.2 bump + v0.1 archive**. G6 PASS(전부 문서). CD success, 배포 중 일시 502→헬스 `/`·`/ai/health` **200 회복 확인**. ⚠️ **후속**: user_consents DB레벨 강제보존(RESTRICT) 필요 시 마이그레이션 요 — 현재 `UserConsent.java`는 FK 미매핑·Flyway 없음(스키마 수동관리)
- **#205 `chore: dev→main 승격` (→ main, 2026-07-15)** — #179(로컬 개발 도구·시드 옵트인·MSW) + **문서 버전 관리 체계(A안: docs/*.md v0.1 헤더·PRD 파일명 정규화)** 프로덕션 배포. **PR머신 오프 → 메타 G6 로컬 검수 GO**(운영 config 무영향·destructive 없음, merge `9ade866`). CD arm1 success, 외부 헬스 `/`·`/ai/health` **200**. #179은 prod dormant(@Profile local·!env.DEV). → **phase 2 문서 최신화 완료(#206)**
- **#201 `chore: dev→main 승격` (→ main, 2026-07-15)** — 기업 인증 3화면(#195·#196·#200·#191) + 마지막 승격(#134) 이후 dev 반영분 전체를 프로덕션 배포(main push→CD→arm1). **PR머신 오프 기간 → 메타 G6 로컬 검수 GO**(부팅/validate·R1 회귀·R3 config·R4 destructive 전부 PASS). OAuth 핫픽스 #180(redirect https)·#188(nginx 콜백프록시)을 **main→dev back-merge로 반영 후 승격**(핫픽스 회귀 방지). dev PG DDL(companies/user_consents/enum) 실측 확인. ⚠️ **후속 P2**: 사업자등록증 업로드 볼륨 arm1 compose 미마운트(기업가입 공개 시 배선 필요)
- **#179 `docs+chore: 로컬 개발 가이드 + 시드 테스트 유저 + MSW 토글`** (HAJA-165 → dev, 2026-07-15) — `@Profile("local")` 시드 유저 + 로컬 개발 가이드 + 프론트 MSW 토글. **PR머신 오프 → 메타 로컬 검수**(code-reviewer·security-reviewer opus, P1 0). 시드 ADMIN 유저를 `app.local-seed.enabled` 기본 **false(옵트인)**로 하드닝(공유 DB known-password 백도어 방지). 리뷰 P2(시드 경합 시 `@Transactional` 경계 때문에 catch 실효 없음→기동 실패) 픽스 후 재검토 PASS. CI green, squash `7a71053`. → **main 승격 완료(#205, 2026-07-15)**
- **기업 인증 플로우 3화면 풀스택 (→ dev, 2026-07-15)** — Figma 인증 화면 구현. 4개 PR 일괄 머지:
  - **#195** `feat: 기업 회원가입·아이디 찾기 백엔드 API` (HAJA-168, Critical) — Company/UserConsent 엔티티(DDL v0.3 매핑), 회원가입(multipart)·이메일중복확인·아이디찾기·가입상태 API, FileStorage(로컬볼륨, prod 정적서빙 @Profile("!prod"))·TokenStore(Redis)·ErrorCode 8종. code-reviewer P1 0 + security-reviewer P1 0. **비밀번호 찾기는 계정탈취 P1로 범위 제외→후속 #194**
  - **#196** `feat: 기업 회원가입·아이디찾기·승인대기 3화면 프론트` (HAJA-170) — 다음 우편번호·사업자등록증 업로드·MSW mock. 필수 동의 2개 분리(개인정보보호법)·signupToken sessionStorage 등 P2 반영
  - **#200** `fix: 카카오 소셜 로그인 이메일 미수집 허용` (HAJA-174) — 개인 카카오앱 이메일 동의항목 불가 대응, email 없거나 미검증 시 placeholder(`{provider}_{socialId}@social.local`) 가입. 검증 이메일만 실제 저장. security-reviewer P1 0
  - **#191** `feat: 사업자등록증 OCR stub 엔드포인트 (AI서버)` (HAJA-169) — seam only(백엔드 미배선), 실제 OCR은 후속
  - ✅ **배포 전제 확인(2026-07-15)**: dev PG에 `companies`/`user_consents`/enum 3종이 이미 존재하고 엔티티와 컬럼·타입 일치 → ddl-auto=validate 통과 가능(승격 차단 아님)
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
  - [x] **✅ dev→main 첫 프로덕션 승격 + B전환 DB 컷오버 완료 (2026-07-18)** — 승격 PR #358(135커밋, awaiting-promotion 41건 Closes). arm1이 이제 **bridge net + 전용 postgres/redis 컨테이너**로 라이브. 5개 컨테이너 healthy, 데이터 보존(users 7·companies 2·user_consents 4), 외부 검증(`/`200·`/api`401·OAuth 302) 통과.
    - 절차: cd.yml `disable`로 자동배포 잠금 → host DB `pg_dump`(롤백 보험) → 승격 머지 → 서버 수동 컷오버(구 스택 정지 → 새 pg/redis → `pg_restore` → deploy.sh) → cd.yml `enable`.
    - ⚠️ **스키마 드리프트 실발생·임시 봉합**: 복원 스키마에 새 코드 컬럼(`inspections.assigned_inspector_id` 등) 부재 → spring `validate` 실패 → **`ddl-auto=update` 1회 부팅으로 자동 반영**(데이터 무손실) 후 validate 복귀. **근본 원인 = Flyway 부재 → #359(P1)**. 마이그레이션 없이는 다음 승격마다 재발.
    - **롤백 보험 잔존**: 구 host postgres(5432, 무손실) + 구 `hajacheck-redis`(정지) 당분간 보존, 안정 확인 후 정리.
    - **남은 사람 작업**: 카카오 콘솔 Web 도메인 확인(브라우저 `/facilities/map` 실측) · 롤백 보험(구 host postgres 5432·구 redis) 안정 확인 후 정리. ~~팀 공지(5432→5433)~~는 5433이 prod 컨테이너 내부 전용이라 불필요로 결론. ✅ **승격 Jira 벌크 Done 완료(2026-07-18)** — 승격 #358 포함 dev-pr-check 26건 전부 완료 전환(HAJA-31·113·120·133·143·144·145·150·152·153·158·162·173·179·180·181·182·183·185 + 정재봉 담당 188·190·191·193·194·197·209), 앞서 HAJA-229·240 포함.
  - [x] **#194/HAJA-172·HAJA-224 비밀번호 찾기·새 비번설정 — dev 완료(2026-07-17)**: 백엔드 #304(`9fe6890`)·프론트 #309(`2823894`). ⚠️ 착수 시점 계획이던 **"보안질문 방식·SMTP 미사용"은 폐기**됐다 — 보안질문 자체가 계정탈취 표면이라 **이메일 링크 + SMTP 발송**으로 전환. arm1 아웃바운드 587/465 열림 확인(OCI 차단은 25번뿐 → SES 등 대안 불필요). **승격 전제 있음 → ↓알려진 이슈**
  - [ ] **후속** 카카오 이메일 P3(placeholder→검증이메일 backfill·타인 노출 마스킹)·인증 하드닝(rate-limit·파일 MIME 매직바이트·사업자등록증 서빙 인가 게이트·승인 게이팅) — #194 하드닝 묶음
  - [ ] AI 온보딩 세션 자료에 HF `tool_choice` 400 트러블슈팅 반영 (HAJA-20 ⑥, 7/15 전)
  - [ ] 워킹 스켈레톤 관통 (7/18~19, HAJA-21)

## 알려진 이슈

| 이슈 | 상태 |
|---|---|
| **arm1 호스트 JDK 17 부재** (Docker 전환 부작용) — 앱 빌드가 컨테이너(`temurin:17`) 안으로 들어가며 호스트 JDK가 불필요→소실, 호스트에서 직접 `./gradlew build` 시 "Java 17 못 찾음" 실패 | arm1에 JDK 17 설치로 **해소**. 항구 대책(`settings.gradle` foojay-resolver로 JDK 자동 조달)은 별도 |
| **#100 Grounding Check** — 부가 텍스트 정규식 함수의 환각 오탐(P1)+`AI_개발_컨벤션 §4` 위반(P1)+dev 충돌 | Jira INSPECTION CHECK, 작성자 수정 대기 |
| ~~#73 P2 sync stale 중복본~~ | 폐기 완료(dev에 우수 버전 존재, 이슈 이미 CLOSED) |
| **AI 보안 배포 전제(#236/HAJA-191)**: ① 서버 `~/apps/hajacheck/.env`에 `AI_INTERNAL_KEY` 설정(arm1/prod compose `:?` 강제 → 미설정 시 배포 fail-closed, 값 1개로 spring·fastapi 공유, `openssl rand -hex 32`) ② 운영 host nginx `/ai/`→8101 location 제거(레포 밖 ops — 라이브 공개 엣지, 제거해야 완전 폐쇄) | **실측 갱신(2026-07-17)**: ① `AI_INTERNAL_KEY` ✅ **arm1 `.env`에 기입 완료** → 승격 시 배포 실패 없음. ② host nginx `/ai/` location **❌ 아직 남아 있음**(`/etc/nginx/conf.d/hajacheck.conf:7`, 외부 `GET /ai/health`→**200** 확인). **단 실제 LLM 호출은 불가** — `proxy_pass ...:8101/`(끝 슬래시)가 `/ai/` prefix를 잘라내 FastAPI 실 라우트(`/ai/*`)에 안 닿음(`/ai/ping`·`/ai/defect-explain`→**404** 확인). ⚠️ **그러나 루트 경로는 그대로 노출**: `/ai/docs`·`/ai/openapi.json`→**200**, 내부 API 스키마(엔드포인트 5종) 인터넷 공개 상태 → **#242(`/docs` prod 비활성)가 이 픽스이며 미승격**. ②는 여전히 제거 대상 |
| **비밀번호 재설정 메일 배포 전제(#194·#304/HAJA-172)**: 서버 `~/apps/hajacheck/.env`에 **5키 필수** — `SMTP_HOST`·`SMTP_USERNAME`·`SMTP_PASSWORD`·`MAIL_FROM`·`FRONTEND_BASE_URL`. `docker-compose.arm1.yml:107-116`이 `:?` 가드라 **하나라도 없으면 spring 기동 실패**(AI_INTERNAL_KEY와 동일 패턴). `SMTP_PORT`(기본 587)·`SMTP_SSL_ENABLE`(기본 false)은 선택 — 465(SSL) 쓸 때만 세트로 지정. `FRONTEND_BASE_URL`은 재설정 링크 base의 **유일한 출처**(Host poisoning 방어로 요청에서 유도하지 않음). | ⏳ **1키 남음 — arm1 `.env` 실측(2026-07-17, 키 이름만 조회)**: `SMTP_HOST`·`SMTP_USERNAME`·`SMTP_PASSWORD`·`MAIL_FROM` ✅ 기입됨(Gmail 실인증 성공 확인) / **`FRONTEND_BASE_URL` ❌ 없음**. → **지금 승격하면 이 한 키 때문에 spring 기동 실패.** 승격 순서: **`.env`에 `FRONTEND_BASE_URL` 주입 → 그 다음 main 승격**. 가드가 없었다면 더 나쁜 무증상 장애(재설정 링크가 사용자 대신 서버 로그로 감 + 토큰 로그 잔존)로 갔을 것 |
| **마이페이지 배포 전제: dev PG에 plans/user_plans/usage_counters 미확인** — #211(HAJA-177) 백엔드가 `ddl-auto=validate`라 이 3테이블 + enum(plan_name_type·user_plan_status_type)이 dev PG(oci-arm1)에 없으면 부팅 실패. DDL 정본(`HajaCheck_script_v0.3.sql`)에 정의됨. | ⏳ **승격/배포 전 dev PG 실측 필요** — companies/user_consents처럼 이미 반영됐는지 확인, 없으면 DDL 적용 후 배포 |
| ~~기업 인증 배포 전제: companies/user_consents/enum DDL 미반영~~ | ✅ **해소·확인(2026-07-15)** — dev PG(oci-arm1) 실측 결과 companies·user_consents 테이블 + 3 enum이 **이미 존재하고 컬럼·타입·named enum이 엔티티와 정확히 일치**(jsonb·timestamptz·user_consents updated_at 없음까지). `ddl-auto=validate` 통과 가능 → 승격 차단 아님. (STATUS 07-13의 "DDL 배포 미실행" 기재가 stale이었음) |
