package za.co.epservice.jamguard;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  private WebView web;
  private Store db;
  private android.content.SharedPreferences p;
  private String pendingAction;
  private boolean destroyed;

  @Override
  public void onCreate(Bundle s) {
    super.onCreate(s);
    getWindow().setStatusBarColor(0xff0a1017);
    getWindow().setNavigationBarColor(0xff0a1017);
    db = new Store(this);
    p = getSharedPreferences("settings", 0);
    web = new WebView(this);
    web.setBackgroundColor(0xff0a1017);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(false);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(false);
    web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
            return true;
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest r) {
            String u = r.getUrl().toString();
            if (!u.startsWith("file:///android_asset/"))
              return new WebResourceResponse(
                  "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            return null;
          }
        });
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  private void message(String t) {
    runOnUiThread(
        () -> {
          if (!destroyed) Toast.makeText(this, t, Toast.LENGTH_LONG).show();
        });
  }

  private boolean bluetoothPermissions() {
    List<String> a = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 31) {
      for (String x :
          new String[] {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
        if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) a.add(x);
    } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) a.add(Manifest.permission.ACCESS_FINE_LOCATION);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) a.add(Manifest.permission.POST_NOTIFICATIONS);
    if (!a.isEmpty()) {
      requestPermissions(a.toArray(new String[0]), 10);
      return false;
    }
    return true;
  }

  private void service(String action, String json) {
    runOnUiThread(
        () -> {
          if (destroyed) return;
          if (!bluetoothPermissions()) {
            pendingAction = action;
            return;
          }
          Intent i = new Intent(this, BleService.class).setAction(action);
          if (json != null) i.putExtra("json", json);
          try {
            startForegroundService(i);
          } catch (Exception e) {
            message("Open the app and grant Bluetooth permissions to start monitoring");
          }
        });
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
    super.onRequestPermissionsResult(request, perms, results);
    if (request == 10) {
      boolean granted = true;
      for (int k = 0; k < perms.length; k++)
        if (!perms[k].equals(Manifest.permission.POST_NOTIFICATIONS)
            && results[k] != PackageManager.PERMISSION_GRANTED) granted = false;
      if (granted && pendingAction != null) {
        String a = pendingAction;
        pendingAction = null;
        service(a, null);
      } else message("Bluetooth permissions are needed to connect");
    }
    if (request == 11) service("LOCATION", null);
  }

  public final class Bridge {
    @JavascriptInterface
    public String snapshot() {
      try {
        JSONObject j = new JSONObject(BleService.live);
        if (j.optLong("last_seen") == 0)
          j.put("last_seen", p.getLong("seen_" + p.getString("address", ""), 0));
        JSONObject settings = new JSONObject();
        for (String key :
            new String[] {"chat", "device_name", "address", "mode", "site_lat", "site_lon"})
          settings.put(key, p.getString(key, key.equals("mode") ? "vehicle" : ""));
        for (String key : new String[] {"telegram", "gps", "auto", "siren", "notify_clear"})
          settings.put(key, p.getBoolean(key, key.equals("siren") || key.equals("notify_clear")));
        settings.put("token_set", Vault.present(MainActivity.this));
        settings.put("devices", new JSONArray(p.getString("devices", "[]")));
        j.put("settings", settings)
            .put("events", db.events())
            .put("queue", db.count("queued") + db.count("retry"))
            .put("failed", db.count("failed"))
            .put("sent", db.count("sent"));
        return j.toString();
      } catch (Exception e) {
        return "{}";
      }
    }

    @JavascriptInterface
    public void action(String a) {
      if (Arrays.asList(
              "CONNECT",
              "SCAN",
              "DISCONNECT",
              "STOP",
              "SILENCE",
              "TEST_SIREN",
              "TEST_TELEGRAM",
              "RETRY",
              "CONFIG")
          .contains(a)) service(a, null);
    }

    @JavascriptInterface
    public String save(String raw) {
      try {
        JSONObject j = new JSONObject(raw);
        String chat = j.optString("chat").trim();
        if (!chat.isEmpty() && !chat.matches("-?[0-9]{1,20}|@[A-Za-z0-9_]{5,32}"))
          return "Enter a numeric Telegram chat ID or @channelname";
        String token = j.optString("token").trim();
        if (!token.isEmpty() && !token.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,150}"))
          return "Bot token format is invalid";
        if (j.optBoolean("telegram")
            && (chat.isEmpty() || token.isEmpty() && !Vault.present(MainActivity.this)))
          return "Add the bot token and chat ID first";
        String lat = j.optString("site_lat").trim(), lon = j.optString("site_lon").trim();
        if (!lat.isEmpty() || !lon.isEmpty()) {
          double a = Double.parseDouble(lat), o = Double.parseDouble(lon);
          if (!Double.isFinite(a) || !Double.isFinite(o) || Math.abs(a) > 90 || Math.abs(o) > 180)
            return "Invalid fixed-site coordinates";
        }
        if (!token.isEmpty()) Vault.save(MainActivity.this, token);
        SharedPreferences.Editor e = p.edit();
        for (String key : new String[] {"chat", "device_name", "mode", "site_lat", "site_lon"})
          e.putString(key, j.optString(key));
        for (String key : new String[] {"telegram", "gps", "auto", "siren", "notify_clear"})
          e.putBoolean(key, j.optBoolean(key));
        JSONArray profiles = new JSONArray(p.getString("devices", "[]"));
        for (int n = 0; n < profiles.length(); n++)
          if (profiles.getJSONObject(n).optString("address").equals(p.getString("address", "")))
            profiles.getJSONObject(n).put("name", j.optString("device_name", "JamGuard"));
        e.putString("devices", profiles.toString());
        if (!e.commit()) return "Could not persist phone settings";
        runOnUiThread(
            () -> {
              if (j.optBoolean("gps")
                  && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                      != PackageManager.PERMISSION_GRANTED)
                requestPermissions(
                    new String[] {
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    11);
              else if (!BleService.live.equals("{}")) service("LOCATION", null);
            });
        return "Saved phone settings";
      } catch (Exception e) {
        return "Could not save. Check coordinates and secret storage.";
      }
    }

    @JavascriptInterface
    public void select(String raw) {
      try {
        JSONObject d = new JSONObject(raw);
        String address = d.getString("address");
        if (!address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return;
        if (new JSONObject(BleService.live).optBoolean("connected")
            && !address.equals(p.getString("address", ""))) {
          message("Disconnect the current detector before selecting another.");
          return;
        }
        JSONArray old = new JSONArray(p.getString("devices", "[]")), next = new JSONArray();
        for (int i = 0; i < old.length(); i++)
          if (!old.getJSONObject(i).optString("address").equals(address))
            next.put(old.getJSONObject(i));
        if (next.length() >= 30) {
          message("Forget a saved detector before adding another (limit 30).");
          return;
        }
        next.put(d);
        p.edit()
            .putString("devices", next.toString())
            .putString("address", address)
            .putString("device_name", d.optString("name", "JamGuard"))
            .commit();
        message("Detector selected. Tap Connect to monitor it.");
      } catch (Exception ignored) {
      }
    }

    @JavascriptInterface
    public void forget(String address) {
      try {
        JSONArray old = new JSONArray(p.getString("devices", "[]")), next = new JSONArray();
        for (int i = 0; i < old.length(); i++)
          if (!old.getJSONObject(i).optString("address").equals(address))
            next.put(old.getJSONObject(i));
        p.edit().putString("devices", next.toString()).commit();
        if (p.getString("address", "").equals(address)) {
          p.edit().remove("address").commit();
          service("DISCONNECT", null);
        }
      } catch (Exception ignored) {
      }
    }

    @JavascriptInterface
    public void command(String raw) {
      try {
        JSONObject j = new JSONObject(raw);
        String cmd = j.optString("cmd");
        if (Arrays.asList("get", "status", "set", "test_telegram").contains(cmd))
          service("DEVICE_COMMAND", j.toString());
      } catch (Exception ignored) {
      }
    }

    @JavascriptInterface
    public void acknowledge(String id, String note) {
      db.acknowledge(id, note);
    }

    @JavascriptInterface
    public void exportCsv() {
      runOnUiThread(
          () -> {
            Intent i =
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType("text/csv")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        "JamGuard_Incidents_" + java.time.LocalDate.now() + ".csv");
            startActivityForResult(i, 100);
          });
    }

    @JavascriptInterface
    public void printReport() {
      runOnUiThread(
          () -> {
            android.print.PrintManager m =
                (android.print.PrintManager) getSystemService(PRINT_SERVICE);
            m.print(
                "JamGuard Incident Report",
                web.createPrintDocumentAdapter("JamGuard Incident Report"),
                null);
          });
    }

    @JavascriptInterface
    public void map(double lat, double lon) {
      if (Double.isFinite(lat)
          && Double.isFinite(lon)
          && Math.abs(lat) <= 90
          && Math.abs(lon) <= 180)
        runOnUiThread(
            () -> {
              try {
                startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon)));
              } catch (Exception e) {
                message("Install a maps application to view coordinates");
              }
            });
    }

    @JavascriptInterface
    public void telegram() {
      runOnUiThread(
          () -> {
            String chat = p.getString("chat", "");
            if (chat.startsWith("@"))
              try {
                startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + chat.substring(1))));
              } catch (Exception ignored) {
              }
            else message("Open Telegram and select your authorised Control Room chat");
          });
    }

    @JavascriptInterface
    public void privacy() {
      runOnUiThread(
          () ->
              new AlertDialog.Builder(MainActivity.this)
                  .setTitle("Local data and Telegram")
                  .setMessage(
                      "Incidents and saved devices stay in this app. Enabling Telegram sends"
                          + " incident details and available coordinates to your configured chat."
                          + " Anyone in that chat can see them. The bot token is encrypted with"
                          + " Android Keystore. No server account, analytics or advertising."
                          + " Uninstall clears local data. Export reports before uninstalling."
                          + " Telegram messages remain in Telegram. GPS collection runs only during"
                          + " an enabled monitoring session. Device health depends on firmware"
                          + " telemetry.")
                  .setPositiveButton("Close", null)
                  .show());
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 100 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      new Thread(
              () -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                  if (out == null) throw new IOException();
                  out.write(db.csv().getBytes(StandardCharsets.UTF_8));
                  message("CSV report saved");
                } catch (Exception e) {
                  message("Could not save CSV report");
                }
              })
          .start();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (p != null
        && p.getBoolean("auto", false)
        && !p.getString("address", "").isEmpty()
        && BleService.live.equals("{}")) service("CONNECT", null);
  }

  @Override
  public void onBackPressed() {
    web.evaluateJavascript("window.goBack && window.goBack()", null);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    web.removeJavascriptInterface("Native");
    web.destroy();
    db.close();
    super.onDestroy();
  }
}
