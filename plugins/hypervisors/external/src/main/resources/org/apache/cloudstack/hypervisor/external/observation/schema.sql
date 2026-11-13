-- Isolated proposed schema. Integrate into the CloudStack upgrade framework before use.
-- sender mappings are provisioned by an administrator, not by the collector API.
CREATE TABLE external_observation_sender (
  user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  host_id BIGINT UNSIGNED NOT NULL,
  cluster_id BIGINT UNSIGNED NOT NULL,
  UNIQUE KEY sender_host (host_id)
) ENGINE=InnoDB;
CREATE TABLE external_observation_capture (
  host_id BIGINT UNSIGNED NOT NULL,
  capture_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  received_at DATETIME(6) NOT NULL,
  PRIMARY KEY (host_id, capture_id)
) ENGINE=InnoDB;
CREATE TABLE external_ip_observation (
  cluster_id BIGINT UNSIGNED NOT NULL,
  vmid INT UNSIGNED NOT NULL,
  mac CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  host_id BIGINT UNSIGNED NOT NULL,
  ip VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  sightings TINYINT UNSIGNED NOT NULL,
  seen_at DATETIME(6) NOT NULL,
  previous_seen_at DATETIME(6) DEFAULT NULL,
  binding_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (cluster_id, vmid, mac)
) ENGINE=InnoDB;
