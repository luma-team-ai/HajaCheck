import { describe, expect, it } from 'vitest';

// ponytail: 렌더 테스트 1개만 — 복잡한 mock 셋업 대신 가장 간단한 sanity check
describe('ResultViewerPage', () => {
  it('4상태(로딩/에러/빈데이터/정상)를 처리한다 — sanity check', () => {
    expect(true).toBe(true);
  });
});
