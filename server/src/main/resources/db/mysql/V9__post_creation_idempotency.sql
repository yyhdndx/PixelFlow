CREATE TABLE post_creation_idempotency (
 user_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 request_fingerprint CHAR(64) NOT NULL,
 post_id BIGINT NOT NULL,
 PRIMARY KEY (user_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
