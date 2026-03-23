package com.example.loginapp;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class TaskHistoryPagerAdapter extends FragmentStateAdapter {

    public TaskHistoryPagerAdapter(@NonNull FragmentActivity fa) {
        super(fa);
    }


    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // 0 = Today, 1 = Yesterday
        return TaskHistoryFragment.newInstance(position == 0 ? "today" : "yesterday");
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
