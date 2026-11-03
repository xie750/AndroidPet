package com.example.desktoppet.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.desktoppet.model.Item;

import java.util.ArrayList;
import java.util.List;

public final class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "desktop_pet.db";
    private static final int DB_VERSION = 1;

    public AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "content TEXT NOT NULL DEFAULT ''," +
                "due_at INTEGER NOT NULL DEFAULT 0," +
                "done INTEGER NOT NULL DEFAULT 0," +
                "repeat_type TEXT NOT NULL DEFAULT 'NONE'," +
                "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 has no migrations yet.
    }

    public long insert(String type, String title, String content, long dueAt, String repeatType) {
        ContentValues values = new ContentValues();
        values.put("type", type);
        values.put("title", title);
        values.put("content", content == null ? "" : content);
        values.put("due_at", dueAt);
        values.put("repeat_type", repeatType == null ? "NONE" : repeatType);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("items", null, values);
    }

    public List<Item> getAll() {
        List<Item> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,type,title,content,due_at,done,repeat_type,created_at " +
                        "FROM items ORDER BY done ASC, CASE WHEN due_at=0 THEN 1 ELSE 0 END, due_at ASC, created_at DESC",
                null)) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return result;
    }

    public List<Item> getPendingTasks() {
        List<Item> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,type,title,content,due_at,done,repeat_type,created_at " +
                        "FROM items WHERE type='TASK' AND done=0 AND due_at>0",
                null)) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return result;
    }

    public Item get(long id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,type,title,content,due_at,done,repeat_type,created_at FROM items WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    public void markDone(long id, boolean done) {
        ContentValues values = new ContentValues();
        values.put("done", done ? 1 : 0);
        getWritableDatabase().update("items", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateDue(long id, long dueAt) {
        ContentValues values = new ContentValues();
        values.put("due_at", dueAt);
        getWritableDatabase().update("items", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("items", "id=?", new String[]{String.valueOf(id)});
    }

    private Item read(Cursor cursor) {
        Item item = new Item();
        item.id = cursor.getLong(0);
        item.type = cursor.getString(1);
        item.title = cursor.getString(2);
        item.content = cursor.getString(3);
        item.dueAt = cursor.getLong(4);
        item.done = cursor.getInt(5) != 0;
        item.repeatType = cursor.getString(6);
        item.createdAt = cursor.getLong(7);
        return item;
    }
}
