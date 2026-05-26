ALTER TABLE investors ADD COLUMN IF NOT EXISTS relationship_type VARCHAR(20) NOT NULL DEFAULT 'SELF';
ALTER TABLE investors ADD COLUMN IF NOT EXISTS household_id UUID;
ALTER TABLE investors ADD COLUMN IF NOT EXISTS household_name VARCHAR(255);
ALTER TABLE investors ADD COLUMN IF NOT EXISTS guardian_investor_id UUID;
ALTER TABLE investors ADD COLUMN IF NOT EXISTS guardian_pan VARCHAR(10);

UPDATE investors
SET household_id = id
WHERE household_id IS NULL;

ALTER TABLE investors ALTER COLUMN household_id SET NOT NULL;

ALTER TABLE investors
    ADD CONSTRAINT chk_investors_relationship_type
    CHECK (relationship_type IN ('SELF', 'SPOUSE', 'MINOR', 'HUF'));

ALTER TABLE investors
    ADD CONSTRAINT fk_investors_guardian_investor
    FOREIGN KEY (guardian_investor_id) REFERENCES investors(id);

ALTER TABLE investors
    ADD CONSTRAINT chk_investors_minor_guardian
    CHECK (relationship_type <> 'MINOR' OR guardian_pan IS NOT NULL OR guardian_investor_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_investors_household_id
    ON investors (household_id);

CREATE INDEX IF NOT EXISTS idx_investors_guardian_investor_id
    ON investors (guardian_investor_id);

CREATE INDEX IF NOT EXISTS idx_investors_distributor_household
    ON investors (distributor_id, household_id);
