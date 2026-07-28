# 점검 목록 자연어 검색 필터 변환 스키마 설계

> **문서 버전:** v0.4 · **최종 수정:** 2026-07-28 · 이전 버전 `archive/`

> 관련 GitHub: #1140 · Jira: HAJA-537
> 구현: `ai-server/ai/chains/nl_search_chain.py` · 프롬프트: `ai-server/ai/prompts/nl_search_convert.md`
> 공개 경계: Spring Boot `POST /api/defects/nl-search` · 내부 경계: FastAPI `POST /ai/nl-search`

## 0. 목표와 책임 경계

자연어를 점검 목록 조회에 사용할 수 있는 구조화 필터로 변환한다. LLM은 DB를 조회하거나 SQL을
생성하지 않는다. LLM의 책임은 표현의 **의미를 구조화하는 것**이고, 날짜 및 숫자 비교를 최종
조회 파라미터로 바꾸는 일은 Python 정규화기의 책임이다.

처리 순서는 다음과 같다.

1. Spring Boot가 사용자 질의와 KST 기준일(`referenceDate`)을 내부 API에 전달한다.
2. LLM이 기준일과 무관한 `NlSearchIntentV2`를 structured output으로 반환한다.
3. Python 정규화기가 상대 날짜·회차·하자 건수 연산자를 최종 필터로 변환한다.
4. FastAPI가 공통 `AIResponse` envelope에 최종 `NlSearchResult`를 담아 반환한다.
5. Spring Boot와 프론트가 같은 필터 계약으로 점검 목록을 조회하고 칩을 표시한다.

`NlSearchIntentV2`를 캐시하고, 기준일로 계산된 최종 날짜는 캐시하지 않는다. 따라서 같은
"지난 두 달간" 캐시를 다음 날 재사용해도 요청의 `referenceDate`를 기준으로 다시 계산된다.
V1 캐시와 섞이지 않도록 structured output 모델 이름과 `intentVersion`을 V2로 고정한다.

## 1. 최종 필터 계약

```python
class NlSearchFilters(BaseModel):
    type: list[DefectTypeCode]
    grade: list[DefectGradeCode]
    status: list[DefectStatusCode]
    confidenceMin: float | None
    inspectionType: list[InspectionTypeCode]
    inspectionStatus: list[InspectionStatusCode]
    inspectionDateFrom: date | None
    inspectionDateTo: date | None
    roundNoMin: int | None
    roundNoMax: int | None
    defectCountMin: int | None
    defectCountMax: int | None
```

| 축 | 값·범위 | 의미 |
|---|---|---|
| 하자 유형 `type` | `CRACK`, `SPALLING`, `LEAK_EFFLORESCENCE`, `REBAR_EXPOSURE`, `PAINT_DAMAGE` | 같은 축 안에서는 OR |
| 하자 등급 `grade` | `A`~`E` | 비교 표현은 포함 집합으로 확장 |
| 하자 상태 `status` | `DETECTED`, `CONFIRMED`, `IN_PROGRESS`, `RESOLVED` | 점검 상태와 별도 축 |
| 탐지 신뢰도 | 0~1 하한 | 기존 계약 유지 |
| 점검 종류 | `REGULAR`, `DETAILED`, `EMERGENCY` | 같은 축 안에서는 OR |
| 점검 진행 상태 | `CREATED`, `UPLOADING`, `ANALYZING`, `ANALYZED`, `REVIEWED`, `REPORTED` | 대시보드 그룹이 아닌 원시 상태 |
| 점검일 | inclusive from/to | `inspections.inspection_date` 기준 |
| 점검 회차 | 1 이상의 inclusive min/max | 시설별 회차 값 |
| 하자 건수 | 0 이상의 inclusive min/max | 점검에 속한 전체 미삭제 하자 건수 |

서로 다른 축은 AND로 결합한다. 하자 유형·등급·상태가 함께 지정되면 백엔드는 **동일한 하자
한 건**이 세 조건을 모두 만족하도록 적용한다. `defectCount`는 유형·등급·상태 필터와 무관하게
점검에 속한 전체 미삭제 하자 수이며, 목록에 표시되는 하자 건수와 같은 정의를 쓴다.

## 2. LLM 의미 스키마 V2

```python
class NlSearchIntentV2(BaseModel):
    intentVersion: Literal["2"]
    type: list[DefectTypeCode]
    grade: list[DefectGradeCode]
    defectStatus: list[DefectStatusCode]
    confidenceMin: float | None
    inspectionType: list[InspectionTypeCode]
    inspectionStatus: list[InspectionStatusCode]
    inspectionDate: DateIntent | None
    roundNo: RoundIntent | None
    defectCount: DefectCountIntent | None
    unsupported_terms: list[str]
    clarifying_question: str | None
    interpretation_confidence: float
```

날짜 의미는 두 형태만 지원한다.

```python
class DateIntent(BaseModel):
    kind: Literal["ABSOLUTE_RANGE", "ROLLING_PAST"]
    dateFrom: date | None
    dateTo: date | None
    amount: int | None
    unit: Literal["DAY", "WEEK", "MONTH"] | None
```

- 절대 날짜는 `ABSOLUTE_RANGE`로 시작일과 종료일을 모두 제공한다. 날짜 하나는 양쪽에 같은 값을 쓴다.
- "지난 N일/주/달간"은 `ROLLING_PAST`의 `amount`와 `unit`으로만 표현한다.
- LLM 프롬프트에는 `referenceDate`를 넣지 않으며 실제 날짜 계산을 금지한다.

회차와 하자 건수는 `EXACT`, `GTE`, `LTE`, `BETWEEN` 연산자와 정수 값으로 표현한다.
`BETWEEN`만 `maxValue`를 가지며, 모델 검증에서 역전 범위를 거부한다. 회차는 1 이상, 하자 건수는
0 이상이다.

## 3. 요청과 결정적 정규화

내부 요청:

```json
{
  "query": "지난 두 달간의 1회차 점검 알려줘",
  "referenceDate": "2026-07-28"
}
```

`query`는 trim 후 1~500자다. `referenceDate`는 `YYYY-MM-DD`이며 Spring Boot가 KST 기준일을
주입한다. 점진 배포 중 구버전 Spring 호출을 허용하기 위해 누락 시 FastAPI가
`Asia/Seoul`의 현재 날짜를 사용한다. 형식이 잘못된 값은 LLM을 호출하지 않고
`VALIDATION_ERROR` envelope으로 반환한다.

위 질의의 LLM 의미:

```json
{
  "intentVersion": "2",
  "inspectionDate": {
    "kind": "ROLLING_PAST",
    "amount": 2,
    "unit": "MONTH"
  },
  "roundNo": {
    "operator": "EXACT",
    "value": 1,
    "maxValue": null
  }
}
```

정규화 결과:

```json
{
  "inspectionDateFrom": "2026-05-28",
  "inspectionDateTo": "2026-07-28",
  "roundNoMin": 1,
  "roundNoMax": 1
}
```

월 계산은 달력 월을 빼며 말일은 대상 월의 마지막 날로 보정한다. 예를 들어
`2026-03-31`에서 한 달을 빼면 `2026-02-28`이다. 시작일과 종료일은 모두 포함한다.

## 4. 표현 매핑과 모호성 정책

- "정기점검", "정밀점검", "긴급점검"은 각각 `REGULAR`, `DETAILED`, `EMERGENCY`다.
- "분석 완료된 점검"은 `ANALYZED`, "검수 완료된 점검"은 `REVIEWED`,
  "보고서 생성 완료"는 `REPORTED`다.
- "검수 완료 하자", "검수확정 하자"는 하자 상태 `CONFIRMED`다.
- 대상 없이 "검수 완료"라고만 하면 하자 상태와 점검 상태 중 어느 것인지 되묻는다.
- "완료된 점검", "진행 중인 점검"처럼 단계를 특정하지 않으면 점검 단계를 되묻는다.
- "1회차"는 `roundNoMin=1`, `roundNoMax=1`이다.
- "하자가 3건 이상인 점검"은 `defectCountMin=3`이다.
- "균열이 3건 이상"은 전체 하자 건수인지 균열 건수인지 모호하므로 값을 추측하지 않고 되묻는다.
- 위치·담당자 등 미지원 조건은 원문 구절을 `unsupported_terms`에 넣고, 확실한 다른 축은 유지한다.

`clarifying_question`이 있으면 프론트는 필터를 적용하지 않는다. 지원 조건과 미지원 조건이 함께 있고
되묻기가 필요하지 않으면 지원 조건을 적용하고 `unsupported_terms`를 안내한다.

## 5. 테스트 기준

- 기준일 `2026-07-28`, "지난 두 달간의 1회차 점검" → `2026-05-28..2026-07-28`,
  회차 `1..1`
- 3월 31일에서 한 달 전 계산 시 2월 말일 보정
- 같은 LLM 상대 의도를 서로 다른 기준일로 정규화하면 날짜 결과만 달라짐
- 절대 날짜 범위, `BETWEEN` 회차, `GTE` 하자 건수 변환
- 잘못된 날짜 범위·숫자 범위·`referenceDate` 형식 거부
- `NlSearchIntentV2` structured output 사용 및 공통 envelope 유지
- 빈 질의·501자 질의·내부 서비스 토큰 검증 회귀 방지

## 6. 변경 이력

- v0.4 (2026-07-28): #1140/HAJA-537 — 점검일·점검 종류·점검 진행 상태·점검 회차·전체 하자
  건수를 추가하고, 의미 수준 `NlSearchIntentV2`와 기준일 기반 결정적 정규화를 도입했다.
- v0.3 (2026-07-28): #1079 — `ACTION_PENDING` 제거에 맞춰 하자 상태를 4값으로 갱신했다.
- v0.1 (2026-07-15): 최초 하자 자연어 검색 필터 스키마를 설계했다.
