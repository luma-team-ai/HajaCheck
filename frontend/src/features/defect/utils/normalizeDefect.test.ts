import { describe, expect, it } from 'vitest';
import type { Defect } from '../types';
import { normalizeDefect } from './normalizeDefect';

const baseDefect: Defect = {
  id: 1,
  inspectionId: 10,
  facilityId: 100,
  facilityName: '테스트 시설물',
  facilityType: '교량',
  type: 'CRACK',
  typeLabel: '균열',
  grade: 'C',
  status: 'IN_PROGRESS',
  confidence: 0.9,
  reviewed: true,
  bboxX: null,
  bboxY: null,
  bboxW: null,
  bboxH: null,
  crackWidthMm: null,
  crackLengthMm: null,
  imageUrl: null,
  createdAt: '2026-07-01T00:00:00',
};

describe('normalizeDefect', () => {
  it('백엔드 flat action* 필드를 actionResult 중첩 객체로 조립한다(#1182)', () => {
    const raw = {
      ...baseDefect,
      actionContent: '균열 부위 보수 완료',
      actionDate: '2026-07-20',
      actionAssigneeId: 5,
      actionAssigneeName: '홍길동',
      actionPhotoUrl: '/api/media/9/thumbnail',
    };

    const result = normalizeDefect(raw);

    expect(result.actionResult).toEqual({
      actionContent: '균열 부위 보수 완료',
      actionDate: '2026-07-20',
      assigneeId: 5,
      assigneeName: '홍길동',
      afterPhotoUrl: '/api/media/9/thumbnail',
    });
  });

  it('actionContent가 null이면 actionResult도 null이다(조치 등록 전)', () => {
    const raw = {
      ...baseDefect,
      actionContent: null,
      actionDate: null,
      actionAssigneeId: null,
      actionAssigneeName: null,
      actionPhotoUrl: null,
    };

    expect(normalizeDefect(raw).actionResult).toBeNull();
  });

  it('이미 중첩된 actionResult가 있으면(mock 등) 그대로 통과시킨다', () => {
    const raw = {
      ...baseDefect,
      actionResult: {
        actionContent: '기존 mock 값',
        actionDate: '2026-07-15',
        assigneeId: 3,
        assigneeName: '김철수',
        afterPhotoUrl: null,
      },
    };

    expect(normalizeDefect(raw).actionResult).toEqual(raw.actionResult);
  });

  it('ACTION_PENDING 상태는 CONFIRMED로 정규화한다(기존 동작 유지)', () => {
    const raw = { ...baseDefect, status: 'ACTION_PENDING' as unknown as Defect['status'] };

    expect(normalizeDefect(raw).status).toBe('CONFIRMED');
  });

  it('점검별 하자 응답의 mediaId는 그대로 보존하고 단건 상세의 누락도 허용한다', () => {
    expect(normalizeDefect({ ...baseDefect, mediaId: 901 }).mediaId).toBe(901);
    expect(normalizeDefect(baseDefect).mediaId).toBeUndefined();
  });
});
