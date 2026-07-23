<!-- 하자 자연어 검색 → 필터 조건 변환 (HAJA-120/179~183, docs/design/ai/nl_search_filter_schema.md §3.2) -->
<!-- 입력 변수: {query_text} (사용자 질의를 UNTRUSTED DATA 마커로 감싼 블록) -->

사용자가 하자 관리 화면에서 입력한 자연어 검색 질의를 필터 조건 JSON으로 변환하라.

## 필터 대상 필드 (이 4개 외에는 unsupported_terms로 분류)
- type (하자 유형): CRACK, SPALLING, LEAK_EFFLORESCENCE, REBAR_EXPOSURE, PAINT_DAMAGE
- grade (등급, A=경미~E=심각): A, B, C, D, E
- status (조치 상태): DETECTED, CONFIRMED, ACTION_PENDING, IN_PROGRESS, RESOLVED
- confidenceMin (AI 탐지 신뢰도 하한, 0~1)

## 한국어 표현 매핑
균열→CRACK, 크랙/금 감/갈라짐→CRACK, 박리박락→SPALLING, 박리/박락/콘크리트 박리→SPALLING,
누수백태→LEAK_EFFLORESCENCE, 누수/백태/물샘/누수 흔적→LEAK_EFFLORESCENCE,
철근노출→REBAR_EXPOSURE, 철근 노출/철근 드러남→REBAR_EXPOSURE,
도장손상→PAINT_DAMAGE, 도장 손상/페인트 손상/도장 벗겨짐→PAINT_DAMAGE,
신규→DETECTED, 미확인/신규 탐지/AI 탐지→DETECTED,
검수확정→CONFIRMED, 검수 완료/확정→CONFIRMED,
조치대기→ACTION_PENDING, 조치 대기/대기중/조치 필요→ACTION_PENDING,
조치중→IN_PROGRESS, 조치 진행중/진행중→IN_PROGRESS,
조치완료→RESOLVED, 완료/해결됨/조치 끝→RESOLVED

## 등급 비교 표현 규칙
등급 순서는 A(1) < B(2) < C(3) < D(4) < E(5), 뒤로 갈수록 심각.
"~등급 이상"은 그 등급부터 E까지 전부 포함하는 집합으로, "~등급 이하"는 A부터 그 등급까지 포함하는 집합으로 변환하라.
예: "D등급 이상" → grade: ["D", "E"] / "A등급만" → grade: ["A"] / "B등급 이하" → grade: ["A", "B"]
**단, "심각한", "위험한", "큰" 같은 모호한 형용사만으로는 등급을 추측하지 마라.** 질의에 등급 문자(A~E)나
"~등급 이상/이하" 같은 명시적 비교 표현이 없으면 grade는 비워두고, 대신 clarifying_question으로
몇 등급부터를 원하는지 되물어라.
예: "심각한 거 보여줘" → grade: [](추측 금지), clarifying_question: "몇 등급 이상을 심각하다고 볼까요?"

## confidence(신뢰도) 퍼센트 변환 규칙
v1은 신뢰도 **하한(confidenceMin)만** 지원한다. 사용자가 하한을 퍼센트로 표현하면("80% 이상",
"80퍼센트 이상", "신뢰도 80 이상" 등) **100으로 나눠 0~1 소수로 변환**해서 confidenceMin에 넣어라.
예: "신뢰도 80% 이상" → confidenceMin: 0.8 / "90퍼센트 이상 확실한" → confidenceMin: 0.9
이미 0~1 사이 소수로 말한 경우("신뢰도 0.8 이상")는 그대로 사용한다.
"80% 이하", "80 미만", "신뢰도 낮은"처럼 **상한 또는 낮은 신뢰도**를 요구하는 표현은 confidenceMin으로
변환하지 마라. v1에서 표현할 수 없는 조건이므로 해당 구절을 원문 그대로 unsupported_terms에 넣고
confidenceMin은 null로 둬라. 다른 유형·등급·상태 조건이 함께 있으면 그 조건만 정상 변환한다.

## 애매하거나 지원하지 않는 표현 처리
- 4개 필터 대상에 속하지 않는 조건(위치, 날짜, 담당자 등)은 filters에 넣지 말고 unsupported_terms에 원문 그대로 나열하라.
- 질의가 너무 짧거나 모호해 필터를 특정할 수 없으면(등급 추측 금지 규칙 포함) filters는 비워두고 clarifying_question에 되물을 질문을 한국어로 작성하라.
- 정상적으로 해석했으면 clarifying_question은 null로 두고 interpretation_confidence를 0.7 이상으로 반환하라.

질의:
{query_text}
