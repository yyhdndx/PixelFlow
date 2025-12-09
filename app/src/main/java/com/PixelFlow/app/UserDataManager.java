// UserDataManager.java
package com.PixelFlow.app;

import android.content.Context;
import android.content.SharedPreferences;

public class UserDataManager {
    private static final String PREFS_NAME = "user_data";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_USER_BIO = "user_bio";
    private static final String KEY_AVATAR_PATH = "avatar_path";
    private static final String KEY_POSTS_COUNT = "posts_count";
    private static final String KEY_FOLLOWERS_COUNT = "followers_count";
    private static final String KEY_FOLLOWING_COUNT = "following_count";

    private SharedPreferences prefs;

    public UserDataManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // 保存用户名
    public void saveUserName(String userName) {
        prefs.edit().putString(KEY_USER_NAME, userName).apply();
    }

    // 获取用户名
    public String getUserName() {
        return prefs.getString(KEY_USER_NAME, "");
    }

    // 保存用户简介
    public void saveUserBio(String userBio) {
        prefs.edit().putString(KEY_USER_BIO, userBio).apply();
    }

    // 获取用户简介
    public String getUserBio() {
        return prefs.getString(KEY_USER_BIO, "");
    }

    // 保存头像路径
    public void saveAvatarPath(String avatarPath) {
        prefs.edit().putString(KEY_AVATAR_PATH, avatarPath).apply();
    }

    // 获取头像路径
    public String getAvatarPath() {
        return prefs.getString(KEY_AVATAR_PATH, "");
    }

    // 获取作品数
    public int getPostsCount() {
        return prefs.getInt(KEY_POSTS_COUNT, 128); // 默认值
    }

    // 获取粉丝数
    public int getFollowersCount() {
        return prefs.getInt(KEY_FOLLOWERS_COUNT, 2400); // 默认值
    }

    // 获取关注数
    public int getFollowingCount() {
        return prefs.getInt(KEY_FOLLOWING_COUNT, 156); // 默认值
    }

    // 更新作品数
    public void updatePostsCount(int delta) {
        int current = getPostsCount();
        prefs.edit().putInt(KEY_POSTS_COUNT, current + delta).apply();
    }

    // 更新粉丝数
    public void updateFollowersCount(int delta) {
        int current = getFollowersCount();
        prefs.edit().putInt(KEY_FOLLOWERS_COUNT, current + delta).apply();
    }

    // 更新关注数
    public void updateFollowingCount(int delta) {
        int current = getFollowingCount();
        prefs.edit().putInt(KEY_FOLLOWING_COUNT, current + delta).apply();
    }

    // 清除用户数据（退出登录时使用）
    public void clearUserData() {
        // 只清除用户特定数据，不删除头像文件
        prefs.edit()
                .remove(KEY_USER_NAME)
                .remove(KEY_USER_BIO)
                .apply();
        // 注意：这里不清除头像路径，因为用户重新登录时可能还想使用之前的头像
    }

    // 完全重置用户数据
    public void resetUserData() {
        prefs.edit().clear().apply();
    }
}