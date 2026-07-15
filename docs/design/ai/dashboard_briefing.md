# 대시보드 AI 주간 브리핑 로직 설계 — WBS design-03-16

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-15 · 이전 버전 `archive/`

> 메뉴: 대시보드 (담당: 허남) · 관련 요구: PRD §4 대시보드 "AI 주간 브리핑 카드 (LLM 자연어 요약)"
> 구현: `ai-server/ai/chains/briefing_chain.py` · 프롬프트: `ai/prompts/dashboard_briefing.md` · 엔드포인트: `POST /ai/briefing` · 테스트: `ai-server/tests/test_briefing.py`

## 1. 목적

대시보드 상단 현황 데이터(전체 시설물·이번 주 하자·중대 결함 등)를 LLM이 **자연어 주간 브리핑**으로 요약해 관리자에게 카드로 보여준다.
예: "이번 주 등록된 하자는 총 45건으로 지난 주 대비 12% 감소했습니다. 주요 발생 유형은 '균열'이며, D등급 이상 중대 결함이 3건 발견되어 즉각적인 조치가 권장됩니다."

## 2. 설계 원칙 (수치 환각 방지 — Grounding Check와 동일 철학)

| 원칙 | 이유 |
|---|---|
| **수치는 코드로 계산·주입, LLM은 자연어만** | 전주 대비 변화율·추세를 LLM이 계산하면 틀린다. `derive_facts()`가 코드로 계산해 프롬프트에 넣고, LLM은 문장만 만든다. |
| **프롬프트에 "제공 수치만 사용" 명시** | 없는 시설물명·수치 생성 금지 (`_system_base.md` + 프롬프트 지침). |
| **structured output** | `WeeklyBriefing(briefing, recommendation)` Pydantic — 자유 텍스트 파싱 금지(AI_개발_컨벤션 §4). |
| **공통 기반 재사용** | `get_llm()` 단일 호출 지점 · 프롬프트 파일 분리 · 공통 `AIResponse` envelope. |

## 3. 로직 흐름

```
대시보드 현황 데이터(백엔드 집계)
   │  DashboardStats
   ▼
run_briefing_chain(stats)
   │  ① derive_facts(stats) → 코드 계산: change_pct, trend(감소/증가/유지)
   │     - delta = this_week - last_week
   │     - change_pct = round(|delta|/last_week*100)  (지난 주 0이면 None)
   │  ② _build_prompt(stats, facts) → _system_base + dashboard_briefing.md 채우기
   │  ③ get_llm().with_structured_output(WeeklyBriefing).invoke(prompt)
   ▼
(WeeklyBriefing{briefing, recommendation}, BriefingFacts)  → AIResponse.ok(data + facts)
```

## 4. 입력 / 출력

**입력 `DashboardStats`** (백엔드 대시보드 집계 실측치):
`total_facilities, monthly_analysis, pending_review, pending_action, this_week_defects, last_week_defects, top_defect_type, critical_defects, grade_distribution?`

**출력 (data)**:
```jsonc
{
  "briefing": "이번 주 등록된 하자는 총 45건으로 ... 즉각적인 조치가 권장됩니다.",
  "recommendation": "D등급 이상 3건 우선 조치 권장.",
  "facts": { "this_week_defects": 45, "last_week_defects": 51, "change_pct": 12, "trend": "감소", "top_defect_type": "균열", "critical_defects": 3 }
}
```
`facts.change_pct`/`trend`는 프론트 배지("12% 감소")에 그대로 사용 — LLM 문장과 별개로 코드가 보증하는 값.

## 5. API

`POST /ai/briefing` — 공통 `AIResponse` envelope. 실패 시 `LLM_INVALID_OUTPUT` 폴백(프론트는 "AI 분석을 불러올 수 없습니다" + 재시도).

## 6. 프론트 연동 (대시보드 카드)

- 카드 본문 = `data.briefing`, 하단 권고 = `data.recommendation`, 우측 배지 = `data.facts.change_pct + trend`.
- AI 실패가 대시보드의 비-AI 위젯(통계 타일 등)을 막지 않도록 카드 단위 폴백(AI_개발_컨벤션 §5).

## 7. 범위 / 후속

- 현재: 주간 현황 요약 1종. 입력 수치는 백엔드가 집계해 전달(브리핑 체인은 집계하지 않음).
- 후속: 브리핑 문장 속 수치를 Grounding Check(`ai/core/grounding` 아이디어)로 재검증하는 게이트를 옵션으로 얹을 수 있음(현재는 코드 주입으로 1차 방어).
- 캐시: 동일 현황 반복 호출은 `get_llm` 응답 캐시(프롬프트 해시)로 크레딧 절약.
