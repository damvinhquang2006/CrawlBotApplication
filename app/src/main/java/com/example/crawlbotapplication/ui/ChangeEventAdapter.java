package com.example.crawlbotapplication.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crawlbotapplication.R;
import com.example.crawlbotapplication.data.ChangeEventItem;

import java.util.ArrayList;
import java.util.List;

public class ChangeEventAdapter extends RecyclerView.Adapter<ChangeEventAdapter.ViewHolder> {

    private final List<ChangeEventItem> items = new ArrayList<>();

    public void updateData(List<ChangeEventItem> newItems) {
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
                .inflate(R.layout.item_change_event, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChangeEventItem item = items.get(position);

        holder.tvMessage.setText(item.getMessage());
        holder.tvTime.setText(item.getCreatedAt());

        if ("PRICE_CHANGED".equalsIgnoreCase(item.getEventType())) {
            holder.tvBadge.setText("BIẾN ĐỘNG GIÁ");
            holder.tvBadge.setBackgroundColor(Color.parseColor("#E65100")); // Orange badge
        } else {
            holder.tvBadge.setText("MẶT HÀNG MỚI");
            holder.tvBadge.setBackgroundColor(Color.parseColor("#1565C0")); // Blue badge
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvBadge, tvMessage, tvTime;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvBadge = itemView.findViewById(R.id.tvBadge);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvTime = itemView.findViewById(R.id.tvTime);
        }
    }
}
