-- When the applicant ticked the consent box (research-demo notice and storage of what they submitted).
ALTER TABLE loan_applications ADD COLUMN consent_at TIMESTAMPTZ;
