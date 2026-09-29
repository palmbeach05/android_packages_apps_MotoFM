package com.motorola.fmradio;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;

import java.util.ArrayList;

public class FMDataProvider extends ContentProvider {
    private static final String AUTHORITY = "com.motorola.provider.fmradio";
    private static final String DATABASE_NAME = "fmradio.db";
    private static final int DATABASE_VERSION = 3;

    private static final String CHANNEL_TABLE = "channels";
    private static final String FAVORITE_TABLE = "favorites";
    static final int CHANNEL_COUNT = 30;

    public static class Channels {
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/channels");
        public static final String ID = "_id";
        public static final String FREQUENCY = "frequency";
        public static final String NAME = "name";
        public static final String RDS_NAME = "rds_name";
    };

    public static class Favorites {
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/favorites");
        public static final String FREQUENCY = "frequency";
        public static final String NAME = "name";
        public static final String ID = "_id";
    }

    private static final int CHANNELS = 1;
    private static final int CHANNELS_ID = 2;
    private static final int FAVORITES = 3;
    private static final int FAVORITES_ID = 4;

    private static final UriMatcher sUriMatcher = new UriMatcher(-1);
    static {
        sUriMatcher.addURI(AUTHORITY, "channels", CHANNELS);
        sUriMatcher.addURI(AUTHORITY, "channels/#", CHANNELS_ID);
        sUriMatcher.addURI(AUTHORITY, "favorites", FAVORITES);
        sUriMatcher.addURI(AUTHORITY, "favorites/#", FAVORITES_ID);
    }

    private DatabaseHelper mOpenHelper;
    private boolean mApplyingBatch;

    private class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE channels ("
                    + "_id INTEGER PRIMARY KEY,"
                    + "frequency INT NOT NULL DEFAULT 0,"
                    + "name TEXT,"
                    + "rds_name TEXT"
                    + ");");
            insertChannels(db, 0);
            createFavorites(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 1 && newVersion >= 2) {
                insertChannels(db, 20);
            }
            if (oldVersion < 3 && newVersion >= 3) {
                createFavorites(db);
            }
        }

        private void createFavorites(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE favorites ("
                    + "_id INTEGER PRIMARY KEY,"
                    + "frequency INTEGER NOT NULL UNIQUE,"
                    + "name TEXT NOT NULL DEFAULT ''"
                    + ");");
        }

        private void insertChannels(SQLiteDatabase db, int firstId) {
            for (int id = firstId; id < CHANNEL_COUNT; id++) {
                db.execSQL("insert into channels (_id, frequency, name, rds_name) "
                        + "values('" + id + "', '0', '', '');");
            }
        }
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        switch (sUriMatcher.match(uri)) {
            case CHANNELS:
                qb.setTables(CHANNEL_TABLE);
                if (sortOrder == null || sortOrder.length() == 0) {
                    sortOrder = Channels.ID + " ASC";
                }
                break;
            case CHANNELS_ID: {
                long id = ContentUris.parseId(uri);
                qb.setTables(CHANNEL_TABLE);
                selectionArgs = insertSelectionArg(selectionArgs, String.valueOf(id));
                qb.appendWhere("_id=?");
                break;
            }
            case FAVORITES:
                qb.setTables(FAVORITE_TABLE);
                if (sortOrder == null || sortOrder.length() == 0) {
                    sortOrder = Favorites.FREQUENCY + " ASC";
                }
                break;
            case FAVORITES_ID:
                qb.setTables(FAVORITE_TABLE);
                qb.appendWhere(Favorites.ID + "=" + ContentUris.parseId(uri));
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    @Override
    public synchronized Uri insert(Uri uri, ContentValues initialValues) {
        if (sUriMatcher.match(uri) != FAVORITES || initialValues == null
                || !initialValues.containsKey(Favorites.FREQUENCY)) {
            throw new IllegalArgumentException("Unknown or invalid favorite URI " + uri);
        }
        Integer savedFrequency = initialValues.getAsInteger(Favorites.FREQUENCY);
        if (savedFrequency == null) {
            throw new IllegalArgumentException("Missing favorite frequency");
        }
        int frequency = savedFrequency;
        if (frequency < FMUtil.MIN_FREQUENCY || frequency > FMUtil.MAX_FREQUENCY
                || (frequency - FMUtil.MIN_FREQUENCY) % FMUtil.STEP != 0) {
            throw new IllegalArgumentException("Invalid favorite frequency " + frequency);
        }
        ContentValues values = new ContentValues(initialValues);
        values.put(Favorites.ID, frequency);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long id = db.insertWithOnConflict(FAVORITE_TABLE, null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1) {
            getContext().getContentResolver().notifyChange(Favorites.CONTENT_URI, null);
        }
        return ContentUris.withAppendedId(Favorites.CONTENT_URI, frequency);
    }

    @Override
    public synchronized int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = 0;

        switch (sUriMatcher.match(uri)) {
            case CHANNELS:
                count = db.update(CHANNEL_TABLE, values, where, whereArgs);
                break;
            case CHANNELS_ID: {
                long id = ContentUris.parseId(uri);
                count = db.update(CHANNEL_TABLE, values, "_id=?", new String[] { String.valueOf(id) });
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }


        if (count > 0 && !mApplyingBatch) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public synchronized ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        ContentProviderResult[] results;
        db.beginTransaction();
        mApplyingBatch = true;
        try {
            results = super.applyBatch(operations);
            db.setTransactionSuccessful();
        } finally {
            mApplyingBatch = false;
            db.endTransaction();
        }
        getContext().getContentResolver().notifyChange(Channels.CONTENT_URI, null);
        return results;
    }

    @Override
    public synchronized int delete(Uri uri, String where, String[] whereArgs) {
        int count;
        switch (sUriMatcher.match(uri)) {
            case FAVORITES:
                count = mOpenHelper.getWritableDatabase().delete(FAVORITE_TABLE, where, whereArgs);
                break;
            case FAVORITES_ID:
                count = mOpenHelper.getWritableDatabase().delete(FAVORITE_TABLE,
                        Favorites.ID + "=?", new String[] { String.valueOf(ContentUris.parseId(uri)) });
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            getContext().getContentResolver().notifyChange(Favorites.CONTENT_URI, null);
        }
        return count;
    }

    private String[] insertSelectionArg(String[] selectionArgs, String arg) {
        if (selectionArgs == null) {
            return new String[] { arg };
        } else {
            int newLength = selectionArgs.length + 1;
            String[] newSelectionArgs = new String[newLength];
            newSelectionArgs[0] = arg;
            System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
            return newSelectionArgs;
        }
    }
}
