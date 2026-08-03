// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LocationDrawingPhotosSectionData } from '../../types';
import { LocationDrawingPhotosForm } from './LocationDrawingPhotosForm';

vi.mock('../../utils/resizeImageToDataUrl', () => ({
  resizeImageToDataUrl: vi.fn((file: File) => Promise.resolve(`data:image/jpeg;base64,resized-${file.name}`)),
}));

afterEach(() => cleanup());

const EMPTY: LocationDrawingPhotosSectionData = { images: [] };

describe('LocationDrawingPhotosForm', () => {
  it('이미지가 없으면 빈 상태 문구를 보여준다', () => {
    render(<LocationDrawingPhotosForm data={EMPTY} onChange={vi.fn()} readOnly={false} />);
    expect(screen.getByText('추가된 이미지가 없습니다.')).toBeTruthy();
  });

  it('파일을 선택하면 리사이즈된 data URL을 새 이미지로 추가한다', async () => {
    const handleChange = vi.fn();
    render(<LocationDrawingPhotosForm data={EMPTY} onChange={handleChange} readOnly={false} />);

    const input = screen.getByLabelText('위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도 이미지 업로드') as HTMLInputElement;
    const file = new File(['bytes'], 'location.png', { type: 'image/png' });
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() =>
      expect(handleChange).toHaveBeenCalledWith({
        images: [{ dataUrl: 'data:image/jpeg;base64,resized-location.png', caption: '' }],
      }),
    );
  });

  it('캡션을 입력하면 해당 이미지의 caption만 갱신한다', () => {
    const handleChange = vi.fn();
    const data: LocationDrawingPhotosSectionData = {
      images: [
        { dataUrl: 'data:image/jpeg;base64,AAA', caption: '' },
        { dataUrl: 'data:image/jpeg;base64,BBB', caption: '기존 캡션' },
      ],
    };
    render(<LocationDrawingPhotosForm data={data} onChange={handleChange} readOnly={false} />);

    const captionInputs = screen.getAllByPlaceholderText('예: 한남대교 위치도');
    fireEvent.change(captionInputs[0], { target: { value: '한남대교 위치도' } });

    expect(handleChange).toHaveBeenCalledWith({
      images: [
        { dataUrl: 'data:image/jpeg;base64,AAA', caption: '한남대교 위치도' },
        { dataUrl: 'data:image/jpeg;base64,BBB', caption: '기존 캡션' },
      ],
    });
  });

  it('삭제 버튼을 누르면 해당 이미지만 제거한다', () => {
    const handleChange = vi.fn();
    const data: LocationDrawingPhotosSectionData = {
      images: [
        { dataUrl: 'data:image/jpeg;base64,AAA', caption: '첫 번째' },
        { dataUrl: 'data:image/jpeg;base64,BBB', caption: '두 번째' },
      ],
    };
    render(<LocationDrawingPhotosForm data={data} onChange={handleChange} readOnly={false} />);

    fireEvent.click(screen.getByRole('button', { name: '이미지 1번 삭제' }));

    expect(handleChange).toHaveBeenCalledWith({
      images: [{ dataUrl: 'data:image/jpeg;base64,BBB', caption: '두 번째' }],
    });
  });

  it('readOnly면 업로드 버튼과 삭제 버튼을 노출하지 않는다', () => {
    const data: LocationDrawingPhotosSectionData = {
      images: [{ dataUrl: 'data:image/jpeg;base64,AAA', caption: '전경' }],
    };
    render(<LocationDrawingPhotosForm data={data} onChange={vi.fn()} readOnly />);

    expect(screen.queryByRole('button', { name: '+ 이미지 추가' })).toBeNull();
    expect(screen.queryByRole('button', { name: '이미지 1번 삭제' })).toBeNull();
    expect((screen.getByPlaceholderText('예: 한남대교 위치도') as HTMLInputElement).readOnly).toBe(true);
  });
});
