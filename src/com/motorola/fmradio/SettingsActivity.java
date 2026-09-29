package com.motorola.fmradio;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;

public class SettingsActivity extends PreferenceActivity implements OnPreferenceChangeListener {
    public static final String ACTION_RSSI_UPDATED = "com.motorola.fmradio.action.RSSI_SETTING_UPDATED";
    public static final String EXTRA_RSSI = "rssi";

    private static final int DIALOG_INFO_HEADSET = 1;
    private static final int REQUEST_EXPORT_PRESETS = 2;
    private static final int REQUEST_IMPORT_PRESETS = 3;

    private static final String BACKUP_PREFIX = "presets-";

    private CheckBoxPreference mIgnoreNoHeadsetPref;
    private ListPreference mSeekSensitivityPref;
    private ListPreference mMediaButtonPref;
    private ListPreference mThemePref;
    private Preference mBackupPresetsPref;
    private Preference mRestorePresetsPref;
    private String[] mPresetBackupNames = new String[0];

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Preferences.applyTheme(this);
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        PreferenceScreen prefs = getPreferenceScreen();

        mThemePref = (ListPreference) prefs.findPreference("theme");
        mThemePref.setOnPreferenceChangeListener(this);

        mIgnoreNoHeadsetPref = (CheckBoxPreference) prefs.findPreference("ignore_no_headset");
        mIgnoreNoHeadsetPref.setOnPreferenceChangeListener(this);
        mSeekSensitivityPref = (ListPreference) prefs.findPreference("seek_sensitivity");
        mSeekSensitivityPref.setOnPreferenceChangeListener(this);
        mMediaButtonPref = (ListPreference) prefs.findPreference("media_button_behaviour");
        mMediaButtonPref.setOnPreferenceChangeListener(this);
        mBackupPresetsPref = prefs.findPreference("backup_presets");
        mRestorePresetsPref = prefs.findPreference("restore_presets");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mThemePref.setSummary(Preferences.isDarkTheme(this)
                ? R.string.theme_dark : R.string.theme_light);
        updateListPreferenceSummary(mSeekSensitivityPref, R.string.seek_sensitivity_summary,
                mSeekSensitivityPref.getValue());
        updateListPreferenceSummary(mMediaButtonPref, R.string.media_button_summary,
                mMediaButtonPref.getValue());
        updatePresetBackupList();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        if (preference == mBackupPresetsPref) {
            showBackupStorageChoice();
            return true;
        } else if (preference == mRestorePresetsPref) {
            showRestoreStorageChoice();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mThemePref) {
            Preferences.setDarkTheme(this, "dark".equals(newValue));
            recreate();
            return false;
        } else if (preference == mIgnoreNoHeadsetPref) {
            final Boolean value = (Boolean) newValue;
            if (value) {
                showDialog(DIALOG_INFO_HEADSET);
            }
        } else if (preference == mSeekSensitivityPref) {
            final int value = Integer.parseInt((String) newValue);
            Intent i = new Intent(ACTION_RSSI_UPDATED);
            i.putExtra(EXTRA_RSSI, value);
            sendBroadcast(i);
            updateListPreferenceSummary(mSeekSensitivityPref, R.string.seek_sensitivity_summary,
                    (String) newValue);
        } else if (preference == mMediaButtonPref) {
            updateListPreferenceSummary(mMediaButtonPref, R.string.media_button_summary,
                    (String) newValue);
        }

        return true;
    }

    private void updateListPreferenceSummary(ListPreference preference, int descriptionId,
            String value) {
        int index = preference.findIndexOfValue(value);
        if (index >= 0) {
            preference.setSummary(getString(R.string.list_preference_summary,
                    getString(descriptionId), preference.getEntries()[index]));
        } else {
            preference.setSummary(descriptionId);
        }
    }

    private void showBackupStorageChoice() {
        CharSequence[] choices = {
                getString(R.string.backup_presets_app_storage_choice),
                getString(R.string.backup_presets_choose_location_choice)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_presets_title)
                .setSingleChoiceItems(choices, -1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            showBackupNameDialog();
                        } else {
                            launchExportPicker();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRestoreStorageChoice() {
        CharSequence[] choices = {
                getString(R.string.restore_presets_app_storage_choice),
                getString(R.string.restore_presets_choose_location_choice)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_presets_title)
                .setSingleChoiceItems(choices, -1, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            showPresetBackupList();
                        } else {
                            launchImportPicker();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPresetBackupList() {
        updatePresetBackupList();
        final String[] backupNames = mPresetBackupNames;
        if (backupNames.length == 0) {
            Toast.makeText(this, R.string.restore_presets_failure_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.select_backup)
                .setItems(backupNames, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        File restore = buildBackupFileFromName(SettingsActivity.this,
                                backupNames[which]);
                        if (restore != null && restore.isFile()) {
                            showRestoreConfirmation(restore, null);
                        } else {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.restore_presets_failure_toast, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        launchDocumentPicker(intent, REQUEST_IMPORT_PRESETS, R.string.restore_presets_failure_toast);
    }

    private void showBackupNameDialog() {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(DateFormat.format("yyyy-MM-dd", new Date()));
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_presets_title)
                .setMessage(R.string.backup_presets_dialog_message)
                .setView(name)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = name.getText().toString();
                        if (!TextUtils.isEmpty(value)) {
                            if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                                    || value.equals(".") || value.equals("..")) {
                                Toast.makeText(SettingsActivity.this,
                                        R.string.backup_presets_failure_toast, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            final File backup = buildBackupFileFromName(SettingsActivity.this, value);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    final boolean success = backup != null
                                            && PresetBackupHelper.backupPresets(SettingsActivity.this,
                                                    backup);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (success) {
                                                updatePresetBackupList();
                                            }
                                            Toast.makeText(SettingsActivity.this,
                                                    success ? R.string.backup_presets_success_toast
                                                            : R.string.backup_presets_failure_toast,
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            }).start();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/xml");
        intent.putExtra(Intent.EXTRA_TITLE, BACKUP_PREFIX
                + DateFormat.format("yyyy-MM-dd", new Date()) + ".xml");
        launchDocumentPicker(intent, REQUEST_EXPORT_PRESETS, R.string.backup_presets_failure_toast);
    }

    private void launchDocumentPicker(Intent intent, int requestCode, int failureMessage) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_PRESETS && requestCode != REQUEST_IMPORT_PRESETS) {
            return;
        }
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        Uri document = data != null ? data.getData() : null;
        if (document == null) {
            Toast.makeText(this, requestCode == REQUEST_EXPORT_PRESETS
                    ? R.string.backup_presets_failure_toast : R.string.restore_presets_failure_toast,
                    Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQUEST_EXPORT_PRESETS) {
            exportPresets(document);
        } else {
            showRestoreConfirmation(null, document);
        }
    }

    private void exportPresets(final Uri document) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean success = writePresets(document);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this,
                                success ? R.string.backup_presets_success_toast
                                        : R.string.backup_presets_failure_toast,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private boolean writePresets(Uri document) {
        boolean success = false;
        try {
            OutputStream output = getContentResolver().openOutputStream(document, "wt");
            if (output != null) {
                try {
                    success = PresetBackupHelper.backupPresets(this, output);
                } finally {
                    output.close();
                }
            }
        } catch (IOException e) {
            success = false;
        } catch (SecurityException e) {
            success = false;
        } catch (IllegalArgumentException e) {
            success = false;
        }
        if (!success) {
            try {
                DocumentsContract.deleteDocument(getContentResolver(), document);
            } catch (RuntimeException e) {
                // Providers may not support deletion or may deny access.
            }
        }
        return success;
    }

    private int importPresets(Uri document) {
        try {
            InputStream input = getContentResolver().openInputStream(document);
            if (input == null) {
                return -1;
            }
            return PresetBackupHelper.restorePresets(this, input);
        } catch (IOException e) {
            return -1;
        } catch (SecurityException e) {
            return -1;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private void showRestoreConfirmation(final File file, final Uri document) {
        new AlertDialog.Builder(this)
                .setTitle(document != null ? R.string.import_presets_title
                        : R.string.restore_presets_app_storage_title)
                .setMessage(R.string.restore_presets_confirm_message)
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final int presets = document != null ? importPresets(document)
                                        : PresetBackupHelper.restorePresets(SettingsActivity.this, file);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        String message = presets >= 0
                                                ? getString(R.string.restore_presets_success_toast, presets)
                                                : getString(R.string.restore_presets_failure_toast);
                                        Toast.makeText(SettingsActivity.this, message,
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_INFO_HEADSET:
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.notice)
                        .setMessage(R.string.no_headset_ignore_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
        }

        return null;
    }

    private void updatePresetBackupList() {
        File backupDir = getPresetBackupDirectory(this);
        File[] files = backupDir != null ? backupDir.listFiles() : null;
        ArrayList<String> items = new ArrayList<String>();

        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                final String name = file.getName();
                if (!name.startsWith(BACKUP_PREFIX) || !name.endsWith(".xml")) {
                    continue;
                }
                items.add(name.substring(BACKUP_PREFIX.length(), name.length() - 4));
            }
        }

        mPresetBackupNames = items.toArray(new String[items.size()]);
    }

    private static File getPresetBackupDirectory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            return null;
        }
        return new File(base, "backups");
    }

    private static File buildBackupFileFromName(Context context, String name) {
        File backupDir = getPresetBackupDirectory(context);
        if (backupDir == null) {
            return null;
        }

        final StringBuilder fileName = new StringBuilder();
        fileName.append(BACKUP_PREFIX);
        fileName.append(name);
        fileName.append(".xml");

        return new File(backupDir, fileName.toString());
    }
}
