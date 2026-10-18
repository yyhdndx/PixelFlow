CREATE TABLE recommendation_preferences (
  user_id VARCHAR(36) PRIMARY KEY REFERENCES users(id),
  personalized BOOLEAN NOT NULL DEFAULT TRUE,
  version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE recommendation_blocked_authors (
  user_id VARCHAR(36) NOT NULL REFERENCES users(id),
  author_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (user_id, author_id)
);
CREATE TABLE recommendation_suppressed_topics (
  user_id VARCHAR(36) NOT NULL REFERENCES users(id),
  topic VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, topic)
);
ALTER TABLE behavior_events ADD COLUMN recommendation_id VARCHAR(36);
