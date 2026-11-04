# Backup and restore

PRD Bab 7: RPO ≤ 1 h, RTO ≤ 4 h, encrypted daily backups and a **quarterly restore test**; Bab 14: disaster
recovery simulation proves the targets.

## What is backed up

| Data | Mechanism (Terraform) | Recovery point |
|------|------------------------|----------------|
| PostgreSQL (all module schemas, audit log) | RDS automated backups + point-in-time recovery, KMS-encrypted (`modules/postgresql`, `backup_retention_days`) | ≈ 5 min (PITR) |
| Documents and renditions (S3) | Bucket versioning + KMS (`modules/object-storage`) | Every object version |
| Search index (OpenSearch) | Rebuildable from PostgreSQL + S3; managed automated snapshots | — |
| Keycloak realm | Realm in its own database (backed up like PostgreSQL); realm JSON in Git for configuration | — |

## Restore PostgreSQL (point in time)

1. Declare an incident; stop writes: scale the backend to 0 (`kubectl -n diligence scale deploy/diligence-backend --replicas=0`).
2. RDS → *Restore to point in time* → new instance `<name>-restore-<date>` with the same subnet group, parameter
   group, KMS key and security group.
3. Verify: row counts of `prj.project`, `dr.document`, latest `audit.audit_log.created_at` ≤ the chosen time.
4. Point `DB_URL` (Vault secret) at the restored instance, scale the backend back up; Flyway validates the schema.
5. Documents uploaded after the restore point exist in S3 without metadata: list them from the S3 inventory and ask
   uploaders to re-upload, or re-attach them from the audit log.

## Restore S3 objects

Deleted or overwritten documents: restore the previous object version (`aws s3api list-object-versions`, then copy
the version back). Project purges (ADM-06) delete all versions on purpose and are not recoverable.

## Quarterly restore test

Restore the latest production snapshot into staging, run the e2e smoke suite (`tests/e2e`), and record in the
incident register: snapshot time, start/end time (**RTO**), data loss window (**RPO**), issues found. Failure to meet
RPO ≤ 1 h / RTO ≤ 4 h is a SEV-2 action item.
