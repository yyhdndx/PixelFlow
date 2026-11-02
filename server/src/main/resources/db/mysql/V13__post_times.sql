ALTER TABLE posts
    ADD COLUMN submit_time BIGINT NULL,
    ADD COLUMN review_time BIGINT NULL,
    ADD COLUMN publish_time BIGINT NULL;
UPDATE posts SET publish_time=create_time WHERE status='PUBLISHED';
