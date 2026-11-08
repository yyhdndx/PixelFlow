ALTER TABLE users ADD COLUMN avatar_media_id VARCHAR(36);
ALTER TABLE users ADD CONSTRAINT users_avatar_media_fk FOREIGN KEY (avatar_media_id) REFERENCES media(id);
CREATE INDEX users_avatar_media ON users(avatar_media_id);
