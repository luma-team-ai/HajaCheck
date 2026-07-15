<!-- 보고서 "조치 권고" 섹션 (FR-5-06, report-chain-design.md §6.4) -->
<!-- 입력 변수: {defect_count}, {defects_list_text}, {legal_basis_context} -->

아래 확정 하자 목록과 법규·지침 검색 결과를 바탕으로 조치 권고를 작성하세요.

## 확정 하자 목록 ({defect_count}건)

{defects_list_text}

## 법규·지침 검색 결과 (근거 인용은 이 결과 범위 내에서만 — 없으면 임의 생성 금지)

{legal_basis_context}

## 출력

1. **items**: 하자 유형/부위별 조치 권고 목록. 각 항목마다:
   - **target**: 대상 하자 유형/부위
   - **method**: 권고 조치·보수 방안
   - **priority**: 조치 우선순위 (예: 상/중/하)
   - **legal_basis**: 위 검색 결과에서 인용한 문서명+조문. **검색 결과가 없거나 비어있으면 "관련 근거 없음"이라고만 적고 절대 법규·조문을 지어내지 마세요.**
2. **monitoring_points**: 지속 관찰이 필요한 부위 목록

검색 결과에 없는 법규·조문·수치를 임의로 생성하지 마세요.
