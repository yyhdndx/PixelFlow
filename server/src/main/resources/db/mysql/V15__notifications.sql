ALTER TABLE comments ADD COLUMN parent_comment_id BIGINT NULL;
ALTER TABLE users ADD COLUMN muted_notify_types VARCHAR(32) NOT NULL DEFAULT '';

CREATE TABLE notifications (
  notification_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient_id VARCHAR(36) NOT NULL,
  type VARCHAR(16) NOT NULL CHECK (type IN ('MENTION','REPLY','LIKE')),
  actor_id VARCHAR(36) NOT NULL,
  post_id BIGINT NOT NULL,
  comment_id BIGINT NULL,
  read_flag BOOLEAN NOT NULL DEFAULT FALSE,
  create_time BIGINT NOT NULL,
  FOREIGN KEY (recipient_id) REFERENCES users(id),
  FOREIGN KEY (actor_id) REFERENCES users(id),
  FOREIGN KEY (post_id) REFERENCES posts(id),
  FOREIGN KEY (comment_id) REFERENCES comments(comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX notifications_user ON notifications(recipient_id, type, notification_id);
