// getApiErrorMessage 단위 테스트(코드 리뷰 P3) — axios 인터셉터가 던지는 ApiError(평범한 객체,
// Error 서브클래스가 아님)에서 서버 메시지를 뽑아내는지, 그 외 값에서는 fallback을 쓰는지 고정한다.
import { describe, expect, it } from 'vitest';
import { getApiErrorMessage } from './types';

describe('getApiErrorMessage', () => {
  it('ApiError 형태(message 문자열 보유)면 그 메시지를 반환한다', () => {
    const apiError = { code: 'ANALYSIS_NO_MEDIA', message: '업로드된 이미지가 없습니다.', status: 400 };

    expect(getApiErrorMessage(apiError, '대체 문구')).toBe('업로드된 이미지가 없습니다.');
  });

  it('표준 Error 인스턴스는 ApiError가 아니라도 message가 있으면 그 메시지를 반환한다', () => {
    // Error 인스턴스도 message: string을 가지므로 이 판별식으로는 자연스럽게 통과한다 —
    // 별도 분기가 필요 없다는 것 자체가 이 유틸의 요점이다.
    expect(getApiErrorMessage(new Error('네트워크 오류'), '대체 문구')).toBe('네트워크 오류');
  });

  it('message가 없는 값(null·undefined·문자열 등)은 fallback을 반환한다', () => {
    expect(getApiErrorMessage(null, '대체 문구')).toBe('대체 문구');
    expect(getApiErrorMessage(undefined, '대체 문구')).toBe('대체 문구');
    expect(getApiErrorMessage('그냥 문자열', '대체 문구')).toBe('대체 문구');
    expect(getApiErrorMessage({ code: 'X' }, '대체 문구')).toBe('대체 문구');
  });
});
