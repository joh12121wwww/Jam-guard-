package za.co.epservice.jamguard;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.media.*;
import android.os.*;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class BleService extends Service {
  public static volatile String live = "{}";
  private static final String PRIMARY = "6c8e0001-5cd0-4f6b-9d5d-b31c8803a001",
      DATA = "6c8e0002-5cd0-4f6b-9d5d-b31c8803a001",
      RX = "6c8e0003-5cd0-4f6b-9d5d-b31c8803a001",
      TX = "6c8e0004-5cd0-4f6b-9d5d-b31c8803a001";
  private final Handler h = new Handler(Looper.getMainLooper());
  private Store db;
  private SharedPreferences prefs;
  private ScheduledExecutorService network;
  private Telegram telegram;
  private BluetoothGatt gatt;
  private BluetoothLeScanner scanner;
  private BluetoothGattCharacteristic configRx, configTx;
  private BluetoothDevice chosen;
  private boolean wanted,
      scanning,
      connected,
      configReady,
      alarm,
      silenced,
      staleLogged,
      destroyed,
      scanOnly;
  private int retries;
  private Double rf;
  private long seen, connectTime, persistedSeen;
  private String status = "Monitoring stopped",
      fault = "",
      commandStatus = "Not paired for settings";
  private JSONObject reported = new JSONObject(), lastReply = new JSONObject();
  private final LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
  private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
  private MediaPlayer player;
  private LocationManager locations;
  private Location fix;
  private final ArrayDeque<Runnable> operations = new ArrayDeque<>();
  private boolean busy;
  private Runnable opTimeout;
  private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
  private boolean dropFrame;
  private byte[] command;
  private int commandOffset;
  private long commandId, sequence;
  private String commandKind = "";
  private Runnable commandTimeout;

  private static UUID uuid(String s) {
    return UUID.fromString(s);
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    db = new Store(this);
    prefs = getSharedPreferences("settings", 0);
    createChannels();
    foreground(false);
    telegram = new Telegram(this, db);
    network = Executors.newSingleThreadScheduledExecutor();
    network.scheduleWithFixedDelay(
        () -> {
          try {
            telegram.pump();
          } catch (Exception ignored) {
          }
        },
        2,
        5,
        TimeUnit.SECONDS);
    IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(bond, f, Context.RECEIVER_EXPORTED);
    else registerReceiver(bond, f);
    h.post(tick);
  }

  private void createChannels() {
    NotificationManager n = getSystemService(NotificationManager.class);
    n.createNotificationChannel(
        new NotificationChannel(
            "monitor", "JamGuard monitoring", NotificationManager.IMPORTANCE_LOW));
    NotificationChannel a =
        new NotificationChannel("alert", "JamGuard alerts", NotificationManager.IMPORTANCE_HIGH);
    a.setSound(null, null);
    a.enableVibration(true);
    n.createNotificationChannel(a);
  }

  private Notification notification(String text) {
    Intent open = new Intent(this, MainActivity.class);
    Intent mute = new Intent(this, BleService.class).setAction("SILENCE");
    return new Notification.Builder(this, "monitor")
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("JamGuard • 433.92 MHz")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(
            new Notification.Action.Builder(
                    null,
                    "Silence",
                    PendingIntent.getService(
                        this,
                        1,
                        mute,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build())
        .build();
  }

  private void foreground(boolean gps) {
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          1,
          notification(status),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
              | (gps ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0));
    else startForeground(1, notification(status));
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    if (i == null) {
      status = "Session ended by Android — open app to resume";
      stopSelf();
      return START_NOT_STICKY;
    }
    String a = i.getAction();
    try {
      if ("CONNECT".equals(a)) {
        wanted = true;
        scanOnly = false;
        retries = 0;
        closeLink();
        startLocation();
        scan();
      }
      if ("SCAN".equals(a)) {
        scanOnly = true;
        scan();
      }
      if ("DISCONNECT".equals(a)) {
        wanted = false;
        stopScan();
        closeLink();
        stopLocation();
        status = "Disconnected by user";
      }
      if ("STOP".equals(a)) {
        wanted = false;
        stopSelf();
      }
      if ("SILENCE".equals(a)) {
        silenced = true;
        silence();
      }
      if ("TEST_SIREN".equals(a)) {
        sound();
        h.postDelayed(
            () -> {
              if (!alarm) silence();
            },
            3000);
      }
      if ("TEST_TELEGRAM".equals(a))
        event("TEST • phone gateway", null, "Manual Telegram connection test");
      if ("RETRY".equals(a)) db.retry();
      if ("LOCATION".equals(a)) {
        stopLocation();
        startLocation();
      }
      if ("CONFIG".equals(a)) enableConfig();
      if ("DEVICE_COMMAND".equals(a)) {
        JSONObject obj = new JSONObject(i.getStringExtra("json"));
        sendCommand(obj);
      }
    } catch (Exception e) {
      commandStatus = "Action failed: check permissions, Bluetooth and settings";
      toast(commandStatus);
    }
    publish();
    return START_NOT_STICKY;
  }

  private boolean btPermission() {
    return Build.VERSION.SDK_INT < 31
        || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
  }

  private final ScanCallback scanCallback =
      new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
          h.post(
              () -> {
                if (!scanning) return;
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
                boolean primary =
                    r.getScanRecord() != null
                        && r.getScanRecord().getServiceUuids() != null
                        && r.getScanRecord()
                            .getServiceUuids()
                            .contains(new ParcelUuid(uuid(PRIMARY)));
                if ((name == null || !name.toLowerCase(Locale.ROOT).startsWith("jamguard"))
                    && !primary) return;
                try {
                  JSONObject j =
                      new JSONObject()
                          .put("address", d.getAddress())
                          .put("name", name == null ? "JamGuard" : name)
                          .put("ble_rssi", r.getRssi());
                  found.put(d.getAddress(), j);
                  String target = prefs.getString("address", "");
                  if (wanted && !scanOnly && !target.isEmpty() && target.equals(d.getAddress())) {
                    stopScan();
                    connect(d);
                  }
                } catch (Exception ignored) {
                }
                publish();
              });
        }

        @Override
        public void onScanFailed(int code) {
          h.post(
              () -> {
                scanning = false;
                status = "Bluetooth scan failed (" + code + ")";
                retry();
              });
        }
      };

  private void scan() {
    try {
      if (!btPermission()) {
        status = "Grant Bluetooth permission";
        return;
      }
      BluetoothManager m = getSystemService(BluetoothManager.class);
      BluetoothAdapter b = m == null ? null : m.getAdapter();
      if (b == null || !b.isEnabled()) {
        status = "Enable Bluetooth, then connect";
        return;
      }
      stopScan();
      scanner = b.getBluetoothLeScanner();
      if (scanner == null) return;
      found.clear();
      scanner.startScan(
          null,
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          scanCallback);
      scanning = true;
      status = "Scanning nearby detectors";
      h.postDelayed(scanTimeout, 12000);
    } catch (SecurityException denied) {
      scanning = false;
      wanted = false;
      status = "Bluetooth permission revoked — grant permission and reconnect";
    }
  }

  private final Runnable scanTimeout =
      () -> {
        if (scanning) {
          stopScan();
          status =
              wanted
                  ? "Selected detector unavailable — retrying"
                  : "Scan complete — select a detector";
          retry();
        }
      };

  private void stopScan() {
    h.removeCallbacks(scanTimeout);
    if (scanner != null && scanning)
      try {
        scanner.stopScan(scanCallback);
      } catch (SecurityException ignored) {
      }
    scanning = false;
  }

  private void connect(BluetoothDevice d) {
    chosen = d;
    status = "Connecting to selected detector";
    try {
      gatt = d.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
      BluetoothGatt expected = gatt;
      h.postDelayed(
          () -> {
            if (gatt == expected && !connected) {
              closeLink();
              status = "Connection timed out";
              retry();
            }
          },
          20000);
    } catch (SecurityException e) {
      status = "Bluetooth access denied";
    }
  }

  private void retry() {
    if (!wanted || destroyed) return;
    h.removeCallbacks(reconnect);
    h.postDelayed(reconnect, Math.min(60000, 2000L * (1L << Math.min(retries++, 5))));
  }

  private final Runnable reconnect =
      () -> {
        if (wanted && gatt == null && !scanning) {
          scanOnly = false;
          scan();
        }
      };

  private void closeLink() {
    h.removeCallbacks(reconnect);
    operations.clear();
    busy = false;
    if (opTimeout != null) h.removeCallbacks(opTimeout);
    if (commandTimeout != null) h.removeCallbacks(commandTimeout);
    command = null;
    configReady = false;
    configRx = null;
    configTx = null;
    frame.reset();
    dropFrame = false;
    connected = false;
    rf = null;
    seen = 0;
    fault = "";
    reported = new JSONObject();
    lastReply = new JSONObject();
    if (alarm) {
      alarm = false;
      event(
          "MONITORING INTERRUPTED",
          null,
          "Alarm session interrupted; RF clearance was not confirmed");
    }
    silence();
    if (gatt != null) {
      BluetoothGatt old = gatt;
      gatt = null;
      try {
        old.disconnect();
        old.close();
      } catch (SecurityException ignored) {
      }
    }
  }

  private final BluetoothGattCallback callback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int result, int state) {
          h.post(
              () -> {
                if (g != gatt) return;
                if (result == 0 && state == BluetoothProfile.STATE_CONNECTED) {
                  status = "Discovering detector services";
                  try {
                    g.discoverServices();
                  } catch (SecurityException denied) {
                    closeLink();
                    wanted = false;
                    status = "Bluetooth permission revoked";
                  }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || result != 0) {
                  boolean had = connected;
                  closeLink();
                  status = "Detector disconnected — reconnecting";
                  if (had) event("CONNECTION LOST", null, "Bluetooth link lost");
                  retry();
                }
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int result) {
          h.post(
              () -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(uuid(PRIMARY));
                BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(uuid(DATA));
                if (c == null) {
                  s = g.getService(uuid("c63c0001-92b0-4d7a-8ca8-13f57a6d0101"));
                  c =
                      s == null
                          ? null
                          : s.getCharacteristic(uuid("c63c0002-92b0-4d7a-8ca8-13f57a6d0101"));
                }
                if (result != 0 || c == null) {
                  status = "Incompatible detector firmware";
                  closeLink();
                  return;
                }
                BluetoothGattService p = g.getService(uuid(PRIMARY));
                configRx = p == null ? null : p.getCharacteristic(uuid(RX));
                configTx = p == null ? null : p.getCharacteristic(uuid(TX));
                subscribe(c, false);
              });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int statusCode) {
          h.post(
              () -> {
                if (g != gatt) return;
                boolean settings = d.getCharacteristic().getUuid().equals(uuid(TX));
                done();
                if (statusCode != 0) {
                  commandStatus =
                      "Subscription failed ("
                          + statusCode
                          + "). Pair with detector boot PIN and retry.";
                  if (!settings) {
                    status = "Telemetry subscription failed";
                    closeLink();
                    retry();
                  }
                  return;
                }
                if (settings) {
                  configReady = true;
                  commandStatus = "Settings connected";
                  try {
                    sendCommand(new JSONObject().put("cmd", "get"));
                  } catch (Exception ignored) {
                  }
                } else {
                  connected = true;
                  connectTime = System.currentTimeMillis();
                  staleLogged = false;
                  status = "Connected • waiting for RF telemetry";
                  retries = 0;
                }
                publish();
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
          h.post(
              () -> {
                if (g != gatt) return;
                done();
                if (statusCode != 0) {
                  cancelCommand("Device write failed (" + statusCode + ") — pair and retry");
                  return;
                }
                if (command != null) {
                  commandOffset = Math.min(command.length, commandOffset + 20);
                  if (commandOffset < command.length) chunk();
                  else commandStatus = "Awaiting detector acknowledgement";
                }
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
          byte[] copy = value.clone();
          h.post(() -> received(g, c, copy));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
          byte[] v = c.getValue();
          if (v != null) {
            byte[] copy = v.clone();
            h.post(() -> received(g, c, copy));
          }
        }
      };

  private void queue(Runnable r) {
    operations.add(r);
    next();
  }

  private void next() {
    if (busy || operations.isEmpty() || gatt == null) return;
    busy = true;
    opTimeout =
        () -> {
          status = "Bluetooth operation timed out";
          closeLink();
          retry();
        };
    h.postDelayed(opTimeout, 12000);
    try {
      operations.remove().run();
    } catch (Exception e) {
      done();
      commandStatus = "Bluetooth operation rejected";
    }
  }

  private void done() {
    if (opTimeout != null) h.removeCallbacks(opTimeout);
    busy = false;
    next();
  }

  private void subscribe(BluetoothGattCharacteristic c, boolean settings) {
    queue(
        () -> {
          try {
            if (gatt == null) return;
            BluetoothGattDescriptor d =
                c.getDescriptor(uuid("00002902-0000-1000-8000-00805f9b34fb"));
            if (d == null || !gatt.setCharacteristicNotification(c, true)) {
              done();
              commandStatus = "Notification channel unavailable";
              return;
            }
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33)
              ok =
                  gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                      == BluetoothStatusCodes.SUCCESS;
            else {
              d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
              ok = gatt.writeDescriptor(d);
            }
            if (!ok) {
              done();
              commandStatus = "Subscription could not start";
            }
          } catch (SecurityException denied) {
            done();
            commandStatus = "Bluetooth permission revoked";
          }
        });
  }

  private void enableConfig() {
    try {
      if (gatt == null || !connected || configRx == null || configTx == null) {
        commandStatus = "This firmware has no configuration channel";
        return;
      }
      if (configReady) {
        try {
          sendCommand(new JSONObject().put("cmd", "get"));
        } catch (Exception ignored) {
        }
        return;
      }
      if (chosen.getBondState() != BluetoothDevice.BOND_BONDED) {
        commandStatus = "Enter the six-digit detector boot PIN in Android pairing";
        chosen.createBond();
      } else subscribe(configTx, true);
    } catch (SecurityException denied) {
      commandStatus = "Bluetooth permission revoked — reconnect after granting access";
    }
  }

  private final BroadcastReceiver bond =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(i.getAction())) return;
          BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
          if (chosen == null || d == null || !d.getAddress().equals(chosen.getAddress())) return;
          if (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) == BluetoothDevice.BOND_BONDED
              && d.getBondState() == BluetoothDevice.BOND_BONDED)
            h.post(
                () -> {
                  if (gatt != null && configTx != null) subscribe(configTx, true);
                });
        }
      };

  private void cancelCommand(String message) {
    if (commandTimeout != null) h.removeCallbacks(commandTimeout);
    command = null;
    commandStatus = message;
  }

  private void sendCommand(JSONObject j) throws Exception {
    if (!configReady || configRx == null) {
      commandStatus = "Pair / load detector settings first";
      return;
    }
    if (command != null) {
      commandStatus = "Wait for the current detector command";
      return;
    }
    String kind = j.optString("cmd");
    if (!Arrays.asList("get", "status", "set", "test_telegram").contains(kind))
      throw new Exception();
    commandId = ++sequence;
    j.put("id", commandId);
    byte[] data = (j.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    if (data.length > 768) {
      commandStatus = "Settings exceed detector frame limit";
      return;
    }
    command = data;
    commandOffset = 0;
    commandKind = kind;
    commandStatus = "Sending " + kind + " to detector";
    frame.reset();
    commandTimeout =
        () -> cancelCommand("No detector acknowledgement. Read settings before retrying.");
    h.postDelayed(commandTimeout, 15000);
    chunk();
  }

  private void chunk() {
    if (command == null) return;
    byte[] part =
        Arrays.copyOfRange(command, commandOffset, Math.min(command.length, commandOffset + 20));
    queue(
        () -> {
          try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33)
              ok =
                  gatt.writeCharacteristic(
                          configRx, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                      == BluetoothStatusCodes.SUCCESS;
            else {
              configRx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
              configRx.setValue(part);
              ok = gatt.writeCharacteristic(configRx);
            }
            if (!ok) {
              done();
              cancelCommand("Detector write could not start");
            }
          } catch (SecurityException denied) {
            done();
            cancelCommand("Bluetooth permission revoked");
          }
        });
  }

  private void received(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] bytes) {
    if (g != gatt) return;
    if (c.getUuid().equals(uuid(TX))) {
      for (byte b : bytes) {
        if (b == 10) {
          if (!dropFrame && frame.size() > 0)
            reply(new String(frame.toByteArray(), StandardCharsets.UTF_8));
          frame.reset();
          dropFrame = false;
        } else if (b != 13 && !dropFrame) {
          if (frame.size() >= 16384) {
            frame.reset();
            dropFrame = true;
          } else frame.write(b);
        }
      }
      return;
    }
    for (String line : new String(bytes, StandardCharsets.UTF_8).split("[\\r\\n]+")) {
      Protocol.Reading r = Protocol.parse(line);
      if (r == null) continue;
      seen = System.currentTimeMillis();
      staleLogged = false;
      if (r.fault != null) {
        boolean changed = !r.fault.equals(fault);
        fault = r.fault;
        rf = null;
        status = "Detector RF fault • " + fault;
        if (changed && !alarm)
          event("RADIO FAULT", null, "Receiver reports a fault; RF detection unavailable");
        if (alarm) {
          alarm = false;
          silence();
          event("RADIO FAULT", null, "Alarm interrupted — RF clearance not confirmed");
        }
        continue;
      }
      fault = "";
      if (r.rssi != null) {
        rf = r.rssi;
        try {
          samples.add(new JSONObject().put("time", seen).put("rssi", rf));
          while (samples.size() > 240) samples.remove();
        } catch (Exception ignored) {
        }
      }
      if (r.alarm != null) {
        if (r.alarm && !alarm) {
          alarm = true;
          silenced = false;
          event("SUSPECTED JAMMING", rf, "Check that the vehicle is locked");
          if (prefs.getBoolean("siren", true)) sound();
          alert();
        } else if (!r.alarm && alarm) {
          alarm = false;
          silence();
          if (prefs.getBoolean("notify_clear", true)) event("RF ACTIVITY CLEARED", rf, "");
        }
      }
      status = alarm ? "Suspected jamming detected" : "Monitoring • RF reporting";
    }
    publish();
  }

  private void reply(String raw) {
    try {
      JSONObject j = new JSONObject(raw);
      if (command == null || !j.has("id") || j.optLong("id", -1) != commandId) return;
      String kind = commandKind;
      cancelCommand(
          j.optBoolean("ok")
              ? "Detector acknowledged " + kind
              : "Detector rejected: " + j.optString("error", "unknown"));
      lastReply = j;
      if (j.optBoolean("ok")) {
        if (!kind.equals("test_telegram")) reported = j;
        if (kind.equals("set")) {
          commandStatus = "Saved — verifying with detector";
          h.post(
              () -> {
                try {
                  sendCommand(new JSONObject().put("cmd", "get"));
                } catch (Exception ignored) {
                }
              });
        }
      }
    } catch (Exception ignored) {
      commandStatus = "Invalid configuration reply";
    }
    publish();
  }

  private JSONObject location() {
    try {
      if (prefs.getString("mode", "vehicle").equals("fixed")) {
        String a = prefs.getString("site_lat", ""), o = prefs.getString("site_lon", "");
        if (!a.isEmpty() && !o.isEmpty())
          return new JSONObject()
              .put("lat", Double.parseDouble(a))
              .put("lon", Double.parseDouble(o))
              .put("source", "configured fixed site")
              .put("age_seconds", 0);
      }
      if (fix != null) {
        long age =
            Math.max(
                0,
                (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1000000000L);
        if (age <= 120)
          return new JSONObject()
              .put("lat", fix.getLatitude())
              .put("lon", fix.getLongitude())
              .put("accuracy_m", fix.getAccuracy())
              .put("source", "phone GPS/network")
              .put("age_seconds", age);
      }
    } catch (Exception ignored) {
    }
    return new JSONObject();
  }

  private void event(String kind, Double r, String note) {
    db.event(
        prefs.getString("device_name", "JamGuard"),
        kind,
        r,
        location(),
        note,
        prefs.getBoolean("telegram", false));
  }

  private final LocationListener listener =
      new LocationListener() {
        public void onLocationChanged(Location l) {
          fix = l;
        }

        public void onProviderEnabled(String p) {}

        public void onProviderDisabled(String p) {}

        public void onStatusChanged(String p, int s, Bundle b) {}
      };

  private void startLocation() {
    if (!prefs.getBoolean("gps", false) || prefs.getString("mode", "vehicle").equals("fixed"))
      return;
    if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return;
    try {
      foreground(true);
      locations = getSystemService(LocationManager.class);
      for (String provider :
          new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER})
        try {
          if (locations.isProviderEnabled(provider))
            locations.requestLocationUpdates(provider, 15000, 10, listener, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
    } catch (Exception ignored) {
      commandStatus = "GPS unavailable — incidents will say location unavailable";
    }
  }

  private void stopLocation() {
    if (locations != null) locations.removeUpdates(listener);
    fix = null;
  }

  private final Runnable stopSound =
      () -> {
        silence();
        if (alarm) silenced = true;
      };

  private void sound() {
    silence();
    try {
      player =
          MediaPlayer.create(
              this,
              R.raw.siren,
              new AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ALARM)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build(),
              0);
      if (player != null) {
        player.setLooping(true);
        player.start();
      }
      Vibrator v = getSystemService(Vibrator.class);
      if (v != null && v.hasVibrator())
        v.vibrate(VibrationEffect.createWaveform(new long[] {0, 500, 250, 500, 250}, 0));
    } catch (Exception ignored) {
    }
    h.postDelayed(stopSound, 60000);
  }

  private void silence() {
    h.removeCallbacks(stopSound);
    if (player != null) {
      try {
        player.stop();
      } catch (Exception ignored) {
      }
      player.release();
      player = null;
    }
    Vibrator v = getSystemService(Vibrator.class);
    if (v != null) v.cancel();
  }

  private void alert() {
    Intent mute = new Intent(this, BleService.class).setAction("SILENCE");
    Notification n =
        new Notification.Builder(this, "alert")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("JamGuard • suspected jamming")
            .setContentText("433.92 MHz interference. Check vehicle locking.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                new Notification.Action.Builder(
                        null,
                        "Silence phone",
                        PendingIntent.getService(
                            this,
                            1,
                            mute,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build())
            .build();
    getSystemService(NotificationManager.class).notify(2, n);
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (destroyed) return;
          long now = System.currentTimeMillis();
          if (connected && now - (seen == 0 ? connectTime : seen) > 15000 && !staleLogged) {
            staleLogged = true;
            fault = "STALE";
            rf = null;
            status = "No fresh RF telemetry • detection unavailable";
            event("TELEMETRY LOST", null, "No RF update for 15 seconds");
          }
          publish();
          h.postDelayed(this, 1000);
        }
      };

  private void publish() {
    try {
      if (seen - persistedSeen >= 1000) {
        persistedSeen = seen;
        prefs.edit().putLong("seen_" + prefs.getString("address", ""), seen).apply();
      }
      JSONObject j =
          new JSONObject()
              .put("running", true)
              .put("status", status)
              .put("connected", connected)
              .put("scanning", scanning)
              .put("alarm", alarm)
              .put("silenced", silenced)
              .put("rssi", rf == null ? JSONObject.NULL : rf)
              .put("last_seen", seen)
              .put("fault", fault)
              .put("config_ready", configReady)
              .put("command_status", commandStatus)
              .put("reported", reported)
              .put("last_reply", lastReply)
              .put("location", location())
              .put("found", new JSONArray(found.values()))
              .put("samples", new JSONArray(samples));
      live = j.toString();
      getSystemService(NotificationManager.class).notify(1, notification(status));
    } catch (Exception ignored) {
    }
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    wanted = false;
    h.removeCallbacksAndMessages(null);
    stopScan();
    closeLink();
    stopLocation();
    unregisterReceiver(bond);
    if (network != null) {
      network.shutdownNow();
      ScheduledExecutorService executor = network;
      new Thread(
              () -> {
                try {
                  if (executor.awaitTermination(30, TimeUnit.SECONDS)) db.close();
                } catch (InterruptedException ignored) {
                }
              },
              "JamGuardClose")
          .start();
    }
    live = "{}";
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
