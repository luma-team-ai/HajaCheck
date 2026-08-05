# API 명세 ↔ 구현 대조 검수 (2026-07-21)

> **문서 버전:** v0.1
> **최종 수정:** 2026-07-21
> 대상: `docs/api-contract/openapi.yaml` (v0.6.0-draft) vs 실제 구현(Spring Boot 컨트롤러 12 + FastAPI 라우터)
> 목적: 명세 32개 path와 실제 엔드포인트 전수 대조 → 갱신 대상 식별

## 결론 요약
명세가 **보고서 영속화·멤버십·대시보드·AI 프록시 계층 도입 이후 갱신되지 않아** 실제 구현과 벌어짐.
- **A** = 아직 미구현(로드맵상 정상, 계약만 선행) → 지금 못 씀
- **B** = 구현 완료됐으나 명세 미갱신(스펙이 뒤처짐)
- **C** = 명세 초안과 실제 구현이 구조적으로 갈라짐(method/path 불일치) → 조정 필요

---

## 🔴 A. 명세 O / 구현 X — 아직 구현 안 됨(못 쓰는 상태)
| 명세 path | 현황 |
|---|---|
| `POST /api/inspections/{id}/analyze` | 미구현 (InspectionController엔 POST `/`, GET `/{id}`만) |
| `GET /api/inspections/{id}/defects` | 미구현 |
| `GET /api/defects`, `GET /api/defects/{id}`, `POST /api/defects/nl-search` | **DefectController 부재** — 엔티티·리포지토리만 존재. read API는 PR **#372**(`backend/17-defect-list-detail`) 진행 중 |
| `POST /ai/rag-chat` | ai-server 라우트 없음 (계약만 확정 #459) |
| `POST /ai/nl-search` | ai-server 라우트 없음 |

→ **A는 계약이 구현을 앞서 정의한 정상 케이스.** 현재 호출 불가. defect 계열은 #372 머지 시 A→구현 전환.

---

## 🟡 B. 구현 O / 명세 X — 개발만 되고 명세 갱신 누락
| 실제 엔드포인트 | 도입 PR | 담당 |
|---|---|---|
| `GET /api/me/plan`, `GET /api/me/seats`, `POST /api/me/plan/upgrade-inquiry` | 멤버십 API | (MembershipController) |
| `POST /api/auth/password-reset-request`, `POST /api/auth/password-reset` | 계정복구 | (AccountRecoveryController) |
| `GET /api/dashboard/{summary, grade-distribution, pending-priority, recent-inspections}` | 대시보드 | (DashboardController) — 명세엔 `upcoming-inspections` 1개만 |
| `POST /api/ai/{defect-explain, report, briefing}` (Spring 프록시) | #236 내부키 경유 | (AiProxyController) — 명세는 `/ai/*` 직접만 기술, 프록시 계층 누락 |
| `POST /ai/business-license-ocr` | ai-server | 사업자등록증 OCR, 명세 없음 |

→ **B는 "구현이 명세를 앞질렀는데 SOT를 안 고친" 케이스.** 계약에 추가만 하면 됨(구조 논쟁 없음).

---

## 🟠 C. 명세-구현 불일치 — 초안과 실제 구현이 갈라짐 (담당자 확인 완료)
| 명세(초안) | 실제 구현 | 판정 |
|---|---|---|
| `POST /api/reports` — "보고서 생성 요청(비동기 잡 발족)" | 생성은 `POST /api/inspections/{id}/reports`(초안 생성). 단독 `/api/reports` POST **없음** | 명세 stale |
| `GET /api/reports/{id}/pdf` — 단일 다운로드 | `POST /api/reports/{id}/pdf`(업로드/저장) + `GET /api/reports/{id}/pdf/{storageKey}`(다운로드)로 분리 | method·path 불일치 |
| (명세 없음) | `GET /api/inspections/{id}/reports`(버전 목록), `GET /api/reports/{id}`, `PATCH /api/reports/{id}`, `POST /api/reports/{id}/finalize` | 실제만 존재 |

### 담당자
- **명세 초안 작성**: **오영석** — `docs(ai-server): OpenAPI 계약 초안 + 점검관리B 요구사항 명세 (HAJA-20)`
- **실제 구현**: **Ketose** — `feat: 보고서 영속화 — 초안 생성/조회/수정/확정 + PDF 저장 (#446)(#455)`

→ **C 조정 주체 = Ketose(구현이 진실 소스)** + 오영석(계약 오너 협의).
실제 구현이 더 정교(초안 생성/버전관리/finalize 분리)하므로 **openapi를 Ketose 구현에 맞춰 재작성**하는 방향이 맞음.

---

## 후속 조치(제안)
1. **B/C를 openapi.yaml에 반영** + `info.version` bump(v0.6.0-draft → v0.7.0). 문서 버전관리 규칙상 계약 실변경 → bump 강제.
2. **A는 `x-status: planned` 마킹** 또는 그대로 두되 미구현 명시.
3. C는 Ketose에게 계약 재작성 위임 or 메타가 구현 기준으로 정리 후 오영석 리뷰.
