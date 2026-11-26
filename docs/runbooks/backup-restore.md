# Backup and restore

PRD Bab 7: RPO ≤ 1 h, RTO ≤ 4 h, encrypted daily backups and a **quarterly restore test**; Bab 14: disaster
recovery simulation proves the targets.

## Single-server (Docker Compose) deployment

The default production deployment (ADR-0012, `compose.prod.yaml`) runs PostgreSQL in a container, not RDS, so the
RDS / `kubectl` procedure below does **not** apply — use this section. The sections after it apply only to the
Kubernetes scale-up deployment.

### Backup

`infrastructure/docker/scripts/backup-postgres.sh` dumps the application DB, the Keycloak DB and the cluster roles
(`pg_dump` + `pg_dumpall --roles-only`, gzipped), keeps `BACKUP_RETENTION_DAYS` (14) locally, and copies each file to
`BACKUP_S3_URI` when set. Run it daily from cron:

```
0 2 * * * /opt/diligence/infrastructure/docker/scripts/backup-postgres.sh >> /var/log/diligence-backup.log 2>&1
```

Confirm off-host copies exist (`aws s3 ls "$BACKUP_S3_URI"`) — a backup only on the same VPS does not survive host loss.

> **RPO note.** Daily dumps give an RPO of up to 24 h, not the PRD's ≤ 1 h. For a tighter RPO on single-server, run
> the script more frequently or add WAL archiving; otherwise record the relaxed RPO as an accepted early-scale risk
> in the incident register.

### Restore (disaster recovery)

**Destructive** — recreates the databases from the latest `*.sql.gz` dumps. Substitute the user/db names from your
`.env.prod` (defaults: app `diligence`/`diligence`, Keycloak `keycloak`/`keycloak`).

1. Declare an incident; stop writes and release DB connections:
   ```
   docker compose -f compose.prod.yaml --env-file .env.prod stop backend frontend keycloak
   ```
2. Recreate the cluster roles first (skip if restoring onto the same cluster, where they already exist):
   ```
   gunzip -c /var/backups/diligence/globals-<stamp>.sql.gz \
     | docker compose -f compose.prod.yaml --env-file .env.prod exec -T postgres psql -U diligence -d postgres
   ```
3. Recreate and reload the application database:
   ```
   docker compose -f compose.prod.yaml --env-file .env.prod exec -T postgres \
     psql -U diligence -d postgres -c "DROP DATABASE IF EXISTS diligence WITH (FORCE); CREATE DATABASE diligence OWNER diligence;"
   gunzip -c /var/backups/diligence/diligence-<stamp>.sql.gz \
     | docker compose -f compose.prod.yaml --env-file .env.prod exec -T postgres psql -U diligence -d diligence
   ```
4. Repeat step 3 for the Keycloak database (`DROP DATABASE … keycloak … CREATE DATABASE keycloak OWNER keycloak`, then
   restore `keycloak-<stamp>.sql.gz` into `-d keycloak`).
5. Start the stack; Flyway validates the restored schema:
   ```
   docker compose -f compose.prod.yaml --env-file .env.prod up -d
   ```
6. Verify: row counts of `prj.project`, `dr.document`, and the latest `audit.audit_log.created_at` are consistent with
   the backup time, and sign in to confirm Keycloak works. If base tables and the search index drifted, rebuild it
   (**Administration → Search index → Rebuild index**, [search-reindex.md](search-reindex.md)).
7. Documents uploaded after the backup point exist in object storage without database metadata: re-attach from the
   audit log or ask uploaders to re-upload.

## What is backed up

| Data | Mechanism (Terraform) | Recovery point |
|------|------------------------|----------------|
| PostgreSQL (all module schemas, audit log) | RDS automated backups + point-in-time recovery, KMS-encrypted (`modules/postgresql`, `backup_retention_days`) | ≈ 5 min (PITR) |
| Documents and renditions (S3) | Bucket versioning + KMS (`modules/object-storage`) | Every object version |
| Search index (PostgreSQL FTS `dr.document_search_index`) | Part of the PostgreSQL database, so RDS PITR restores it; also rebuildable from PostgreSQL + S3 ([search-reindex.md](search-reindex.md)) | follows PostgreSQL |
| Keycloak realm | Realm in its own database (backed up like PostgreSQL); realm JSON in Git for configuration | — |

## Restore PostgreSQL (point in time)

1. Declare an incident; stop writes: scale the backend to 0 (`kubectl -n diligence scale deploy/diligence-backend --replicas=0`).
2. RDS → *Restore to point in time* → new instance `<name>-restore-<date>` with the same subnet group, parameter
   group, KMS key and security group.
3. Verify: row counts of `prj.project`, `dr.document`, latest `audit.audit_log.created_at` ≤ the chosen time.
4. Point `DB_URL` (AWS Secrets Manager secret) at the restored instance, scale the backend back up; Flyway validates the schema.
5. The search index (`dr.document_search_index`) lives in the same database, so a point-in-time restore brings it back
   consistently — no rebuild is normally needed. Only if the index and the base tables have drifted (e.g. a partial or
   object-level restore) rebuild it: **Administration → Search index → Rebuild index** ([search-reindex.md](search-reindex.md)).
6. Documents uploaded after the restore point exist in S3 without metadata: list them from the S3 inventory and ask
   uploaders to re-upload, or re-attach them from the audit log.

## Restore S3 objects

Deleted or overwritten documents: restore the previous object version (`aws s3api list-object-versions`, then copy
the version back). Project purges (ADM-06) delete all versions and delete markers under `projects/<id>/` on purpose
and are not recoverable (#124); this needs `s3:ListBucketVersions` and `s3:DeleteObjectVersion` for the backend. A
purge that fails (S3 unavailable, a database error, missing permission) is retried by the failed-event job, see
[failed-events.md](failed-events.md).

## Quarterly restore test

Restore the latest production snapshot into staging, run the e2e smoke suite (`tests/e2e`), and record in the
incident register: snapshot time, start/end time (**RTO**, including the search re-index), data loss window (**RPO**),
issues found. Failure to meet
RPO ≤ 1 h / RTO ≤ 4 h is a SEV-2 action item.
