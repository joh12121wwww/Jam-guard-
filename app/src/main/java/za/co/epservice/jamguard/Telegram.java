package za.co.epservice.jamguard;

import android.content.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Single consumer; queue is durable. No inbound polling or device control via Telegram. */
public final class Telegram {
  private final Context context;
  private final Store db;

  public Telegram(Context c, Store d) {
    context = c;
    db = d;
  }

  public void pump() {
    SharedPreferences p = context.getSharedPreferences("settings", 0);
    if (!p.getBoolean("telegram", false)) return;
    JSONArray rows = db.pending();
    if (rows.length() == 0) return;
    JSONObject e = rows.optJSONObject(0);
    int attempt = e.optInt("attempts") + 1;
    try {
      String token = Vault.get(context), chat = e.optString("chat", "");
      if (token.isEmpty() || chat.isEmpty()) return;
      if (!token.startsWith(e.optString("bot_id") + ":")) {
        db.failed(e.optString("id"), attempt, 0, true);
        return;
      }
      JSONObject body =
          new JSONObject()
              .put("chat_id", chat)
              .put("text", message(e))
              .put("link_preview_options", new JSONObject().put("is_disabled", true));
      HttpURLConnection c =
          (HttpURLConnection)
              new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
      try {
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
          o.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null)
          try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = input.read(buf)) != -1 && out.size() < 65536) out.write(buf, 0, n);
            text = out.toString("UTF-8");
          }
        JSONObject r = new JSONObject(text);
        if (code == 200 && r.optBoolean("ok")) {
          db.delivered(e.optString("id"), r.getJSONObject("result").getLong("message_id"));
          return;
        }
        long wait =
            Math.max(
                backoff(attempt),
                1000L
                    * (r.optJSONObject("parameters") == null
                            ? new JSONObject()
                            : r.optJSONObject("parameters"))
                        .optLong("retry_after", 0));
        db.failed(e.optString("id"), attempt, wait, code >= 400 && code < 500 && code != 429);
      } finally {
        c.disconnect();
      }
    } catch (Exception ignored) {
      db.failed(e.optString("id"), attempt, backoff(attempt), false);
    }
  }

  private static long backoff(int a) {
    return Math.min(3600000L, 5000L * (1L << Math.min(a, 9)));
  }

  public static String message(JSONObject e) throws JSONException {
    StringBuilder s = new StringBuilder("JAMGUARD | EPS GLOBAL TRAC\n");
    s.append(e.optString("kind"))
        .append("\nDevice: ")
        .append(e.optString("device"))
        .append("\nTime (UTC): ")
        .append(java.time.Instant.ofEpochMilli(e.optLong("time")))
        .append("\nIncident: ")
        .append(e.optString("id"));
    if (!e.isNull("rssi"))
      s.append("\nRF: ").append(e.optString("rssi")).append(" dBm @ 433.92 MHz");
    JSONObject loc = new JSONObject(e.optString("location", "{}"));
    if (loc.has("lat"))
      s.append("\nLocation: https://maps.google.com/?q=")
          .append(loc.getDouble("lat"))
          .append(',')
          .append(loc.getDouble("lon"))
          .append("\nLocation source: ")
          .append(loc.optString("source"))
          .append("; age at incident: ")
          .append(loc.optLong("age_seconds"))
          .append("s; accuracy: ")
          .append(loc.optString("accuracy_m", "unknown"))
          .append("m");
    else s.append("\nLocation unavailable");
    if (!e.optString("note").isEmpty()) s.append("\nNote: ").append(e.optString("note"));
    return s.substring(0, Math.min(4000, s.length()));
  }
}
