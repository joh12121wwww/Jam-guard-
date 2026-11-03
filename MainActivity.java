package za.co.epservice.jamguard;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
 private final int GOLD=Color.rgb(245,191,52),BLACK=Color.rgb(9,9,9),PANEL=Color.rgb(25,25,25);
 private TextView status,rssi,alarm,history;private BroadcastReceiver receiver;
 private GradientDrawable bg(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(22);d.setStroke(2,stroke);return d;}
 private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextColor(color);v.setTextSize(size);v.setPadding(8,10,8,10);return v;}
 private void add(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(-1,-2));}
 private void action(String cmd){Intent i=new Intent(this,BleService.class);i.setAction(cmd);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BLACK);getWindow().setNavigationBarColor(BLACK);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BLACK);
  LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(24,24,24,24);scroll.addView(page);setContentView(scroll);
  ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.eps_logo);logo.setAdjustViewBounds(true);
  logo.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,190);page.addView(logo,lp);
  TextView title=label("JAMGUARD",30,GOLD);title.setGravity(Gravity.CENTER);title.setTypeface(null,Typeface.BOLD);add(page,title);
  TextView brand=label("BY EPS GLOBAL TRACK",13,Color.LTGRAY);brand.setGravity(Gravity.CENTER);add(page,brand);
  LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(18,18,18,18);panel.setBackground(bg(PANEL,GOLD));
  LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=24;page.addView(panel,pp);
  add(panel,label("BLUETOOTH STATUS",13,GOLD));status=label("Not connected",17,Color.WHITE);add(panel,status);
  add(panel,label("LIVE RF SIGNAL — 433.92 MHz",13,GOLD));rssi=label("-- dBm",34,Color.WHITE);rssi.setTypeface(null,Typeface.BOLD);add(panel,rssi);
  add(panel,label("Threshold: -110 dBm  |  Delay: 1 second",12,Color.LTGRAY));
  Button connect=new Button(this);connect.setText("CONNECT TO JAMGUARD");connect.setTextColor(Color.BLACK);connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));add(page,connect);connect.setOnClickListener(v->{if(checkPermissions())action("CONNECT");});
  Button disconnect=new Button(this);disconnect.setText("DISCONNECT");add(page,disconnect);disconnect.setOnClickListener(v->action("DISCONNECT"));
  alarm=label("SYSTEM READY — WAITING FOR DETECTOR",17,GOLD);alarm.setBackground(bg(PANEL,GOLD));add(page,alarm);
  Button silence=new Button(this);silence.setText("SILENCE PHONE SIREN");silence.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(180,40,40)));add(page,silence);silence.setOnClickListener(v->{action("SILENCE");alarm.setText("Siren silenced — alarm saved");});
  add(page,label("ALARM HISTORY",18,GOLD));history=label("No recorded alarms",13,Color.LTGRAY);add(page,history);refreshHistory();
  receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String key=i.getStringExtra("key"),value=i.getStringExtra("value");if(key==null||value==null)return;
   if(key.equals("status"))status.setText(value);if(key.equals("rssi"))rssi.setText(value+" dBm");
   if(key.equals("alarm")){alarm.setText("⚠ JAMMING WARNING\n"+value);alarm.setTextColor(Color.RED);refreshHistory();}
   if(key.equals("state")){alarm.setText("SYSTEM NORMAL");alarm.setTextColor(GOLD);}
  }};
  IntentFilter filter=new IntentFilter(BleService.UPDATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,filter);
  checkPermissions();
 }
 private void refreshHistory(){String h=getSharedPreferences("events",MODE_PRIVATE).getString("history","");history.setText(h.isEmpty()?"No recorded alarms":h);}
 private boolean checkPermissions(){java.util.ArrayList<String> missing=new java.util.ArrayList<>();
  if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_SCAN);
   if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_CONNECT);
  }else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
  if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),100);return false;}return true;
 }
 @Override public void onDestroy(){if(receiver!=null)unregisterReceiver(receiver);super.onDestroy();}
}
