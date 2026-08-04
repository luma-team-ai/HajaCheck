# PRD ↔ 테스트 시트 ↔ 코드 정합성 검사 (2026-08-04)

> **문서 버전:** v0.1
> **최종 수정:** 2026-08-04
> 대상: PRD v1.1 · 통합테스트 시나리오 시트 v1.3 · 단위테스트 시트 · dev HEAD `a877b0b3`
> 방법: 3개 스택 테스트 전수 실행 + 시트 항목별 코드 실측 대조

## 1. 테스트 실행 결과 (dev HEAD 실측)

| 스택 | 명령 | 결과 |
|---|---|---|
| backend | `./gradlew test --rerun-tasks` | 2,111 tests — **1회차 2 fail / 2회차 0 fail**(플레이키, 아래) |
| ai-server | `pytest` | **421 passed** |
| frontend | `npm test` (vitest) | **1,843 passed / 8 failed** (247 파일 중 3개) |

### 백엔드 플레이키 테스트 1건

`CounselWebSocketIntegrationTest` 2건(`정상세션_연결_메시지전송_저장_브로드캐스트`, `타인티켓_구독_거부`)이
전체 실행 1회차에서 `ConnectionLostException: Connection closed`로 실패했다가, **단독 실행과 전체 2회차에서는
통과**했다. 전체 스위트 부하 상태에서 STOMP 연결 타임아웃이 걸리는 타이밍 의존으로 보인다.
CI 신뢰도를 갉아먹으므로 연결 대기 타임아웃 상향 또는 격리 실행 검토 대상.

### 프론트 실패 8건 — 로컬 Node 버전 이슈 (제품 코드 결함 아님)

실패 파일: `facilityMediaApi.test.ts`(5) · `FacilityListPage.test.tsx`(1) · `ReportGeneratePage.test.tsx`(2)

전부 동일 원인이다. multipart 요청(사진 업로드·PDF 확정)을 MSW가 가로챌 때 Node 내장 undici의
`multipartFormDataParser`가 assertion으로 죽는다:

```
AssertionError [ERR_ASSERTION]: assert(typeof value === "string" && webidl.is.USVString(value) || webidl.is.File(value))
    at multipartFormDataParser (node:internal/deps/undici/undici:6386:9)
[MSW] Encountered an unhandled exception during the handler lookup for "POST /api/facilities/1/media"
```

- 로컬 Node **v24.14.0**, CI(`.github/workflows/ci.yml`)는 **Node 20** → CI는 GREEN(dev 최근 런 success).
- 재현은 결정적(재실행해도 동일 8건), 그러나 실패 지점이 전부 `request.formData()` 파싱 단계라
  핸들러·제품 코드 진입 전이다.
- 조치 제안: 로컬 개발 Node를 20 LTS로 고정(`.nvmrc` 추가)하거나 msw/undici 업데이트로 회피.
- 부수 확인: `ReportGeneratePage` 실행 중 미등록 핸들러 경고 1건 —
  `GET /api/inspections/1/defects/deleted` (테스트 노이즈, 실패 원인은 아님).

## 2. 시트가 코드와 어긋난 항목 (결과 정정 필요)

| # | 시트 항목 | 시트 기재 | 코드 실측 | 근거 |
|---|---|---|---|---|
| 2-1 | **UT-074 / HC_016_07** | `/action`은 상태머신을 트리거하지 않고 **조치 필드만** 갱신, `defect.status` 변경 없음 | `/action`은 **`targetStatus`(IN_PROGRESS·RESOLVED) 필수**이며 정방향 규칙으로 **상태를 전이**한다(#1128) | `DefectService.registerActionResult` / `DefectTest.registerActionResult_CONFIRMED에서_IN_PROGRESS로전이하고조치필드저장` / `openapi.yaml:2756` |
| 2-2 | **HC_007_02** | 이미지 다수 + **영상 1건** 업로드, "영상은 프레임 자동 추출" → PASS | 업로드 허용 타입 = `image/jpeg`·`image/png` **뿐**. 영상은 `FILE_INVALID_TYPE`으로 거부 | `application.yml:79-87`, `MediaUploadProperties:15` · 같은 시트 UT-082와 정면 충돌 |
| 2-3 | **HC_003_01/02/04** | 아이디·비밀번호 찾기 = **이메일 인증코드 발송·검증** | 아이디 찾기 = **사업자번호 + 상호명/대표자명 대조**, 비밀번호 = **재설정 링크 + 1회성 토큰** | `AccountRecoveryService` / `PasswordResetService:52 RESET_PATH="/reset-password?token="` (UT-012~021이 정본) |
| 2-4 | **HC_001_03/04** 비고 | "N/A 설계변경으로 **코드 없음**" | 진위확인 버튼 + 결과 뱃지 6종 + 가입 게이팅 **FE 구현 완료(#663)**, BE API도 존재 | `CompanySignupPage.tsx:69-70`, `CompanySignupPage.businessVerification.test.tsx` |
| 2-5 | **HC_015_05 / HC_014 검증포인트5** | 시스템 모니터링에 **HF 사용량 · 예산 가드레일 경고** 표시 → PASS | 모니터링 응답은 `serverHealth·jobQueue·resourceUsage·errorLogs` **4종뿐**. HF 사용량 카드는 **서버 자원 카드로 의도적 대체(#728 — HF 사용량 공개 API 부재)**, 예산 가드레일은 미구현 | `SystemMonitoringResponse.java:9-14`, `monitoring.types.ts:40-41` |

> 2-4의 **HC_001_02(이메일 [중복확인] 버튼)**만은 비고가 맞다 — `GET /api/auth/email-availability`는
> 존재하지만 가입 화면에 버튼 UI는 없다(해당 API는 CSRF prime 용도로도 쓰인다).

## 3. 시트 FAIL 항목 — 코드로 확증됨 (시트가 정확)

| 항목 | 확증 내용 |
|---|---|
| **HC_016_06** (역행/건너뛰기 사유 UI 미연결) | `DefectStatusReasonModal` → `DefectActionBoard` → **어떤 라우트에서도 렌더되지 않음**. `DefectListPage.tsx:18-22` 주석에 "보드 보기 탭 롤백(#726) 이후 참조만 제거" 명시. 백엔드는 지원(`DefectControllerTest.하자상태전이_사유있는건너뛰기요청_200`) |
| **HC_011_03** (대화 맥락 미유지) | `session_id`는 `ai_router.py:93` 요청 스키마에만 존재하고 체인에서 **전혀 사용되지 않음**. 백엔드 프록시는 아예 전송하지 않음(`RagChatAiRequest` javadoc: "세션·이력 연동 전") |

## 4. PRD ↔ 코드 드리프트 (신규 — `_local/prd-code-alignment-plan` 미조사 영역)

기존 플랜(로그인/회원가입/마이페이지 6건)은 그대로 유효하고, 아래는 이번에 추가로 확인된 건이다.

| # | PRD | PRD 기재 | 코드 실측 | 판정 제안 |
|---|---|---|---|---|
| 4-1 | FR-9 `:657` | 알림 구현 = `notifications` 테이블 + **폴링(30초)** | `useNotifications`에 `refetchInterval` **없음** — 마운트·포커스 시 refetch만 | 구현에 맞춰 PRD 수정(또는 폴링 추가 여부 결정) |
| 4-2 | FR-8-B `:649-650` | **HF Inference API 호출량·누적 토큰**, **예산 가드레일**(한도 초과 경고 배지 + 디그레이드 모드) | 둘 다 미구현. HF 카드는 #728에서 서버 자원 카드로 대체 결정 | PRD를 구현에 맞춤(대체 사실 명기) + 가드레일은 후속 이슈 |
| 4-3 | FR-6 `:608` | 대화 이력 관리(**LangChain Memory + Redis**) | 미구현(§3 HC_011_03). 같은 절 `:610`이 "멀티턴은 후속 범위(YAGNI)"라 **PRD 내부 모순** | PRD 정정 — `:608`을 후속 범위로 이동 |
| 4-4 | FR-4 `:570` | `/action`은 상태 전이(`/status`)와 **의도적 분리**, **4필드** | 엔드포인트는 분리돼 있으나 `/action`이 **`targetStatus`로 전이까지 수행**, 필수 필드는 **5개** | PRD·`contract.md:485`("상태 전이는 RESOLVED로 고정") 갱신 — `openapi.yaml`은 이미 최신 |

## 5. UT 시트 ↔ 자동 테스트 커버리지

### 5-1. "PASS"인데 대응 자동 테스트가 없는 항목

| UT | 항목 | 상태 |
|---|---|---|
| UT-031 | EXIF GPS 정상 좌표 추출 | `ExifGpsExtractorTest`에 GPS 태그 추출 성공 케이스 **없음**(범위 가드·파싱 실패 케이스만) |
| UT-032 | EXIF 없는 PNG → EMPTY | 유사 케이스(`무작위 가비지 바이트_EMPTY`)만 존재, PNG 지정 케이스 없음 |
| UT-034 | Orientation 범위 밖 → 기본값 1 | 구현은 있음(`ExifGpsExtractor:36,113`), **테스트 없음** |
| UT-082 | `video/mp4` 업로드 거부 | 설정으로는 차단되나 이를 검증하는 테스트 **없음** |
| UT-008 | 이메일 중복확인 API | 전용 단위 테스트 없음(시트 비고와 일치) — `CompanyAuthIntegrationTest`가 통합 수준에서 커버 |

### 5-2. "미수행"인데 자동 테스트가 이미 있는 항목 (수행 처리 가능)

| UT | 대응 테스트 |
|---|---|
| UT-022~025 | `BuiltYearValidatorTest` — `nullIsValid` · `inRange_isValid` · `outOfRange_isInvalid` · `upperBoundFollowsCurrentYear` |
| UT-066~068 | `InspectionDueNotificationSchedulerTest` — dedupe skip · 개별 실패 격리 · 조회 실패 페이지 스킵 등 20여 건 |
| UT-069 | `DashboardServiceTest:336` — `ZoneId.of("Asia/Seoul")` 기준 집계 |
| UT-070 | `DashboardServiceTest.getPendingPriority_이름유형주소모두있으면_3파트를이어붙인다` |
| UT-079/080 | `FacilityServiceTest.create_등록_소유자와입력값으로저장` · `create_초기등급담당자메모_함께저장` |

### 5-3. 문구 정정 필요

- **UT-081**: "0 이하 거부"는 **점검주기 설정 요청에만** 해당(`FacilityScheduleRequest` `@Min(1)`).
  **등록 폼은 `@Min(0)`으로 0을 의도적으로 허용**한다(= "주기 미설정", `FacilityCreateRequest:32-34` 주석).
- **UT-078**: 부분 유니크 인덱스 `uq_company_memberships_approved_user`는 `V1`에 실재하나,
  **제약 위반을 검증하는 테스트는 없다**(`CompanyMembershipRepositoryTest`는 stale pointer 케이스 1건뿐).

## 6. 시트 자체 정합성 문제

1. **시나리오 케이스 ID가 시나리오 ID와 +1 어긋남** — `HC_009`(보고서) 시나리오의 케이스가 `HC_010_01~05`,
   `HC_010`(RAG)의 케이스가 `HC_011_xx` … `HC_015`의 케이스가 `HC_016_xx`. `HC_008`까지는 정상.
   개정이력 v1.2의 "HC_009 폐기 → HC_016으로 흡수"에서 시나리오 번호만 당겨진 흔적으로 보인다.
   요약표는 `HC_015`까지인데 케이스는 `HC_016`까지 존재해, 요약표 비고도 "HC_016_06 Fail"로 적혀 있다.
2. **단위 시트 상단 전체목록 ↔ 메뉴별 시트 결과 상충**
   - UT-031·032·034: 상단 **PASS**(황승현 07-30) ↔ 메뉴별 **미수행**
   - UT-082: 상단 **미수행** ↔ 메뉴별 **PASS**(황승현 08-04)
   - UT-026~030: 동일 PASS이나 수행일자 상이(07-30 ↔ 08-04)
   - UT-069/070·022~025·078~081: 상단에 수행자 있으나 메뉴별 시트는 수행자 공란
3. **UT-085 중복 등재** — 상단 전체목록과 "랜딩페이지" 시트에 동일 내용 2회.
4. **UT-008 비고 모순** — 결과 PASS인데 비고는 "전용 단위 테스트 부재".
5. **UT-030/031 행 순서 뒤바뀜**(상단 목록).
6. **화면ID ↔ 실제 라우트 불일치**(경미):
   `inspections/:id/detail` → 실제 `/inspections/:id/defects` ·
   `support/counsel-history` → `/support/history` ·
   `platform-admin/counsel` → `/platform-admin/counsels` ·
   `defect-modal > compare` → `/facilities/:id/defects/:defectId/compare`

## 7. 우선 조치 제안

| 순위 | 항목 | 사유 |
|---|---|---|
| P1 | §2 5건 시트 결과 정정 | "PASS"로 남으면 미구현·설계변경 사실이 배포 판단에서 가려진다 (특히 2-2 영상, 2-5 예산 가드레일) |
| P1 | §4 PRD 4건 갱신 + `contract.md:485` | PRD v1.1이 릴리스본이므로 archive 스냅샷 + 헤더 bump 규칙 적용 대상 |
| P2 | HC_016_06 dead code 배선 또는 제거 결정 | 백엔드 기능이 UI 없이 방치 상태 |
| P2 | §5-1 테스트 5건 보강 | 시트상 PASS 근거가 수동 확인뿐 |
| P2 | `CounselWebSocketIntegrationTest` 플레이키 해소 | 전체 실행 2회 중 1회 실패 → CI 재실행 유발 |
| P3 | 로컬 Node 20 고정(`.nvmrc`) | 로컬 `npm test`가 항상 빨간 상태 → 실제 회귀를 가린다 |
| P3 | §6 시트 ID 체계 정리 | 케이스 ID ↔ 시나리오 ID 오프셋 |
