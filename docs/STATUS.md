# hajaCheck — STATUS

> 마지막 갱신: 2026-07-03

## 인프라

| 항목 | 상태 | 비고 |
|---|---|---|
| GitHub 레포 | ⬜ 미생성 | `gh repo create` 필요 |
| OCI A1 VM | ⬜ 미확보 | 착수 주간(7/9~13) 선확보 — Out of capacity 리스크 |
| 공용 개발 DB (PostgreSQL/Redis) | ⬜ | VM 확보 후 설치, 팀원 IP 제한 |
| OAuth 앱 (Kakao/Google) | ⬜ | 착수 주간 등록 선행 |
| GitHub Actions CI | ✅ 워크플로 작성 | `.github/workflows/ci.yml` |

## 마지막 머지 PR

- 없음 (스켈레톤 생성 단계)

## 다음 작업

- **P0**
  - [ ] GitHub 레포 생성 + develop 브랜치 + 브랜치 보호 규칙
  - [ ] 착수 회의: 담당자 배정, TS/JS·스타일 방식 확정
  - [ ] OpenAPI 스펙 우선 커밋 (Contract-First)
  - [ ] ERD 초안 + 데이터셋 확보
- **P1**
  - [ ] AI-LLM 코치: llm_client 구현 + requirements 버전 확정 (7/15 온보딩 전)
  - [ ] 워킹 스켈레톤 관통 (7/18~19)

## 알려진 이슈

| 이슈 | 상태 |
|---|---|
| — | — |
