# hajaCheck 테이블 디자인 설계

> **문서 버전:** v0.3 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

- 대상 스키마 파일: [HajaCheck_script.sql](HajaCheck_script.sql)
- DB 엔진: PostgreSQL — RAG 벡터 검색은 PostgreSQL이 아닌 **Chroma**(FastAPI 임베디드, 로컬 파일 저장)가 전담한다. PostgreSQL에는 RAG 문서 메타데이터와 인용 참조 정보만 저장한다 (§2.4, §5.5 참조).
- 기준 문서: `report/hajaCheck착수 보고.pdf` 53~60p (테이블 정의서 1/8~8/8), [PRD_hajaCheck_v0.41.md](../../prd/archive/PRD_hajaCheck_v0.41.md)
- 인코딩: UTF-8 (BOM 없음)

---

## 목차

1. [개요](#1-개요)
2. [착수 보고서 대비 변경 사항](#2-착수-보고서-대비-변경-사항)
   - 2.4 [PRD v0.41 대비 정합성 검토](#24-prd-v041-대비-정합성-검토)
   - 2.5 [자체 회원가입(AUTH-02, 개인·기업) 반영](#25-자체-회원가입auth-02-개인기업-반영)
   - 2.6 [기업 임직원의 상담 이용 권한 상속 (plans/user_plans 회사 단위 확장)](#26-기업-임직원의-상담-이용-권한-상속-plansuser_plans-회사-단위-확장)
   - 2.7 [`rag_documents` 컬렉션 라우팅·시행일 컬럼 추가 (Chroma 메타데이터 설계 대조, HAJA-113)](#27-rag_documents-컬렉션-라우팅시행일-컬럼-추가-chroma-메타데이터-설계-대조-haja-113)
   - 2.8 [`rag_documents` 발행처·작성일·검증여부 컬럼 추가 (HAJA-143/144 필드 누락 보완)](#28-rag_documents-발행처작성일검증여부-컬럼-추가-haja-143144-필드-누락-보완)
3. [ERD 개요 (테이블 관계)](#3-erd-개요-테이블-관계)
4. [Enum 타입 정의](#4-enum-타입-정의)
5. [테이블 상세](#5-테이블-상세)
   - 5.1 [계정·인증](#51-계정인증)
   - 5.2 [과금·정책](#52-과금정책)
   - 5.3 [시설물·점검](#53-시설물점검)
   - 5.4 [결함·보고서](#54-결함보고서)
   - 5.5 [RAG·상담 공용 (챗봇/상담)](#55-rag상담-공용-챗봇상담)
   - 5.6 [공통 알림](#56-공통-알림)
6. [공통 함수·트리거](#6-공통-함수트리거)
7. [RAG·상담 공용 테이블 설계 요약](#7-rag상담-공용-테이블-설계-요약)
8. [핵심 요구사항 요약](#8-핵심-요구사항-요약)
9. [최종 산출물 목록](#9-최종-산출물-목록)

---

## 1. 개요

hajaCheck는 시설물 점검 사진/영상을 AI로 분석해 결함을 탐지하고, 점검 보고서를 생성하며, 사용자에게 RAG 기반 AI 문답·시나리오 챗봇·전문 상담사 상담을 제공하는 서비스다. 본 문서는 `hajaCheck_script.sql`(프로젝트 SQL 제네레이터 초안)을 기준으로 전체 테이블 구조, 컬럼, 키, enum 값의 의미를 정리하고, 착수 보고서(킥오프) 및 `PRD_hajaCheck_v0.41.md` 대비 무엇이 달라졌는지·정합성이 맞는지 기록한다.

전체 테이블은 아래 6개 영역으로 구성된다.

| 영역 | 테이블 |
|---|---|
| 계정·인증 | `users`, `companies`, `company_memberships`, `user_consents` |
| 과금·정책 | `plans`, `user_plans`, `usage_counters` |
| 시설물·점검 | `facilities`, `inspections`, `media` |
| 결함·보고서 | `defects`, `defect_revisions`, `reports` |
| RAG·상담 공용 | `chat_sessions`, `chat_messages`, `counsel_tickets`, `bot_scenarios`, `rag_documents`, `chat_message_citations` |
| 공통 알림 | `notifications` |

---

## 2. 착수 보고서 대비 변경 사항

착수 보고서(`hajaCheck착수 보고.pdf`) 53~60p "핵심 테이블 정의서 1/8~8/8"에는 8개 핵심 테이블(`users`, `facilities`, `inspections`, `reports`, `chat_sessions`, `rag_documents`, `plans`, `notifications`)만 정의되어 있었다. 이후 SQL 제네레이터 초안(`hajaCheck_script.sql`) 작업 과정에서 세부 설계가 구체화되면서 아래와 같이 달라졌다.

### 2.1 기존 8개 핵심 테이블의 변경 내역

| 테이블 | 착수 보고서(53~60p) 시점 | 최종 `hajaCheck_script.sql` | 변경 취지 |
|---|---|---|---|
| `users` | `social_provider`, `social_id` NULL 허용 | `social_provider`, `social_id` **NOT NULL** + `unique(social_provider, social_id)` | 소셜 로그인 전용 서비스로 확정 — 계정은 반드시 소셜 제공자에 귀속되며, 동일 제공자+동일 소셜 ID 중복 가입 방지 |
| `facilities` | `address`, `inspection_cycle_months` **NOT NULL** | 두 컬럼 모두 **NULL 허용** | 시설물 등록 시 주소/점검주기를 즉시 확정하지 않고 단계적으로 입력할 수 있도록 완화. PRD §6.3도 점검 주기 필드를 "P1"로 분류하고 있어 필수값이 아닌 것이 타당하다 |
| `inspections` | 컬럼 동일 | 변경 없음 + `unique(facility_id, round_no)` 명시 | 시설물별 회차 중복 방지 제약을 SQL 레벨에 고정 |
| `reports` | `created_by`/`updated_at` 컬럼 없음, `grounding_check_passed` NOT NULL | `created_by`(작성자), `updated_at`(트리거로 자동 갱신) 컬럼 **신규 추가**, `grounding_check_passed`는 **NULL 허용**으로 완화 | 보고서 최초 작성자와 최종 수정자(`edited_by`)를 구분해 이력 추적을 강화하고, 근거검증(Grounding)이 아직 수행되지 않은 "미검증" 상태를 NULL로 표현 가능하게 함 |
| `chat_sessions` | `id, user_id, session_type, started_at, ended_at` | 동일 | 변경 없음 — RAG/시나리오봇/상담을 하나의 세션 테이블로 묶는 설계는 착수 시점부터 유지 |
| `rag_documents` | `id, title, source_type, file_url, embedding_status, chunk_count, embedded_at, created_at` | 동일 | 문서 메타데이터 구조는 변경 없음. 실제 벡터 검색은 Chroma가 전담하므로 이 테이블은 시종일관 "메타데이터 저장소" 역할에 머문다(§2.4) |
| `plans` | `updated_at` 컬럼 없음 | `updated_at` **신규 추가** + `trg_plans_set_updated_at` 트리거 연결 | 요금제 변경 이력을 자동으로 추적하도록 갱신 시각 관리 일원화 |
| `notifications` | 컬럼 동일 | 변경 없음 | - |

### 2.2 착수 보고서에 없던 신규 테이블/컬럼 (RAG·상담 공용 설계 확장)

착수 보고서 53~60p에는 `chat_sessions`와 `rag_documents`만 존재했고, 실제 대화 메시지·상담 큐·시나리오 봇·RAG 출처 추적 구조는 정의되어 있지 않았다. SQL 초안 작업 중 다음을 신규로 설계·추가했다.

| 신규 대상 | 내용 | 배경 |
|---|---|---|
| `chat_messages` | 채팅 세션의 개별 메시지(발신자, 내용, 생성 시각) | 착수 보고서엔 세션만 있고 메시지 저장소가 없었음 |
| `chat_messages.scenario_id` | 사용자가 선택한 `bot_scenarios` 노드 참조 | 시나리오 봇 대화 이력 추적을 위해 추가 |
| `counsel_tickets.session_id` | `chat_sessions` 참조 FK | 상담 티켓(큐/배정/상태)과 실제 대화(`chat_messages`)를 연결해, 상담도 RAG·시나리오봇과 동일한 공용 메시지 구조를 재사용하도록 통합 |
| `bot_scenarios` | 부모-자식 계층형 시나리오 트리 테이블 | 버튼 선택형 챗봇 시나리오 관리용으로 신규 설계 |
| `chat_message_citations` | 메시지가 인용한 RAG 문서·Chroma 청크를 기록하는 참조 테이블 | 최초에는 `chat_messages.citation`(자유 텍스트)으로, 이후 한때 `rag_chunks`(pgvector)를 참조하는 조인 테이블로 설계했으나, 실제 벡터·청크 본문의 소유자가 Chroma임이 확정되어(§2.4) `rag_documents`(FK) + Chroma 청크 식별자(`chunk_ref`, 문자열)를 함께 저장하는 현재 구조로 정리 |

> `rag_chunks`(pgvector 기반 청크·임베딩 테이블)는 SQL 초안 중간 단계에서 한 차례 추가되었으나, PRD가 이미 Chroma를 벡터스토어로 확정하고 있다는 사실이 확인되어(§2.4) **최종적으로 제거**했다. 상세 경위는 §2.4, §5.5 참조.

### 2.3 착수 보고서 범위 밖에서 이미 설계되어 있던 테이블

`media`, `defects`, `defect_revisions`, `user_plans`, `usage_counters`는 53~60p 슬라이드 범위에는 없었지만(다른 슬라이드 또는 이후 설계 단계에서 정의), 현재 `hajaCheck_script.sql`에는 이미 포함되어 있다. AI 결함 탐지 파이프라인(미디어 등록 → 결함 탐지/수정 이력)과 요금제 사용량 집계 체계를 담당한다.

정정: §2.2에서 다룬 `chat_messages`, `counsel_tickets`, `bot_scenarios`, `plans`/`user_plans`/`usage_counters`, `notifications`는 정확히는 53~60p **슬라이드(컬럼 단위 정의서)**에만 없었던 것이며, `PRD_hajaCheck_v0.41.md` §6.3 "주요 데이터 모델(안)"에는 착수 보고서 작성 시점부터 **테이블명 수준으로 이미 나열**되어 있었다. 즉 SQL 초안 작업은 이 테이블들을 새로 발명한 것이 아니라, PRD가 개념적으로만 언급했던 테이블들을 컬럼·제약조건 수준까지 구체화한 것이다.

---

### 2.4 PRD v0.41 대비 정합성 검토

`PRD_hajaCheck_v0.41.md`를 함께 검토한 결과, 초기 SQL 초안에는 PRD와 어긋나는 지점이 있었고(§2.4.1, 현재는 수정 완료), PRD의 요구사항을 잘 반영한 지점도 있었다(§2.4.2).

#### 2.4.1 RAG 벡터스토어 — 확인 후 수정 완료

- **PRD 규정**: FR-6, §6.3에 RAG 벡터스토어로 **Chroma**("FastAPI 내 임베디드 모드, 로컬 파일 저장")가 명시되어 있고, 임베딩 모델도 **HuggingFace 한국어 모델(ko-sbert / BGE-m3, CPU)**로 정해져 있다.
- **초안 단계의 문제**: 한때 `pgvector` 확장과 `rag_chunks(embedding vector(1536))` 테이블을 PostgreSQL에 추가했었다. 이는 (1) 벡터를 저장·검색하는 장소를 Chroma가 아닌 PostgreSQL로 바꾸는 것이었고, (2) 임베딩 차원도 OpenAI 계열(1536) 기준으로 잘못 가정한 것이었다(ko-sbert ≈768, BGE-m3=1024차원).
- **확정 및 조치**: 사용자 확인 결과 Chroma가 확정 아키텍처임을 재확인했다. 이에 따라 `hajaCheck_script.sql`에서 `create extension if not exists vector;`와 `rag_chunks` 테이블을 **완전히 제거**했다. 실제 벡터 유사도 검색은 FastAPI 프로세스 내 Chroma가 전담하고, PostgreSQL에는 `rag_documents`(문서 메타데이터)와 `chat_message_citations`(어떤 메시지가 어떤 문서의 어떤 Chroma 청크를 인용했는지에 대한 참조 정보)만 남긴다(§5.5).

#### 2.4.2 PRD 요구사항이 잘 반영된 지점

| PRD 근거 | 반영 내용 |
|---|---|
| §6.3: "RAG 메시지에는 출처 citation 필드 필수 — KPI '출처 표기율 100%' 검증용" | `chat_messages.citation`(자유 텍스트) 대신 `chat_message_citations(message_id, document_id, chunk_ref)` 참조 테이블로 구현. 단일 텍스트 필드보다 구조화되어 있어 "출처 표기율 100%"를 답변 건수 대비 인용 레코드 존재 여부로 **기계적으로 집계**할 수 있다는 점에서 PRD 요구를 더 엄격하게 충족한다(§7 참조). |
| §11 오픈 이슈 3: "심각도 등급 체계 — 시설물 안전등급 A~E 준용 권장 (미확정)" | `defect_grade_type`이 이미 `A`/`B`/`C`/`D`/`E`로 정의됨. PRD상으로는 "팀 확정 필요"인 오픈 이슈였지만, 스키마 설계 시점에 PRD가 제시한 권장안을 그대로 채택해 선반영했다. 팀 논의에서 다른 체계로 바뀔 경우 이 enum도 함께 수정해야 한다. |
| FR-4: "하자 상태머신: 신규(AI탐지) → 검수확정 → 조치대기 → 조치중 → 조치완료" | `defect_status_type`(`DETECTED`→`CONFIRMED`→`ACTION_PENDING`→`IN_PROGRESS`→`RESOLVED`)이 순서·의미 모두 1:1로 대응한다. |
| FR-4: "defects 직접 UPDATE 대신 defect_revisions에 append-only 기록, 삭제는 Soft Delete" | `defect_revisions` 테이블과 `defects.is_deleted` 플래그로 그대로 구현됨. |
| FR-7: "상담원 부재 시 대기 순번 안내 + 문의 남기기(오프라인 티켓)" | `counsel_ticket_status_type.OFFLINE_LEFT`로 반영됨. |
| §2.4 비즈니스 모델: "상담" 이용 여부가 플랜별로 다름(Free=시나리오 챗봇만, Standard 이상=상담원 연결) | `plans.has_counselor_access` boolean으로 게이팅. RAG Q&A 자체는 P0 전 플랜 공통 기능이라 별도 플랜 제한 컬럼이 없는 것도 PRD와 일치한다. |
| FR-1: "역할(Role): 일반 사용자/점검자/관리자/상담원, 계층 ADMIN > INSPECTOR > USER, COUNSELOR는 별도 축" | `role_type`(`ADMIN`,`INSPECTOR`,`USER`,`COUNSELOR`)로 그대로 반영됨. |

---

### 2.5 자체 회원가입(AUTH-02, 개인·기업) 반영

**배경**: "자체 회원가입(AUTH-02)" 스토리보드(이메일·비밀번호, 회사명·사업자등록증 OCR, 개인/기업 탭)가 다른 문서에 반영되지 않은 상태였다. 확인 결과:

- 기능요구사항(FR-001~021), API 명세(AP-001~011): 소셜 로그인만 정의
- 로그인 업무흐름도: OAuth2만 정의
- ERD `users`: `password`, 회사/사업자 관련 컬럼 없음(소셜 전용 설계)

FR·API·플로우 동기화는 이번 범위에서 **제외**하고, ERD(DB 설계)부터 우선 진행하기로 했다. 스토리보드(기업 회원가입 화면, 로그인 화면 개인/기업 탭, 역할별 권한 매트릭스)를 근거로 다음을 확정했다.

| 결정 항목 | 확정 내용 |
|---|---|
| 회사-사용자 관계 | **1:N** — 회사 하나에 여러 임직원(`users`)이 소속. `companies` 테이블 신설 |
| 회사 소유자 구분 | `companies.owner_user_id`(FK→users) — 가입 신청자 본인이 계정 소유자(플랜 보유자, 협업자 초대 권한). `users.role`은 기능 권한(역할별 권한 매트릭스)만 나타내는 별개 축 |
| 사업자등록증 검증 | 국세청 사업자등록정보 **진위확인 API** 연동 — OCR 자동 인식과는 별개로 `verification_status`(대기/확인됨/실패) 상태 관리 |
| 사업자등록증 원본 보관 | 보관함 — `business_registration_file_url` 컬럼으로 업로드 파일 URL 저장 |
| 개인→기업 전환 | 가능해야 함 — `users.company_id`는 가입 시점이 아니라 이후에 채워질 수 있는 nullable FK. 단 **사용자 셀프 세팅이 아니라 회사 오너의 초대 승인 경로로만** 채워진다(자기 신고형 입력 금지, §2.6) |
| 로그인 ID | 이메일과 동일 — 별도 컬럼 없이 기존 `users.email` UNIQUE 재사용 |
| 기업 가입 승인 흐름 | 스토리보드에 "가입 승인 후 검수 워크스페이스가 활성화됩니다", "관리자 승인 후 완료, 영업일 기준 2~3일 소요" 문구 확인 → 즉시 가입이 아니라 **신청 → 관리자 승인** 워크플로. `companies.status`(승인대기/승인됨/반려됨) + `reviewed_by`/`reviewed_at`/`rejection_reason` |
| 약관 동의 | 약관별·버전별 동의 이력 테이블(`user_consents`) — 법무 감사 대비 |

**반영 내용**: 신규 enum 3개(`company_status_type`, `business_verification_status_type`, `consent_policy_type`), `users` 컬럼 변경(`social_provider`/`social_id` nullable화, `password_hash`·`company_id` 추가, `ck_users_auth_method` CHECK), `companies`·`user_consents` 테이블 신설. 상세는 §4, §5.1 참조.

**아직 미확정인 부분** (ERD 밖의 범위이거나 팀 확인 필요):
- 개인 자체가입(이메일/비밀번호)도 있는지, 있다면 즉시 활성화인지 승인이 필요한지 — 현재는 소셜 개인가입만 즉시 활성화로 가정하고, 자체가입 개인의 승인 절차는 별도 상태 컬럼 없이 설계함
- `role` 부여 규칙(가입 신청자=`USER`, 협업자 초대=`INSPECTOR`)은 DB CHECK로 강제하지 않음 — 애플리케이션/트리거 레벨에서 지켜야 함
- FR-001~021·API 명세·로그인 업무흐름도·스토리보드 자체의 "P2 제안" 딱지 처리 여부는 이번 ERD 작업 범위 밖으로 남겨둠

---

### 2.6 기업 임직원의 상담 이용 권한 상속 (plans/user_plans 회사 단위 확장)

**배경**: §2.5에서 `companies`를 도입하면서 `companies.owner_user_id`를 "플랜 보유자"로 명시했지만(§2.5 표), 실제 `user_plans`는 `user_id` 단위로만 걸려 있어 이 설명과 실제 구조가 어긋나 있었다. `plans.max_seats`/`usage_counters.seat_count` 컬럼도 이미 존재해 원래 좌석제(회사 단위) 플랜을 염두에 둔 흔적이 있었지만, 이를 `companies`와 연결하는 FK나 판단 로직은 없었다. 이 상태로는 기업 오너가 유료 플랜(Standard 이상, `has_counselor_access = true`)을 결제해도 소속 임직원(`users.company_id`)은 개인 `user_plans` 행이 따로 없으면 상담 기능을 쓸 수 없다.

**검토한 두 방향**:
1. 개인 플랜 유지 + 임직원 초대 시 오너와 동일한 `plan_id`로 `user_plans` 행을 자동 발급
2. 플랜을 회사 단위로 승격 — `user_plans`가 사용자 또는 회사 중 하나에 걸리도록 확장

`max_seats`가 이미 "요금제가 허용하는 최대 좌석 수"로 정의되어 있고 `owner_user_id`가 "플랜 보유자"로 명시된 점을 볼 때, 원래 설계 의도는 2번(회사 단위 상속)에 더 가깝다고 판단해 이 방향으로 확정했다.

| 결정 항목 | 확정 내용 |
|---|---|
| 플랜 귀속 주체 | `user_plans`가 개인(`user_id`) 또는 회사(`company_id`) 중 **정확히 하나**에 귀속되도록 확장. 회사 귀속 행 하나가 그 회사 소속 임직원 전체의 상담 이용 권한을 대표한다 |
| `user_plans.user_id` | 기존 NOT NULL → **nullable**로 완화 |
| `user_plans.company_id` | 신규 컬럼, nullable, **FK→companies** |
| 상호 배타 제약 | `ck_user_plans_owner_xor`: `(user_id IS NOT NULL) <> (company_id IS NOT NULL)` — 개인 플랜과 회사 플랜이 같은 행에 동시에 걸리거나 둘 다 비는 것을 DB 레벨에서 차단 |
| 접근 판단 로직(애플리케이션) | `has_counselor_access` 등 플랜 게이팅 항목은 **"내 개인 `user_plans` OR 유효한 `company_memberships`가 가리키는 회사의 `user_plans`"** 중 하나라도 ACTIVE + 해당 boolean이 true면 허용. `users.company_id`는 교차 확인용 포인터일 뿐 단독 근거가 아님 |
| `usage_counters` | 컬럼 변경 없음. `user_plan_id`가 회사 귀속 `user_plans.id`를 가리키면 `seat_count` 등 사용량이 자연스럽게 회사 전체 기준으로 집계된다 |
| `companies` 테이블 자체 | 재검토 결과 컬럼 추가 불필요 — `owner_user_id`가 이미 "플랜 보유자"를 가리키고 있고, 플랜 데이터는 `user_plans.company_id`로 참조되므로 `companies`에 중복 컬럼(예: `plan_id`)을 두지 않는다. 다만 `owner_user_id` 코멘트 문구("플랜 보유자")가 이제 "회사 귀속 `user_plans`를 관리할 권한을 가진 사용자"라는 의미로 재해석됨을 명시해 둔다 |

**반영 내용**: `user_plans.user_id` nullable화, `user_plans.company_id` 컬럼·FK·인덱스(`idx_user_plans_company`) 추가, `ck_user_plans_owner_xor` CHECK 추가, **ACTIVE 구독 부분 유니크 인덱스**(`uq_user_plans_active_user`·`uq_user_plans_active_company`) 추가. 회사 귀속 상속은 **승인된 회사(APPROVED+VERIFIED)·`company_memberships`의 유효한 승인 멤버십**을 전제로 게이팅한다. 상세는 §5.1 `company_memberships`, §5.2를 참조한다. ERD는 §3에 `companies ──< company_memberships`, `companies ──< user_plans` 관계를 추가로 표기했다.

**플랜 상속 관련 규칙 (✅ 확정 / ⏳ 미확정)**:
- ✅ **확정(승인된 회사만 결제·상속)**: 회사 귀속 `user_plans`의 결제/활성화는 `status = APPROVED`(그리고 §5.1의 `verification_status = VERIFIED`) 회사만 가능하다. `PENDING_REVIEW`/`REJECTED` 회사는 회사 귀속 플랜을 `ACTIVE`로 둘 수 없으며, 반려 시 기존 회사 귀속 `user_plans`를 `EXPIRED`/정지 처리한다. (DB CHECK가 아닌 애플리케이션/승인 트랜잭션에서 보장)
- ✅ **확정(cross-tenant 권한 상속 차단)**: 회사 플랜 상속 판단은 `users.company_id` 값을 **그대로 신뢰하지 않는다**. `company_memberships`에서 `(company_id, user_id)`가 유일하고 `status=APPROVED`, `approved_at IS NOT NULL`, `revoked_at IS NULL`, `expires_at IS NULL OR expires_at > now()`인 행이 존재하며, 사용자도 `ACTIVE`이고 `users.company_id`가 같은 회사를 가리킬 때만 현재 소속으로 인정한다. 회사 오너도 예외 없이 멤버십 행을 가지며, 기업 승인 트랜잭션에서 오너 멤버십을 `APPROVED`로 전이하고 `users.company_id`를 함께 세팅한다. 초대 승인도 동일한 원자 트랜잭션으로 처리하고, 반려·회수·만료 시 멤버십 상태를 바꾸면서 일치하는 `users.company_id`를 제거한다. 이로써 임의의 `company_id` 세팅만으로 유료 기능을 상속하는 cross-tenant IDOR 경로를 차단한다.
- ⏳ **미확정**: 임직원이 개인 자격으로 별도 유료 플랜을 동시에 구독할 수 있는지(회사 플랜 + 개인 플랜 이중 구독)는 이번 범위에서 막지 않음 — 필요 시 애플리케이션에서 정책적으로 제한.

---

### 2.7 `rag_documents` 컬렉션 라우팅·시행일 컬럼 추가 (Chroma 메타데이터 설계 대조, HAJA-113)

**배경**: `Chroma_컬렉션_메타데이터_설계.md`(HAJA-113/143/144/145) 작업 중 이 ERD와 대조한 결과, `rag_documents`가 `regulations`/`defect_kb` 두 Chroma 컬렉션을 모두 커버해야 하는데 구분 컬럼이 `source_type`(`LAW`/`GUIDELINE` 2종)뿐이라 어떤 문서가 어느 컬렉션에 임베딩되는지 판단할 근거가 없었다(HAJA-113 코멘트, 2026-07-13). 또한 regulations 컬렉션의 필수 필드인 `effective_date`(시행일)도 Postgres에 SoT 컬럼이 없었다.

| 결정 항목 | 확정 내용 |
|---|---|
| 컬렉션 구분 | `target_collection`(신규 enum `rag_target_collection_type`: `REGULATIONS`/`DEFECT_KB`) 컬럼 추가 — `source_type`(출처 유형: 법령/지침)과는 별개 축으로 분리. NOT NULL, 기본값 없음(등록 시 명시 필수) |
| 법규 시행일 | `effective_date`(date, nullable) 컬럼 추가 — LAW 문서만 채우고 GUIDELINE/DEFECT_KB 문서는 NULL 허용 |

**반영 내용**: `rag_target_collection_type` enum 신규, `rag_documents.target_collection`/`effective_date` 컬럼 추가. 상세는 §4, §5.5 참조. SQL 반영: `HajaCheck_script.sql`(구 `v0.3`은 `archive/`로 이동). 이 SQL은 신규 DB용 최종 DDL이며 운영 DB 증분 마이그레이션으로 직접 실행하지 않는다.

**기존 DB 마이그레이션 순서**: `target_collection`을 nullable·기본값 없이 추가한 뒤, 현재 Chroma의 `regulations`/`defect_kb` 컬렉션에 실제 저장된 `doc_id`를 기준으로 각 행을 명시적으로 백필한다. `source_type`만으로 컬렉션을 추론하거나 전체 행을 `REGULATIONS`로 일괄 백필하지 않는다. 미분류(NULL) 또는 양쪽 컬렉션에 중복된 문서가 없는지 확인한 후에만 NOT NULL 제약을 적용하며, 검증 실패 시 마이그레이션을 중단한다.

---

### 2.8 `rag_documents` 발행처·작성일·검증여부 컬럼 추가 (HAJA-143/144 필드 누락 보완)

**배경**: HAJA-143(regulations 컬렉션 필드 설계)·HAJA-144(defect_kb 컬렉션 필드 설계) 원 요구사항을 `Chroma_컬렉션_메타데이터_설계.md` 확정본과 재대조한 결과, 요구됐던 필드 3개가 설계에서 누락된 채로 "확정" 처리되어 있었다: HAJA-143의 **발행처**, HAJA-144의 **작성일**·**신뢰도/검증여부**. §2.7과 동일하게 Postgres SoT 컬럼 부재가 원인이라 이번에 함께 보완한다.

| 결정 항목 | 확정 내용 |
|---|---|
| 발행처(HAJA-143) | `publisher`(varchar(200), nullable) 컬럼 추가 — 법규·지침 문서의 발행 기관/부처명. regulations 대상, defect_kb 등 해당 없는 문서는 NULL |
| 작성일(HAJA-144) | `authored_at`(date, nullable) 컬럼 추가 — 문서(주로 하자 지식 문서) 작성 시점. `effective_date`(법규 시행일)와는 별개 개념이라 컬럼을 분리했다 |
| 신뢰도/검증여부(HAJA-144) | `verification_status`(신규 enum `rag_doc_verification_status_type`: `UNVERIFIED`/`VERIFIED`, nullable) 컬럼 추가 — 하자 지식 문서가 전문가 검토를 통과했는지 여부. regulations는 공식 법규 출처라 별도 검증 프로세스가 없으므로 NULL 허용 |

**반영 내용**: `rag_doc_verification_status_type` enum 신규, `rag_documents.publisher`/`authored_at`/`verification_status` 컬럼 추가. 상세는 §4, §5.5 참조. SQL 반영: `HajaCheck_script.sql`. Chroma 필드 정의서(`Chroma_컬렉션_메타데이터_설계.md`, `docs/design/ai/rag_chroma_schema.md`)도 함께 갱신했다.

---

## 3. ERD 개요 (테이블 관계)

```
companies ── (owner_user_id) >── users
companies ──< users (company_id, 개인→기업 전환 시 오너 초대 승인 경로로 세팅)
companies ──< company_memberships >── users (승인·회수·만료 가능한 소속 SoT)
companies ──< user_plans (company_id, 회사 단위 플랜 — 소속 임직원 전체가 상속)

users ──┬──< user_consents
        ├──< user_plans >── plans (user_id 또는 company_id 중 하나에만 귀속, §2.6)
        │        └──< usage_counters
        ├──< facilities ──< inspections ──┬──< media
        │                                 ├──< defects ──< defect_revisions
        │                                 └──< reports
        ├──< chat_sessions ──< chat_messages ──< chat_message_citations >── rag_documents
        │        │                  │                  (+ chunk_ref → Chroma, FK 아님)
        │        │                  └── (scenario_id) >── bot_scenarios (self-referencing tree)
        ├──< counsel_tickets ── (session_id) >── chat_sessions
        └──< notifications

범례: A ──< B  =  A 1 : N B (B가 A를 참조하는 FK를 가짐)
      A >── B  =  A가 B를 참조하는 FK를 가짐
```

`users`와 `companies`는 서로를 참조하는 **양방향 FK** 관계다: `companies.owner_user_id`는 그 회사를 가입·소유한 단 한 명의 사용자를 가리키고(회사 1건당 소유자 1명), `users.company_id`는 그 회사에 소속된 모든 사용자(소유자 본인 포함, 협업자 초대로 합류한 임직원 포함)를 가리킨다(회사 1건당 소속 사용자 N명).

- `users`는 `company_memberships.user_id/invited_by`, `facilities.owner_id`, `inspections.created_by/assigned_inspector_id`, `defect_revisions.revised_by`, `reports.created_by/edited_by`, `chat_sessions.user_id`, `counsel_tickets.user_id/counselor_id`, `notifications.user_id` 등 서비스 전반의 액터로 참조된다.
- `chat_sessions`는 `session_type`(`RAG`/`SCENARIO_BOT`/`COUNSEL`) 하나로 세 가지 대화 흐름을 통합하고, `chat_messages`도 공용으로 사용한다.
- `counsel_tickets`는 큐/배정/상태만 관리하고, 실제 대화는 `session_id`를 통해 `chat_sessions`/`chat_messages`를 그대로 재사용한다.
- `bot_scenarios`는 `parent_id`로 자기 자신을 참조하는 계층형(트리) 구조다.
- RAG 인용 체인은 `chat_messages → chat_message_citations → rag_documents`로 연결되며, 실제 청크 본문·임베딩 벡터는 PostgreSQL 밖의 **Chroma**에 있다. `chat_message_citations.chunk_ref`는 Chroma 쪽 식별자를 문자열로만 보관하는 참조이지 FK가 아니다.

---

## 4. Enum 타입 정의

| 타입명 | 값 | 의미 | 사용 테이블.컬럼 |
|---|---|---|---|
| `role_type` | `ADMIN`, `INSPECTOR`, `USER`, `COUNSELOR` | 사용자 권한 역할 | `users.role` |
| `social_provider_type` | `KAKAO`, `GOOGLE` | 소셜 로그인 제공자 | `users.social_provider` |
| `user_status_type` | `ACTIVE`, `SUSPENDED` | 사용자 계정 상태 | `users.status` |
| `plan_name_type` | `FREE`, `STANDARD`, `ENTERPRISE` | 구독 요금제 명칭 | `plans.name` |
| `user_plan_status_type` | `ACTIVE`, `EXPIRED`, `UPGRADE_REQUESTED` | 사용자 구독 상태 | `user_plans.status` |
| `inspection_status_type` | `CREATED`, `UPLOADING`, `ANALYZING`, `ANALYZED`, `REVIEWED`, `REPORTED` | 점검 처리 상태(생성→업로드→분석→분석완료→검토완료→보고서화) | `inspections.status` |
| `media_file_type` | `IMAGE`, `VIDEO` | 미디어 파일 유형 | `media.file_type` |
| `defect_type` | `CRACK`, `SPALLING`, `LEAK_EFFLORESCENCE`, `REBAR_EXPOSURE`, `PAINT_DAMAGE` | 결함 유형(균열/박리·박락/누수·백태/철근노출/도장손상) | `defects.type` |
| `defect_grade_type` | `A`, `B`, `C`, `D`, `E` | 결함 위험·심각도 등급 | `defects.grade` |
| `defect_status_type` | `DETECTED`, `CONFIRMED`, `ACTION_PENDING`, `IN_PROGRESS`, `RESOLVED` | 결함 조치 상태 | `defects.status` |
| `report_status_type` | `DRAFT`, `FINALIZED` | 보고서 작성 상태 | `reports.status` |
| `chat_session_type` | `RAG`, `SCENARIO_BOT`, `COUNSEL` | 채팅 세션 유형(AI 문답/시나리오 봇/전문 상담) | `chat_sessions.session_type` |
| `chat_sender_type` | `USER`, `BOT`, `COUNSELOR` | 채팅 메시지 발신자 유형 | `chat_messages.sender` |
| `counsel_ticket_status_type` | `WAITING`, `IN_PROGRESS`, `RESOLVED`, `OFFLINE_LEFT` | 상담 티켓 처리 상태 | `counsel_tickets.status` |
| `rag_doc_source_type` | `LAW`, `GUIDELINE` | RAG 문서 출처 유형(법령/지침) | `rag_documents.source_type` |
| `rag_target_collection_type` | `REGULATIONS`, `DEFECT_KB` | RAG 문서가 임베딩되는 Chroma 컬렉션 | `rag_documents.target_collection` |
| `rag_doc_verification_status_type` | `UNVERIFIED`, `VERIFIED` | RAG 문서(주로 defect_kb) 검증 여부 | `rag_documents.verification_status` |
| `rag_embedding_status_type` | `PENDING`, `EMBEDDING`, `DONE`, `FAILED` | RAG 문서 임베딩 처리 상태 | `rag_documents.embedding_status` |
| `notification_type` | `ANALYSIS_DONE`, `REVIEW_PENDING`, `COUNSEL_REPLIED`, `INSPECTION_DUE` | 알림 유형 | `notifications.type` |
| `company_status_type` | `PENDING_REVIEW`, `APPROVED`, `REJECTED` | 기업 회원가입 관리자 승인 상태 | `companies.status` |
| `business_verification_status_type` | `PENDING`, `VERIFIED`, `FAILED` | 사업자등록번호 국세청 진위확인 상태 | `companies.verification_status` |
| `company_membership_status_type` | `PENDING`, `APPROVED`, `REJECTED`, `REVOKED`, `EXPIRED` | 기업 초대·소속 승인 상태 | `company_memberships.status` |
| `consent_policy_type` | `TERMS_OF_SERVICE`, `PRIVACY_POLICY` | 약관 동의 정책 유형 | `user_consents.policy_type` |

---

## 5. 테이블 상세

표기 규칙: **PK** = 기본키, **FK** = 외래키, **UQ** = unique 제약, **CK** = check 제약. NULL 열의 `N`은 NOT NULL, `Y`는 NULL 허용.

### 5.1 계정·인증

#### `users` — 서비스 사용자 계정과 인증 및 권한 정보

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 사용자 식별자 |
| email | varchar(255) | N | - | UQ | 사용자 이메일, 서비스 내 고유. 자체가입 사용자의 로그인 ID로도 사용 |
| name | varchar(100) | N | - | | 사용자 이름 또는 표시명 |
| role | role_type | N | `USER` | | 사용자 권한 역할 |
| social_provider | social_provider_type | Y | - | UQ(복합) | 소셜 로그인 제공자(자체가입 사용자는 NULL) |
| social_id | varchar(255) | Y | - | UQ(복합) | 소셜 로그인 제공자가 발급한 사용자 식별자(자체가입 사용자는 NULL) |
| password_hash | varchar(255) | Y | - | | 자체가입(이메일/비밀번호) 사용자의 비밀번호 해시(소셜 전용 사용자는 NULL) |
| company_id | bigint | Y | - | **FK→companies**(`fk_users_company`) | 소속 기업 계정. 개인 사용자는 NULL, 개인→기업 전환 시 **오너 초대 승인 경로로만 세팅**(셀프 세팅 금지, §2.6) |
| profile_image_url | varchar(500) | Y | - | | 프로필 이미지 URL |
| status | user_status_type | N | `ACTIVE` | | 계정 상태 |
| last_login_at | timestamptz | Y | - | | 마지막 로그인 시각 |
| created_at | timestamptz | N | now() | | 계정 생성 시각 |
| updated_at | timestamptz | N | now() | | 계정 최종 수정 시각 |

- **UQ**: `email`, `(social_provider, social_id)` — 동일 소셜 계정으로 중복 가입 방지. `social_provider`/`social_id`가 NULL인 자체가입 사용자끼리는 이 UNIQUE 제약에 걸리지 않는다(PostgreSQL은 NULL을 서로 다른 값으로 취급).
- **CK** `ck_users_auth_method`: `(social_provider IS NOT NULL AND social_id IS NOT NULL) OR password_hash IS NOT NULL` — 소셜 로그인 또는 자체가입(비밀번호) 중 최소 하나의 인증 수단은 반드시 있어야 함.
- 인덱스: `idx_users_company (company_id)`
- 참조 대상: `company_memberships.user_id/invited_by`, `facilities.owner_id`, `inspections.created_by/assigned_inspector_id`, `defect_revisions.revised_by`, `reports.edited_by/created_by`, `chat_sessions.user_id`, `counsel_tickets.user_id/counselor_id`, `notifications.user_id`, `user_plans.user_id`, `companies.owner_user_id/reviewed_by`, `user_consents.user_id`.
- §2.5 근거: 착수 시점엔 소셜 로그인 전용으로 `social_provider`/`social_id`가 NOT NULL이었으나, 자체 회원가입(AUTH-02) 지원을 위해 nullable로 완화하고 `password_hash`·`company_id`를 추가했다.

#### `companies` — 기업 회원가입으로 생성된 회사 계정

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 기업 계정 식별자 |
| owner_user_id | bigint | N | - | **FK→users** | 가입을 신청하고 계정을 소유·관리하는 사용자(플랜 보유자, 협업자 초대 권한) |
| name | varchar(200) | N | - | | 상호명 |
| business_registration_number | varchar(20) | N | - | UQ | 사업자등록번호 |
| representative_name | varchar(100) | N | - | | 대표자명 |
| address | varchar(300) | N | - | | 사업장 도로명주소 |
| address_detail | varchar(200) | Y | - | | 사업장 상세주소 |
| business_registration_file_url | varchar(500) | N | - | | 업로드된 사업자등록증 원본 파일 URL |
| business_registration_ocr_raw | jsonb | Y | - | | 사업자등록증 OCR 추출 원본 결과(감사·재처리용) |
| verification_status | business_verification_status_type | N | `PENDING` | | 국세청 사업자등록정보 진위확인 상태 |
| verified_at | timestamptz | Y | - | | 진위확인 완료 시각 |
| status | company_status_type | N | `PENDING_REVIEW` | | 관리자의 기업 회원가입 승인 상태 |
| reviewed_by | bigint | Y | - | **FK→users** | 승인/반려를 처리한 관리자 |
| reviewed_at | timestamptz | Y | - | | 승인/반려 처리 시각 |
| rejection_reason | varchar(500) | Y | - | | 반려 사유 |
| created_at | timestamptz | N | now() | | 생성(가입 신청) 시각 |
| updated_at | timestamptz | N | now() | | 최종 수정 시각 |

- **UQ**: `business_registration_number` — 동일 사업자등록번호로 중복 가입 방지.
- 인덱스: `idx_companies_owner (owner_user_id)`
- §2.5 근거: 스토리보드의 사업자등록증 업로드→OCR 자동 인식(사업자등록번호/상호명/대표자)→관리자 승인 대기 흐름을 그대로 컬럼화했다. OCR 인식(자동)과 국세청 진위확인(외부 API)은 서로 다른 검증 단계라 `business_registration_ocr_raw`(원본 추출값)와 `verification_status`(진위확인 결과)를 분리했다.
- `verification_status`(진위확인, 자동)와 `status`(관리자 승인, 사람이 처리)는 서로 독립적인 두 축이다 — 진위확인이 통과해도 관리자가 반려할 수 있다.
- ✅ **승인 게이팅 확정(미검증 기업 유료권한 차단)**: 단, 두 축이 독립이라도 **`status`를 `APPROVED`로 전이하려면 `verification_status = VERIFIED`가 전제조건**이다(`FAILED`/`PENDING` 회사는 승인 불가). 승인 전이는 이 조건을 강제하는 애플리케이션 트랜잭션(가능하면 트리거 병행)으로만 처리하고, **회사 귀속 `user_plans` 결제/활성화 시에도 `verification_status = VERIFIED AND status = APPROVED`를 함께 확인**한다. 승인 전이 시점의 검증 상태는 감사 로그(`reviewed_by`/`reviewed_at` + 검증 상태)로 남긴다. 이로써 국세청 진위확인에 실패한 위조/미검증 기업이 관리자 실수·절차 누락만으로 유료 기능·기업 워크스페이스 권한을 얻는 경로를 차단한다.
- §2.6 근거: `owner_user_id` 코멘트의 "플랜 보유자"는 회사 귀속 `user_plans.company_id`(§5.2)를 관리할 권한을 가진 사용자라는 뜻이다. `companies` 자체에는 플랜 참조 컬럼을 추가하지 않았다 — `user_plans.company_id` FK가 이미 그 역할을 하므로 중복 컬럼을 두면 두 값이 어긋날 위험만 생긴다.

#### `company_memberships` — 기업 초대·소속 승인 이력과 현재 소속 SoT

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 기업 멤버십 식별자 |
| company_id | bigint | N | - | **FK→companies**, UQ(복합) | 소속 회사 |
| user_id | bigint | N | - | **FK→users**, UQ(복합) | 소속 사용자 |
| invited_by | bigint | Y | - | **FK→users** | 초대한 회사 오너/관리자. 기업 가입 신청자의 오너 멤버십은 NULL 가능 |
| status | company_membership_status_type | N | `PENDING` | | 초대·승인·반려·회수·만료 상태 |
| approved_at | timestamptz | Y | - | | 승인 시각. `APPROVED` 상태에서는 필수 |
| expires_at | timestamptz | Y | - | | 소속 만료 시각. NULL이면 명시적 만료 없음 |
| revoked_at | timestamptz | Y | - | | 회수 시각. `REVOKED` 상태에서는 필수 |
| created_at | timestamptz | N | now() | | 초대/오너 멤버십 생성 시각 |
| updated_at | timestamptz | N | now() | | 최종 상태 변경 시각 |

- **UQ**: `(company_id, user_id)` — 같은 회사에 동일 사용자의 멤버십 이력을 중복 생성하지 않고 상태 전이로 관리한다.
- **부분 UQ** `uq_company_memberships_approved_user`: 사용자 한 명이 동시에 두 회사에서 `APPROVED`일 수 없게 한다. 만료 시에는 상태를 `EXPIRED`로 전이한 뒤 다른 회사 승인을 처리한다.
- **CK**: `APPROVED`이면 `approved_at IS NOT NULL`, `REVOKED`이면 `revoked_at IS NOT NULL`, `expires_at`이 있으면 `created_at`보다 뒤여야 한다.
- **유효 멤버십 판정**: `status=APPROVED AND approved_at IS NOT NULL AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())`. 여기에 `users.status=ACTIVE`, `users.company_id=company_id`, 회사 `status=APPROVED`, `verification_status=VERIFIED`를 함께 검사한다.
- `users.company_id`는 조회 편의를 위한 현재 소속 포인터이며 멤버십 SoT가 아니다. 승인 시 같은 트랜잭션에서 세팅하고 반려·회수·만료 시 일치하는 값을 제거한다. 회사 플랜 상속은 이 포인터만으로 허용하지 않는다.
- **기존 데이터 전환**: 신규 테이블 배포 시 `companies.owner_user_id`는 회사별 오너 멤버십을 만드는 신뢰 가능한 근거로 사용한다. 비오너의 기존 `users.company_id`는 초대 승인 근거를 별도 감사자료와 대조해 확인된 건만 `APPROVED`로 백필하고, 근거가 없는 건은 `PENDING`으로 격리해 관리자 재승인 전에는 플랜을 상속하지 않는다. 자연어 검색의 회사 플랜 게이트는 이 백필·검증이 끝난 뒤 활성화한다.
- **기업 가입/초대 구현 전제**: 현재 기업 가입 API는 회사·오너 사용자 생성과 함께 오너의 `PENDING` 멤버십을 원자 생성하도록 확장하고, 회사 승인 트랜잭션이 이를 `APPROVED`로 전이해야 한다. 임직원 초대도 멤버십 생성→사용자 승인→`users.company_id` 반영 순서로 구현한다.

#### `user_consents` — 약관·개인정보 처리방침 동의 이력

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 동의 이력 식별자 |
| user_id | bigint | N | - | **FK→users**, ON DELETE CASCADE, UQ(복합) | 동의한 사용자 |
| policy_type | consent_policy_type | N | - | UQ(복합) | 동의한 정책 유형(이용약관/개인정보 처리방침) |
| policy_version | varchar(20) | N | - | UQ(복합) | 동의한 정책 버전 |
| agreed_at | timestamptz | N | now() | | 동의 시각 |

- **UQ**: `(user_id, policy_type, policy_version)` — 동일 버전 중복 동의 방지.
- 인덱스: `idx_user_consents_user (user_id)`
- §2.5 근거: 회원가입 화면의 "(필수) 서비스 이용약관 및 개인정보 처리방침 동의" 체크박스를, 법무 감사에 대비해 단일 타임스탬프가 아니라 약관별·버전별 이력으로 저장한다. 약관 개정 시에도 과거 동의 시점의 버전을 그대로 보존한다.
- ✅ **삭제 정책(감사 보존 우선) — 라이브 DB 실측 반영(2026-07-15)**: 이 테이블은 법무 감사·분쟁 대응이 존재 목적이므로 동의 이력은 사용자 삭제와 **독립적으로 보존**해야 한다. **실측 결과 `user_id` FK는 `ON DELETE CASCADE`**다(참조 DDL `HajaCheck_script.sql`과 일치 — 과거 문서의 "RESTRICT로 확정" 서술은 실제와 달라 정정함). 따라서 보존은 DB 레벨 제약이 아니라 **운영 원칙으로 보장**한다: 사용자 탈퇴는 **soft delete**(탈퇴 플래그/`status`)로 처리해 원본 행을 물리 삭제하지 않으므로 CASCADE가 실제로는 발생하지 않는다(§`chat_message_citations`의 CASCADE 운영과 동일 패턴). 개인정보 파기 요건이 걸리면 사용자 식별정보만 **익명화 후 동의 이력 자체는 보존**한다(파기·감사 요건 상충 시 익명화 보존 우선). ⚠️ **후속**: DB 레벨 강제 보존이 필요하면 FK를 `ON DELETE RESTRICT`로 바꾸는 마이그레이션이 필요하다 — 현재 엔티티(`UserConsent.java`)는 `user_id`를 FK 관계로 매핑하지 않고 Flyway도 없어 스키마 정합이 수동 관리 상태이므로, 마이그레이션 도구 도입과 함께 다뤄야 한다.

---

### 5.2 과금·정책

#### `plans` — 구독 요금제와 이용 한도

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 요금제 식별자 |
| name | plan_name_type | N | - | UQ | 요금제 명칭(`FREE`/`STANDARD`/`ENTERPRISE`) |
| max_facilities | integer | Y | - | | 최대 등록 가능 시설 수 |
| max_monthly_analyses | integer | Y | - | | 월 최대 분석 가능 횟수 |
| max_seats | integer | N | 0 | | 최대 사용자 좌석 수 |
| has_pdf_watermark | boolean | N | false | | PDF 워터마크 표시 여부 |
| has_counselor_access | boolean | N | false | | 전문 상담사 연결 기능 제공 여부 |
| has_ai_addon | boolean | N | false | | AI 부가 기능 제공 여부 |
| price_monthly | numeric(10,2) | Y | - | | 월 구독 가격 |
| created_at | timestamptz | N | now() | | 생성 시각 |
| updated_at | timestamptz | N | now() | | 최종 수정 시각(트리거 자동 갱신) |

#### `user_plans` — 사용자 또는 회사에 적용된 구독과 이용 기간

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 구독 식별자 |
| user_id | bigint | Y | - | **FK→users** | 구독 주체가 개인일 때의 사용자(회사 귀속 행은 NULL) |
| company_id | bigint | Y | - | **FK→companies** | 구독 주체가 회사일 때의 회사(개인 귀속 행은 NULL) |
| plan_id | bigint | N | - | **FK→plans** | 적용된 요금제 |
| status | user_plan_status_type | N | `ACTIVE` | | 구독 상태 |
| started_at | timestamptz | N | now() | | 구독 시작 시각 |
| ended_at | timestamptz | Y | - | | 구독 종료 시각 |

- **CK** `ck_user_plans_owner_xor`: `(user_id IS NOT NULL) <> (company_id IS NOT NULL)` — 개인 귀속과 회사 귀속 중 정확히 하나만 채워지도록 강제(§2.6).
- **부분 UQ(중복 활성 구독 차단, 확정)**: `uq_user_plans_active_user` = `CREATE UNIQUE INDEX ... ON user_plans (user_id) WHERE status='ACTIVE'`, `uq_user_plans_active_company` = `... ON user_plans (company_id) WHERE status='ACTIVE'`. 동일 사용자/회사에 `ACTIVE` 구독이 둘 이상 생기는 것을 DB 레벨에서 차단해 중복 과금·엔타이틀먼트 혼선을 막는다. 구독 업그레이드/갱신(`UPGRADE_REQUESTED` 등)은 **단일 트랜잭션 내에서 기존 `ACTIVE`를 `EXPIRED`로 내리고 신규를 `ACTIVE`로 올리는** 순서로 처리한다.
- 인덱스: `idx_user_plans_user (user_id)`, `idx_user_plans_company (company_id)`
- §2.6 근거: 회사 귀속 행(`company_id` 세팅)은 그 회사의 **유효한 `company_memberships` 사용자**에게 상담 이용 권한 등 플랜 게이팅을 대표한다. 판단 로직은 "내 개인 `user_plans` OR 유효한 멤버십이 가리키는 회사의 `user_plans`" 중 하나라도 `ACTIVE`면 허용하는 방식이며, `users.company_id` 일치도 함께 확인한다. 이 조합 판정은 DB CHECK가 아니라 애플리케이션에서 처리한다.

#### `usage_counters` — 구독(개인/회사)별 월간 기능 사용량 집계

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 사용량 집계 식별자 |
| user_plan_id | bigint | N | - | **FK→user_plans** | 구독 식별자 |
| period | date | N | - | UQ(복합) | 집계 기준 월(해당 월 1일로 저장) |
| analyzed_image_count | integer | N | 0 | | 해당 월 분석 이미지 수 |
| facility_count | integer | N | 0 | | 등록 시설 수 |
| analysis_request_count | integer | N | 0 | | 분석 요청 수 |
| seat_count | integer | N | 0 | | 사용 좌석 수 |
| counsel_ticket_count | integer | N | 0 | | 생성된 상담 티켓 수 |
| pdf_generation_count | integer | N | 0 | | 생성된 PDF 수 |
| created_at | timestamptz | N | now() | | 레코드 생성 시각 |

- **UQ**: `(user_plan_id, period)` — 구독별 월 1건.
- **CK** `ck_usage_counters_period_month_start`: `period`가 해당 월 1일이어야 함.
- **CK** `ck_usage_counters_nonnegative`: 모든 카운트 컬럼 ≥ 0.
- **동시성 정책 확정(쿼터 초과 방지)**: `QuotaInterceptor`의 한도 판정은 "조회 후 증가"를 분리하지 않고 **원자적 조건부 UPDATE**로 처리한다 — 예: `UPDATE usage_counters SET analysis_request_count = analysis_request_count + 1 WHERE user_plan_id=:id AND period=:p AND analysis_request_count < :limit RETURNING ...` 로, 갱신 행 수가 0이면 한도 초과로 판정한다. period 행 최초 생성 경합은 위 `(user_plan_id, period)` UNIQUE 기반 **UPSERT(`INSERT ... ON CONFLICT (user_plan_id, period) DO UPDATE ...`)**로 흡수해 동시 요청이 같은 잔여 한도를 읽고 함께 통과하는 경쟁 조건을 제거한다.

---

### 5.3 시설물·점검

#### `facilities` — 사용자가 소유·관리하는 점검 대상 시설

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 시설 식별자 |
| owner_id | bigint | N | - | **FK→users** | 시설 소유자/관리자 |
| name | varchar(200) | N | - | | 시설 명칭 |
| type | varchar(20) | N | - | | 시설 유형 |
| address | varchar(300) | Y | - | | 시설 주소 |
| latitude | numeric(9,6) | Y | - | | 위도 |
| longitude | numeric(9,6) | Y | - | | 경도 |
| built_year | integer | Y | - | | 건축 연도 |
| scale | varchar(100) | Y | - | | 규모 설명(연면적/층수 등) |
| inspection_cycle_months | integer | Y | - | | 정기 점검 주기(개월) |
| next_inspection_due_at | date | Y | - | | 다음 점검 예정일 |
| created_at | timestamptz | N | now() | | 생성 시각 |
| updated_at | timestamptz | N | now() | | 최종 수정 시각 |

- 인덱스: `idx_facilities_owner (owner_id)`
- 착수 보고서 대비: `address`, `inspection_cycle_months`가 NOT NULL → NULL 허용으로 완화됨 (§2.1 참조)

#### `inspections` — 시설별 점검 회차와 진행 상태

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 점검 식별자 |
| facility_id | bigint | N | - | **FK→facilities**, UQ(복합) | 점검 대상 시설 |
| created_by | bigint | N | - | **FK→users** | 점검 생성자 |
| assigned_inspector_id | bigint | N | - | **FK→users** | 점검 담당자로 배정된 점검자 |
| round_no | integer | N | - | UQ(복합) | 시설별 점검 회차 |
| inspection_date | date | N | - | | 점검 수행일 |
| status | inspection_status_type | N | `CREATED` | | 점검 처리 상태 |
| created_at | timestamptz | N | now() | | 생성 시각 |

- **UQ**: `(facility_id, round_no)` — 시설별 회차 중복 방지.
- 인덱스: `idx_inspections_facility (facility_id)`, `idx_inspections_assigned_inspector (assigned_inspector_id)`
- `assigned_inspector_id`가 가리키는 사용자는 애플리케이션에서 `users.status=ACTIVE AND role IN (INSPECTOR, ADMIN)`인지 검증한다. 기존 데이터 마이그레이션은 담당자 확정값으로 백필한 뒤 NOT NULL을 적용하며, 근거 없이 `created_by`를 자동 복사하지 않는다.

#### `media` — 점검 과정에서 등록·추출한 이미지 및 영상

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 미디어 식별자 |
| inspection_id | bigint | N | - | **FK→inspections** | 소속 점검 |
| file_type | media_file_type | N | - | | 미디어 파일 유형(IMAGE/VIDEO) |
| original_url | varchar(500) | N | - | | 원본 파일 URL |
| thumbnail_url | varchar(500) | Y | - | | 썸네일 이미지 URL |
| source_video_id | bigint | Y | - | (FK 미설정, 자기 참조 개념) | 프레임 이미지의 원본 영상 식별자 |
| frame_index | integer | Y | - | | 원본 영상 내 프레임 순번 |
| captured_at | timestamptz | Y | - | | 촬영 시각 |
| gps_lat | numeric(9,6) | Y | - | | 촬영 위치 위도 |
| gps_lng | numeric(9,6) | Y | - | | 촬영 위치 경도 |
| mime_signature_verified | boolean | N | false | | 파일 시그니처-MIME 타입 일치 검증 여부 |
| created_at | timestamptz | N | now() | | 레코드 생성 시각 |
| mime_type | varchar(100) | Y | - | | MIME 타입(예: image/jpeg, video/mp4) |

- 인덱스: `idx_media_inspection (inspection_id)`
- 참고: `source_video_id`는 개념상 `media.id`를 가리키는 자기 참조 값이지만, 현재 `hajaCheck_script.sql`에는 FK 제약이 걸려 있지 않다(영상 프레임 추출 파이프라인에서 유연하게 채워 넣기 위함으로 추정). 데이터 정합성이 필요하면 별도 FK 추가를 검토할 수 있다.
- PRD FR-2 근거: `mime_signature_verified`는 "확장자 외 매직바이트(파일 시그니처) 검증"이라는 업로드 보안 요구사항을 그대로 반영한 컬럼이다.

---

### 5.4 결함·보고서

#### `defects` — 점검 이미지에서 탐지·검토된 시설 결함

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 결함 식별자 |
| inspection_id | bigint | N | - | **FK→inspections** | 결함이 발견된 점검 |
| type | defect_type | N | - | | 결함 유형 |
| bbox_x / bbox_y / bbox_w / bbox_h | double precision | Y | - | | 결함 바운딩 박스 좌표/크기 |
| confidence | double precision | N | - | | AI 결함 탐지 신뢰도 |
| grade | defect_grade_type | Y | - | | 결함 위험·심각도 등급(A~E) |
| status | defect_status_type | N | `DETECTED` | | 결함 조치 상태 |
| is_reviewed | boolean | N | false | | 검토 여부 |
| is_deleted | boolean | N | false | | 논리 삭제 여부 |
| crack_width_mm | double precision | Y | - | | 균열 폭(mm) |
| crack_length_mm | double precision | Y | - | | 균열 길이(mm) |
| created_at | timestamptz | N | now() | | 생성 시각 |

- 인덱스: `idx_defects_inspection (inspection_id)`

#### `defect_revisions` — 결함 정보 변경 이력

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 수정 이력 식별자 |
| defect_id | bigint | N | - | **FK→defects** | 수정된 결함 |
| revised_by | bigint | N | - | **FK→users** | 수정자 |
| field_changed | varchar(50) | N | - | | 변경된 컬럼/항목명 |
| old_value | varchar(255) | Y | - | | 변경 전 값 |
| new_value | varchar(255) | Y | - | | 변경 후 값 |
| reason | varchar(500) | Y | - | | 변경 사유 |
| created_at | timestamptz | N | now() | | 생성 시각 |

- 인덱스: `idx_defect_revisions_defect (defect_id)`

#### `reports` — 점검 결과 기반 버전별 보고서

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 보고서 식별자 |
| inspection_id | bigint | N | - | **FK→inspections**, UQ(복합) | 보고서 대상 점검 |
| version | integer | N | 1 | UQ(복합) | 동일 점검 내 보고서 버전 |
| content_json | jsonb | N | - | | 보고서 본문 구조화 데이터 |
| grounding_check_passed | boolean | Y | - | | 근거검증(Grounding) 통과 여부 |
| grounding_warnings | jsonb | Y | - | | 근거검증 경고 목록 |
| pdf_url | varchar(500) | Y | - | | 생성된 PDF URL |
| edited_by | bigint | Y | - | **FK→users** | 최종 수정자 |
| status | report_status_type | N | `DRAFT` | | 보고서 작성 상태 |
| created_at | timestamptz | N | now() | | 생성 시각 |
| created_by | bigint | Y | - | **FK→users**(`fk_reports_created_by`) | 최초 작성자 |
| updated_at | timestamptz | N | now() | | 최종 수정 시각(트리거 자동 갱신) |

- **UQ**: `(inspection_id, version)`
- 인덱스: `idx_reports_created_by (created_by)`, `idx_reports_edited_by (edited_by)`
- 트리거: `trg_reports_set_updated_at` — 행 수정 시 `updated_at` 자동 갱신
- 착수 보고서 대비: `created_by`, `updated_at` 신규 추가, `grounding_check_passed` NOT NULL → NULL 허용 (§2.1 참조)

---

### 5.5 RAG·상담 공용 (챗봇/상담)

이 영역은 RAG 문답, 시나리오 버튼 챗봇, 전문 상담사 상담이라는 세 가지 채널을 `chat_sessions`/`chat_messages` 공용 테이블로 통합하고, 상담 큐(`counsel_tickets`)와 시나리오 트리(`bot_scenarios`), RAG 문서·출처 추적(`rag_documents`/`chat_message_citations`)을 그 위에 연결한 구조다. 설계 배경은 §7에서 별도로 정리한다.

**PostgreSQL과 Chroma의 역할 분담**: PRD 확정 아키텍처상 RAG 임베딩·유사도 검색은 FastAPI 프로세스에 임베디드로 뜨는 **Chroma**가 전담한다(§2.4.1). PostgreSQL(`hajaCheck_script.sql`)에는 벡터나 청크 원문을 두지 않고, ① 어떤 법령/지침 문서가 있는지(`rag_documents`) ② 특정 답변 메시지가 그중 어떤 문서·어떤 Chroma 청크를 인용했는지(`chat_message_citations`)만 기록한다.

#### `chat_sessions` — 사용자별 채팅 세션

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 채팅 세션 식별자 |
| user_id | bigint | N | - | **FK→users** | 세션을 시작한 사용자 |
| session_type | chat_session_type | N | - | | 세션 유형(`RAG`/`SCENARIO_BOT`/`COUNSEL`) |
| started_at | timestamptz | N | now() | | 세션 시작 시각 |
| ended_at | timestamptz | Y | - | | 세션 종료 시각 |

- 인덱스: `idx_chat_sessions_user (user_id)`

#### `chat_messages` — 채팅 세션 내 메시지

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 메시지 식별자 |
| session_id | bigint | N | - | **FK→chat_sessions** | 소속 채팅 세션 |
| sender | chat_sender_type | N | - | | 발신자 유형(`USER`/`BOT`/`COUNSELOR`) |
| content | text | N | - | | 메시지 내용 |
| scenario_id | bigint | Y | - | **FK→bot_scenarios**(`fk_chat_messages_scenario`) | 사용자가 선택한 시나리오 노드(SCENARIO_BOT 세션에서 사용) |
| created_at | timestamptz | N | now() | | 생성 시각 |

- 인덱스: `idx_chat_messages_session (session_id)`, `idx_chat_messages_scenario (scenario_id)`
- RAG 세션의 근거 인용은 `citation` 텍스트 컬럼이 아니라 `chat_message_citations` 참조 테이블로 관리한다(§2.2, §7 참조).

#### `counsel_tickets` — 전문 상담 요청과 진행 상태

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 상담 티켓 식별자 |
| user_id | bigint | N | - | **FK→users** | 상담 요청 사용자 |
| counselor_id | bigint | Y | - | **FK→users** | 배정된 상담사 |
| session_id | bigint | Y | - | **FK→chat_sessions** | 상담 대화가 이루어지는 채팅 세션(`session_type='COUNSEL'`) |
| status | counsel_ticket_status_type | N | `WAITING` | | 상담 티켓 처리 상태 |
| queue_position | integer | Y | - | | 상담 대기열 순번 |
| created_at | timestamptz | N | now() | | 생성 시각 |
| ended_at | timestamptz | Y | - | | 상담 종료 시각 |

- 인덱스: `idx_counsel_tickets_counselor (counselor_id)`, `idx_counsel_tickets_user (user_id)`, `idx_counsel_tickets_session (session_id)`
- `session_id`는 티켓 생성 시점에는 대화가 아직 시작되지 않았을 수 있어 NULL을 허용하며, 상담사가 배정되어 채팅이 열리는 시점에 채워진다.

#### `bot_scenarios` — 버튼 선택형 계층 챗봇 시나리오

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 시나리오 식별자 |
| parent_id | bigint | Y | - | **FK→bot_scenarios**(`fk_bot_scenarios_parent`, ON DELETE SET NULL) | 상위 시나리오 노드 |
| category | varchar(100) | N | - | | 시나리오 분류 |
| button_label | varchar(200) | N | - | | 선택 버튼 문구 |
| response_text | text | Y | - | | 선택 시 제공할 봇 응답 |
| leads_to_counselor | boolean | N | false | | 전문 상담사 연결 단계 여부 |
| sort_order | integer | N | 0 | | 동일 단계 내 노출 순서 |
| created_at | timestamptz | N | now() | | 생성 시각 |
| updated_at | timestamptz | N | now() | | 최종 수정 시각(트리거 자동 갱신) |

- **CK** `ck_bot_scenarios_not_self_parent`: `parent_id`가 자기 자신을 가리킬 수 없음.
- 인덱스: `idx_bot_scenarios_parent (parent_id)`
- 트리거: `trg_bot_scenarios_set_updated_at`
- 자기 참조(self-referencing) 트리 구조로 계층형 시나리오를 표현한다. `parent_id IS NULL`이면 최상위(루트) 노드다.

#### `rag_documents` — RAG용 원본 법령·지침 문서 (메타데이터)

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 문서 식별자 |
| title | varchar(300) | N | - | | 문서 제목 |
| source_type | rag_doc_source_type | N | - | | 문서 출처 유형(`LAW`/`GUIDELINE`) |
| target_collection | rag_target_collection_type | N | - | | 이 문서의 청크가 임베딩되는 Chroma 컬렉션(`REGULATIONS`/`DEFECT_KB`, 등록 시 명시 필수) |
| effective_date | date | Y | - | | 문서 시행일(법규 개정 추적용, LAW 문서만 채움) |
| publisher | varchar(200) | Y | - | | 발행 기관/부처명(법규·지침 문서 출처 표시용, regulations 대상 — 해당 없는 문서는 NULL) |
| authored_at | date | Y | - | | 문서 작성일(주로 하자 지식 문서 대상 — `effective_date`와 별개 개념) |
| verification_status | rag_doc_verification_status_type | Y | - | | 문서 검증 여부(주로 defect_kb 하자 지식 문서의 전문가 검토 통과 여부 — regulations는 NULL 허용) |
| file_url | varchar(500) | N | - | | 원본 파일 URL |
| embedding_status | rag_embedding_status_type | N | `PENDING` | | 임베딩 처리 상태 |
| chunk_count | integer | Y | - | | 문서를 분할하여 Chroma에 임베딩한 청크 수 |
| embedded_at | timestamptz | Y | - | | 임베딩 완료 시각 |
| created_at | timestamptz | N | now() | | 업로드 시작 시각 |

- 이 테이블은 문서 메타데이터만 갖는다. 실제 청크 본문·임베딩 벡터는 Chroma(FastAPI 임베디드, 로컬 파일)에 저장되며 PostgreSQL에는 존재하지 않는다(§2.4.1).
- §2.7 근거: `source_type`(출처 유형: 법령/지침)과 `target_collection`(저장 위치: regulations/defect_kb Chroma 컬렉션)은 서로 다른 축이다. 기존에는 컬렉션 구분 컬럼이 없어 `defect_kb` 문서가 `rag_documents`의 어디에 속하는지 판단할 근거가 없었다(HAJA-113 코멘트 2026-07-13) — 이를 해결하기 위해 `target_collection` 컬럼을 신설했다. `effective_date`도 같은 이유로 함께 추가 — 기존 스키마에는 법규 시행일을 추적할 SoT 컬럼이 없었다.
- §2.8 근거: `publisher`(HAJA-143), `authored_at`·`verification_status`(HAJA-144)는 각 Jira 하위 업무가 원래 요구했지만 Chroma 필드 설계 확정 과정에서 누락됐던 필드다. 셋 다 nullable로 두고 대상 컬렉션(target_collection)에 따라 조건부로 채운다 — `effective_date`와 동일한 패턴.
- `target_collection`은 문서 생성 시 확정하는 불변값이다. 임베딩 또는 citation이 생성된 뒤에는 변경하지 않으며, 문서를 다른 컬렉션으로 재분류할 때는 새 `rag_documents` 행을 생성해 새 문서 ID로 재임베딩한다.

#### `chat_message_citations` — RAG 답변의 근거 문서·청크 인용

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 인용 식별자 |
| message_id | bigint | N | - | **FK→chat_messages**, ON DELETE CASCADE, UQ(복합) | 인용을 포함한 메시지 |
| document_id | bigint | N | - | **FK→rag_documents**, UQ(복합) | 인용된 RAG 문서 |
| chunk_ref | varchar(100) | N | - | UQ(복합) | Chroma에 저장된 청크(벡터)의 식별자 — Postgres 외부 저장소 참조라 FK 불가 |
| locator | text | N | - | | 화면 표시용 출처 라벨(예: `제12조`, `제12조 ①`, `12페이지`) |
| snippet | text | N | - | | 인용된 청크 원문 발췌(표시용 캐시, Chroma 재조회 없이 UI에 노출) |
| created_at | timestamptz | N | now() | | 생성 시각 |

- **UQ**: `(message_id, document_id, chunk_ref)` — 동일 청크 중복 인용 방지.
- 인덱스: `idx_chat_message_citations_message (message_id)`, `idx_chat_message_citations_document (document_id)`
- `document_id`는 PostgreSQL 내 `rag_documents`를 정상적으로 FK 참조하지만, `chunk_ref`는 Chroma가 관리하는 값이라 데이터베이스 레벨 참조 무결성을 보장할 수 없다(애플리케이션에서 Chroma 조회 결과와 정합성을 맞춰야 한다).
- `locator`는 채팅 이력 표시용 짧은 라벨이고, `snippet`은 실제 검색된 청크 원문 발췌다. 둘을 분리해 UI 표시와 원문 캐시의 의미가 섞이지 않도록 한다.
- API의 `SourceCitation.doc_id`는 양의 정수 문자열만 허용하며 저장 경계에서 `int(doc_id)`로 변환해 `document_id`에 저장한다.
- API의 `SourceCitation.collection`(`regulations`/`defect_kb`)은 이 테이블에 중복 저장하지 않고, 이력 복원 시 불변인 `rag_documents.target_collection`(`REGULATIONS`/`DEFECT_KB`)을 조인해 변환한다.
- 기존 citation 행의 `locator`/`snippet`이 NULL이면 `chunk_ref`로 실제 Chroma 청크를 조회해 두 값을 복원한 뒤 NOT NULL 제약을 적용한다. 복원 불가능한 행이 하나라도 있으면 제약 적용을 중단한다.
- 메시지 하나가 여러 문서·청크를 인용하는 1:N 구조를 표현하며, `chat_messages`가 삭제되면 인용 레코드도 함께 삭제된다(ON DELETE CASCADE).
- ✅ **삭제 정책 확정(KPI 근거 보존)**: 이 테이블은 §7 KPI "출처 표기율 100%" 검증의 유일한 근거이므로, `chat_messages`는 **물리 삭제 대신 soft delete**를 원칙으로 해 메시지·인용 레코드를 보존한다(CASCADE는 실제로는 발생하지 않도록 운영). 만약 보존기간 만료 등으로 메시지 물리 삭제를 도입할 경우, 삭제 전 인용 준수 지표(답변 건수 대비 인용 존재율)를 **별도 집계·스냅샷으로 보존**해 사후 재검증 가능성을 유지한다.

---

### 5.6 공통 알림

#### `notifications` — 사용자에게 전달되는 서비스 알림

| 컬럼 | 타입 | NULL | 기본값 | 키 | 설명 |
|---|---|---|---|---|---|
| id | bigint (identity) | N | - | **PK** | 알림 식별자 |
| user_id | bigint | N | - | **FK→users** | 알림 수신 사용자 |
| type | notification_type | N | - | | 알림 유형 |
| payload_json | jsonb | Y | - | | 알림 표시/이동에 필요한 부가 데이터 |
| is_read | boolean | N | false | | 읽음 여부 |
| created_at | timestamptz | N | now() | | 생성 시각 |

- 부분 인덱스: `idx_notifications_user_unread` — `is_read = false`인 행만 대상으로 `user_id` 인덱싱(안 읽은 알림 조회 최적화).
- PRD FR-9 근거: "인앱 폴링(30초), WebSocket 푸시·이메일은 범위 제외" — 본 테이블은 폴링 조회 대상 저장소 역할만 하면 되므로 별도 발행/구독 컬럼이 없는 현재 구조로 충분하다.

---

## 6. 공통 함수·트리거

| 이름 | 종류 | 설명 |
|---|---|---|
| `set_updated_at()` | 함수(plpgsql) | 행이 UPDATE될 때 `NEW.updated_at`을 `now()`로 설정하는 공용 트리거 함수 |
| `trg_plans_set_updated_at` | 트리거 (BEFORE UPDATE, `plans`) | `plans` 행 수정 시 `updated_at` 자동 갱신 |
| `trg_reports_set_updated_at` | 트리거 (BEFORE UPDATE, `reports`) | `reports` 행 수정 시 `updated_at` 자동 갱신 |
| `trg_bot_scenarios_set_updated_at` | 트리거 (BEFORE UPDATE, `bot_scenarios`) | `bot_scenarios` 행 수정 시 `updated_at` 자동 갱신 |
| `trg_users_set_updated_at` | 트리거 (BEFORE UPDATE, `users`) | `users` 행 수정 시 `updated_at` 자동 갱신 |
| `trg_companies_set_updated_at` | 트리거 (BEFORE UPDATE, `companies`) | `companies` 행 수정 시 `updated_at` 자동 갱신 |
| `trg_facilities_set_updated_at` | 트리거 (BEFORE UPDATE, `facilities`) | `facilities` 행 수정 시 `updated_at` 자동 갱신 |

✅ **확정**: `updated_at` 컬럼을 가진 테이블(`users`, `companies`, `plans`, `facilities`, `reports`, `bot_scenarios`)에는 **모두** `set_updated_at()` 트리거를 연결한다. 초안에서 누락됐던 `users`·`companies`·`facilities`도 위와 같이 트리거를 추가해, `updated_at`이 생성 시각에 고정되지 않고 행 수정 시 일관되게 자동 갱신되도록 통일한다.

---

## 7. RAG·상담 공용 테이블 설계 요약

착수 보고서 시점에는 "AI 챗봇 및 실시간 상담 세션의 시작/종료 정보 관리"라는 목적으로 `chat_sessions` 테이블 하나만 정의되어 있었고, 실제 메시지 저장, 상담 큐 연동, 시나리오 트리, RAG 출처 추적은 범위 밖이었다. SQL 초안 작업을 통해 다음과 같은 순서로 공용 구조를 구체화했다.

1. **세션 통합**: `chat_session_type`(`RAG`/`SCENARIO_BOT`/`COUNSEL`) enum으로 세 가지 대화 채널을 하나의 `chat_sessions`/`chat_messages` 테이블 쌍으로 통합. 채널마다 별도 테이블을 만들지 않음으로써 대화 조회·검색 로직을 공용화.
2. **상담 큐와 대화의 분리**: `counsel_tickets`는 배정/대기열/상태 같은 운영 정보만 갖고, 실제 대화는 `session_id`로 `chat_sessions`를 참조해 재사용. 상담 티켓 생성 시점엔 대화가 아직 없을 수 있어 `session_id`는 nullable로 설계.
3. **시나리오 이력 추적**: `chat_messages.scenario_id`로 시나리오 봇 세션에서 사용자가 어떤 버튼(노드)을 선택했는지 남길 수 있도록 `bot_scenarios`와 연결. `bot_scenarios`는 `parent_id` 자기 참조로 계층형 트리를 표현.
4. **RAG 벡터스토어는 PostgreSQL 밖(Chroma)에 유지**: PRD가 이미 Chroma(FastAPI 임베디드, 로컬 파일 저장)와 HuggingFace 한국어 임베딩 모델(ko-sbert/BGE-m3)을 확정하고 있음을 재확인했다. SQL 초안 중간 단계에서 `pgvector`+`rag_chunks`를 PostgreSQL에 추가했던 시도는 이 아키텍처와 어긋나 **되돌렸다**(§2.4.1). PostgreSQL은 문서 메타데이터(`rag_documents`)만 갖고, 실제 임베딩·유사도 검색은 전부 Chroma가 담당한다.
5. **근거 인용 구조화**: 초기에는 `chat_messages.citation`을 자유 텍스트로 설계했다. 이후 `rag_chunks`(pgvector)를 참조하는 조인 테이블로 바꿨다가, 4번 결정에 따라 `rag_documents`(FK, PostgreSQL 내부) + `chunk_ref`(Chroma 청크 식별자, 문자열) + `snippet`(표시용 발췌 캐시)을 함께 저장하는 `chat_message_citations`로 최종 정리했다. `citation` 컬럼은 제거.

이 결과 RAG 문답은 `chat_sessions(RAG) → chat_messages → chat_message_citations → rag_documents`(+ Chroma의 `chunk_ref`), 시나리오 봇은 `chat_sessions(SCENARIO_BOT) → chat_messages → bot_scenarios`, 상담은 `counsel_tickets → chat_sessions(COUNSEL) → chat_messages` 흐름으로 각각 표현되며, 세 채널 모두 동일한 `chat_sessions`/`chat_messages` 공용 테이블을 기반으로 동작한다.

**PRD KPI와의 연결**: `PRD_hajaCheck_v0.41.md` §9는 "챗봇 답변 출처 표기율 100%"를 성공 지표로, §6.3은 "RAG 메시지에는 출처 citation 필드 필수"를 데이터 모델 요구사항으로 명시한다. `chat_message_citations`는 이 요구를 자유 텍스트 필드보다 엄격하게 만족한다 — `session_type='RAG'`이고 `sender='BOT'`인 메시지 중 `chat_message_citations`에 매칭되는 행이 없는 메시지 수를 세면 출처 표기율 미달 건을 코드로 바로 검출할 수 있다(자유 텍스트였다면 "citation이 비어있지 않다"는 것만 확인 가능하고 실제로 유효한 근거를 가리키는지는 검증 불가능했다).

---

## 8. 핵심 요구사항 요약

본 테이블 설계는 착수보고서와 `PRD_hajaCheck_v0.41.md`의 핵심 업무 흐름을 데이터 모델 수준에서 구현 가능하도록 정리한 것이다. 요구사항별 반영 범위는 다음과 같다.

| 요구사항 영역 | 핵심 요구 | DB 설계 반영 |
|---|---|---|
| 계정·인증 | 소셜 로그인, 자체 회원가입, 개인·기업 가입, 관리자 승인 | `users`, `companies`, `company_memberships`, `user_consents`로 계정, 기업 승인, 승인된 소속, 약관 동의 이력 분리. `users`는 소셜 로그인과 이메일/비밀번호 가입을 모두 허용하도록 `ck_users_auth_method` 제약 적용 |
| 멤버십·쿼터 | Free/Standard/Enterprise 플랜, 월 분석 수, 시설 수, 좌석 수 제한, 기업 임직원의 회사 플랜 상속(§2.6) | `company_memberships`가 승인·회수·만료 가능한 회사 소속 SoT를 제공하고, `plans`, `user_plans`, `usage_counters`가 플랜 정책과 개인/회사(`user_plans.company_id`) 단위 월간 사용량을 분리한다. 애플리케이션의 `QuotaInterceptor`가 두 축을 함께 검증한다. |
| 시설물·점검 | 시설물 등록, 회차별 점검 생성, 이미지·영상 업로드, EXIF/GPS 메타데이터 | `facilities`, `inspections`, `media`로 자산-점검-미디어 흐름 구성. `media`에 파일 유형, 원본/썸네일 URL, 영상 프레임, 촬영시각, GPS, MIME 검증 결과 저장 |
| AI 하자 탐지·검수 | 결함 유형·좌표·신뢰도·등급·상태 관리, 검수 수정 이력 | `defects`에 탐지 결과와 상태를 저장하고, `defect_revisions`에 수정 이력을 append-only로 기록. 삭제는 `is_deleted` 기반 soft delete |
| 보고서 생성 | 점검 회차별 보고서 버전 관리, LLM 생성 결과, 근거검증 결과, PDF 산출물 | `reports`에 `content_json`, `grounding_check_passed`, `grounding_warnings`, `pdf_url`, 작성자/수정자, 버전 정보를 저장 |
| RAG 챗봇 | 법규·지침 문서 임베딩 상태 관리, 답변 출처 표기율 100% 검증 | `rag_documents`는 문서 메타데이터와 Chroma 임베딩 상태를 관리하고, `chat_message_citations`는 답변 메시지와 문서·Chroma 청크 참조를 구조화 |
| 시나리오 챗봇·상담 | 버튼형 챗봇, 상담원 연결, 상담 대기열, 상담 메시지 이력 | `chat_sessions`/`chat_messages`를 공용 대화 구조로 사용하고, `bot_scenarios`, `counsel_tickets`를 연결해 시나리오와 상담 큐를 분리 |
| 알림 센터 | 분석 완료, 검수 대기, 상담 답변, 점검일 도래 알림 | `notifications`에 사용자별 알림과 읽음 여부를 저장하고, 미읽음 조회 최적화를 위해 부분 인덱스 적용 |

핵심 설계 원칙은 다음 세 가지다.

1. **PRD 확정 아키텍처 우선**: RAG 벡터 검색은 PostgreSQL `pgvector`가 아니라 FastAPI 내 Chroma가 담당한다. PostgreSQL은 업무 데이터와 RAG 메타데이터·출처 참조만 관리한다.
2. **감사 추적 가능성 확보**: 검수 수정은 `defects` 직접 변경 결과만 남기지 않고 `defect_revisions`에 변경 항목·이전값·새값·사유·수정자를 기록한다.
3. **역할과 플랜의 축 분리**: `users.role`은 권한, `plans`/`user_plans`/`usage_counters`는 사용량·좌석·유료 기능 제한을 담당한다.

---

## 9. 최종 산출물 목록

HAJA-102 기준 최종 산출물은 아래 파일들이다.

| 산출물 | 파일 | 용도 |
|---|---|---|
| 테이블 디자인 설계서 | `table_design.md` | 착수보고서·PRD 대비 변경 사항, ERD 개요, enum/테이블 상세, RAG·상담 공용 설계, 핵심 요구사항 요약을 설명하는 기준 문서 |
| 최종 통합 DDL | `HajaCheck_script.sql` | 신규 DB를 현재 최종 스키마로 생성할 때 사용하는 기준 SQL |
| 기준 요구사항 문서 | `PRD_hajaCheck_v0.41.md` | 테이블 설계의 근거가 되는 기능 요구사항, IA, 시스템 아키텍처, 주요 데이터 모델 정의 |

제출·공유 시에는 `table_design.md`를 설명 문서로, `HajaCheck_script.sql`을 최종 적용 기준으로 사용한다.
