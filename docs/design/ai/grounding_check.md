# Grounding Check (사실 검증) 로직 설계 — HAJA-117

> 에픽: HAJA-103 [AI 설계] · 담당: 허남 · 관련 요구: PRD_hajaCheck_v0.41 §6.2 (line 274)
> 구현: `ai-server/ai/core/grounding.py` · 엔드포인트: `POST /ai/grounding-check` · 테스트: `ai-server/tests/test_grounding.py`

## 1. 목적

sLLM(Qwen3-8B 등 소형 LLM)은 보고서·브리핑을 생성할 때 **하자 개수·등급 같은 수치를 환각**하는 경향이 있다.
Grounding Check는 생성 체인의 **마지막 후처리 단계**로서, LLM이 언급한 수치·등급을 `defects` **실측치와 코드로 대조**해
불일치를 잡아내는 환각 방어 게이트다.

> PRD 정의: "생성 체인 마지막에 후처리 검증 단계 — LLM이 언급한 하자 개수·등급을 `defects` 실측치와 코드로 대조,
> 불일치 시 해당 섹션 재생성 또는 경고 배지 표시(sLLM 수치 환각 방어)"

## 2. 설계 원칙

| 원칙 | 이유 |
|---|---|
| **LLM 호출 없음 (순수 코드)** | 검증기 자체가 환각하면 안 됨. 결정론적·재현 가능·크레딧 0. |
| **공통 `ai/core/` 모듈** | 특정 체인 전용이 아니라 보고서·브리핑 등 모든 생성 체인이 재사용 (AI_개발_컨벤션 §0). |
| **실측치가 유일한 진실(SOT)** | `defects`(유형·등급) 실측 집계가 기준. 생성물은 이 기준에 "맞춰야" 통과. |
| **조치는 호출 측이 선택** | 불일치 시 `REGENERATE`(섹션 재생성) 또는 `WARN`(경고 배지) — 체인이 정책 지정. |

## 3. 로직 흐름

```
생성 체인 (예: 보고서)
   │  ① LLM structured output → 주장 수치(claims)
   ▼
check_grounding(defects, claims, on_mismatch)
   │  ② summarize_defects(defects) → 실측 집계(GroundTruth: total·등급별·유형별)
   │  ③ claims 각 항목을 실측치와 코드 대조 → CheckItem(MATCH/MISMATCH)
   │  ④ 언급 등급이 실제 존재하는지 / 유효 등급(A~E)인지 검증
   ▼
GroundingResult(grounded, action, checks, mismatches, ground_truth)
   │
   ├─ action=PASS        → 그대로 확정
   ├─ action=REGENERATE  → 해당 섹션 재생성 (기본)
   └─ action=WARN        → 프론트 경고 배지 표시
```

입력 `claims` 는 생성 체인의 **structured output**(Pydantic)에서 채운다 — 자유 텍스트 정규식 파싱 금지
(AI_개발_컨벤션.md §4). 서술형 텍스트에서 수치를 추출하는 경로는 **후속 이슈 + AI 코치 협의**로 분리(§7).

## 4. 대조 항목

| 항목 | 판정 |
|---|---|
| `total_count` | 주장 총 건수 ≠ 실측 총 건수 → MISMATCH |
| `grade:{등급}` | 등급별 주장 건수 ≠ 실측 건수 → MISMATCH |
| `type:{유형}` | 유형별 주장 건수 ≠ 실측 건수 → MISMATCH |
| `mentioned_grade:{등급}` | 실측에 0건인 등급 언급, 또는 A~E 아닌 등급 언급 → MISMATCH |

`grounded = (MISMATCH 0건)`. 하나라도 불일치면 `action = on_mismatch` 정책값.

## 5. API

`POST /ai/grounding-check` — 공통 `AIResponse` envelope.

```jsonc
// 요청
{
  "defects": [{"defect_type": "균열", "grade": "C"}, {"defect_type": "누수", "grade": "C"}],
  "claims":  {"total_count": 2, "count_by_grade": {"C": 2}},
  "on_mismatch": "regenerate"   // 또는 "warn" (기본 regenerate)
}
// 응답 (data)
{
  "grounded": true,
  "action": "PASS",             // PASS | REGENERATE | WARN
  "ground_truth": {"total_count": 2, "count_by_grade": {"C": 2}, "count_by_type": {"균열": 1, "누수": 1}},
  "checks": [ ... ],
  "mismatches": []              // 불일치 항목만 — 프론트 재생성/배지 트리거
}
```

## 6. 생성 체인 통합 방법 (재사용 가이드)

```python
from ai.core.grounding import GroundingAction, GroundingClaims, MismatchPolicy, check_grounding

# 1) LLM structured output에서 주장 수치를 claims로 구성
claims = GroundingClaims(total_count=report.defect_count, count_by_grade=report.grade_counts)

# 2) 실측 defects와 대조 (게이트)
result = check_grounding(defects, claims, on_mismatch=MismatchPolicy.REGENERATE)

# 3) 조치
if result.action is not GroundingAction.PASS:
    ...  # 해당 섹션 재생성 or 경고 배지 + result.mismatches 로그
```

## 7. 범위 / 한계 (후속)

- 현재 범위: **수치·등급의 코드 대조**(PRD 정의 그대로). 서술 문장의 의미적 사실성(원인·조치의 근거 부합)은 범위 밖.
- 서술 근거 검증(RAG 출처 인용 대조, 문장 단위 entailment)은 후속 확장 포인트 — 이 모듈의 `check_grounding` 뒤에
  LLM entailment 계층을 선택적으로 얹을 수 있도록 결과 구조(CheckItem)를 열어 둠.
- **서술형 텍스트 추출 경로는 이번 범위에서 제외**(후속 이슈 + AI 코치 협의). 정규식 best-effort 추출은
  어순("3건의 A등급" vs "A등급 3건")·지역/전역 총계 구분에서 오탐 위험이 있어(정상 출력을 환각으로 오탐),
  AI_개발_컨벤션 §4(자유 텍스트 파싱 금지)에 따라 분리한다. 생성 체인은 structured output의 구조화 수치를
  `claims` 로 직접 넘긴다.
