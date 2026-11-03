-- 作品级「评论自动回复」规则：作者配置「场景 → 答案」，由 Agent 判定一级评论是否命中场景。
CREATE TABLE comment_auto_replies (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  owner_id VARCHAR(36) NOT NULL,
  question VARCHAR(200) NOT NULL,
  answer VARCHAR(300) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  create_time BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  FOREIGN KEY (post_id) REFERENCES posts(id),
  FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX comment_auto_replies_post ON comment_auto_replies(post_id, id);

-- 自动回复的评论要能标明来源，不能冒充真人回复。
ALTER TABLE comments ADD COLUMN auto_reply BOOLEAN NOT NULL DEFAULT FALSE;
