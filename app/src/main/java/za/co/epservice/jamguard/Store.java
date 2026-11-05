package za.co.epservice.jamguard;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

public final class Store extends SQLiteOpenHelper {
  private final Context context;

  public Store(Context c) {
    super(c, "jamguard-v2.db", null, 1);
    context = c.getApplicationContext();
  }

  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE events(id TEXT PRIMARY KEY,time INTEGER,device TEXT,kind TEXT,rssi"
            + " REAL,location TEXT,note TEXT,ack INTEGER DEFAULT 0,delivery TEXT,attempts INTEGER"
            + " DEFAULT 0,next INTEGER DEFAULT 0,message_id INTEGER,chat TEXT,bot_id TEXT)");
    d.execSQL("CREATE INDEX event_time ON events(time DESC)");
  }

  public void onUpgrade(SQLiteDatabase d, int a, int b) {}

  public synchronized String event(
      String device, String kind, Double rssi, JSONObject location, String note, boolean telegram) {
    ContentValues v = new ContentValues();
    String id = UUID.randomUUID().toString();
    v.put("id", id);
    v.put("time", System.currentTimeMillis());
    v.put("device", device);
    v.put("kind", kind);
    if (rssi != null) v.put("rssi", rssi);
    v.put("location", location == null ? "{}" : location.toString());
    v.put("note", note);
    v.put("delivery", telegram ? "queued" : "local");
    v.put("chat", context.getSharedPreferences("settings", 0).getString("chat", ""));
    try {
      String token = Vault.get(context);
      v.put("bot_id", token.contains(":") ? token.substring(0, token.indexOf(":")) : "");
    } catch (Exception ignored) {
    }
    getWritableDatabase().insertOrThrow("events", null, v);
    return id;
  }

  private JSONArray query(String where, String[] args, String limit) {
    JSONArray a = new JSONArray();
    try (Cursor c =
        getReadableDatabase().query("events", null, where, args, null, null, "time DESC", limit)) {
      while (c.moveToNext()) {
        JSONObject j = new JSONObject();
        for (String n : c.getColumnNames()) {
          int i = c.getColumnIndexOrThrow(n);
          try {
            if (c.isNull(i)) j.put(n, JSONObject.NULL);
            else if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) j.put(n, c.getLong(i));
            else if (c.getType(i) == Cursor.FIELD_TYPE_FLOAT) j.put(n, c.getDouble(i));
            else j.put(n, c.getString(i));
          } catch (JSONException ignored) {
          }
        }
        a.put(j);
      }
    }
    return a;
  }

  public synchronized JSONArray events() {
    return query(null, null, "1000");
  }

  public synchronized JSONArray pending() {
    return query(
        "id=(SELECT id FROM events WHERE delivery IN ('queued','retry') AND next<=? ORDER BY time"
            + " ASC LIMIT 1)",
        new String[] {"" + System.currentTimeMillis()},
        "1");
  }

  public synchronized void delivered(String id, long messageId) {
    ContentValues v = new ContentValues();
    v.put("delivery", "sent");
    v.put("message_id", messageId);
    getWritableDatabase().update("events", v, "id=?", new String[] {id});
  }

  public synchronized void failed(String id, int attempt, long wait, boolean permanent) {
    ContentValues v = new ContentValues();
    v.put("delivery", permanent ? "failed" : "retry");
    v.put("attempts", attempt);
    v.put("next", System.currentTimeMillis() + wait);
    getWritableDatabase().update("events", v, "id=?", new String[] {id});
  }

  public synchronized void acknowledge(String id, String note) {
    ContentValues v = new ContentValues();
    v.put("ack", 1);
    v.put("note", note.substring(0, Math.min(note.length(), 1000)));
    getWritableDatabase().update("events", v, "id=?", new String[] {id});
  }

  public synchronized void retry() {
    getWritableDatabase()
        .execSQL("UPDATE events SET delivery='queued',next=0 WHERE delivery IN ('failed','retry')");
  }

  public synchronized long count(String state) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT count(*) FROM events WHERE delivery=?", new String[] {state})) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }

  public synchronized String csv() {
    StringBuilder b =
        new StringBuilder(
            "id,time_utc,device,type,rssi_dbm,location,acknowledged,note,telegram,message_id\r\n");
    JSONArray a = query(null, null, null);
    for (int i = 0; i < a.length(); i++) {
      JSONObject e = a.optJSONObject(i);
      String[] cols = {
        e.optString("id"),
        java.time.Instant.ofEpochMilli(e.optLong("time")).toString(),
        e.optString("device"),
        e.optString("kind"),
        e.isNull("rssi") ? "" : e.optString("rssi"),
        e.optString("location"),
        e.optString("ack"),
        e.optString("note"),
        e.optString("delivery"),
        e.isNull("message_id") ? "" : e.optString("message_id")
      };
      for (int k = 0; k < cols.length; k++) {
        if (k > 0) b.append(',');
        String s = cols[k];
        if (s.matches("^[=+@\\t\\r].*") || s.startsWith("-")) s = "'" + s;
        b.append('"').append(s.replace("\"", "\"\"")).append('"');
      }
      b.append("\r\n");
    }
    return b.toString();
  }
}
