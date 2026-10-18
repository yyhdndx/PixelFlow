CREATE TABLE recommendation_preferences (
  user_id VARCHAR(36) PRIMARY KEY,
  personalized BOOLEAN NOT NULL DEFAULT TRUE,
  version INTEGER NOT NULL DEFAULT 0,
 FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE recommendation_blocked_authors (
  user_id VARCHAR(36) NOT NULL,
  author_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (user_id, author_id),
 FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE recommendation_suppressed_topics (
  user_id VARCHAR(36) NOT NULL,
  topic VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, topic),
 FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
ALTER TABLE behavior_events ADD COLUMN recommendation_id VARCHAR(36);
