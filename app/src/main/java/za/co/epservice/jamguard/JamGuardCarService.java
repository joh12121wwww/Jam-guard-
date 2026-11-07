package za.co.epservice.jamguard;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import androidx.car.app.*;
import androidx.car.app.model.*;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import org.json.JSONException;
import org.json.JSONObject;

public final class JamGuardCarService extends CarAppService {
    @Override
    public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        return new HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @Override
    public Session onCreateSession() {
        return new Session() {
            @Override
            public Screen onCreateScreen(Intent intent) {
                return new DetectorScreen(getCarContext());
            }
        };
    }

    private static final class DetectorScreen extends Screen
            implements DefaultLifecycleObserver {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private String displayedStatus = "";
        private final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                if (!readStatus().equals(displayedStatus)) invalidate();
                handler.postDelayed(this, 1000);
            }
        };

        DetectorScreen(CarContext context) {
            super(context);
            getLifecycle().addObserver(this);
        }

        @Override
        public void onStart(LifecycleOwner owner) {
            handler.post(refresh);
        }

        @Override
        public void onStop(LifecycleOwner owner) {
            handler.removeCallbacks(refresh);
        }

        @Override
        public Template onGetTemplate() {
            displayedStatus = readStatus();
            Row row = new Row.Builder()
                    .setTitle("Vehicle detector")
                    .addText(displayedStatus)
                    .build();
            return new PaneTemplate.Builder(new Pane.Builder().addRow(row).build())
                    .setTitle("JamGuard")
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

        private String readStatus() {
            try {
                JSONObject state = new JSONObject(BleService.live);
                if (!state.optBoolean("running")) {
                    return "Monitoring off. Open JamGuard on your phone when parked.";
                }
                if (!state.optBoolean("connected")) {
                    return "Detection unavailable. Detector disconnected.";
                }
                if (!state.optString("fault", "").isEmpty()) {
                    return "Detection unavailable. Detector reports an RF fault.";
                }
                long lastSeen = state.optLong("last_seen");
                long age = System.currentTimeMillis() - lastSeen;
                if (lastSeen <= 0 || age < 0 || age > 15000) {
                    return "Detection unavailable. Waiting for a fresh RF reading.";
                }
                if (state.optBoolean("alarm")) return "Suspected jamming detected";
                if (state.isNull("rssi")) return "Monitoring";
                return "Monitoring · RF " + state.optInt("rssi") + " dBm";
            } catch (JSONException e) {
                return "Detection unavailable";
            }
        }
    }
}
