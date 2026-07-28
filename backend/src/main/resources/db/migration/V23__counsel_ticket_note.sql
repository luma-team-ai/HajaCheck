-- Flyway V23 — 상담원 전용 비공개 메모(고객 비노출, 티켓당 1개, #1021/HAJA-503).
-- (V19~V22는 다른 브랜치 작업이 dev에 먼저 병합돼 선점 — 번호 충돌 방지를 위해 V23으로 이어 붙인다.)
--
-- counsel_ticket_notes: counsel_tickets 1건당 최대 1개(ticket_id unique) — 담당 상담원이 조회·작성한다.
-- 인가(담당 상담원 본인 확인)는 애플리케이션 레이어(CounselTicketNoteService)에서 처리하므로 여기서는
-- 데이터 정합성(unique, FK)만 보장한다.
--
-- 캐노니컬 DDL(HajaCheck_script.sql)에 이미 이 테이블이 반영돼 있어, 혹시 모를 재실행/기존 환경 대비
-- IF NOT EXISTS로 멱등 처리한다(V12/V18과 동일 컨벤션). id/FK 스타일은 캐노니컬·V20(payments)과
-- 동일한 컨벤션(identity 컬럼 + 이름 없는 references, Postgres 기본 제약 이름)을 따른다 —
-- Ha25IncrementalMigrationTest가 두 경로(캐노니컬 DDL vs Flyway 전체 적용)의 카탈로그를 대조한다.
create table if not exists counsel_ticket_notes (
    id            bigint generated always as identity primary key,
    ticket_id     bigint not null references counsel_tickets,
    counselor_id  bigint not null,
    content       text,
    updated_at    timestamp with time zone not null default now()
);

create unique index if not exists uq_counsel_ticket_notes_ticket_id
    on counsel_ticket_notes (ticket_id);

comment on table counsel_ticket_notes is '상담원 전용 비공개 메모(고객 비노출, 티켓당 1개, #1021/HAJA-503)';
comment on column counsel_ticket_notes.ticket_id is 'counsel_tickets FK, 티켓당 1개(unique)';
comment on column counsel_ticket_notes.counselor_id is '최근 메모 작성/갱신 상담원 ID';
comment on column counsel_ticket_notes.content is '메모 본문(nullable — 빈 메모 허용)';
