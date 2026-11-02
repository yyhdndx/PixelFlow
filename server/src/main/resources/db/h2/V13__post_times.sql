ALTER TABLE posts ADD COLUMN submit_time BIGINT;
ALTER TABLE posts ADD COLUMN review_time BIGINT;
ALTER TABLE posts ADD COLUMN publish_time BIGINT;
UPDATE posts SET publish_time=create_time WHERE status='PUBLISHED';
