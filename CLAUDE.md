# HajaCheck — 프로젝트 CLAUDE.md (전역 오버라이드)

> 이 레포는 **PR머신 게이트 레포**(`luma-team-ai/HajaCheck`)다. 전역 `~/.claude/CLAUDE.md`를 **그대로 상속**하고, 아래 항목만 덮는다. 겹치지 않는 룰(시크릿 스캔·커밋 컨벤션·워크트리 워크플로우·이슈/PR 규칙·G1/G6 게이트)은 전역을 따른다.

## PR 흐름 (전역 `base=main` 오버라이드)
- **PR base = `dev`** (전역 기본 main 아님). 기능 PR은 항상 `dev`로 올린다.
- `dev`로의 PR은 **PR머신이 자동 검수·머지**한다(티어별 sonnet/opus). 사람이 먼저 머지하지 않는다.
- `dev → main` 승격은 머신이 검수 후 **사람 핑(hold_promote)** — 자동 머지 아님, 운영자가 승인.

## 검수 위임 (PR머신 가동 중일 때만)
전역의 "메타 수동 검수 사이클(code-reviewer 호출 + G3.5)"은 PR머신 티어 검수와 겹친다. 중복 제거:
- **Normal / Trivial** → 메타 수동 code-reviewer·G3.5 **생략**. PR머신 티어 검수(sonnet)에 위임. 사람은 머신이 단 반려(`ai:p1-blocked`) 사유만 확인.
- **Critical**(인증·인가·결제·분산정합성·webhook·멱등성) → 전역대로 **메타 code-reviewer 1회 유지**. 머신 opus 2차 검증과 이중이 되지만 이 영역은 의도된 안전장치.
- **머신이 꺼져 있으면(go-live 전 공백 기간)** → 위임 무효, **전역 검수 사이클을 그대로 수행**. (무검수 PR 방지)

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

## Jira 연동 (Rovo MCP 오케스트레이션, 2026-07-08 확정, 2026-07-08 사이트 정정, 2026-07-08 프로젝트 키 정정, 2026-07-11 4단계 동기화로 확장, 2026-07-13 INSPECTION CHECK 추가로 5단계 확장)
- 팀 표준화로 paca → **Jira(`human2-team.atlassian.net`) + Slack**로 전환. GitHub↔Jira 양방향 동기화는 **네이티브 앱(GitHub for Jira) 설치 없이**, Claude가 워크플로우 단계마다 Atlassian Rovo MCP 도구를 직접 호출하는 방식으로 구현한다. PR머신 repo(`.github/workflows/*.yml` 등)에는 아무것도 추가하지 않는다 — PR머신은 리뷰·머지만 하고 Jira를 모른다.
- **Jira 사이트 = `human2-team.atlassian.net`, 프로젝트 키 = `HAJA`** (프로젝트 표시명은 "haja-check"). ⚠️ 이름이 비슷한 `human-2team.atlassian.net`(프로젝트 키 `SCRUM`)은 **다른 사이트**이며 현재 claude.ai 커넥터 권한이 없음(재연결 시 `human2-team` 하나만 승인됨) — 헷갈리지 말 것. 이슈 타입: 에픽/스토리/작업/버그/Feature/Subtask. (과거 `KAN`으로 잘못 기재돼 있었음 — 2026-07-08 `jira-p2-sync.yml` 버그 조사 중 실제 라이브 데이터로 `HAJA`가 맞다는 걸 확인해 정정.)
- **워크플로우 상태 5개** (`getTransitionsForJiraIssue`로 확인됨, transition id는 이슈마다 다시 조회할 것 — 아래는 참고용): 해야 할 일(status 10003) / 진행 중(status 10004) / **INSPECTION CHECK**(status 10046, 검수 단계, statusCategory=진행 중 계열, HAJA-117 기준 transition id 2) / **dev-pr-check**(status 10006, statusCategory=진행 중 계열, transition id 31) / 완료(status 10005, transition id 41). (2026-07-13 팀이 진행 중과 dev-pr-check 사이에 **INSPECTION CHECK**를 신설 — "검수해줘" 단계.)
- **동기화 지점 (전역 워크트리 워크플로우 2단계·4단계·5단계·9단계에 얹는다):**
  1. **이슈 등록**(`gh issue create` 직후) → `createJiraIssue`(cloudId=`human2-team.atlassian.net`, projectKey=`HAJA`)로 대응 Jira 티켓 생성, 상태=**할 일**. 이슈 타입 매핑: GitHub 라벨 `bug`→버그, 그 외(`feature` 등)→작업. GitHub 이슈 본문에 Jira 키 추가 + Jira 설명에 GitHub 이슈 링크 추가(상호 참조).
  2. **브랜치 생성**(`git worktree add -b {branch}` 직후, 구현 서브 디스패치 직전) → Jira 상태를 **진행 중**으로 전환.
  3. **PR 생성**(`gh pr create`로 PR이 올라온 직후 = 검수 진입) → Jira 상태를 **INSPECTION CHECK**로 전환. 필요 시 PR 링크 코멘트. (사람/PR머신 검수가 여기서 일어남 — 반려되면 이 상태 유지, 통과·머지 시 다음 단계로.)
  4. **dev 머지**(PR머신 자동 머지 완료) → Jira 상태를 **dev-pr-check**로 전환(코드는 끝났지만 아직 미배포). 필요 시 dev PR 링크 코멘트.
  5. **main 승격**(사람 승인 완료, 실배포) → 승격 PR에 포함된 이슈들의 Jira 키를 식별해 각각 **완료(Done)**로 전환 + main PR 링크 코멘트. 개별 승격이든 스프린트 단위 배치 승격이든 상관없이, 그 PR에 걸린 이슈 전부 처리.
- **한계**: ①②③은 Claude가 직접 수행하는 동작(이슈 생성·브랜치 생성·PR 생성) 직후라 그 자리에서 트리거되지만, ④⑤(dev 머지·main 승격)는 PR머신/사람이 하는 동작이라 Claude가 자동으로 감지하지 못한다. 사용자가 알려주거나 Claude가 `gh pr view` 등으로 상태를 확인하는 시점에야 전환이 실행된다.
- 정확한 transition ID는 이슈마다 `getTransitionsForJiraIssue`로 그때그때 조회해서 사용(상태명 하드코딩 금지 — Jira 워크플로우 스킴이 프로젝트별로 다를 수 있음).
