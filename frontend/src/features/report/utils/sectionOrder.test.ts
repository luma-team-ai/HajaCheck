// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import type { ManualSection } from '../types';
import { insertSectionAtCanonicalPosition } from './sectionOrder';

// 회귀 테스트(#1375) — "+ 서식 섹션 추가"가 항상 맨 끝에 붙던 걸 표준 서식 순서(제출문→
// 기본현황→...→위치도·사진)에 맞는 위치로 자동 삽입하도록 바꿨다. 표지(제출문)를 나중에
// 추가해도 맨 위로 가야 사용자가 드래그로 끌어올릴 필요가 없다.
describe('insertSectionAtCanonicalPosition', () => {
  it('제출문을 나중에 추가해도 최상단(고정 섹션들보다 앞)에 삽입된다', () => {
    const order = ['overview', 'summary', 'detail', 'recommendation', 'photos'];
    const result = insertSectionAtCanonicalPosition(order, 'manual-submission-1', 'submission', []);
    expect(result).toEqual(['manual-submission-1', 'overview', 'summary', 'detail', 'recommendation', 'photos']);
  });

  it('참여 기술진 명단은 부위별 사진(photos) 앞, 고정 섹션들 뒤에 삽입된다', () => {
    const order = ['overview', 'summary', 'detail', 'recommendation', 'photos'];
    const result = insertSectionAtCanonicalPosition(order, 'manual-participants-1', 'participants', []);
    expect(result).toEqual(['overview', 'summary', 'detail', 'recommendation', 'manual-participants-1', 'photos']);
  });

  it('이미 추가된 다른 수동 섹션 사이에도 표준 순서에 맞게 끼워 넣는다', () => {
    const existingSubmission: ManualSection = {
      id: 'manual-submission-1',
      type: 'submission',
      title: '제출문',
      data: { recipient: '', contractDate: '', companyName: '', companyAddress: '', representativeName: '' },
    };
    const order = ['manual-submission-1', 'overview', 'summary', 'detail', 'recommendation', 'photos'];
    // 안전성평가 결과 — 표준 순서상 detail/recommendation 뒤, facility-status류보다 앞이지만
    // 여기선 고정 섹션만 있으므로 recommendation 뒤(=photos 앞)에 들어가야 한다.
    const result = insertSectionAtCanonicalPosition(
      order,
      'manual-safety-assessment-1',
      'safety-assessment',
      [existingSubmission],
    );
    expect(result).toEqual([
      'manual-submission-1',
      'overview',
      'summary',
      'detail',
      'recommendation',
      'manual-safety-assessment-1',
      'photos',
    ]);
  });

  it('알 수 없는 타입은 안전하게 맨 끝에 추가된다', () => {
    const order = ['overview', 'summary'];
    const result = insertSectionAtCanonicalPosition(order, 'manual-unknown-1', 'unknown-type', []);
    expect(result).toEqual(['overview', 'summary', 'manual-unknown-1']);
  });
});
