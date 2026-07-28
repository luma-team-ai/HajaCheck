<!-- 점검 목록 자연어 검색 → 의미 수준 필터 의도 V2 (HAJA-537) -->
<!-- 입력 변수: {{query_text}} (사용자 질의를 UNTRUSTED DATA 마커로 감싼 블록) -->

사용자가 점검 목록 화면에서 입력한 자연어 검색 질의를 `NlSearchIntentV2`로 변환하라.
DB 쿼리, SQL, 현재 날짜를 추측한 날짜값은 만들지 마라. 상대 날짜 표현은 반드시
`inspectionDate.kind=ROLLING_PAST` 의미로만 반환하며 실제 날짜 계산은 서버가 수행한다.

## 지원 필터
- type (하자 유형): CRACK, SPALLING, LEAK_EFFLORESCENCE, REBAR_EXPOSURE, PAINT_DAMAGE
- grade (하자 등급, A=경미~E=심각): A, B, C, D, E
- defectStatus (하자 상태): DETECTED, CONFIRMED, IN_PROGRESS, RESOLVED
- confidenceMin (AI 탐지 신뢰도 하한): 0~1
- inspectionType (점검 종류): REGULAR, DETAILED, EMERGENCY
- inspectionStatus (점검 진행 상태): CREATED, UPLOADING, ANALYZING, ANALYZED, REVIEWED, REPORTED
- inspectionDate (점검일): 절대 범위 또는 과거로부터의 상대 범위
- roundNo (점검 회차): EXACT, GTE, LTE, BETWEEN
- defectCount (점검에 속한 전체 하자 건수): EXACT, GTE, LTE, BETWEEN

모든 목록 필드는 질의에 명시된 값만 넣고, 명시되지 않은 목록은 빈 배열로 반환하라.
`intentVersion`은 항상 문자열 `"2"`로 반환하라.

## 하자 표현
균열/크랙/금 감/갈라짐→CRACK, 박리박락/박리/박락→SPALLING,
누수백태/누수/백태/물샘→LEAK_EFFLORESCENCE, 철근노출/철근 노출→REBAR_EXPOSURE,
도장손상/도장 손상/페인트 손상→PAINT_DAMAGE.

신규/미확인→DETECTED, 검수확정/조치대기/조치 대기→CONFIRMED,
조치중/조치 진행중→IN_PROGRESS, 조치완료/해결됨→RESOLVED.
하자라는 대상이 명시된 "검수 완료 하자", "검수확정 하자"는 defectStatus=CONFIRMED다.

등급 순서는 A < B < C < D < E이며 뒤로 갈수록 심각하다.
"D등급 이상"→["D","E"], "B등급 이하"→["A","B"], "A등급만"→["A"].
"심각한", "위험한"만으로 등급을 추측하지 말고 clarifying_question으로 등급 기준을 물어라.

신뢰도 하한의 퍼센트는 100으로 나눈다. "80% 이상"→0.8.
신뢰도 상한/미만은 지원하지 않으므로 해당 구절을 unsupported_terms에 넣는다.

## 점검 표현
- 정기점검→REGULAR, 정밀점검→DETAILED, 긴급점검→EMERGENCY
- 점검 생성/준비→CREATED, 업로드 중→UPLOADING, AI 분석 중→ANALYZING,
  분석 완료된 점검→ANALYZED, 검수 완료된 점검→REVIEWED, 보고서 생성 완료→REPORTED

"검수 완료된 점검"처럼 점검이 명시되면 inspectionStatus=REVIEWED다.
"검수 완료"만 있어 하자 상태인지 점검 상태인지 불명확하면 어느 대상을 뜻하는지 되물어라.
"완료된 점검", "진행 중인 점검"처럼 어느 단계를 뜻하는지 불명확하면 점검 단계를 되물어라.
되묻는 경우 애매한 축의 값을 임의로 채우지 마라.

## 날짜 의미
- "지난 N일/주/달(개월)간"은 `kind=ROLLING_PAST`, amount=N,
  unit=DAY/WEEK/MONTH로 반환한다. 오늘이나 실제 날짜를 계산하지 마라.
- 사용자가 시작일과 종료일을 YYYY-MM-DD로 명시한 경우에만
  `kind=ABSOLUTE_RANGE`, dateFrom/dateTo에 그대로 반환한다.
- 날짜 하나만 명시하면 같은 날짜를 dateFrom/dateTo에 모두 넣는다.
- 해석할 수 없는 달력 표현은 날짜를 추측하지 말고 clarifying_question으로 정확한 기간을 물어라.

## 회차·하자 건수 의미
- "1회차"→roundNo `{{operator: EXACT, value: 1, maxValue: null}}`
- "3회차 이상/이하"→roundNo의 GTE/LTE, "2회차부터 4회차"→BETWEEN(2,4)
- "하자가 3건 이상인 점검"→defectCount `{{operator: GTE, value: 3, maxValue: null}}`
- defectCount는 유형과 무관하게 한 점검에 속한 전체 하자 건수다.
- "균열이 3건 이상"처럼 특정 하자 유형의 건수인지 전체 하자 건수인지 애매하면
  defectCount를 채우지 말고 "전체 하자 건수 기준인지 특정 유형 건수 기준인지" 되물어라.

## 애매하거나 지원하지 않는 표현
- 담당자, 층/위치 등 지원 필터 밖 조건은 원문 구절을 unsupported_terms에 넣는다.
- 확실한 조건은 유지할 수 있지만, 모호한 조건은 추측하지 않는다.
- 정상 해석이면 clarifying_question=null, interpretation_confidence는 0.7 이상으로 반환한다.

질의:
{query_text}
