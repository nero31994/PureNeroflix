package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.R;

public class NetworkLogoAdapter extends RecyclerView.Adapter<NetworkLogoAdapter.ViewHolder> {

    public interface OnNetworkClickListener {
        void onClick(String networkId, String networkName, String logoUrl);
    }

    private final Context context;
    private final String[][] networks;
    private final OnNetworkClickListener listener;
    private final String type; // "network" or "company"

    public NetworkLogoAdapter(Context context, String[][] networks, OnNetworkClickListener listener) {
        this(context, networks, listener, "network");
    }

    public NetworkLogoAdapter(Context context, String[][] networks, OnNetworkClickListener listener, String type) {
        this.context = context;
        this.networks = networks;
        this.listener = listener;
        this.type = type;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_network_logo, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String[] network = networks[position];
        holder.name.setText(network[0]);
        // Use /company/{id} for studios, /network/{id} for streaming networks
        com.neroflix.tv.app.network.TmdbClient.NetworkCallback cb = new com.neroflix.tv.app.network.TmdbClient.NetworkCallback() {
            @Override
            public void onSuccess(String logoPath) {
                if (logoPath != null && !logoPath.isEmpty()) {
                    String logoUrl = "https://image.tmdb.org/t/p/w500" + logoPath;
                    Glide.with(context).load(logoUrl).placeholder(android.R.color.darker_gray).fitCenter().into(holder.logo);
                } else {
                    Glide.with(context).load(network[2]).placeholder(android.R.color.darker_gray).fitCenter().into(holder.logo);
                }
            }
            @Override
            public void onError(String error) {
                Glide.with(context).load(network[2]).placeholder(android.R.color.darker_gray).fitCenter().into(holder.logo);
            }
        };
        if ("company".equals(type)) {
            com.neroflix.tv.app.network.TmdbClient.getInstance(context).fetchCompany(network[1], cb);
        } else {
            com.neroflix.tv.app.network.TmdbClient.getInstance(context).fetchNetwork(network[1], cb);
        }
        holder.itemView.setOnClickListener(v -> listener.onClick(network[1], network[0], network[2]));
    }

    @Override
    public int getItemCount() { return networks.length; }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView logo;
        TextView name;
        ViewHolder(View v) {
            super(v);
            logo = v.findViewById(R.id.network_logo);
            name = v.findViewById(R.id.network_name);
        }
    }
}
