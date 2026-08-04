// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ImageWithFallback } from './ImageWithFallback';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('ImageWithFallback', () => {
  it('src가 있으면 이미지를 렌더링한다', () => {
    render(<ImageWithFallback src="/img.png" alt="테스트 이미지" fallback={<span>사진 없음</span>} />);

    expect(screen.getByAltText('테스트 이미지')).not.toBeNull();
    expect(screen.queryByText('사진 없음')).toBeNull();
  });

  it('src가 없으면 fallback을 렌더링한다', () => {
    render(<ImageWithFallback src={null} alt="테스트 이미지" fallback={<span>사진 없음</span>} />);

    expect(screen.getByText('사진 없음')).not.toBeNull();
    expect(screen.queryByAltText('테스트 이미지')).toBeNull();
  });

  // #1494 — 인증 필요·캐시 금지 썸네일이 목록 진입 시 동시 요청 폭주로 첫 시도가 실패해도, 그
  // 자리에서 바로 fallback으로 영구 고정되면 안 된다. 지연 후 재시도하는 동안은 fallback이 아니어야
  // 하고, 재시도가 성공하면(추가 에러 없이) 이미지가 정상적으로 남아있어야 한다.
  it('첫 로드 실패 시 즉시 fallback으로 전환하지 않고 지연 후 재시도한다(#1494)', () => {
    vi.useFakeTimers();
    render(<ImageWithFallback src="/img.png" alt="테스트 이미지" fallback={<span>사진 없음</span>} />);

    fireEvent.error(screen.getByAltText('테스트 이미지'));

    // 재시도 대기 중에는 아직 fallback으로 안 바뀐다.
    expect(screen.queryByText('사진 없음')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(500);
    });

    // 재시도로 <img>가 다시 렌더링되고, 추가 에러가 없으면 정상 표시 상태를 유지한다.
    expect(screen.getByAltText('테스트 이미지')).not.toBeNull();
    expect(screen.queryByText('사진 없음')).toBeNull();
  });

  it('재시도 후에도 실패하면 그때서야 fallback으로 전환한다(#1494)', () => {
    vi.useFakeTimers();
    render(<ImageWithFallback src="/img.png" alt="테스트 이미지" fallback={<span>사진 없음</span>} />);

    fireEvent.error(screen.getByAltText('테스트 이미지'));
    act(() => {
      vi.advanceTimersByTime(500);
    });
    fireEvent.error(screen.getByAltText('테스트 이미지'));

    expect(screen.getByText('사진 없음')).not.toBeNull();
    expect(screen.queryByAltText('테스트 이미지')).toBeNull();
  });

  it('src가 바뀌면 이전 실패 기록이 초기화돼 새 src로 다시 시도한다', () => {
    vi.useFakeTimers();
    const { rerender } = render(
      <ImageWithFallback src="/img1.png" alt="테스트 이미지" fallback={<span>사진 없음</span>} />,
    );
    fireEvent.error(screen.getByAltText('테스트 이미지'));
    act(() => {
      vi.advanceTimersByTime(500);
    });
    fireEvent.error(screen.getByAltText('테스트 이미지'));
    expect(screen.getByText('사진 없음')).not.toBeNull();

    rerender(<ImageWithFallback src="/img2.png" alt="테스트 이미지" fallback={<span>사진 없음</span>} />);

    expect(screen.getByAltText('테스트 이미지')).not.toBeNull();
    expect(screen.queryByText('사진 없음')).toBeNull();
  });
});
