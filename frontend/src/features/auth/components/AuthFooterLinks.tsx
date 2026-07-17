import { useNavigate } from 'react-router-dom';
import { FIND_ID_ROUTE, FIND_PASSWORD_ROUTE } from '../constants';

// 개인/기업회원 탭 공통 하단 링크·배너 — 두 탭에서 동일하게 노출(스펙: "하단 링크·배너 동일")
export function AuthFooterLinks() {
  const navigate = useNavigate();

  const handleComingSoon = () => {
    window.alert('준비 중인 기능입니다.');
  };

  return (
    <>
      <div className="flex items-center justify-between text-[13px] text-text-muted">
        <div className="flex items-center gap-3">
          <button
            type="button"
            className="cursor-pointer border-none bg-transparent p-0 text-text-muted"
            onClick={() => navigate(FIND_ID_ROUTE)}
          >
            아이디 찾기
          </button>
          <span aria-hidden="true">|</span>
          <button
            type="button"
            className="cursor-pointer border-none bg-transparent p-0 text-text-muted"
            onClick={() => navigate(FIND_PASSWORD_ROUTE)}
          >
            비밀번호 찾기
          </button>
        </div>
        <button
          type="button"
          className="cursor-pointer border-none bg-transparent p-0 text-text-muted underline"
          onClick={handleComingSoon}
        >
          협업자 로그인
        </button>
      </div>

      <div className="mt-6 flex flex-col gap-2 rounded-2xl bg-primary p-5">
        <p className="m-0 text-sm font-medium text-surface">결함 검수의 모든 과정을 한 곳에서</p>
        <button
          type="button"
          className="cursor-pointer self-start border-none bg-transparent p-0 text-[13px] font-bold text-surface/70"
          onClick={handleComingSoon}
        >
          HajaCheck 워크스페이스 둘러보기 →
        </button>
      </div>
    </>
  );
}
