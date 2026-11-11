package com.PixelFlow.app;

import androidx.lifecycle.ViewModel;

/** 「我的」页资料的常驻缓存：作用域为主 Activity，跨 Tab 切换/碎片重建不丢失，避免反复「正在读取」。 */
public final class ProfileDataModel extends ViewModel {
    public long loadedSession = -1L;
    public String name = "", username = "", bio = "", avatarUrl = "";
    public String posts = "", followers = "", following = "";
}
