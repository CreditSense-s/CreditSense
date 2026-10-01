ALTER TABLE loan_applications ADD COLUMN feature_vector JSONB NOT NULL DEFAULT '{}'::jsonb;
