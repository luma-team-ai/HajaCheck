# 전체 프로젝트 검수 보고 (2026-07-21)

> **문서 버전:** v0.1
> **최종 수정:** 2026-07-21
> 방식: 3스택 빌드/테스트 실행 + 스택별 리뷰어(Java/Python/TS) 병렬 전수 검수

## 1. 빌드/테스트 결과

| 스택 | 결과 | 비고 |
|---|---|---|
| frontend | ✅ 빌드 PASS + 테스트 536/536 | 1차 실패는 로컬 `npm install` 미실행(recharts 등 #413 이후 미설치)이었음 |
| ai-server | ✅ pytest 136/136 | 1차 실패는 `.venv`에 `langchain-chroma` 미설치였음 |
| backend | ⚠️ 499 중 150 실패 — **코드 결함 아님** | 아래 근본 원인 참조 |

### backend 150건 실패 근본 원인 (확정)
- 로컬 **Docker Engine 29.2.1은 API <1.44 요청을 400 거절** (`curl /v1.43/_ping` → "client version too old" 직접 확인).
- 프로젝트의 **Testcontainers 1.19.8(docker-java 3.3.6)** 이 구버전 API로 협상 → 컨테이너 기동 전원 실패.
- 테스트/프로덕션 코드 문제 아님. CI(구버전 Docker)는 통과하나 최신 Docker Desktop 로컬은 전원 재현.
- **수정**: Testcontainers 1.20.x+ bump → 이슈/PR 분리 처리.

## 2. 코드 리뷰 — 3스택 공통 **P1 없음**

### backend (Java) — P2 2건
1. `FacilityController.list()` / `ReportController.listReports()` 페이지네이션·상한 부재 (대시보드·알림은 상한 있음).
2. `MembershipService` 좌석 목록 무제한 조회 (maxSeats로 실질 상한 → 위험도 낮음).
- 긍정: Entity/DTO/예외 컨벤션 준수, path traversal 방어, 계정열거 방지, CSRF 더블서브밋, 필드주입 0, 하드코딩 시크릿 0.

### ai-server (Python) — P2 1건
1. `/ai/defect-explain`: 자유 문자열 4필드(defect_type/location 등)를 UNTRUSTED 마커 없이 프롬프트 직삽입 — `report_chain._wrap_untrusted` 미적용 구멍. → `ai/core/prompt_safety.py` 공용화 + 전 체인 적용 (P3: briefing_chain 동일).
- 긍정: 내부키 상수시간 비교, /docs fail-closed, 표준 폴백(200+success) 일관, async/sync 혼용 없음.

### frontend (TS) — P2 2건 + P3
1. `@stomp/stompjs` 미사용 의존성(참조 0건) — 제거 또는 `useCounselSocket` 골격 선행.
2. `eslint-plugin-react-hooks` 미설치 — exhaustive-deps 자동 검증 부재.
- P3: `CompanySignupPendingPage.tsx:52` 모래시계(⏳) 이모지 잔존(#479 승인대기 폐지 후 뉘앙스 불일치).
- 긍정: `any` 0건, 백엔드 Enum 정합, `implementedRoutes.ts` 화이트리스트 정착, MSW 프로덕션 누출 차단.

## 3. 후속 조치 (이슈 분리)
1. **[backend/chore]** Testcontainers 1.20.x bump — Docker Engine 29 호환 (최우선)
2. **[ai]** 프롬프트 인젝션 방어 공용화 (`prompt_safety.py` + defect_explain/briefing 적용)
3. **[backend]** 목록 조회 상한 — facility list + membership seats
4. **[frontend]** 위생 — stompjs 제거 + eslint-plugin-react-hooks 도입 + ⏳ 아이콘 교체

관련: [api-contract-audit-2026-07-21.md](api-contract-audit-2026-07-21.md) (API 명세 대조 — 팀 협의 후 진행 보류 중)
