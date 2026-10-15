CREATE TABLE media (
 id VARCHAR(36) PRIMARY KEY,
 owner_id VARCHAR(36) NOT NULL REFERENCES users(id),
 width INTEGER NOT NULL, height INTEGER NOT NULL,
 content_bytes BYTEA NOT NULL,
 created_at BIGINT NOT NULL
);
CREATE INDEX media_owner ON media(owner_id);
CREATE TABLE post_media (
 post_id BIGINT NOT NULL REFERENCES posts(id),
 media_id VARCHAR(36) NOT NULL REFERENCES media(id),
 position INTEGER NOT NULL,
 PRIMARY KEY(post_id,media_id), UNIQUE(post_id,position)
);
CREATE INDEX post_media_lookup ON post_media(media_id);
