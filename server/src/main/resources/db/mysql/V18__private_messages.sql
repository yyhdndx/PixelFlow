-- 一对一会话（私信）：独立于系统通知域，不往 notifications 里塞。
-- user_low/user_high 按字典序归一化，保证同一对用户只有一个会话（UNIQUE 兜底）。
CREATE TABLE conversations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_low VARCHAR(36) NOT NULL,
  user_high VARCHAR(36) NOT NULL,
  create_time BIGINT NOT NULL,
  last_message_id BIGINT NULL,
  last_message_time BIGINT NULL,
  UNIQUE (user_low, user_high),
  FOREIGN KEY (user_low) REFERENCES users(id),
  FOREIGN KEY (user_high) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 纯文本消息，只存文字（1–1000 字，上限在服务层校验）。
CREATE TABLE messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  sender_id VARCHAR(36) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  create_time BIGINT NOT NULL,
  FOREIGN KEY (conversation_id) REFERENCES conversations(id),
  FOREIGN KEY (sender_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX messages_conversation ON messages(conversation_id, id);

-- 会话级已读位置：每个用户在该会话已读到哪条消息；未读 = id > last_read_message_id 且非本人发送。
CREATE TABLE conversation_reads (
  conversation_id BIGINT NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  last_read_message_id BIGINT NOT NULL,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY (conversation_id) REFERENCES conversations(id),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
