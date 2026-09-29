package com.motorola.fmradio;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;

import com.motorola.fmradio.FMDataProvider.Channels;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

public class PresetBackupHelper {
    private static final String TAG = "PresetBackupHelper";

    private static final String ROOT_ELEMENT = "fmradio";
    private static final String PRESETS_ELEMENT = "presets";
    private static final String PRESET_ELEMENT = "preset";
    private static final String FREQUENCY_ELEMENT = "frequency";
    private static final String NAME_ELEMENT = "name";
    private static final String INDEX_ATTRIBUTE = "index";

    private PresetBackupHelper() {
        /* this class is not supposed to be instantiated */
    }

    public static boolean backupPresets(Context context, File destination) {
        File temporary = null;
        boolean replaced = false;
        try {
            final File dir = destination.getAbsoluteFile().getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            temporary = File.createTempFile("." + destination.getName(), ".tmp", dir);
            FileOutputStream os = new FileOutputStream(temporary);
            try {
                if (!backupPresets(context, os)) {
                    return false;
                }
                os.getFD().sync();
            } finally {
                os.close();
            }

            replaced = temporary.renameTo(destination);
            return replaced;
        } catch (IOException e) {
            Log.w(TAG, "Could not write preset backup", e);
            return false;
        } finally {
            if (!replaced && temporary != null) {
                temporary.delete();
            }
        }
    }

    // The caller owns the stream, including flushing and closing the document provider handle.
    public static boolean backupPresets(Context context, OutputStream output) {
        Cursor cursor = context.getContentResolver().query(Channels.CONTENT_URI,
                FMUtil.PROJECTION, null, null, null);
        if (cursor == null) {
            return false;
        }
        try {
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(output, "UTF-8");
            serializer.startDocument(null, Boolean.TRUE);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, ROOT_ELEMENT);
            serializer.startTag(null, PRESETS_ELEMENT);
            exportPresets(serializer, cursor);
            serializer.endTag(null, PRESETS_ELEMENT);
            serializer.endTag(null, ROOT_ELEMENT);
            serializer.endDocument();
            serializer.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Could not write preset backup", e);
            return false;
        } finally {
            cursor.close();
        }
    }

    private static void exportPresets(XmlSerializer serializer, Cursor cursor) throws IOException {
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            int frequency = cursor.getInt(FMUtil.CHANNEL_COLUMN_FREQ);
            if (frequency != 0) {
                int id = cursor.getInt(FMUtil.CHANNEL_COLUMN_ID);
                String name = cursor.getString(FMUtil.CHANNEL_COLUMN_NAME);

                serializer.startTag(null, PRESET_ELEMENT);
                serializer.attribute(null, INDEX_ATTRIBUTE, Integer.toString(id + 1));
                writeElement(serializer, FREQUENCY_ELEMENT, Integer.toString(frequency));
                writeElement(serializer, NAME_ELEMENT, name);
                serializer.endTag(null, PRESET_ELEMENT);
            }
            cursor.moveToNext();
        }
    }

    private static void writeElement(XmlSerializer serializer, String name, String value)
            throws IOException {
        if (!TextUtils.isEmpty(value)) {
            serializer.startTag(null, name);
            serializer.text(value);
            serializer.endTag(null, name);
        }
    }

    private static class PresetDescription {
        public int index;
        public int frequency;
        public String name;

        public boolean isValid() {
            return index > 0 && index <= FMDataProvider.CHANNEL_COUNT
                    && frequency >= FMUtil.MIN_FREQUENCY && frequency <= FMUtil.MAX_FREQUENCY;
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Preset description for index ");
            sb.append(index);
            sb.append(": frequency ");
            sb.append(frequency);
            sb.append(", name ");
            sb.append(TextUtils.isEmpty(name) ? "<empty>" : name);
            return sb.toString();
        }
    }

    public static int restorePresets(Context context, File source) {
        try {
            return restorePresets(context, new FileInputStream(source));
        } catch (IOException e) {
            Log.w(TAG, "Could not read from backup file", e);
            return -1;
        }
    }

    // This method closes the stream before changing any presets.
    public static int restorePresets(Context context, InputStream input) {
        HashMap<Integer, PresetDescription> importResults;
        try {
            try {
                importResults = parseBackup(input);
            } finally {
                input.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read from backup file", e);
            return -1;
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Invalid XML in backup file", e);
            return -1;
        }

        ContentResolver cr = context.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(Channels.FREQUENCY, 0);
        cv.put(Channels.NAME, "");
        cv.put(Channels.RDS_NAME, "");
        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();
        operations.add(ContentProviderOperation.newUpdate(Channels.CONTENT_URI)
                .withValues(cv).withExpectedCount(FMDataProvider.CHANNEL_COUNT).build());

        for (PresetDescription desc : importResults.values()) {
            Log.d(TAG, "Importing preset " + desc);
            cv = new ContentValues();
            cv.put(Channels.FREQUENCY, desc.frequency);
            cv.put(Channels.NAME, desc.name);

            Uri uri = Uri.withAppendedPath(Channels.CONTENT_URI, String.valueOf(desc.index - 1));
            operations.add(ContentProviderOperation.newUpdate(uri)
                    .withValues(cv).withExpectedCount(1).build());
        }

        try {
            cr.applyBatch(Channels.CONTENT_URI.getAuthority(), operations);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not replace presets", e);
            return -1;
        } catch (OperationApplicationException e) {
            Log.w(TAG, "Could not replace presets", e);
            return -1;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not replace presets", e);
            return -1;
        }

        return importResults.size();
    }

    private static HashMap<Integer, PresetDescription> parseBackup(InputStream is)
            throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xpp = factory.newPullParser();
        xpp.setInput(is, null);
        xpp.nextTag();
        xpp.require(XmlPullParser.START_TAG, null, ROOT_ELEMENT);
        xpp.nextTag();
        xpp.require(XmlPullParser.START_TAG, null, PRESETS_ELEMENT);
        HashMap<Integer, PresetDescription> result = new HashMap<Integer, PresetDescription>();
        parsePresets(xpp, result);
        xpp.nextTag();
        xpp.require(XmlPullParser.END_TAG, null, ROOT_ELEMENT);
        while (xpp.next() != XmlPullParser.END_DOCUMENT) {
            if (xpp.getEventType() == XmlPullParser.START_TAG
                    || xpp.getEventType() == XmlPullParser.END_TAG
                    || (xpp.getEventType() == XmlPullParser.TEXT && !xpp.isWhitespace())) {
                throw new XmlPullParserException("Unexpected content after preset backup");
            }
        }
        return result;
    }

    private static void parsePresets(XmlPullParser xpp, HashMap<Integer, PresetDescription> results)
            throws XmlPullParserException, IOException {
        while (xpp.nextTag() == XmlPullParser.START_TAG) {
            xpp.require(XmlPullParser.START_TAG, null, PRESET_ELEMENT);
            PresetDescription desc = parsePreset(xpp);
            if (!desc.isValid() || results.put(desc.index, desc) != null) {
                throw new XmlPullParserException("Invalid or duplicate preset index/frequency");
            }
        }
        xpp.require(XmlPullParser.END_TAG, null, PRESETS_ELEMENT);
    }

    private static PresetDescription parsePreset(XmlPullParser xpp)
            throws XmlPullParserException, IOException {
        PresetDescription desc = new PresetDescription();
        String index = xpp.getAttributeValue(null, INDEX_ATTRIBUTE);
        try {
            desc.index = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            throw new XmlPullParserException("Invalid preset index", xpp, e);
        }
        desc.name = "";
        boolean hasFrequency = false;
        boolean hasName = false;
        while (xpp.nextTag() == XmlPullParser.START_TAG) {
            if (TextUtils.equals(xpp.getName(), FREQUENCY_ELEMENT) && !hasFrequency) {
                try {
                    desc.frequency = Integer.parseInt(xpp.nextText());
                } catch (NumberFormatException e) {
                    throw new XmlPullParserException("Invalid preset frequency", xpp, e);
                }
                hasFrequency = true;
            } else if (TextUtils.equals(xpp.getName(), NAME_ELEMENT) && !hasName) {
                desc.name = xpp.nextText();
                hasName = true;
            } else {
                throw new XmlPullParserException("Unexpected preset element");
            }
        }
        xpp.require(XmlPullParser.END_TAG, null, PRESET_ELEMENT);
        if (!hasFrequency) {
            throw new XmlPullParserException("Missing preset frequency");
        }
        return desc;
    }
}
