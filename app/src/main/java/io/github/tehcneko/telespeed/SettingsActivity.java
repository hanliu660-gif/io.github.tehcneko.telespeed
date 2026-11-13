package io.github.tehcneko.telespeed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.View;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class SettingsActivity extends Activity implements XposedServiceHelper.OnServiceListener {

    public static final String PREFS_NAME = "conf";
    public static final String KEY_SPEED = "speed";
    private static final long BIND_CHECK_DELAY_MS = 1500;

    private SharedPreferences mRemotePrefs;
    private boolean mServiceBound;
    private boolean mDialogShown;
    private Runnable mBindCheck;
    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        XposedServiceHelper.registerListener(this);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        mRemotePrefs = service.getRemotePreferences(PREFS_NAME);
        mServiceBound = true;
        runOnUiThread(() -> {
            if (mBindCheck != null) {
                mHandler.removeCallbacks(mBindCheck);
            }
            syncRemotePrefs();
            notifyFragment();
        });
    }

    @Override
    public void onServiceDied(XposedService service) {
        mRemotePrefs = null;
        mServiceBound = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mServiceBound) {
            mBindCheck = this::showUnsupportedDialog;
            mHandler.postDelayed(mBindCheck, BIND_CHECK_DELAY_MS);
        }
        notifyFragment();
    }

    @Override
    protected void onPause() {
        if (mBindCheck != null) {
            mHandler.removeCallbacks(mBindCheck);
        }
        super.onPause();
    }

    private void showUnsupportedDialog() {
        if (mDialogShown || mServiceBound || isFinishing()) {
            return;
        }
        mDialogShown = true;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.not_supported))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void syncRemotePrefs() {
        if (mRemotePrefs == null) {
            return;
        }
        SharedPreferences local = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String remoteVal = mRemotePrefs.getString(KEY_SPEED, null);
        String localVal = local.getString(KEY_SPEED, null);
        if (remoteVal == null && localVal != null) {
            // First run after upgrading from the legacy version: migrate the
            // existing local setting into the framework-managed remote prefs.
            mRemotePrefs.edit().putString(KEY_SPEED, localVal).apply();
        } else if (remoteVal != null && !remoteVal.equals(localVal)) {
            local.edit().putString(KEY_SPEED, remoteVal).apply();
        }
    }

    private void notifyFragment() {
        Fragment f = getFragmentManager().findFragmentById(R.id.fragment_container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        }
    }

    public static class SettingsFragment extends PreferenceFragment {
        private ListPreference mSpeedPref;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
            addPreferencesFromResource(R.xml.prefs);
            mSpeedPref = (ListPreference) getPreferenceManager().findPreference(KEY_SPEED);
            if (mSpeedPref != null) {
                mSpeedPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    SettingsActivity activity = (SettingsActivity) getActivity();
                    if (activity != null && activity.mRemotePrefs != null) {
                        activity.mRemotePrefs.edit().putString(KEY_SPEED, (String) newValue).apply();
                    }
                    return true;
                });
            }
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            View list = view.findViewById(android.R.id.list);
            list.setOnApplyWindowInsetsListener((v, insets) -> {
                list.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getStableInsetBottom());
                return insets.consumeSystemWindowInsets();
            });

            super.onViewCreated(view, savedInstanceState);
        }

        void refresh() {
            if (mSpeedPref == null || !isAdded()) {
                return;
            }
            SharedPreferences local = getActivity().getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String val = local.getString(KEY_SPEED, SpeedHook.BOOST_AVERAGE);
            mSpeedPref.setValue(val);
            mSpeedPref.setSummary(mSpeedPref.getEntry());
        }
    }
}
