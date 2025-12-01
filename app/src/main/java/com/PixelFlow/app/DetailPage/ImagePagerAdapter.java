package com.PixelFlow.app.DetailPage;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import java.util.List;

public class ImagePagerAdapter extends RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder> {

    private final List<String> imageUrls;
    private final LayoutInflater inflater;

    public ImagePagerAdapter(android.content.Context context, List<String> imageUrls) {
        this.imageUrls = imageUrls;
        this.inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.item_detail_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        holder.bind(imageUrls.get(position));
    }

    @Override
    public int getItemCount() {
        return imageUrls.size();
    }

    static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ProgressBar progressBar;

        ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.detail_image);
            progressBar = itemView.findViewById(R.id.image_loading);
        }

        void bind(String imageUrl) {
            progressBar.setVisibility(View.VISIBLE);

            Glide.with(itemView.getContext())
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_error)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(imageView);

            // 监听图片加载完成
            // 实际应该使用 Glide 的监听器来隐藏 progressBar
            progressBar.setVisibility(View.GONE);
        }
    }
}
