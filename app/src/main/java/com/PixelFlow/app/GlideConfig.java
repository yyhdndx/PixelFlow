package com.PixelFlow.app;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

/**
 * 统一 Glide 缓存预算。
 * 内存缓存给可用堆的 1/3（约 85–170MB，随设备堆变化），磁盘缓存 250MB。
 * 显式设预算便于按真机测量结果调优，避免完全依赖默认值。
 */
@GlideModule
public final class GlideConfig extends AppGlideModule {
    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        int memoryCacheSize = (int) (Runtime.getRuntime().maxMemory() / 3);
        builder.setMemoryCache(new LruResourceCache(memoryCacheSize));
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, 250L * 1024 * 1024));
    }
}
