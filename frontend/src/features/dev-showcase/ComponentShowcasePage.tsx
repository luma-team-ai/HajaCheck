import { useState } from 'react';
import { AIErrorFallback } from '../../shared/components/AIErrorFallback/AIErrorFallback';
import { AILoadingIndicator } from '../../shared/components/AILoadingIndicator/AILoadingIndicator';
import { BottomNavBarFab } from '../../shared/components/BottomNavBarFab/BottomNavBarFab';
import { Button } from '../../shared/components/Button/Button';
import { ErrorFallback } from '../../shared/components/ErrorFallback/ErrorFallback';
import { FloatingPopup } from '../../shared/components/FloatingPopup/FloatingPopup';
import { Footer } from '../../shared/components/Footer/Footer';
import { Modal } from '../../shared/components/Modal/Modal';
import {
  NotificationDropdown,
  type NotificationItem,
} from '../../shared/components/NotificationDropdown/NotificationDropdown';
import { Pagination } from '../../shared/components/Pagination/Pagination';
import { SideNavBar } from '../../shared/components/SideNavBar/SideNavBar';
import { Table, type TableColumn } from '../../shared/components/Table/Table';
import { TableFooterPagination } from '../../shared/components/TableFooterPagination/TableFooterPagination';
import { TopNavigation } from '../../shared/components/TopNavigation/TopNavigation';
import './ComponentShowcasePage.css';

interface DefectRow {
  id: number;
  type: string;
  grade: string;
}

const columns: TableColumn<DefectRow>[] = [
  { key: 'type', header: '유형' },
  { key: 'grade', header: '등급', render: (row) => `${row.grade}등급` },
];

const sampleData: DefectRow[] = [
  { id: 1, type: '균열', grade: 'A' },
  { id: 2, type: '누수', grade: 'B' },
  { id: 3, type: '박락', grade: 'C' },
];

const sampleNotifications: NotificationItem[] = [
  {
    id: 1,
    category: 'analysis',
    title: 'AI 분석 완료',
    description: '강남 오피스타워 8회차 · 하자 87건 탐지',
    timestamp: '방금 전',
    read: false,
    actionLabel: '결과 보기',
  },
  {
    id: 2,
    category: 'review',
    title: '검수 대기 37건이 쌓였어요',
    timestamp: '12분 전',
    read: false,
    actionLabel: '검수하기',
  },
  {
    id: 3,
    category: 'inspection',
    title: '점검일 도래',
    description: '한강대교 북단 D-3',
    timestamp: '3시간 전',
    read: true,
    actionLabel: '점검 시작',
  },
];

// 개발 확인용 임시 데모 페이지 — 이슈 #116 공통 컴포넌트 시각 검증 목적.
// 실제 기능 화면이 아니므로 머지 전 라우트/파일 유지 여부를 팀과 협의할 것.
export default function ComponentShowcasePage() {
  const [modalOpen, setModalOpen] = useState(false);
  const [currentPage, setCurrentPage] = useState(3);
  const [tablePage, setTablePage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [popupOpen, setPopupOpen] = useState(false);

  return (
    <div className="showcase">
      <h1>공통 컴포넌트 데모 (임시 — #116)</h1>

      <section className="showcase-section">
        <h2>TopNavigation</h2>
        <TopNavigation />
      </section>

      <section className="showcase-section">
        <h2>SideNavBar</h2>
        <p className="showcase-note">
          Figma 노드 링크 미제공 — 사용자 제공 텍스트 기준 추정 구현(실제 시안 확인 필요)
        </p>
        <div className="showcase-sidenav-frame">
          <SideNavBar activeHref="/dashboard" user={{ name: '김관리', plan: 'Standard' }} />
        </div>
      </section>

      <section className="showcase-section">
        <h2>NotificationDropdown</h2>
        <NotificationDropdown
          notifications={sampleNotifications}
          unreadCount={2}
          filters={[
            { key: 'all', label: '전체' },
            { key: 'analysis', label: '분석' },
            { key: 'review', label: '검수' },
            { key: 'inspection', label: '점검일' },
          ]}
        />
      </section>

      <section className="showcase-section">
        <h2>BottomNavBarFab / FloatingPopup</h2>
        <Button onClick={() => setPopupOpen((prev) => !prev)}>
          {popupOpen ? '팝업 닫기' : '팝업 열기'}
        </Button>
        {popupOpen && (
          <FloatingPopup
            onClose={() => setPopupOpen(false)}
            links={[
              { label: '서비스 이용 방법', onClick: () => {} },
              { label: '분석 결과 문의', onClick: () => {} },
              { label: '요금·기타', onClick: () => {} },
            ]}
            waitingLabel="현재 대기 2팀"
            onConnectAgent={() => {}}
          />
        )}
        <BottomNavBarFab onClick={() => setPopupOpen((prev) => !prev)} />
      </section>

      <section className="showcase-section">
        <h2>Button</h2>
        <div className="showcase-row">
          <Button variant="primary">Primary</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="danger">Danger</Button>
          <Button variant="primary" disabled>
            Disabled
          </Button>
        </div>
        <div className="showcase-row">
          <Button size="sm">Small</Button>
          <Button size="md">Medium</Button>
          <Button size="lg">Large</Button>
        </div>
      </section>

      <section className="showcase-section">
        <h2>Modal</h2>
        <Button onClick={() => setModalOpen(true)}>모달 열기</Button>
        <Modal open={modalOpen} onClose={() => setModalOpen(false)} title="샘플 모달">
          <p>배경 클릭 또는 ESC로 닫힙니다. Tab으로 포커스가 내부에서만 순환합니다.</p>
          <Button variant="secondary" onClick={() => setModalOpen(false)}>
            닫기
          </Button>
        </Modal>
      </section>

      <section className="showcase-section">
        <h2>Table</h2>
        <Table columns={columns} data={sampleData} />
        <h3>빈 데이터 상태</h3>
        <Table columns={columns} data={[]} />
      </section>

      <section className="showcase-section">
        <h2>Pagination</h2>
        <Pagination currentPage={currentPage} totalPages={5} onPageChange={setCurrentPage} />
        <p>현재 페이지: {currentPage}</p>
      </section>

      <section className="showcase-section">
        <h2>TableFooterPagination</h2>
        <TableFooterPagination
          pageSize={pageSize}
          onPageSizeChange={(size) => {
            setPageSize(size);
            setTablePage(1);
          }}
          currentPage={tablePage}
          totalPages={Math.ceil(47 / pageSize)}
          totalItems={47}
          onPageChange={setTablePage}
        />
      </section>

      <section className="showcase-section">
        <h2>ErrorFallback</h2>
        <ErrorFallback onRetry={() => window.alert('재시도 클릭됨')} />
      </section>

      <section className="showcase-section">
        <h2>AILoadingIndicator</h2>
        <AILoadingIndicator />
      </section>

      <section className="showcase-section">
        <h2>AIErrorFallback</h2>
        <AIErrorFallback onRetry={() => window.alert('AI 재시도 클릭됨')} />
      </section>

      <section className="showcase-section">
        <h2>Footer</h2>
        <Footer />
      </section>
    </div>
  );
}
