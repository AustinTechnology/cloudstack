-- Isolated, NOT wired into a CloudStack upgrade; not a deployment migration.
-- Times are UTC DATETIME(6); identity columns are binary/case-sensitive.
CREATE TABLE external_adoption_claims (
 id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 proxmox_cluster VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 proxmox_node VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 proxmox_vmid INTEGER NOT NULL,
 manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 manifest_json MEDIUMTEXT NOT NULL, generation INTEGER NOT NULL,
 state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_lease_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 cloudstack_vm_ref VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 cloudstack_instance_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 migration_fenced BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NULL, updated_at DATETIME(6) NULL,
 UNIQUE KEY uq_external_adoption_cluster_vmid (proxmox_cluster, proxmox_vmid),
 UNIQUE KEY uq_external_adoption_vm_ref (cloudstack_vm_ref)
) ENGINE=InnoDB;
CREATE TABLE external_adoption_operation_leases (
 id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 claim_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation INTEGER NOT NULL, action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NULL, legacy_json LONGTEXT NULL,
 UNIQUE KEY uq_external_adoption_lease_claim (claim_id)
) ENGINE=InnoDB;
CREATE TABLE external_adoption_executions (
 id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 claim_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation INTEGER NOT NULL, plan_sha256 CHAR(64) NOT NULL, plan_json LONGTEXT NOT NULL,
 state VARCHAR(32) NOT NULL, deploy_job_id VARCHAR(255), start_job_id VARCHAR(255), cleanup_job_id VARCHAR(255),
 cloudstack_vm_ref VARCHAR(255), cloudstack_instance_name VARCHAR(255), error_code VARCHAR(64),
 worker_lease_id VARCHAR(36), worker_lease_expires_at DATETIME(6), attempt_count INTEGER NOT NULL,
 created_at DATETIME(6), updated_at DATETIME(6), legacy_json LONGTEXT NOT NULL,
 UNIQUE KEY uq_external_adoption_execution_claim_generation (claim_id,generation)
) ENGINE=InnoDB;
CREATE TABLE external_adoption_write_authority (
 id INTEGER PRIMARY KEY, mode VARCHAR(16), owner_id VARCHAR(36), target VARCHAR(255),
 expires_at DATETIME(6), updated_at DATETIME(6), legacy_json LONGTEXT NOT NULL
) ENGINE=InnoDB;
-- Exact source export remains immutable. Unknown states, job IDs, plan bytes,
-- orphan leases and live authority are retained even if they cannot be resumed.
-- This singleton is also a GLOBAL admission fence: importing any snapshot blocks
-- every operation until guarded, verified handover of a quiescent snapshot.
CREATE TABLE external_adoption_control (
 id INTEGER PRIMARY KEY, legacy_fenced BOOLEAN NOT NULL
) ENGINE=InnoDB;
INSERT INTO external_adoption_control (id, legacy_fenced) VALUES (1, FALSE);
CREATE TABLE external_adoption_legacy_export (
 id INTEGER PRIMARY KEY, export_json LONGTEXT NOT NULL,
 imported_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
