package za.co.epservice.jamguard;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BleService extends Service {
 public static final String UPDATE="za.co.epservice.jamguard.UPDATE";
 public static final String SERVICE_UUID="c63c0001-92b0-4d7a-8ca8-13f57a6d0101";
 public static final String DATA_UUID="c63c0002-92b0-4d7a-8ca8-13f57a6d0101";
 private static final String CH_MON="jamguard_monitor",CH_ALERT="jamguard_alert";
 private BluetoothGatt gatt; private BluetoothLeScanner scanner; private MediaPlayer player;
 private final Handler handler=new Handler(Looper.getMainLooper()); private boolean scanning=false, wanted=false;
 private BluetoothDevice lastDevice; private int reconnectAttempt=0;
 private final UUID serviceUuid=UUID.fromString(SERVICE_UUID),dataUuid=UUID.fromString(DATA_UUID);
 private final UUID cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
 private final ScanCallback scanCallback=new ScanCallback(){
  @Override public void onScanResult(int callbackType,ScanResult result){
   BluetoothDevice device=result.getDevice();String name=result.getScanRecord()!=null?result.getScanRecord().getDeviceName():null;
   if(name!=null && name.startsWith("JamGuard")){stopScan();lastDevice=device;connect(device);}
  }
  @Override public void onScanFailed(int errorCode){send("status","Scan failed: "+errorCode);scheduleReconnect();}
 };
 @Override public void onCreate(){super.onCreate();createChannels();startForeground(1,monitorNotification("Waiting for connection"));}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null){String action=intent.getAction();
   if("CONNECT".equals(action)){wanted=true;reconnectAttempt=0;startScan();}
   if("SILENCE".equals(action))silence();
   if("DISCONNECT".equals(action)){wanted=false;stopScan();if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}silence();send("status","Disconnected");}
  }return START_STICKY;
 }
 private void createChannels(){NotificationManager nm=getSystemService(NotificationManager.class);
  NotificationChannel mon=new NotificationChannel(CH_MON,"JamGuard connection",NotificationManager.IMPORTANCE_LOW);nm.createNotificationChannel(mon);
  NotificationChannel alert=new NotificationChannel(CH_ALERT,"JamGuard alarms",NotificationManager.IMPORTANCE_HIGH);
  alert.enableVibration(true);alert.setSound(null,null);nm.createNotificationChannel(alert);
 }
 private Notification monitorNotification(String text){Intent i=new Intent(this,MainActivity.class);i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
  return new Notification.Builder(this,CH_MON).setContentTitle("JamGuard monitoring").setContentText(text).setSmallIcon(android.R.drawable.ic_lock_lock)
   .setContentIntent(PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).setOngoing(true).build();}
 private boolean permissions(){return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);}
 private void startScan(){if(!wanted||!permissions())return;BluetoothManager bm=getSystemService(BluetoothManager.class);BluetoothAdapter adapter=bm.getAdapter();
  if(adapter==null||!adapter.isEnabled()){send("status","Enable Bluetooth");return;}
  stopScan();scanner=adapter.getBluetoothLeScanner();if(scanner==null)return;
  try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);scanning=true;send("status","Searching for JamGuard...");
   handler.postDelayed(()->{if(scanning){stopScan();send("status","Detector not found");scheduleReconnect();}},10000);
  }catch(SecurityException ex){send("status","Bluetooth permission required");}
 }
 private void stopScan(){if(scanning&&scanner!=null){try{scanner.stopScan(scanCallback);}catch(SecurityException ignored){}scanning=false;}}
 private void connect(BluetoothDevice device){if(!wanted||!permissions())return;send("status","Connecting...");
  try{if(gatt!=null){gatt.close();gatt=null;}gatt=device.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);}catch(SecurityException ex){send("status","Connection permission required");}
 }
 private final BluetoothGattCallback callback=new BluetoothGattCallback(){
  @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){
   if(state==BluetoothProfile.STATE_CONNECTED){reconnectAttempt=0;send("status","Connected — discovering services");try{g.discoverServices();}catch(SecurityException ignored){}}
   else if(state==BluetoothProfile.STATE_DISCONNECTED){send("status","Disconnected");g.close();if(gatt==g)gatt=null;scheduleReconnect();}
  }
  @Override public void onServicesDiscovered(BluetoothGatt g,int status){
   if(status!=BluetoothGatt.GATT_SUCCESS){send("status","Service discovery failed");return;}
   BluetoothGattService service=g.getService(serviceUuid);BluetoothGattCharacteristic c=service==null?null:service.getCharacteristic(dataUuid);
   if(c==null){send("status","Incompatible JamGuard firmware");g.disconnect();return;}
   try{g.setCharacteristicNotification(c,true);BluetoothGattDescriptor d=c.getDescriptor(cccd);
    if(d!=null){if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
      else{d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);g.writeDescriptor(d);}}
    send("status","Connected to JamGuard");getSystemService(NotificationManager.class).notify(1,monitorNotification("Connected to detector"));
   }catch(SecurityException ignored){}
  }
  @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){parse(new String(value,StandardCharsets.UTF_8));}
  @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){parse(new String(c.getValue(),StandardCharsets.UTF_8));}
 };
 // Protocol: RSSI,-94 or ALARM,-48 or CLEAR,-91. One complete message per BLE notification.
 private void parse(String msg){String[] fields=msg.trim().split(",");if(fields.length<2)return;
  try{int rssi=Integer.parseInt(fields[1].trim());send("rssi",String.valueOf(rssi));
   if(fields[0].equals("ALARM")){saveAlarm(rssi);soundAlarm(rssi);send("alarm","Suspected RF interference: "+rssi+" dBm");}
   else if(fields[0].equals("CLEAR"))send("state","Normal");
  }catch(NumberFormatException ignored){}
 }
 private void saveAlarm(int rssi){android.content.SharedPreferences prefs=getSharedPreferences("events",MODE_PRIVATE);
  String old=prefs.getString("history","");String entry=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date())+" | RF alarm | "+rssi+" dBm";
  prefs.edit().putString("history",entry+"\n"+old.substring(0,Math.min(old.length(),3500))).apply();}
 private void soundAlarm(int rssi){handler.post(()->{
  silence();player=MediaPlayer.create(this,R.raw.siren);if(player!=null){player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());player.setLooping(true);player.start();}
  android.os.Vibrator vib=(android.os.Vibrator)getSystemService(VIBRATOR_SERVICE);
  if(vib!=null&&vib.hasVibrator())vib.vibrate(VibrationEffect.createWaveform(new long[]{0,600,200,600,200},0));
  Intent open=new Intent(this,MainActivity.class);open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  Intent quiet=new Intent(this,BleService.class);quiet.setAction("SILENCE");
  Notification n=new Notification.Builder(this,CH_ALERT).setSmallIcon(android.R.drawable.ic_dialog_alert)
   .setContentTitle("JAMGUARD — RF ALERT").setContentText("Suspected interference: "+rssi+" dBm")
   .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
   .setContentIntent(PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
   .addAction(new Notification.Action.Builder(null,"Silence siren",PendingIntent.getService(this,2,quiet,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build())
   .setAutoCancel(false).build();getSystemService(NotificationManager.class).notify(2,n);
 });}
 private void silence(){if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}
  android.os.Vibrator v=(android.os.Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null)v.cancel();getSystemService(NotificationManager.class).cancel(2);}
 private void scheduleReconnect(){if(!wanted)return;long delay=Math.min(30000,2000L*(1L<<Math.min(reconnectAttempt++,4)));
  handler.postDelayed(()->{if(wanted&&gatt==null)startScan();},delay);}
 private void send(String key,String value){Intent i=new Intent(UPDATE);i.setPackage(getPackageName());i.putExtra("key",key);i.putExtra("value",value);sendBroadcast(i);}
 @Override public void onDestroy(){wanted=false;stopScan();if(gatt!=null){gatt.close();gatt=null;}silence();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
