// @vitest-environment jsdom
import { cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useNoReferrer } from './useNoReferrer';

afterEach(() => {
  cleanup();
});

describe('useNoReferrer', () => {
  it('마운트 시 <meta name="referrer" content="no-referrer">를 head에 추가한다', () => {
    renderHook(() => useNoReferrer());

    const meta = document.head.querySelector('meta[name="referrer"]');
    expect(meta).not.toBeNull();
    expect(meta?.getAttribute('content')).toBe('no-referrer');
  });

  it('언마운트 시 추가했던 meta 태그를 제거한다', () => {
    const { unmount } = renderHook(() => useNoReferrer());

    expect(document.head.querySelector('meta[name="referrer"]')).not.toBeNull();

    unmount();

    expect(document.head.querySelector('meta[name="referrer"]')).toBeNull();
  });
});
