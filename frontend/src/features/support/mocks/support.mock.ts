import type { RagAnswerData } from '../types';

// 정상 응답(근거 있음) — 설계 §2/§4.2 매핑 예시(합성 데이터)
export const mockRagAnswer: RagAnswerData = {
  answer:
    '시설물 안전점검은 「시설물의 안전 및 유지관리에 관한 특별법」에 따라 정기적으로 실시해야 합니다. 정기안전점검은 반기별 1회 이상 시행하며, 시설물 등급에 따라 주기가 달라질 수 있습니다.',
  sources: [
    {
      doc_id: '12',
      title: '시설물의 안전 및 유지관리에 관한 특별법',
      collection: 'regulations',
      locator: '제11조 ①',
      chunk_ref: '12_3',
    },
    {
      doc_id: '12',
      title: '시설물의 안전 및 유지관리에 관한 특별법',
      collection: 'regulations',
      locator: '제12조',
      chunk_ref: '12_5',
    },
  ],
};

// 검색 0건 — 설계 §4.3: 임의 생성 금지, 문구 "관련 근거를 찾지 못했습니다"
// ※ 백엔드가 이를 success=false(RAG_NO_RESULT)로 줄지, 빈 sources 정상 응답으로 줄지는 설계 §9 확정 후 조정.
//   목에서는 빈 sources 정상 응답으로 모델링 → UI가 안내 문구를 표시하고 출처 칩은 렌더하지 않는다.
export const mockRagNoResult: RagAnswerData = {
  answer: '관련 근거를 찾지 못했습니다.',
  sources: [],
};
