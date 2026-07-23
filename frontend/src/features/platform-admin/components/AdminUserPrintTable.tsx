import { ROLE_LABEL, STATUS_LABEL } from '../constants';
import type { AdminUser } from '../types';
import { formatAbsoluteAccess, formatJoinedAt } from '../utils/formatUserDates';

interface AdminUserPrintTableProps {
  users: AdminUser[];
  generatedAt: string;
}

const CELL = 'border border-black px-2 py-1 text-left';

// 사용자 목록 PDF 내보내기 — 별도 라이브러리 없이 브라우저 인쇄 다이얼로그(대상: PDF로 저장)를
// 이용한다. jsPDF 등으로 직접 .pdf를 만들려면 한글(Pretendard) 폰트를 base64로 임베딩해야 하고
// 누락 시 한글이 깨지는 문제가 있어, 인쇄 경로가 더 안전하다(사용자 확인 완료).
// AdminUsersPage가 내보내기 시점에만 이 컴포넌트를 렌더하고 window.print()를 호출한다.
// id="admin-user-print-area"는 global.css의 인쇄 전용 규칙과 짝을 이룬다 — 인쇄 시 사이드바·헤더
// 등 나머지 화면 전체를 숨기고 이 영역만 보이게 한다(리스트만 출력되도록).
export function AdminUserPrintTable({ users, generatedAt }: AdminUserPrintTableProps) {
  return (
    <div id="admin-user-print-area">
      <h1 className="m-0 mb-1 text-lg font-bold text-black">사용자 목록</h1>
      <p className="m-0 mb-4 text-xs text-black">
        내보낸 시각: {generatedAt} · 총 {users.length}명
      </p>
      <table className="w-full border-collapse text-xs text-black">
        <thead>
          <tr>
            <th className={CELL}>이름</th>
            <th className={CELL}>이메일</th>
            <th className={CELL}>역할</th>
            <th className={CELL}>가입일</th>
            <th className={CELL}>최근 접속</th>
            <th className={CELL}>상태</th>
          </tr>
        </thead>
        <tbody>
          {users.map((user) => (
            <tr key={user.id}>
              <td className={CELL}>{user.name}</td>
              <td className={CELL}>{user.email}</td>
              <td className={CELL}>{ROLE_LABEL[user.role]}</td>
              <td className={CELL}>{formatJoinedAt(user.joinedAt)}</td>
              {/* 인쇄물은 정적 문서라 상대 시간("2시간 전")이 아니라 고정 시각으로 표시한다.
                  lastAccessAt은 Instant(UTC)라 formatJoinedAt이 아니라 UTC→로컬 변환을 실제로
                  수행하는 formatAbsoluteAccess를 써야 한다(그렇지 않으면 9시간 어긋난다). */}
              <td className={CELL}>{formatAbsoluteAccess(user.lastAccessAt)}</td>
              <td className={CELL}>{STATUS_LABEL[user.status]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
