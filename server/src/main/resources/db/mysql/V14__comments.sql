ALTER TABLE posts ADD COLUMN comment_count INT NOT NULL DEFAULT 0;

CREATE TABLE comments (
  comment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  author_id VARCHAR(36) NOT NULL,
  content VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE','HIDDEN','DELETED')),
  create_time BIGINT NOT NULL,
  FOREIGN KEY (post_id) REFERENCES posts(id),
  FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX comments_post ON comments(post_id, comment_id);
