-- Sprint Phase 2, remediation tracking and SPA agreement clause links (PRD FND-05).
--   * buyer deal teams map findings to contractual protections in the SPA
--   * resolution categories: Price Chip, Special Indemnity, Pre-Closing Condition, Post-Closing Covenant, Warranty / Representation
--   * remediation progress tracking: OPEN, IN_PROGRESS, MITIGATED, WAIVED
--   * milestone deadlines, responsible owners, and audit timestamps

ALTER TABLE fnd.finding
    ADD COLUMN spa_protection_type    TEXT,
    ADD COLUMN spa_clause_reference   TEXT,
    ADD COLUMN remediation_status     TEXT        NOT NULL DEFAULT 'OPEN',
    ADD COLUMN remediation_action     TEXT,
    ADD COLUMN remediation_deadline   DATE,
    ADD COLUMN remediation_owner      TEXT,
    ADD COLUMN remediation_updated_at TIMESTAMPTZ,
    ADD COLUMN remediation_updated_by UUID;

ALTER TABLE fnd.finding ADD CONSTRAINT finding_spa_protection_type_check
    CHECK (spa_protection_type IS NULL OR spa_protection_type IN (
        'PRICE_CHIP',
        'SPECIAL_INDEMNITY',
        'PRE_CLOSING_CONDITION',
        'POST_CLOSING_COVENANT',
        'WARRANTY_REPRESENTATION'
    ));

ALTER TABLE fnd.finding ADD CONSTRAINT finding_remediation_status_check
    CHECK (remediation_status IN ('OPEN', 'IN_PROGRESS', 'MITIGATED', 'WAIVED'));

CREATE INDEX finding_remediation_status_idx ON fnd.finding (project_id, remediation_status);

