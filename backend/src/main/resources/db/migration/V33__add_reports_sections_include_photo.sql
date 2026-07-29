ALTER TABLE reports ADD COLUMN IF NOT EXISTS sections jsonb;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS include_photo boolean;
