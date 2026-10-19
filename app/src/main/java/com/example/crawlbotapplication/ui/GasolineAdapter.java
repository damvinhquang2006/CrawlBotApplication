package com.example.crawlbotapplication.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crawlbotapplication.R;
import com.example.crawlbotapplication.data.GasolineItem;

import java.util.ArrayList;
import java.util.List;

public class GasolineAdapter extends RecyclerView.Adapter<GasolineAdapter.ViewHolder> {

    private final List<GasolineItem> items = new ArrayList<>();

    public void updateData(List<GasolineItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gasoline_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GasolineItem item = items.get(position);
        holder.tvProduct.setText(item.getProduct());
        holder.tvPrice.setText(item.getPrice() != null ? item.getPrice() + " đ" : "N/A");

        // Format diff badge
        String diff = item.getDiff();
        if (diff != null && !diff.isBlank() && !"0".equals(diff)) {
            holder.tvDiff.setVisibility(View.VISIBLE);
            holder.tvDiff.setText(diff);
            if (diff.startsWith("-")) {
                holder.tvDiff.setTextColor(Color.parseColor("#2E7D32")); // Green for price reduction
                holder.tvDiff.setText("Giảm: " + diff + " đ");
            } else if (diff.startsWith("+")) {
                holder.tvDiff.setTextColor(Color.parseColor("#C62828")); // Red for price hike
                holder.tvDiff.setText("Tăng: " + diff + " đ");
            }
        } else {
            holder.tvDiff.setVisibility(View.GONE);
        }

        // Zone details
        StringBuilder sb = new StringBuilder();
        if (item.getZone1Price() != null) {
            sb.append("Vùng 1: ").append(item.getZone1Price());
        }
        if (item.getZone2Price() != null) {
            if (sb.length() > 0) sb.append("  |  ");
            sb.append("Vùng 2: ").append(item.getZone2Price());
        }
        holder.tvZones.setText(sb.toString());
        holder.tvUpdated.setText("Cập nhật: " + (item.getUpdatedAt() != null ? item.getUpdatedAt() : "Gần đây"));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvProduct, tvPrice, tvDiff, tvZones, tvUpdated;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvProduct = itemView.findViewById(R.id.tvProduct);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            tvDiff = itemView.findViewById(R.id.tvDiff);
            tvZones = itemView.findViewById(R.id.tvZones);
            tvUpdated = itemView.findViewById(R.id.tvUpdated);
        }
    }
}
