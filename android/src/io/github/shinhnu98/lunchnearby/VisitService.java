package io.github.shinhnu98.lunchnearby;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;

/** Watches the location while a picked restaurant is waiting to be visited, even with the app in the background. */
public class VisitService extends Service implements LocationListener {
    static volatile boolean running;
    private static final int ONGOING = 1;
    private LocationManager lm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (VisitStore.active(VisitService.this).isEmpty()) stopSelf();
            else h.postDelayed(this, 60_000);
        }
    };

    static void sync(Context c) {
        boolean need = !VisitStore.active(c).isEmpty();
        if (need && !running) {
            try {
                c.startForegroundService(new Intent(c, VisitService.class));
            } catch (RuntimeException ignored) { /* not allowed from the background; retried when the app is opened */
            }
        } else if (!need && running) c.stopService(new Intent(c, VisitService.class));
        else if (need) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.notify(ONGOING, ongoing(c));
        }
    }

    private static boolean ko(Context c) {
        return !"en".equals(VisitStore.lang(c));
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        boolean k = ko(c);
        nm.createNotificationChannel(new NotificationChannel("track", k ? "방문 확인" : "Visit check", NotificationManager.IMPORTANCE_LOW));
        /* Android needs a status notification while checking location in the background; with alerts off it stays folded away */
        nm.createNotificationChannel(new NotificationChannel("track_quiet", k ? "방문 확인 (알림 끔)" : "Visit check (alerts off)", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel("visit", k ? "방문 완료 알림" : "Visit alerts", NotificationManager.IMPORTANCE_HIGH));
    }

    private static PendingIntent open(Context c, int rq) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, rq, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification ongoing(Context c) {
        channels(c);
        boolean k = ko(c);
        List<JSONObject> a = VisitStore.active(c);
        String text = k ? "음식점 방문을 기다리는 중 · 룰렛 결과 " + a.size() + "곳" : "Waiting for your restaurant visit · " + a.size() + (a.size() == 1 ? " pick" : " picks");
        for (JSONObject o : a)
            if (o.optLong("arr") > 0) {
                String n = o.optString("name");
                text = k ? n + "에 도착했어요 · 식사 후 나가면 방문으로 기록돼요" : "You're at " + n + " · leaving logs the visit";
                break;
            }
        return new Notification.Builder(c, VisitStore.notifyOn(c) ? "track" : "track_quiet")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(k ? "점심 방문 확인 중" : "Checking your lunch visit")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open(c, 0))
                .build();
    }

    private void notifyVisit(JSONObject o) {
        if (!VisitStore.notifyOn(this)) return; /* the app asks about it the next time it's opened */
        boolean k = ko(this), ask = o.optInt("q") == 1;
        String n = o.optString("name");
        Notification nt = new Notification.Builder(this, "visit")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(ask ? (k ? n + "에 방문하셨나요?" : "Did you visit " + n + "?") : (k ? n + " 방문 완료!" : "Visited " + n))
                .setContentText(ask ? (k ? "눌러서 방문했는지 알려 주세요." : "Tap to let us know.") : (k ? "맛있게 드셨나요? 눌러서 리뷰를 남겨 보세요." : "How was it? Tap to leave a review."))
                .setAutoCancel(true)
                .setContentIntent(open(this, 2))
                .build();
        getSystemService(NotificationManager.class).notify(10 + (int) (o.optLong("ts") % 100000), nt);
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(ONGOING, ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        lm = getSystemService(LocationManager.class);
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER})
                if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, 15_000, 0, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            stopSelf();
            return;
        }
        h.postDelayed(tick, 60_000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onLocationChanged(Location l) {
        if (l.hasAccuracy() && l.getAccuracy() > 100) return;
        VisitStore.Change ch = VisitStore.onLocation(this, l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 50);
        for (JSONObject o : ch.visited) notifyVisit(o);
        if (!ch.changed) return;
        MainActivity.refresh();
        if (VisitStore.active(this).isEmpty()) stopSelf();
        else getSystemService(NotificationManager.class).notify(ONGOING, ongoing(this));
    }

    /* abstract before API 30 */
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacks(tick);
        if (lm != null) lm.removeUpdates(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
