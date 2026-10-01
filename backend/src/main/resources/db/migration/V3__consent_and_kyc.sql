ALTER TABLE loan_applications ADD COLUMN kyc_documents JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE loan_applications ADD COLUMN consent_at TIMESTAMPTZ;
