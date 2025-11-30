package com.PixelFlow.app.DAO;

public enum DataType {
    LIKE_STATUS("like_status"),           // 点赞状态
    FAVORITE("favorite"),                 // 收藏状态
    USER_PROFILE("user_profile"),         // 用户资料
    BROWSE_HISTORY("browse_history"),     // 浏览历史
    SEARCH_HISTORY("search_history"),     // 搜索历史
    APP_SETTINGS("app_settings"),         // 应用设置
    DRAFT("draft");                       // 草稿

    private final String tableName;

    DataType(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }
}
