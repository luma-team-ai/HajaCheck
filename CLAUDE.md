# HajaCheck — 프로젝트 CLAUDE.md (전역 오버라이드)

> 이 레포는 **PR머신 게이트 레포**(`luma-team-ai/HajaCheck`)다. 전역 `~/.claude/CLAUDE.md`를 **그대로 상속**하고, 아래 항목만 덮는다. 겹치지 않는 룰(시크릿 스캔·커밋 컨벤션·워크트리 워크플로우·이슈/PR 규칙·G1/G6 게이트)은 전역을 따른다.

## PR 흐름 (전역 `base=main` 오버라이드)
- **PR base = `dev`** (전역 기본 main 아님). 기능 PR은 항상 `dev`로 올린다.
- `dev`로의 PR은 **PR머신이 자동 검수·머지**한다(티어별 sonnet/opus). 사람이 먼저 머지하지 않는다.
- `dev → main` 승격은 머신이 검수 후 **사람 핑(hold_promote)** — 자동 머지 아님, 운영자가 승인.

### GitHub 이슈 종료 규칙 (dev 머지로는 안 닫힘 — 2026-07-16 신설)
> 계기: 열린 이슈 72건 누적 조사. GitHub `Closes #N` 자동종료는 **기본 브랜치(main)에 머지될 때만** 발동한다. 기능 PR은 `dev`로 머지되므로 dev 머지로는 이슈가 **자동으로 닫히지 않는다**. 그대로 두면 완결 이슈가 무한 누적된다.
- **이슈는 "실배포(main 승격)" 시점에 닫는다.** open = "아직 프로덕션 미배포", closed = "배포됨". (Jira `완료`와 정합 — dev 머지 단계는 아직 미배포이므로 open 유지가 정상.)
- **dev 머지 시**: 이슈를 닫지 않고 **`awaiting-promotion` 라벨을 부여**한다(GitHub 보드에서 "승격대기 = 코드 끝남"과 "착수 전"을 구분 가능하게). Jira `dev-pr-check` 전환과 **세트로** 처리(같은 트리거 — Jira 연동 §4단계). native 자동화 아님 → Claude/운영자가 dev 머지 확인 시점에 `gh issue edit {N} --add-label awaiting-promotion`.
- **main 승격 시**: **승격 PR 본문에 그 배치에 포함된 모든 이슈의 `Closes #N`을 나열**한다(의무). squash여도 본문 나열은 main 머지 시 전부 native 자동종료된다(라벨은 close로 소멸). Jira `완료` 전환과 세트(Jira 연동 §5단계).
- **PR머신 repo / `.github/workflows`는 무수정** — 라벨·종료 모두 Claude/운영자 동작으로 처리(전역 원칙 유지).

## 검수 위임 (PR머신 가동 중일 때만)
전역의 "메타 수동 검수 사이클(code-reviewer 호출 + G3.5)"은 PR머신 티어 검수와 겹친다. 중복 제거:
- **Normal / Trivial** → 메타 수동 code-reviewer·G3.5 **생략**. PR머신 티어 검수(sonnet)에 위임. 사람은 머신이 단 반려(`ai:p1-blocked`) 사유만 확인.
- **Critical**(인증·인가·결제·분산정합성·webhook·멱등성) → 전역대로 **메타 code-reviewer 1회 유지**. 머신 opus 2차 검증과 이중이 되지만 이 영역은 의도된 안전장치.
- **머신이 꺼져 있으면(go-live 전 공백 기간)** → 위임 무효, **전역 검수 사이클을 그대로 수행**. (무검수 PR 방지)

## 코드 리뷰 기준 (이 레포 리뷰 체크리스트)
> 리뷰 시 아래 항목을 점검한다 — 사람·PR머신·Claude 공통. 팀원이 이 프로젝트 파일만으로 리뷰 관점을 공유받도록, 전역·스택 컨벤션과 일부 겹치더라도 여기 명시한다. 발견 항목엔 **P등급 + 파일:라인 + 근거 + 수정안**을 함께 단다.

### 보안·인증·인가·개인정보 (대개 P1 — 머지 차단)
- **인가(소유자·회사 스코프) 검증**: 리소스 조회·수정 전 요청자와 소유자/회사 소속 일치를 확인해 **IDOR·cross-tenant를 차단**한다. "인증됨 ≠ 인가됨"(점검 회사 스코프 정책: 개인=보고서 수신 전용, 점검 생성·담당자 배정=회사 소속 한정).
- **시크릿/크레덴셜**: API키·비밀번호·토큰·DB URL 하드코딩 금지 → env/설정 주입. 코드·로그·응답에 평문 노출 금지.
- **입력은 신뢰 금지**: 요청 파라미터·바디·헤더(특히 ID·스코프 파라미터)는 형식·범위·권한을 검증한 뒤 사용한다.
- **세션·개인정보**: 토큰 만료·로그아웃 무효화를 검증하고, 개인정보(이메일·사업자번호·전화 등)는 로그에 평문으로 남기지 않는다(마스킹).

### 안전성·에러·경계 (대개 P2 — 수정 요망)
- **예외를 삼키지 않는다**: 실패는 도메인 에러로 표면화 — 백엔드 `CustomException(ErrorCode)` / FastAPI `ApiError` / 프론트는 `.status` 분기(메시지 매칭 금지).
- **Null/None 안전**: Java `Optional`·Python `None`·TS strict null을 방어한다. 무검증 강제 접근(`get()`·`!`) 지양.
- **경계·정합 계산**: 배열·페이지 경계, 날짜·금액·권한 등 경계 계산은 근거를 갖고 검증한다.
- **동시성·트랜잭션**: 공유 상태·쓰기 경로의 경쟁 조건·트랜잭션 경계를 확인한다(전역 Critical 트리거와 정합).

### 품질 (대개 P3 — 권고)
- **단일 책임(SRP)**: 거대 함수·신(God) 클래스 분해.
- **죽은 코드·디버그 로그 제거**: 불필요한 `print`/`console.log`·주석 처리된 코드 정리.
- **테스트 동반**: 변경엔 테스트를 붙인다(스택별: Java=`./gradlew test` · FastAPI=`pytest` · React=`npm test`/`build`).
- **네이밍·계층 컨벤션**: 전역 스택 컨벤션(패키지 계층·네이밍) 준수.

### 리뷰 출력 규칙
- 발견 항목마다 **P등급(P1/P2/P3) + 파일:라인 + 근거 + 수정 제안**을 함께 제시.
- **P1이 하나라도 있으면 "머지 불가"**로 요약, P3만 있으면 "권고"로 표기.

## PR 위생 & 큐 관리 (파편화·WIP 누적 방지, 2026-07-15 회고 반영)
> 계기: 열린 PR 7건이 하루에 몰려 올라오고, 그중 CI실패(#176)·충돌(#170)·머신반려(#191·#154)가 방치된 채 새 기능 PR이 계속 쌓임. 각 작업자(사람·구현 서브 공통)는 아래를 지킨다. (전역 워크트리 워크플로우·사이클 매트릭스 위에 얹는 이 레포 규칙.)

### 1. 드레인 우선 (WIP 누적 금지) — 최우선
- **내가 연 PR이 `ai:needs-author`(머신 반려)·CI FAILURE·CONFLICTING 상태면, 새 작업 PR을 올리기 전에 그것부터 해소한다.** 반려·실패 PR 위에 다음 로직을 쌓지 않는다.
- **1인당 동시 "진행 중" PR ≤ 2건 권장.** `ai:needs-human`(검수 대기 = 손 떠남)은 카운트에서 제외하되, `needs-author`가 1건이라도 있으면 그것부터 처리 후 신규 착수.
- **CONFLICTING PR은 당일 rebase로 해소하거나 닫는다.** 방치하면 같은 파일을 건드리는 후속 PR들의 머지 순서가 꼬인다(전역 "병렬 스폰 파일 겹침 체크"와 직결).

### 2. 파편화 금지 (소규모 PR 남발 방지)
- **1~2줄·주석/TODO만 바꾸는 단독 PR 금지**(예: #150 +1줄). 리뷰에서 나온 **P2/P3 후속은 원 PR의 픽스 루프(사이클 cap 내)에서 처리**한다. 정말 분리해야 하면 **후속 이슈로만 남기고**, 매번 새 PR로 떼지 않는다.
- **한 기능 = 한 이슈 = 한 PR**(스택 경계로만 분리 — 예: 백엔드/프론트/AI는 각기 다른 이슈). **무관한 기능을 기존 Jira 키에 얹지 않는다** — 시설물 CRUD(#176)를 대시보드 `HAJA-17`로 태깅한 것은 금지 사례. 새 기능은 새 이슈부터.
- hotfix의 main→dev 역반영은 불가피하지만, 그 외 **동일 변경을 여러 PR로 쪼개지 않는다.**

### 3. 과대 PR도 지양
- 리뷰 불가능한 크기(수십 파일 신규)면 논리 단위로 분할해 순차 머지. 파편화 금지가 "거대 PR 허용"을 뜻하지 않는다 — 작지도 크지도 않은 **리뷰 가능한 단위**가 목표.

### 4. 착수 전 셀프 체크 (본인 PR 큐)
- `gh pr list --author @me --state open`으로 **내 열린 PR을 먼저 확인** → `needs-author`/CI실패/충돌 0건인지 보고, 남아 있으면 그것부터. 통과 시에만 다음 작업 브랜치 생성.

## 스택
- `backend`=Java/Spring Boot · `ai-server`=Python/FastAPI · `frontend`=React(Vite SPA, react-router).
- 각 폴더 작업 시 전역 CLAUDE.md의 해당 스택 컨벤션·빌드/테스트 명령을 적용한다. (frontend는 Next.js가 아니라 Vite SPA이므로 전역 Next.js 컨벤션 중 App Router 전용 항목은 제외하고 공통 원칙만 적용)

## Flyway 마이그레이션 워크플로우 (DB 스키마 변경 — 2026-07-22 신설, #359/#544)
> Flyway 도입 완료(#544 dev 머지). 이제 스키마 변경은 **`V{n}__*.sql` forward migration**으로만 한다. `ddl-auto=validate`라 엔티티 변경 시 마이그레이션을 누락하면 앱 기동이 실패한다(= #531 프로덕션 다운 재발 원인). **핵심: Flyway 적용 트리거는 "머지"가 아니라 "그 코드로 spring이 부팅되는 순간"이다**(Flyway는 앱 기동 시 자동 실행 — 사람이 SQL을 손으로 돌리지 않는다).

### DB 지형 (실측 2026-07-22)
- **prod** = arm1 **전용 postgres 컨테이너**(`hajacheck-arm1-postgres-1`)의 DB `hajacheck`. arm1-spring이 붙는 실서비스. Flyway 적용 시점 = **main 승격 자동배포로 arm1-spring이 재기동될 때**.
- **공유 dev** = arm1 **host postgres-16**(127.0.0.1:5432)의 DB `hajacheck_dev`. 팀 로컬이 `docker-compose.oci-db.yml` SSH 터널로 공유한다. Flyway 적용 시점 = **그 코드로 터널 환경 spring을 부팅할 때**(≠ dev 머지 시점 — 머지 전 테스트로도 찍힌다).
- **일회용** = 각자 로컬 컨테이너 / testcontainer. 마이그레이션 검증 전용.
- prod와 공유 dev는 **다른 물리 postgres**다 → 로컬(터널) 재기동은 prod에 무영향이지만, 공유 dev DB(`hajacheck_dev`)는 실제로 건드린다.

### 규칙
1. **한 기능 = 엔티티 변경 + `V{n}__*.sql` 을 같은 PR**에 담는다(둘이 따로 승격되면 validate로 터진다). 번호는 이어서 매긴다(현재 V3까지 존재 = 다음은 **V4**). 파일명 `V{번호}__{설명}.sql`.
2. **기존 마이그레이션(V1~) 절대 수정 금지** — 이미 적용된 파일을 바꾸면 checksum 불일치로 기동 실패한다. 변경은 항상 새 번호로만.
3. **마이그레이션 검증은 일회용 DB(testcontainer/로컬 컨테이너)에서** 한다. 파일이 확정(리뷰 통과)되기 전엔 **공유 dev DB(터널)에 찍지 않는다** — 미확정 `V{n}`을 공유 dev에 찍고 나중에 수정하면 checksum 충돌 + 팀원 간섭이 난다. (빈 DB·기존 DB 양쪽을 CI에서 자동 검증하는 testcontainer 통합테스트를 붙인다 — `FlywayBaselineOnExistingDbIntegrationTest` 참고.)
4. **터널 프로파일(`docker-compose.oci-db.yml`)로 미확정 마이그레이션 spring 부팅 금지** — 부팅 순간 공유 dev DB에 그대로 적용된다.
5. **prod 첫 baseline 스탬프 직전 프리플라이트(딱 1회)**: prod `hajacheck`(전용 postgres, 현재 20테이블) ↔ V1 `pg_dump` diff + 백업. baseline-on-migrate는 실스키마↔V1 정합을 **검사 없이 스탬프**하므로, 드리프트가 있으면 이후 validate/forward migration에서 터진다(#531). 이 프리플라이트 이후 승격부터는 자동.

### 표준 흐름
일회용 DB 검증(자유롭게 수정) → PR(base=dev) 리뷰·머신 검수 → **dev 머지 = 확정** → 확정본으로 공유 dev DB 반영 → dev→main 승격(사람 승인) → 자동배포 재기동 시 **prod 적용**.

## Jira 연동 (Rovo MCP 오케스트레이션, 2026-07-08 확정, 2026-07-08 사이트 정정, 2026-07-08 프로젝트 키 정정, 2026-07-11 4단계 동기화로 확장, 2026-07-13 INSPECTION CHECK 추가로 5단계 확장, 2026-07-16 GitHub 이슈 라벨/종료 세트 추가)
- 팀 표준화로 paca → **Jira(`human2-team.atlassian.net`) + Slack**로 전환. GitHub↔Jira 양방향 동기화는 **네이티브 앱(GitHub for Jira) 설치 없이**, Claude가 워크플로우 단계마다 Atlassian Rovo MCP 도구를 직접 호출하는 방식으로 구현한다. PR머신 repo(`.github/workflows/*.yml` 등)에는 아무것도 추가하지 않는다 — PR머신은 리뷰·머지만 하고 Jira를 모른다.
- **Jira 사이트 = `human2-team.atlassian.net`, 프로젝트 키 = `HAJA`** (프로젝트 표시명은 "haja-check"). ⚠️ 이름이 비슷한 `human-2team.atlassian.net`(프로젝트 키 `SCRUM`)은 **다른 사이트**이며 현재 claude.ai 커넥터 권한이 없음(재연결 시 `human2-team` 하나만 승인됨) — 헷갈리지 말 것. 이슈 타입: 에픽/스토리/작업/버그/Feature/Subtask. (과거 `KAN`으로 잘못 기재돼 있었음 — 2026-07-08 `jira-p2-sync.yml` 버그 조사 중 실제 라이브 데이터로 `HAJA`가 맞다는 걸 확인해 정정.)
- **워크플로우 상태 5개** (`getTransitionsForJiraIssue`로 확인됨, transition id는 이슈마다 다시 조회할 것 — 아래는 참고용): 해야 할 일(status 10003) / 진행 중(status 10004) / **INSPECTION CHECK**(status 10046, 검수 단계, statusCategory=진행 중 계열, HAJA-117 기준 transition id 2) / **dev-pr-check**(status 10006, statusCategory=진행 중 계열, transition id 31) / 완료(status 10005, transition id 41). (2026-07-13 팀이 진행 중과 dev-pr-check 사이에 **INSPECTION CHECK**를 신설 — "검수해줘" 단계.)
- **동기화 지점 (전역 워크트리 워크플로우 2단계·4단계·5단계·9단계에 얹는다):**
  1. **이슈 등록**(`gh issue create` 직후) → `createJiraIssue`(cloudId=`human2-team.atlassian.net`, projectKey=`HAJA`)로 대응 Jira 티켓 생성, 상태=**할 일**. 이슈 타입 매핑: GitHub 라벨 `bug`→버그, 그 외(`feature` 등)→작업. GitHub 이슈 본문에 Jira 키 추가 + Jira 설명에 GitHub 이슈 링크 추가(상호 참조).
  2. **브랜치 생성**(`git worktree add -b {branch}` 직후, 구현 서브 디스패치 직전) → Jira 상태를 **진행 중**으로 전환.
  3. **PR 생성**(`gh pr create`로 PR이 올라온 직후 = 검수 진입) → Jira 상태를 **INSPECTION CHECK**로 전환. 필요 시 PR 링크 코멘트. (사람/PR머신 검수가 여기서 일어남 — 반려되면 이 상태 유지, 통과·머지 시 다음 단계로.)
  4. **dev 머지**(PR머신 자동 머지 완료) → Jira 상태를 **dev-pr-check**로 전환(코드는 끝났지만 아직 미배포). 필요 시 dev PR 링크 코멘트. **+ GitHub 이슈에 `awaiting-promotion` 라벨 부여**(닫지 않음 — dev 머지로는 native 종료 안 되고, open=미배포가 정상). Jira 전환과 세트 → PR 흐름 §"GitHub 이슈 종료 규칙".
  5. **main 승격**(사람 승인 완료, 실배포) → 승격 PR에 포함된 이슈들의 Jira 키를 식별해 각각 **완료(Done)**로 전환 + main PR 링크 코멘트. 개별 승격이든 스프린트 단위 배치 승격이든 상관없이, 그 PR에 걸린 이슈 전부 처리. **+ 승격 PR 본문에 포함 이슈 전부 `Closes #N` 나열**(main 머지 시 GitHub 이슈 native 자동종료, `awaiting-promotion` 라벨 소멸) → PR 흐름 §"GitHub 이슈 종료 규칙".
- **한계**: ①②③은 Claude가 직접 수행하는 동작(이슈 생성·브랜치 생성·PR 생성) 직후라 그 자리에서 트리거되지만, ④⑤(dev 머지·main 승격)는 PR머신/사람이 하는 동작이라 Claude가 자동으로 감지하지 못한다. 사용자가 알려주거나 Claude가 `gh pr view` 등으로 상태를 확인하는 시점에야 전환이 실행된다.
- 정확한 transition ID는 이슈마다 `getTransitionsForJiraIssue`로 그때그때 조회해서 사용(상태명 하드코딩 금지 — Jira 워크플로우 스킴이 프로젝트별로 다를 수 있음).

## 문서 버전 관리 (강제 규칙, 2026-07-15 신설 — PR #214 회고)
> **SOT = `docs/README.md §2 "문서 버전 관리 방법 (A안)"`.** 아래는 그 절차를 **PR 검수 게이트에 강제로 얹는 룰**이다(절차 전문·예시는 §2 참조). 계기: #214가 "문서 버전관리 정리"를 목적으로 하면서도 released v0.2를 bump 없이 내용만 바꾸고, 릴리스된 적 없는 v0.3/v0.4를 헤더 없이 archive에 잘못 등재 — 머신 검수는 통과, 사람 검수에서 적발.

- **적용 대상**: `docs/**/*.md` 전부 + `docs/api-contract/openapi.yaml`. (`STATUS.md`는 살아있는 보드라 **제외** — §2와 동일.)
- **강제 절차 (문서 내용을 바꾸는 모든 PR)**:
  1. 그 문서가 **이미 릴리스됨**(= main에 올라간 적 있음)이고 이번에 **실질 내용**(스키마·계약·정책 등, 오타/서식 아님)을 바꾸면 → **반드시** ① 현재 root를 `archive/{파일}_v{현재버전}.{ext}`로 **버전 헤더 라인 유지한 채** 스냅샷 → ② root 내용 갱신 → ③ 헤더 `> **문서 버전:** vX.Y`를 **bump** + `최종 수정:` 날짜 갱신.
  2. **아직 릴리스 전**(같은 날 baseline 정정)이면 bump 없이 유지, 날짜만 갱신(archive 불필요) — §2 규칙 그대로.
  3. **신규 `docs/**/*.md`**는 H1 바로 아래 `> **문서 버전:** v0.1 …` 헤더를 **반드시** 포함.
  4. `openapi.yaml`은 md 헤더 대신 **native `info.version`** 필드를 bump(계약 실변경 시).
  5. **archive에는 실제 released된 버전만** 넣는다. 브랜치 내부 중간 상태(릴리스된 적 없는 버전명)를 archive에 등재 **금지**. archive 스냅샷은 **원본의 버전 헤더 라인을 그대로 보존**한다.
- **검수 강제력 (P1)**: 위 위반은 **머지 차단 P1**으로 취급한다 — 문서 PR이라 Trivial 티어여도, 버전관리 위반은 **PR머신 통과 여부와 무관하게 사람 검수에서 반려**(변경 요청)한다. G6(머지 직전) 체크에 "released 문서 실변경 시 archive 스냅샷+헤더 bump 존재" 항목을 포함한다.
- **검증 방법**: `git diff origin/dev...HEAD -- <문서>`에서 헤더 라인(`> **문서 버전:**` 또는 `info.version`) 변경 유무를, 내용 변경 유무와 대조. 내용은 바뀌었는데 헤더가 그대로면 위반.
