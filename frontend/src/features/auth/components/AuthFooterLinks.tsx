import { useNavigate } from 'react-router-dom';
import { FIND_ID_ROUTE, FIND_PASSWORD_ROUTE } from '../constants';

// 개인/기업회원 탭 공통 하단 링크 — 두 탭에서 동일하게 노출.
// (하단 워크스페이스 배너·"협업자 로그인"은 #421에서 제거)
export function AuthFooterLinks() {
  const navigate = useNavigate();

  return (
    <div className="flex items-center gap-3 text-[13px] text-text-muted">
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
  );
}
