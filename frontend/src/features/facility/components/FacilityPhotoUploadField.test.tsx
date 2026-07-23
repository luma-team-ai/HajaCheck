// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FacilityPhotoUploadField } from './FacilityPhotoUploadField';

// jsdom은 URL.createObjectURL/revokeObjectURL을 구현하지 않으므로 직접 스텁한다.
let createObjectURLMock: ReturnType<typeof vi.fn>;
let revokeObjectURLMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  let counter = 0;
  createObjectURLMock = vi.fn(() => `blob:mock-${counter++}`);
  revokeObjectURLMock = vi.fn();
  // jsdom은 createObjectURL/revokeObjectURL을 구현하지 않아 직접 대입해야 하는데, vi.fn()의
  // Mock 타입은 URL의 정적 메서드 시그니처와 구조적으로 완전히 일치하지 않는다(생성자 시그니처 포함)
  // — 테스트 전용 스텁이라 unknown 경유 캐스팅으로 좁힌다.
  URL.createObjectURL = createObjectURLMock as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = revokeObjectURLMock as unknown as typeof URL.revokeObjectURL;
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function makeImageFile(name: string): File {
  return new File(['fake-image-bytes'], name, { type: 'image/png' });
}

function makeNonImageFile(name: string): File {
  return new File(['fake-doc-bytes'], name, { type: 'application/pdf' });
}

describe('FacilityPhotoUploadField', () => {
  it('이미지를 선택하면 미리보기가 추가된다', () => {
    render(<FacilityPhotoUploadField />);

    const input = screen.getByLabelText('대표 사진 업로드');
    fireEvent.change(input, { target: { files: [makeImageFile('a.png')] } });

    expect(screen.getByAltText('a.png')).not.toBeNull();
    expect(createObjectURLMock).toHaveBeenCalledTimes(1);
  });

  // PR머신 react-reviewer P2 — 언마운트 cleanup의 useEffect가 dep []라 마운트 시점의(빈)
  // photos를 클로저에 캡처해, 사진 추가 후 모달이 닫혀 언마운트될 때 실제 추가된 blob URL이
  // revoke되지 않는 누수가 있었다. photosRef로 최신 값을 참조하도록 수정했는지 검증한다.
  it('사진을 추가한 뒤 언마운트하면 추가된 사진들의 objectURL을 revoke한다(P2 누수 수정)', () => {
    const { unmount } = render(<FacilityPhotoUploadField />);

    const input = screen.getByLabelText('대표 사진 업로드');
    fireEvent.change(input, {
      target: { files: [makeImageFile('a.png'), makeImageFile('b.png')] },
    });

    expect(createObjectURLMock).toHaveBeenCalledTimes(2);
    expect(revokeObjectURLMock).not.toHaveBeenCalled();

    unmount();

    expect(revokeObjectURLMock).toHaveBeenCalledTimes(2);
    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-0');
    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-1');
  });

  it('사진을 추가하지 않고 언마운트하면 revoke를 호출하지 않는다', () => {
    const { unmount } = render(<FacilityPhotoUploadField />);

    unmount();

    expect(revokeObjectURLMock).not.toHaveBeenCalled();
  });

  it('개별 제거 버튼을 클릭하면 해당 사진의 objectURL을 즉시 revoke한다', () => {
    render(<FacilityPhotoUploadField />);

    const input = screen.getByLabelText('대표 사진 업로드');
    fireEvent.change(input, { target: { files: [makeImageFile('a.png')] } });

    fireEvent.click(screen.getByRole('button', { name: 'a.png 제거' }));

    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-0');
    expect(screen.queryByAltText('a.png')).toBeNull();
  });

  it('최대 4장까지만 선택된다', () => {
    render(<FacilityPhotoUploadField />);

    const input = screen.getByLabelText('대표 사진 업로드');
    fireEvent.change(input, {
      target: {
        files: [
          makeImageFile('a.png'),
          makeImageFile('b.png'),
          makeImageFile('c.png'),
          makeImageFile('d.png'),
          makeImageFile('e.png'),
        ],
      },
    });

    expect(createObjectURLMock).toHaveBeenCalledTimes(4);
    expect(screen.getByText('최대 4장까지 선택했습니다.')).not.toBeNull();
  });

  // PR머신 P3 — accept="image/*"는 드래그앤드롭에는 적용되지 않아 비이미지 파일이 앞쪽에 섞여
  // 들어올 수 있다. slice를 filter보다 먼저 하면 앞쪽 비이미지가 슬롯을 차지해 뒤쪽 유효 이미지가
  // 조용히 잘려나간다 — filter를 slice보다 먼저 적용해 비이미지가 슬롯을 소모하지 않아야 한다.
  it('비이미지 파일이 슬롯 초과분과 섞여 들어와도 유효 이미지가 슬롯 한도까지 모두 추가된다(P3)', () => {
    render(<FacilityPhotoUploadField />);

    const input = screen.getByLabelText('대표 사진 업로드');
    fireEvent.change(input, {
      target: {
        files: [
          makeNonImageFile('doc1.pdf'),
          makeImageFile('a.png'),
          makeNonImageFile('doc2.pdf'),
          makeImageFile('b.png'),
          makeImageFile('c.png'),
          makeImageFile('d.png'),
          makeImageFile('e.png'),
        ],
      },
    });

    // 이미지 5장 중 슬롯(4)만큼 a~d가 전부 추가되어야 한다 — 비이미지가 앞에 있다고 잘려나가면 안 됨.
    expect(createObjectURLMock).toHaveBeenCalledTimes(4);
    expect(screen.getByAltText('a.png')).not.toBeNull();
    expect(screen.getByAltText('b.png')).not.toBeNull();
    expect(screen.getByAltText('c.png')).not.toBeNull();
    expect(screen.getByAltText('d.png')).not.toBeNull();
    expect(screen.queryByAltText('e.png')).toBeNull();
  });
});
