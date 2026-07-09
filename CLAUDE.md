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

## 스택
- `backend`=Java/Spring Boot · `ai-server`=Python/Flask · `frontend`=Next.js.
- 각 폴더 작업 시 전역 CLAUDE.md의 해당 스택 컨벤션·빌드/테스트 명령을 적용한다.

## Jira 연동 (Rovo MCP 오케스트레이션, 2026-07-08 확정, 2026-07-08 사이트 정정, 2026-07-08 프로젝트 키 정정)
- 팀 표준화로 paca → **Jira(`human2-team.atlassian.net`) + Slack**로 전환. GitHub↔Jira 양방향 동기화는 **네이티브 앱(GitHub for Jira) 설치 없이**, Claude가 워크플로우 단계마다 Atlassian Rovo MCP 도구를 직접 호출하는 방식으로 구현한다.
- **Jira 사이트 = `human2-team.atlassian.net`, 프로젝트 키 = `HAJA`** (프로젝트 표시명은 "haja-check"). ⚠️ 이름이 비슷한 `human-2team.atlassian.net`(프로젝트 키 `SCRUM`)은 **다른 사이트**이며 현재 claude.ai 커넥터 권한이 없음(재연결 시 `human2-team` 하나만 승인됨) — 헷갈리지 말 것. 이슈 타입: 에픽/스토리/작업/버그/Feature/Subtask. (과거 `KAN`으로 잘못 기재돼 있었음 — 2026-07-08 `jira-p2-sync.yml` 버그 조사 중 실제 라이브 데이터로 `HAJA`가 맞다는 걸 확인해 정정.)
- **동기화 지점 (전역 워크트리 워크플로우 2단계·9단계에 얹는다):**
  1. **이슈 등록**(`gh issue create` 직후) → `createJiraIssue`(cloudId=`human2-team.atlassian.net`, projectKey=`HAJA`)로 대응 Jira 티켓 생성, 상태=**할 일**. 이슈 타입 매핑: GitHub 라벨 `bug`→버그, 그 외(`feature` 등)→작업. GitHub 이슈 본문에 Jira 키 추가 + Jira 설명에 GitHub 이슈 링크 추가(상호 참조).
  2. **dev 머지**(PR머신 자동 머지 완료) → Jira 상태를 **진행 중**으로 전환(코드는 끝났지만 아직 미배포). 필요 시 dev PR 링크 코멘트.
  3. **main 승격**(사람 승인 완료, 실배포) → 승격 PR에 포함된 이슈들의 Jira 키를 식별해 각각 **완료(Done)**로 전환 + main PR 링크 코멘트. 개별 승격이든 스프린트 단위 배치 승격이든 상관없이, 그 PR에 걸린 이슈 전부 처리.
- 정확한 transition ID는 이슈마다 `getTransitionsForJiraIssue`로 그때그때 조회해서 사용(상태명 하드코딩 금지 — Jira 워크플로우 스킴이 프로젝트별로 다를 수 있음).
