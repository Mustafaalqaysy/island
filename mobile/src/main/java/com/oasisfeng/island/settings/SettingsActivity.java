package com.oasisfeng.island.settings;

import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.annotation.XmlRes;
import androidx.core.app.NavUtils;

import com.oasisfeng.android.app.Activities;
import com.oasisfeng.android.ui.Dialogs;
import com.oasisfeng.island.MainActivity;
import com.oasisfeng.island.mobile.R;
import com.oasisfeng.island.shared.BuildConfig;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Modules;
import com.oasisfeng.island.util.Users;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
import static android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE;
import static java.util.Objects.requireNonNull;

/**
 * A {@link PreferenceActivity} that presents a set of application settings. On handset devices, settings are presented as a single list.
 * On tablets, settings are split by category, with category headers shown to the left of the list of settings.
 */
@SuppressWarnings("deprecation") public class SettingsActivity extends PreferenceActivity {

	public static void startWithPreference(final Context context, final Class<? extends PreferenceFragment> fragment) {
		final Intent intent = new Intent(context, SettingsActivity.class).putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, fragment.getName());
		Activities.startActivity(context, intent);
	}

	@Override protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		final ActionBar actionBar = getActionBar();
		if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);		// Show the Up button in the action bar.
	}

	@Override protected void onResume() {
		super.onResume();
		if (! new DevicePolicies(this).isProfileOrDeviceOwnerOnCallingUser()) {
			final List<UserHandle> profiles = requireNonNull((UserManager) getSystemService(Context.USER_SERVICE)).getUserProfiles();
			if (profiles.size() == 1) {     // The last Island is just destroyed
				Log.i(TAG, "Nothing left, back to initial setup.");
				finishAffinity();
				startActivity(new Intent(this, MainActivity.class));
			}
		}
	}

	@Override public boolean onMenuItemSelected(final int featureId, final MenuItem item) {
		final int id = item.getItemId();
		if (id == android.R.id.home) {
			if (! super.onMenuItemSelected(featureId, item)) NavUtils.navigateUpFromSameTask(this);
			return true;
		}
		return super.onMenuItemSelected(featureId, item);
	}

	/**
	 * Binds a preference's summary to its value. More specifically, when the preference's value is changed, its summary (line of text
	 * below the preference title) is updated to reflect the value. The summary is also immediately updated upon calling this method.
	 * The exact display format is dependent on the type of preference.
	 */
	private static void bindPreferenceSummaryToValue(final Preference preference) {
		// Set the listener to watch for value changes.
		preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);
		// Trigger the listener immediately with the preference's current value.
		sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
				PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), ""));
	}

	/** A preference value change listener that updates the preference's summary to reflect its new value. */
	private static final Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = (preference, value) -> {
		String stringValue = value.toString();

		if (preference instanceof ListPreference) {
			// For list preferences, look up the correct display value in
			// the preference's 'entries' list.
			ListPreference listPreference = (ListPreference) preference;
			int index = listPreference.findIndexOfValue(stringValue);

			// Set the summary to reflect the new value.
			preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);
		} else {
			// For all other preferences, set the summary to the value's simple string representation.
			preference.setSummary(stringValue);
		}
		return true;
	};

	@Override public boolean onIsMultiPane() {
		return (getResources().getConfiguration().screenLayout & SCREENLAYOUT_SIZE_MASK) >= SCREENLAYOUT_SIZE_XLARGE;
	}

	@Override public void onBuildHeaders(final List<Header> target) { loadHeadersFromResource(R.xml.pref_headers, target); }

	@Override public void onHeaderClick(final Header header, final int position) {
		if (header.id != R.id.pref_header_island) {
			super.onHeaderClick(header, position);
			return;
		}
		final List<UserHandle> users = new ArrayList<>();		// Support multiple managed-profiles
		users.add(Users.owner);
		users.addAll(Users.getProfilesManagedByIsland());
		if (users.size() <= 1) {		// God mode without Island
			switchToHeader(header);
			return;
		}
		final Map<UserHandle, String> names = IslandNameManager.getAllNames(this);
		final CharSequence[] profile_labels = users.stream().map(user -> Users.isOwner(user) ? getText(R.string.tab_mainland)
				: names.get(user)).toArray(CharSequence[]::new);
		Dialogs.buildList(this, null, profile_labels, (d, which) -> {
			if (which == 0) switchToHeader(header); else launchSettingsActivityAsUser(users.get(which));
		}).show();
	}

	private void launchSettingsActivityAsUser(final UserHandle user) {
		final LauncherApps la = requireNonNull((LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE));
		final List<LauncherActivityInfo> activities = la.getActivityList(getPackageName(), user);
		if (! activities.isEmpty()) {
			for (final LauncherActivityInfo activity : activities)
				if (IslandSettingsActivity.class.getName().equals(activity.getComponentName().getClassName())) {
					la.startMainActivity(activity.getComponentName(), activity.getUser(), null, null);
					break;
				}
		} else Toast.makeText(this, R.string.prompt_island_not_yet_setup, Toast.LENGTH_LONG).show();
	}

	/** This method stops fragment injection in malicious applications. Make sure to deny any unknown fragments here. */
	protected boolean isValidFragment(final String fragmentName) { return fragmentName.startsWith(requireNonNull(getClass().getPackage()).getName()); }

	public static abstract class SubPreferenceFragment extends PreferenceFragment {

		public SubPreferenceFragment(final @XmlRes int preference_xml, final int... keys_to_bind_summary) {
			mPreferenceXml = preference_xml;
			mKeysToBindSummary = keys_to_bind_summary;
		}

		@Override public void onCreate(final Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			addPreferencesFromResource(mPreferenceXml);
			setHasOptionsMenu(true);
			// Bind the summaries of EditText/List/Dialog/Ringtone preferences to their values.
			// When their values change, their summaries are updated to reflect the new value, per the Android Design guidelines.
			for (final int key : mKeysToBindSummary) bindPreferenceSummaryToValue(findPreference(getString(key)));
		}

		@Override public boolean onOptionsItemSelected(final MenuItem item) {
			final int id = item.getItemId();
			if (id == android.R.id.home) {
				startActivity(new Intent(getActivity(), SettingsActivity.class));
				return true;
			} else return super.onOptionsItemSelected(item);
		}

		private final int mPreferenceXml;
		private final @StringRes int[] mKeysToBindSummary;
	}


	public static class AboutFragment extends SubPreferenceFragment {

		@Override public void onCreate(final Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			try {
				final PackageInfo pkg_info = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
				String summary;
				if (BuildConfig.DEBUG) {
					summary = pkg_info.versionName + " (" + pkg_info.versionCode + ")";
					if (! Modules.MODULE_ENGINE.equals(getActivity().getPackageName())) try {
						final PackageInfo engine_info = getActivity().getPackageManager().getPackageInfo(Modules.MODULE_ENGINE, 0);
						summary += ", Engine: " + engine_info.versionName + " (" + engine_info.versionCode + ")";
					} catch (final PackageManager.NameNotFoundException ignored) {}
				} else summary = pkg_info.versionName;
				findPreference(getString(R.string.key_version)).setSummary(summary);
			} catch (final PackageManager.NameNotFoundException ignored) {}		// Should never happen
		}

		public AboutFragment() { super(R.xml.pref_about); }
	}

	private static final String TAG = "Island.SA";
}