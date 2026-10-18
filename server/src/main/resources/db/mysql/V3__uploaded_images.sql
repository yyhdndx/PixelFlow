CREATE TABLE media (
 id VARCHAR(36) PRIMARY KEY,
 owner_id VARCHAR(36) NOT NULL,
 width INTEGER NOT NULL, height INTEGER NOT NULL,
 content_bytes LONGBLOB NOT NULL,
 created_at BIGINT NOT NULL,
 FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX media_owner ON media(owner_id);
CREATE TABLE post_media (
 post_id BIGINT NOT NULL,
 media_id VARCHAR(36) NOT NULL,
 position INTEGER NOT NULL,
 PRIMARY KEY(post_id,media_id), UNIQUE(post_id,position),
 FOREIGN KEY (post_id) REFERENCES posts(id),
 FOREIGN KEY (media_id) REFERENCES media(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX post_media_lookup ON post_media(media_id);
