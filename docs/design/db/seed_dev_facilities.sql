-- 팀 공용 개발 DB(hajacheck_dev) 시설물 더미데이터 시드 (2026-07-23, 회사 소유 전환)
-- ⚠️ 대상: 호스트 네이티브 PG 5432의 hajacheck_dev 전용. prod(컨테이너 5433 hajacheck)에 절대 실행 금지.
-- 적용: ssh oci-arm1 'sudo -u postgres psql -p 5432 -d hajacheck_dev -f -' < docs/design/db/seed_dev_facilities.sql
-- 멱등: (company_id, name) 기준 NOT EXISTS 가드 — 재실행해도 중복 삽입 없음.
-- type은 프론트 FACILITY_TYPE_OPTIONS(건물/교량/터널/도로/기타)와 일치.
-- owner_user_id는 기존 팀원 계정을 가리키며, 실제 시설 소유 회사는 users.company_id로 결정한다.
-- 회사가 없는 사용자의 행은 임의 회사에 배정하지 않고 시드 대상에서 제외한다.
-- next_inspection_due_at은 CURRENT_DATE 기준 상대값 — 대시보드 '다가오는 점검'(D-표시) 테스트용.

WITH seed(owner_user_id, name, type, address, latitude, longitude, built_year, scale,
          inspection_cycle_months, next_inspection_due_at) AS (
  VALUES
    -- 정재봉(1): 건물 2 + 교량 1
    (1::bigint, '판교 테크밸리 오피스 B동', '건물', '경기도 성남시 분당구 판교역로 235', 37.402056::numeric(9,6), 127.108212::numeric(9,6), 2015, '지상 10층/지하 3층', 12, CURRENT_DATE + 7),
    (1::bigint, '야탑 물류센터',           '건물', '경기도 성남시 분당구 야탑로 205',   37.411226::numeric(9,6), 127.128159::numeric(9,6), 1998, '지상 4층 창고동',   6,  CURRENT_DATE + 21),
    (1::bigint, '탄천 보행교',             '교량', '경기도 성남시 분당구 탄천로',       37.394776::numeric(9,6), 127.111633::numeric(9,6), 2005, '연장 85m 보도교',   24, CURRENT_DATE + 40),
    -- 대표(3): 건물 2
    (3::bigint, '역삼 스퀘어 타워',        '건물', '서울시 강남구 테헤란로 152',        37.500675::numeric(9,6), 127.036420::numeric(9,6), 2008, '지상 20층/지하 5층', 12, CURRENT_DATE + 3),
    (3::bigint, '성수 리모델링 현장동',    '건물', '서울시 성동구 아차산로 68',         37.544579::numeric(9,6), 127.055961::numeric(9,6), 1994, '지상 6층',          NULL, NULL),
    -- Ketose(9): 터널 1 + 도로 1
    (9::bigint, '남산 1호 터널 접속부',    '터널', '서울시 중구 퇴계로',                37.552217::numeric(9,6), 126.985703::numeric(9,6), 1970, '연장 1,530m',       36, CURRENT_DATE + 14),
    (9::bigint, '올림픽대로 잠실 구간',    '도로', '서울시 송파구 올림픽대로',          37.518305::numeric(9,6), 127.087709::numeric(9,6), 1986, '왕복 8차로 2.4km',  12, CURRENT_DATE + 30),
    -- 이은석(10): 교량 1 + 기타 1
    (10::bigint, '한강 성수대교 남단',     '교량', '서울시 강남구 압구정로',            37.529946::numeric(9,6), 127.032055::numeric(9,6), 1997, '연장 1,161m',       24, CURRENT_DATE + 10),
    (10::bigint, '서울숲 옹벽 구간',       '기타', '서울시 성동구 뚝섬로 273',          37.543072::numeric(9,6), 127.041808::numeric(9,6), 2003, '옹벽 높이 6m/연장 120m', 12, CURRENT_DATE + 55),
    -- 허남(11): 기타 1
    (11::bigint, '강남역 지하상가 통로',   '기타', '서울시 강남구 강남대로 지하 396',   37.497942::numeric(9,6), 127.027621::numeric(9,6), 1983, '지하 1층 연장 300m', 6,  CURRENT_DATE + 5),
    -- 황승현(18): 건물 1 + 도로 1
    (18::bigint, '마포 상암 DMC 별관',     '건물', '서울시 마포구 성암로 267',          37.579617::numeric(9,6), 126.889844::numeric(9,6), 2012, '지상 8층/지하 2층',  12, CURRENT_DATE + 90),
    (18::bigint, '상암 월드컵로 고가',     '도로', '서울시 마포구 월드컵로',            37.568326::numeric(9,6), 126.897608::numeric(9,6), 2001, '고가 연장 640m',    24, NULL)
)
INSERT INTO facilities (
    company_id, name, type, address, latitude, longitude, built_year, scale,
    inspection_cycle_months, next_inspection_due_at
)
SELECT
    u.company_id, s.name, s.type, s.address, s.latitude, s.longitude, s.built_year, s.scale,
    s.inspection_cycle_months, s.next_inspection_due_at
FROM seed s
JOIN users u ON u.id = s.owner_user_id
WHERE u.company_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM facilities f
      WHERE f.company_id = u.company_id
        AND f.name = s.name
  );
