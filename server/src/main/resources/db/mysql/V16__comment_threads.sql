-- 两级评论区：一级评论 + 其下的回复。
-- reply_to_user_id 记录“回复了谁”，parent_comment_id 只保存所属一级评论，UI 最多缩进一层。
ALTER TABLE comments ADD COLUMN reply_to_user_id VARCHAR(36) NULL;
CREATE INDEX comments_parent ON comments(parent_comment_id);

-- 历史数据：先记录被回复者（原来的 parent_comment_id 指向直接父评论）。
UPDATE comments c JOIN comments p ON p.comment_id = c.parent_comment_id
SET c.reply_to_user_id = p.author_id;

-- 再把“回复的回复”收拢到一级评论，最多执行两层即可覆盖既有数据。
UPDATE comments c JOIN comments p ON p.comment_id = c.parent_comment_id
JOIN comments g ON g.comment_id = p.parent_comment_id
SET c.parent_comment_id = p.parent_comment_id;

UPDATE comments c JOIN comments p ON p.comment_id = c.parent_comment_id
JOIN comments g ON g.comment_id = p.parent_comment_id
SET c.parent_comment_id = p.parent_comment_id;
