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
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.text.format.DateFormat;
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
    private EditTextPreference mBackupPresetsPref;
    private ListPreference mRestorePresetsPref;
    private Preference mExportPresetsPref;
    private Preference mImportPresetsPref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        PreferenceScreen prefs = getPreferenceScreen();

        mIgnoreNoHeadsetPref = (CheckBoxPreference) prefs.findPreference("ignore_no_headset");
        mIgnoreNoHeadsetPref.setOnPreferenceChangeListener(this);
        mSeekSensitivityPref = (ListPreference) prefs.findPreference("seek_sensitivity");
        mSeekSensitivityPref.setOnPreferenceChangeListener(this);
        mBackupPresetsPref = (EditTextPreference) prefs.findPreference("backup_presets");
        mBackupPresetsPref.setOnPreferenceChangeListener(this);
        mBackupPresetsPref.setText(DateFormat.format("yyyy-MM-dd", new Date()).toString());
        mRestorePresetsPref = (ListPreference) prefs.findPreference("restore_presets");
        mRestorePresetsPref.setOnPreferenceChangeListener(this);
        mExportPresetsPref = prefs.findPreference("export_presets");
        mImportPresetsPref = prefs.findPreference("import_presets");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePresetBackupList();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        if (preference == mExportPresetsPref) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/xml");
            intent.putExtra(Intent.EXTRA_TITLE, BACKUP_PREFIX
                    + DateFormat.format("yyyy-MM-dd", new Date()) + ".xml");
            launchDocumentPicker(intent, REQUEST_EXPORT_PRESETS, R.string.backup_presets_failure_toast);
            return true;
        } else if (preference == mImportPresetsPref) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            launchDocumentPicker(intent, REQUEST_IMPORT_PRESETS, R.string.restore_presets_failure_toast);
            return true;
        }
        if (preference == mRestorePresetsPref) {
            updatePresetBackupList();
        }

        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mIgnoreNoHeadsetPref) {
            final Boolean value = (Boolean) newValue;
            if (value) {
                showDialog(DIALOG_INFO_HEADSET);
            }
        } else if (preference == mSeekSensitivityPref) {
            final int value = Integer.parseInt((String) newValue);
            Intent i = new Intent(ACTION_RSSI_UPDATED);
            i.putExtra(EXTRA_RSSI, value);
            sendBroadcast(i);
        } else if (preference == mBackupPresetsPref) {
            final String value = (String) newValue;
            if (!TextUtils.isEmpty(value)) {
                File backup = buildBackupFileFromName(this, value);
                if (backup != null) {
                    int resId;
                    if (PresetBackupHelper.backupPresets(this, backup)) {
                        resId = R.string.backup_presets_success_toast;
                        updatePresetBackupList();
                    } else {
                        resId = R.string.backup_presets_failure_toast;
                    }
                    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
                }
            }
        } else if (preference == mRestorePresetsPref) {
            final String fileName = (String) newValue;
            final File restore = buildBackupFileFromName(this, fileName);
            if (restore != null && restore.isFile()) {
                showRestoreConfirmation(restore, null);
            } else {
                Toast.makeText(this, R.string.restore_presets_failure_toast, Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        return true;
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

    private void exportPresets(Uri document) {
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
        Toast.makeText(this, success ? R.string.backup_presets_success_toast
                : R.string.backup_presets_failure_toast, Toast.LENGTH_SHORT).show();
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
                        : R.string.restore_presets_title)
                .setMessage(R.string.restore_presets_confirm_message)
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int presets = document != null ? importPresets(document)
                                : PresetBackupHelper.restorePresets(SettingsActivity.this, file);
                        String message = presets >= 0
                                ? getString(R.string.restore_presets_success_toast, presets)
                                : getString(R.string.restore_presets_failure_toast);
                        Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show();
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

        final String[] itemArray = items.toArray(new String[items.size()]);
        mRestorePresetsPref.setEntries(itemArray);
        mRestorePresetsPref.setEntryValues(itemArray);
        mRestorePresetsPref.setValue(null);
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
