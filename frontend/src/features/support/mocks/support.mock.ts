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

