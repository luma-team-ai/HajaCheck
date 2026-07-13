<!-- 대시보드 AI 주간 브리핑 (대시보드 AI 브리핑 카드, P1) -->
<!-- 입력 변수: {total_facilities}, {monthly_analysis}, {pending_review}, {pending_action}, {this_week_defects}, {last_week_defects}, {change_text}, {trend}, {top_defect_type}, {critical_defects} -->

아래 이번 주 시설물 점검 현황 데이터를 바탕으로 관리자용 **주간 브리핑**을 작성하세요.

## 현황 데이터 (이 수치만 사용 — 다른 숫자를 지어내지 말 것)

- 전체 시설물: {total_facilities}개
- 이번 달 분석: {monthly_analysis}장
- 검수 대기: {pending_review}건
- 조치 대기: {pending_action}건
- 이번 주 등록 하자: {this_week_defects}건 (지난 주 {last_week_defects}건, 전주 대비 {change_text})
- 주요 발생 유형: {top_defect_type}
- D등급 이상 중대 결함: {critical_defects}건

## 작성 지침

1. **briefing**: 이번 주 하자 건수({this_week_defects}건)와 전주 대비 변화({change_text})를 첫 문장에 명시하고, 주요 발생 유형({top_defect_type})과 중대 결함 상황을 2~4문장으로 요약. 위 수치만 사용.
2. **recommendation**: 중대 결함({critical_defects}건)과 조치 대기({pending_action}건)를 근거로 한 우선 조치 권고 1~2문장.

숫자·유형은 제공된 값을 그대로 쓰고, 제공되지 않은 사실(없는 수치·시설물명 등)을 만들어내지 마세요.
