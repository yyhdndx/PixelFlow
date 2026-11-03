-- 两级评论区：一级评论 + 其下的回复。
-- reply_to_user_id 记录“回复了谁”，parent_comment_id 只保存所属一级评论，UI 最多缩进一层。
ALTER TABLE comments ADD COLUMN reply_to_user_id VARCHAR(36);
CREATE INDEX comments_parent ON comments(parent_comment_id);

-- 历史数据：先记录被回复者（原来的 parent_comment_id 指向直接父评论）。
UPDATE comments SET reply_to_user_id = (
    SELECT p.author_id FROM comments p WHERE p.comment_id = comments.parent_comment_id
) WHERE parent_comment_id IS NOT NULL;

-- 再把“回复的回复”收拢到一级评论，最多执行两层即可覆盖既有数据。
UPDATE comments SET parent_comment_id = (
    SELECT p.parent_comment_id FROM comments p WHERE p.comment_id = comments.parent_comment_id
) WHERE parent_comment_id IS NOT NULL
  AND (SELECT p.parent_comment_id FROM comments p WHERE p.comment_id = comments.parent_comment_id) IS NOT NULL;

UPDATE comments SET parent_comment_id = (
    SELECT p.parent_comment_id FROM comments p WHERE p.comment_id = comments.parent_comment_id
) WHERE parent_comment_id IS NOT NULL
  AND (SELECT p.parent_comment_id FROM comments p WHERE p.comment_id = comments.parent_comment_id) IS NOT NULL;
