-- Flyway V22 — 상담원 전용 비공개 메모(고객 비노출, 티켓당 1개, #1021/HAJA-503).
-- (V19~V21은 다른 브랜치 작업이 dev 병합 대기 중이라 선점 — 번호 충돌 방지를 위해 V22로 이어 붙인다.)
--
-- counsel_ticket_notes: counsel_tickets 1건당 최대 1개(ticket_id unique) — 담당 상담원이 조회·작성한다.
-- 인가(담당 상담원 본인 확인)는 애플리케이션 레이어(CounselTicketNoteService)에서 처리하므로 여기서는
-- 데이터 정합성(unique, FK)만 보장한다.
--
-- 캐노니컬 DDL(HajaCheck_script.sql)에는 아직 이 테이블이 없어 신규 생성이지만, 혹시 모를 재실행/기존 환경
-- 대비 IF NOT EXISTS로 멱등 처리한다(V12/V18과 동일 컨벤션).
create table if not exists counsel_ticket_notes (
    id            bigserial primary key,
    ticket_id     bigint not null,
    counselor_id  bigint not null,
    content       text,
    updated_at    timestamp with time zone not null default now(),
    constraint fk_counsel_ticket_notes_ticket
        foreign key (ticket_id) references counsel_tickets (id)
);

create unique index if not exists uq_counsel_ticket_notes_ticket_id
    on counsel_ticket_notes (ticket_id);

comment on table counsel_ticket_notes is '상담원 전용 비공개 메모(고객 비노출, 티켓당 1개, #1021/HAJA-503)';
comment on column counsel_ticket_notes.ticket_id is 'counsel_tickets FK, 티켓당 1개(unique)';
comment on column counsel_ticket_notes.counselor_id is '최근 메모 작성/갱신 상담원 ID';
comment on column counsel_ticket_notes.content is '메모 본문(nullable — 빈 메모 허용)';
