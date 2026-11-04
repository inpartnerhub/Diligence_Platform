-- PRD Bab 10.1: optional per-project restriction of access by IP address / network location.
-- One CIDR range or single address per line; empty means no restriction. Applies to external users (client,
-- target, observer); the internal team is protected by SSO + MFA from any location.
ALTER TABLE prj.project ADD COLUMN allowed_ip_ranges TEXT NOT NULL DEFAULT '';
