CREATE DATABASE IF NOT EXISTS nexus_netguard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE nexus_netguard;

CREATE TABLE IF NOT EXISTS ping_nodes (
  id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  host VARCHAR(255) NOT NULL,
  port SMALLINT UNSIGNED NOT NULL DEFAULT 443,
  region VARCHAR(32) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  active TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS dns_servers (
  id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  ip VARCHAR(45) NOT NULL,
  private_dns VARCHAR(255) NOT NULL DEFAULT '',
  sort_order INT NOT NULL DEFAULT 0,
  active TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS hog_packages (
  package_name VARCHAR(255) PRIMARY KEY
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS telemetry_samples (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  node VARCHAR(64) NOT NULL,
  ping_ms INT UNSIGNED NOT NULL,
  jitter_ms INT UNSIGNED NOT NULL,
  loss_pct TINYINT UNSIGNED NOT NULL,
  app_version VARCHAR(24) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_node_time (node, created_at)
) ENGINE=InnoDB;

INSERT INTO ping_nodes (name, host, port, sort_order) VALUES
  ('Cloudflare', '1.1.1.1', 443, 1), ('Google', '8.8.8.8', 443, 2), ('Quad9', '9.9.9.9', 443, 3);
INSERT INTO dns_servers (name, ip, private_dns, sort_order) VALUES
  ('Cloudflare', '1.1.1.1', 'one.one.one.one', 1), ('Google', '8.8.8.8', 'dns.google', 2), ('Quad9', '9.9.9.9', 'dns.quad9.net', 3);
INSERT IGNORE INTO hog_packages (package_name) VALUES
  ('com.facebook.katana'), ('com.facebook.orca'), ('com.instagram.android'), ('com.zhiliaoapp.musically'),
  ('com.ss.android.ugc.trill'), ('com.snapchat.android'), ('com.twitter.android'), ('com.google.android.youtube'),
  ('com.netflix.mediaclient'), ('com.spotify.music'), ('com.android.vending'), ('com.google.android.apps.photos');
