# hajaCheck 문서 가이드 (`docs/`)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

이 문서는 `docs/` 폴더의 **진입점**이다. (1) 어떤 문서가 어디에 있는지, (2) 문서를 어떻게 버전 관리·최신화하는지, (3) 개발 가이드라인이 무엇인지 안내한다.

---

## 1. 폴더 구조 & SOT(단일 진실 소스)

| 경로 | 내용 | SOT 여부 |
|---|---|---|
| `api-contract/openapi.yaml` | REST API 스펙(OpenAPI) | ✅ **API 계약 SOT** |
| `api-contract/contract.md` | API 계약 사람용 요약·설명 | openapi 보조 |
| `api-contract/requirements_endpoints.md` | 요구사항↔엔드포인트 매핑 | 참조 |
| `conventions/` | 스택별 코딩 컨벤션 + 로컬 개발 가이드 + 도메인 규칙 | ✅ **컨벤션 SOT** |
| `design/ai/` | AI 체인 설계(브리핑·grounding·보고서) | 설계 |
| `design/db/table_design.md` | 테이블 설계서(컬럼·enum·제약 의미) | 설계(실 스키마는 아래 §3 참고) |
| `design/db/*.sql` | 참조용 DDL 스크립트 (**배포 실행본 아님**) | 참조 |
| `prd/PRD_hajaCheck.md` | 제품 요구사항 정의서(최신) | ✅ **요구사항 SOT** |
| `report/` | 보고서·버전 정합 자료 | 산출물 |
| `STATUS.md` | 인프라·마지막 머지·다음 작업·알려진 이슈 | ✅ **운영 현황 SOT** |
| `*/archive/` | 각 문서의 **구버전 스냅샷** | 이력 |
| `_local/` | 내부 작업지시·개인 메모(비공개) | — |

> **STATUS.md는 계속 갱신되는 "살아있는 보드"라 버전 관리(§2) 대상이 아니다.**

---

## 2. 문서 버전 관리 방법 (A안)

**원칙: 루트 파일 = 항상 최신본, `archive/` = 지나간 구버전.** 파일명은 그대로 두고(참조 안 깨지게) 버전은 문서 상단 헤더로 표기한다.

### 헤더 형식 (모든 `docs/**/*.md` 최상단, H1 제목 바로 아래)
```markdown
# 문서 제목

> **문서 버전:** vX.Y · **최종 수정:** YYYY-MM-DD · 이전 버전 `archive/`
```

### 갱신 절차 (내용을 바꿀 때)
1. **이미 릴리스된 버전이면**(= main에 올라간 적 있으면) 현재 루트 파일을 같은 디렉토리의 `archive/`에 **버전명으로 스냅샷**한다.
   `cp design/db/table_design.md design/db/archive/table_design_v0.1.md`
2. 루트 파일 내용을 수정한다.
3. 헤더 버전을 **bump**하고(`v0.1 → v0.2`) `최종 수정:` 날짜를 갱신한다.

### 규칙 요약
- **넘버링 없던 문서 → v0.1**부터 시작.
- **아직 릴리스 전(같은 날 baseline 정정)이면 bump 없이 v0.1 유지**, 날짜만 갱신(archive 불필요).
- `openapi.yaml`은 YAML이라 md 헤더 대신 **native `info.version`** 필드가 버전 역할.
- **PRD**: 루트 `PRD_hajaCheck.md`(헤더에 실버전) + `prd/archive/`에 구버전. 파일명에 버전 박지 않음.
- **STATUS.md는 버전 대상 아님**(살아있는 보드).

**예시(현재 상태):** `PRD_hajaCheck.md`(v0.43) · `table_design.md`(v0.3, archive에 v0.1/v0.2) · `contract.md`/`openapi.yaml`(v0.3, archive에 v0.1/v0.2) · 나머지 문서 v0.1.

---

## 3. 문서를 코드와 맞추는 법 (내용 최신화)

문서는 **코드·실제 환경을 따라간다.** "문서가 이렇다고 적혀 있다"가 아니라 **실제 구현이 진실**이다.

- **API**: `openapi.yaml`/`contract.md`는 실제 컨트롤러(백엔드 `@RestController`, AI서버 `ai_router.py`)와 **대조**해서 맞춘다. "구현완료"라고 적혀 있어도 라우트가 실제 있는지 확인.
- **DB 스키마**: `design/db/*.sql`은 **참조용(미배포)**이다. 실제 제약(FK·삭제정책 등)은 **라이브 DB를 실측**해서 확인한다.
  ```bash
  ssh oci-arm1 "sudo -u postgres psql -d hajacheck -tAc \
    \"SELECT pg_get_constraintdef(oid) FROM pg_constraint \
      WHERE conrelid='{테이블}'::regclass AND contype='f';\""
  ```
- **배포 순서**: **문서 내용 최신화 → 검증 → 배포**. 최신화 안 된 문서를 그대로 승격하지 않는다.
- ⚠️ 현재 스키마는 **Flyway 등 마이그레이션 도구가 없어 수동 관리** 상태다(엔티티가 FK를 관계 매핑하지 않는 경우도 있음). 스키마-문서 정합은 실측으로 확인할 것.

---

## 4. 개발 가이드라인 (기존 문서 인덱스)

| 주제 | 문서 |
|---|---|
| **전역 규칙**(커밋·시크릿 스캔·워크트리·사이클·게이트) | 레포 루트 `CLAUDE.md` (+ 전역 `~/.claude/CLAUDE.md`) |
| **Java/Spring Boot 컨벤션** | `conventions/SpringBoot_코드_컨벤션.md` |
| **React(Vite SPA) 컨벤션** | `conventions/React_코드_컨벤션.md` |
| **AI(FastAPI·LLM 체인) 컨벤션** | `conventions/AI_개발_컨벤션.md` |
| **로컬 개발 세팅**(인프라 기동·시드 로그인·MSW) | `conventions/로컬_개발_가이드.md` |
| **도메인 규칙**(하자 심각도 등급) | `conventions/하자_심각도_등급_규칙.md` |
| **API 계약** | `api-contract/openapi.yaml`(SOT) + `contract.md` |
| **DB 설계** | `design/db/table_design.md` |
| **운영 현황·인프라·다음 작업** | `STATUS.md` |

---

## 5. 브랜치·PR·배포 워크플로우 (요약)

- **스택**: `backend`(Java/Spring Boot) · `ai-server`(Python/FastAPI) · `frontend`(React/Vite SPA)
- **브랜치**: `{역할}/{이슈}-{내용}` 개별 워크트리에서 작업. **main 직접 코드 커밋 금지**(비코드 문서만 예외).
- **PR base = `dev`** (기능 PR은 항상 dev로).
- **dev 검수**: PR머신이 티어별 자동 검수·머지. **머신 오프 시** 메타가 로컬 검수(code-reviewer·security-reviewer + G6 게이트) 수행.
- **dev → main 승격**: 프로덕션 배포. **사람 승인(운영자)** 필수 — 자동 아님. 승격 PR + G6(운영 config·destructive·회귀 점검) 통과 후 머지 → CD(arm1) → 헬스 확인.
- **Jira 동기화**(HAJA 프로젝트): 이슈 등록→할 일 / 브랜치→진행 중 / PR→INSPECTION CHECK / dev 머지→dev-pr-check / main 승격→완료.

---

> 이 문서를 고칠 때도 §2 규칙을 따른다(헤더 버전 bump + 필요 시 archive).
