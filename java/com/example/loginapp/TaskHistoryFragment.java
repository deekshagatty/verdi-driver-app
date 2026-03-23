package com.example.loginapp;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loginapp.net.ApiClient;
import com.example.loginapp.net.ApiService;
import com.example.loginapp.net.model.DriverTaskResponse;
import com.example.loginapp.net.model.DriverTaskRow;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TaskHistoryFragment extends Fragment {

    private static final String ARG_MODE = "mode"; // today / yesterday

    public static TaskHistoryFragment newInstance(String mode) {
        TaskHistoryFragment f = new TaskHistoryFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, mode);
        f.setArguments(b);
        return f;
    }

    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvEmpty;

    private HistoryAdapter adapter;
    private final List<DriverTaskRow> rows = new ArrayList<>();

    private ApiService api;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_task_history, container, false);

        rv = v.findViewById(R.id.rvHistory);
        progress = v.findViewById(R.id.progress);
        tvEmpty = v.findViewById(R.id.tvEmpty);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new HistoryAdapter(rows);
        rv.setAdapter(adapter);

        api = ApiClient.get().create(ApiService.class);

        load();
        return v;
    }

    private void load() {
        String mode = getArguments() != null ? getArguments().getString(ARG_MODE, "today") : "today";
        String dateStr = mode.equalsIgnoreCase("yesterday") ? getYesterdayDate() : getTodayDate();

        long driverId = AuthPrefs.driverId(requireContext());

        String bearer = AuthPrefs.bearer(requireContext());
        if (bearer == null || bearer.trim().isEmpty()) {
            String t = AuthPrefs.token(requireContext());
            if (t != null && !t.trim().isEmpty()) bearer = "Bearer " + t;
        }

        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        rv.setVisibility(View.GONE);

        if (driverId <= 0 || bearer == null) {
            progress.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Session expired");
            return;
        }

        api.getDriverTasks(bearer, driverId, dateStr).enqueue(new Callback<DriverTaskResponse>() {
            @Override
            public void onResponse(Call<DriverTaskResponse> call, Response<DriverTaskResponse> res) {
                progress.setVisibility(View.GONE);
                rows.clear();

                if (!res.isSuccessful() || res.body() == null) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("No data (http " + res.code() + ")");
                    return;
                }

                DriverTaskResponse body = res.body();
                if (!body.success || body.data == null) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("No data");
                    return;
                }

                rows.addAll(body.data);

                Log.d("HISTORY", "date=" + dateStr + " rows=" + rows.size()
                        + (rows.size() > 0 ? (" firstAmount=" + rows.get(0).amount) : ""));

                if (rows.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("No tasks");
                    return;
                }

                adapter.notifyDataSetChanged();
                rv.setVisibility(View.VISIBLE);
            }

            @Override
            public void onFailure(Call<DriverTaskResponse> call, Throwable t) {
                Log.e("HISTORY", "load failed", t);
                progress.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("Network error");
            }
        });
    }

    private String getTodayDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    private String getYesterdayDate() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    // ---------------- Adapter ----------------
    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<DriverTaskRow> data;
        HistoryAdapter(List<DriverTaskRow> data) { this.data = data; }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvOrderId, tvAmount;
            VH(@NonNull View itemView) {
                super(itemView);
                tvOrderId = itemView.findViewById(R.id.tvOrderId);
                tvAmount  = itemView.findViewById(R.id.tvAmount);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_history_task, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DriverTaskRow r = data.get(position);

            h.tvOrderId.setText(r.order_id != null ? r.order_id : "-");

            // ✅ Use amount and remove minus sign
            String raw = (r.amount == null || r.amount.trim().isEmpty()) ? "0" : r.amount.trim();
            try {
                double val = Double.parseDouble(raw);
                String show = String.format(Locale.US, "%.3f", Math.abs(val));
                h.tvAmount.setText("Amount: " + show);
            } catch (Exception e) {
                h.tvAmount.setText("Amount: " + raw.replace("-", ""));
            }
        }

        @Override
        public int getItemCount() { return data.size(); }
    }
}
