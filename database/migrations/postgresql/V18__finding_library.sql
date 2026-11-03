-- Sprint Phase 3, Reusable finding template library for cross-deal standard findings (PRD FND-06).
-- Firm-wide repository of recurring diligence risks, standard descriptions, and recommendations.

CREATE TABLE fnd.finding_library_template
(
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workstream_type        VARCHAR(32)  NOT NULL,
    category               VARCHAR(200) NOT NULL,
    title                  VARCHAR(300) NOT NULL,
    standard_description   TEXT         NOT NULL,
    default_recommendation TEXT,
    default_impact         INT          NOT NULL DEFAULT 3 CHECK (default_impact BETWEEN 1 AND 5),
    default_likelihood     INT          NOT NULL DEFAULT 3 CHECK (default_likelihood BETWEEN 1 AND 5),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX finding_library_template_workstream_idx ON fnd.finding_library_template (workstream_type);
CREATE INDEX finding_library_template_category_idx ON fnd.finding_library_template (category);

ALTER TABLE fnd.finding_library_template ENABLE ROW LEVEL SECURITY;

CREATE POLICY finding_library_template_read ON fnd.finding_library_template FOR SELECT USING (
    prj.is_system() OR prj.app_role() IN ('admin', 'partner', 'project_manager', 'analyst', 'observer'));

CREATE POLICY finding_library_template_write ON fnd.finding_library_template FOR ALL
    USING (prj.is_system() OR prj.app_role() IN ('admin', 'partner', 'project_manager'))
    WITH CHECK (prj.is_system() OR prj.app_role() IN ('admin', 'partner', 'project_manager'));

-- Seed recurring cross-deal diligence findings
INSERT INTO fnd.finding_library_template (workstream_type, category, title, standard_description, default_recommendation, default_impact, default_likelihood)
VALUES
('LEGAL', 'Corporate Governance', 'Missing or Incomplete Statutory Registers and Minute Books',
 'The company has not maintained contemporaneous statutory shareholder registers, minute books of general meetings, or formal board resolution records required by prevailing company regulations.',
 'Require the sellers to update, regularize, and ratify all statutory registers and shareholder resolutions as a condition precedent (CP) to completion.', 4, 3),

('LEGAL', 'Material Contracts', 'Change of Control Restrictions in Key Commercial Contracts',
 'Review of material customer and supplier agreements reveals change of control clauses requiring counterparty prior consent or granting termination rights upon acquisition.',
 'Seek formal waiver letters or written consents from counterparties prior to closing, or structure appropriate indemnity protections in the SPA.', 4, 4),

('LEGAL', 'Labor & Employment', 'Lack of Enforceable Non-Compete and Key Person Covenants',
 'Employment contracts for executive officers and key technical personnel lack enforceable non-competition, non-solicitation, and intellectual property assignment covenants.',
 'Execute comprehensive retention and non-compete agreements with key executives concurrently with signing of transaction documents.', 3, 3),

('TAX', 'Transfer Pricing', 'Deficiency in Contemporaneous Transfer Pricing Documentation',
 'The target company engages in cross-border related party transactions exceeding statutory thresholds but lacks formal, contemporaneous transfer pricing local files and master files.',
 'Request comprehensive transfer pricing benchmark study and evaluate tax exposure; secure specific tax indemnity in the SPA.', 4, 3),

('TAX', 'Withholding Tax', 'Potential Under-withholding on Cross-border Technical Services and Royalties',
 'Certain payments made to offshore affiliates or foreign service providers were not subjected to statutory withholding tax or lacked valid Certificate of Domicile (Form DGT).',
 'Quantify tax assessment risk plus penalties; escrow funds or include tax indemnity covering pre-closing withholding tax liabilities.', 3, 4),

('FINANCIAL', 'Revenue Recognition', 'Aggressive Cut-off and Revenue Recognition Prior to Customer Acceptance',
 'Analysis of year-end billing indicates recognition of software license and project revenues prior to obtaining formal written customer sign-off or milestone acceptance.',
 'Perform quality of earnings adjustment to normalize historical EBITDA and establish clear working capital peg reflecting deferred revenue.', 4, 3),

('FINANCIAL', 'Working Capital', 'Aged Trade Receivables Without Adequate Expected Credit Loss Provision',
 'Significant trade receivables aged over 90 days from private commercial clients have not been provided for in accordance with applicable financial reporting standards.',
 'Incorporate trade receivable bad debt provision as a debt-like item reduction against purchase price or adjust net working capital target.', 3, 4),

('HR', 'Labor Compliance', 'Under-accrual of Mandatory Statutory Severance and Pension Benefits',
 'Actuarial valuation for post-employment severance benefits under applicable labor statutes reveals unfunded defined benefit obligations not fully reflected on the balance sheet.',
 'Treat unfunded severance liabilities as debt-like deduction or require seller settlement prior to closing.', 3, 4),

('IT_CYBER', 'Information Security', 'Absence of Multi-Factor Authentication and Formal Disaster Recovery Testing',
 'Core enterprise systems and customer-facing infrastructure lack enforced multi-factor authentication (MFA) and audited business continuity / disaster recovery drills.',
 'Mandate immediate MFA rollout and include post-closing IT security roadmap commitments in the transitional service agreement.', 4, 3),

('ESG_COMPLIANCE', 'Environmental & Licensing', 'Expired or Pending Building Utilization and Environmental Permits',
 'Operational facilities and warehouses operate with expired Building Utilization Certificates (SLF) or uncompleted environmental management monitoring reports.',
 'Require seller to submit renewal filings and obtain proof of receipt from relevant licensing authorities as closing condition.', 3, 3);
