-- Flyway V14 — 상담 티켓 스냅샷 필드 + 채팅 이미지 첨부 컬럼(FR-7, #20/HAJA-33).
--
-- (1) counsel_tickets: 시나리오에서 진입한 카테고리/제목을 스냅샷으로 저장(시나리오 트리가 나중에 바뀌어도
--     과거 티켓의 이력 표시가 안 바뀌도록 — Media.storageKey 스냅샷과 동일 원칙) + 사람이 읽는 티켓번호.
--     ticket_number 포맷 CS-{yyyyMMdd}-{id zero-pad}은 생성 시점 날짜 + PK 기반이라 별도 시퀀스 불필요.
-- (2) chat_messages: 이미지 첨부(FileStorageService storageKey + MIME). Media 테이블에 행을 만들지 않고
--     chat_messages에 직접 컬럼을 둔다(Media는 inspectionId NOT NULL FK로 강결합돼 재사용 불가).
--
-- 캐노니컬 DDL(HajaCheck_script.sql)에 이미 존재할 수 있어(baseline-on-existing 경로) IF NOT EXISTS로
-- 멱등 처리한다(V12 패턴). counsel_tickets는 이 기능의 첫 구현이라 어느 환경에서도 비어 있어 NOT NULL
-- 컬럼을 기본값 없이 추가해도 안전하다.

alter table counsel_tickets
    add column if not exists ticket_number varchar(20)  not null,
    add column if not exists category      varchar(100) not null,
    add column if not exists title         varchar(200) not null;

create unique index if not exists uq_counsel_tickets_ticket_number
    on counsel_tickets (ticket_number);

comment on column counsel_tickets.ticket_number is '사람이 읽는 상담 티켓 번호(CS-yyyyMMdd-{id}), 생성 시 PK 기반 스냅샷';
comment on column counsel_tickets.category is '진입 시나리오 최상위 카테고리 스냅샷(예: INSPECTION_REPORT)';
comment on column counsel_tickets.title is '진입 시나리오 바로 위 부모 노드 라벨 스냅샷';

alter table chat_messages
    add column if not exists attachment_key       varchar(500),
    add column if not exists attachment_mime_type varchar(100);

comment on column chat_messages.attachment_key is '이미지 첨부 저장키(FileStorageService storageKey, 실제 URL 아님), nullable';
comment on column chat_messages.attachment_mime_type is '이미지 첨부 MIME(image/jpeg·image/png), nullable';
