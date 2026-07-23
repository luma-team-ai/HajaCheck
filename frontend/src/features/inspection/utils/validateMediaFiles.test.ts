import { describe, expect, it } from 'vitest';
import { exceedsMaxFileCount, formatFileSize, validateMediaFile } from './validateMediaFiles';

function makeFile(name: string, type: string, sizeBytes: number): File {
  return new File([new Uint8Array(sizeBytes)], name, { type });
}

describe('validateMediaFile', () => {
  it('허용된 타입·용량이면 null을 반환한다', () => {
    expect(validateMediaFile(makeFile('a.jpg', 'image/jpeg', 1024))).toBeNull();
    expect(validateMediaFile(makeFile('a.png', 'image/png', 1024))).toBeNull();
  });

  it('허용되지 않는 타입이면 FILE_INVALID_TYPE을 반환한다', () => {
    expect(validateMediaFile(makeFile('a.mp4', 'video/mp4', 1024))).toBe('FILE_INVALID_TYPE');
  });

  it('20MB를 초과하면 FILE_TOO_LARGE를 반환한다', () => {
    expect(validateMediaFile(makeFile('a.jpg', 'image/jpeg', 21 * 1024 * 1024))).toBe(
      'FILE_TOO_LARGE',
    );
  });
});

describe('exceedsMaxFileCount', () => {
  it('합이 10 이하면 false', () => {
    expect(exceedsMaxFileCount(5, 5)).toBe(false);
  });

  it('합이 10을 초과하면 true', () => {
    expect(exceedsMaxFileCount(8, 3)).toBe(true);
  });
});

describe('formatFileSize', () => {
  it('바이트 단위별로 포맷한다', () => {
    expect(formatFileSize(512)).toBe('512B');
    expect(formatFileSize(1536)).toBe('1.5KB');
    expect(formatFileSize(2 * 1024 * 1024)).toBe('2.0MB');
  });
});
