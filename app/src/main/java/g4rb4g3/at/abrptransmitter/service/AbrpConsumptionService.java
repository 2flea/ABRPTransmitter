package g4rb4g3.at.abrptransmitter.service;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;

import com.github.mikephil.charting.data.Entry;
import com.lge.ivi.greencar.GreenCarManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import g4rb4g3.at.abrptransmitter.Location;
import g4rb4g3.at.abrptransmitter.R;
import g4rb4g3.at.abrptransmitter.abrp.IRoutePlan;
import g4rb4g3.at.abrptransmitter.abrp.RoutePlan;
import g4rb4g3.at.abrptransmitter.gson.abrp.GsonRoutePlan;
import g4rb4g3.at.abrptransmitter.gson.abrp.PathIndices;
import g4rb4g3.at.abrptransmitter.gson.abrp.Route;
import g4rb4g3.at.abrptransmitter.gson.abrp.Step;

import static g4rb4g3.at.abrptransmitter.Constants.ACTION_GPS_CHANGED;
import static g4rb4g3.at.abrptransmitter.Constants.EXTRA_ALT;
import static g4rb4g3.at.abrptransmitter.Constants.EXTRA_LAT;
import static g4rb4g3.at.abrptransmitter.Constants.EXTRA_LON;
import static g4rb4g3.at.abrptransmitter.Constants.NOTIFICATION_ID_ABRPCONSUMPTIONSERVICE;
import static g4rb4g3.at.abrptransmitter.Constants.PREFERENCES_NAME;
import static g4rb4g3.at.abrptransmitter.Constants.PREFERENCES_TOKEN;

public class AbrpConsumptionService extends Service implements IRoutePlan {
  private static final ArrayList<Location> DEMO_COORDINATES = new ArrayList<>();
  private static final Logger sLog = LoggerFactory.getLogger(AbrpConsumptionService.class.getSimpleName());

  static {
    //add your gps simulation here
    DEMO_COORDINATES.add(new Location(0f, 0f, 0f));
  }

  private final IBinder mBinder = new AbrpConsumptionBinder();
  ScheduledFuture<?> mScheduledFuture = null;
  private SharedPreferences mSharedPreferences;
  private ArrayList<Entry> mEstimatedSocValues;
  private ArrayList<Entry> mHeightValues;
  private ArrayList<Entry> mRealSocValues;
  private ArrayList<Location> mRouteLocations;
  private GreenCarManager mGreenCarManager;
  private Location mCurrentLocation = null;
  private ScheduledExecutorService mScheduledExecutorService;
  private RoutePlan mRoutePlan;
  private ArrayList<IAbrpConsumptionService> mListeners = new ArrayList<>();
  private BroadcastReceiver mNaviGpsReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      double lat = intent.getDoubleExtra(EXTRA_LAT, 0);
      double lon = intent.getDoubleExtra(EXTRA_LON, 0);
      double alt = intent.getDoubleExtra(EXTRA_ALT, 0);
      mCurrentLocation = new Location(lat, lon, alt);
    }
  };

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  @Override
  public void onCreate() {
    Notification notification = new NotificationCompat.Builder(this, null)
        .setContentTitle(getString(R.string.app_name))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .build();
    startForeground(NOTIFICATION_ID_ABRPCONSUMPTIONSERVICE, notification);

    mSharedPreferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    registerReceiver(mNaviGpsReceiver, new IntentFilter(ACTION_GPS_CHANGED));
    mGreenCarManager = GreenCarManager.getInstance(getApplicationContext());
    mRoutePlan = RoutePlan.getInstance();
    mRoutePlan.addListener(this);
  }

  @Override
  public void onDestroy() {
    unregisterReceiver(mNaviGpsReceiver);
    mRoutePlan.removeListener(this);
    stopForeground(true);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (mScheduledFuture != null) {
      mScheduledFuture.cancel(true);
    }
    mScheduledExecutorService = Executors.newScheduledThreadPool(1);
    mRoutePlan.requestPlan(mSharedPreferences.getString(PREFERENCES_TOKEN, null));
    return START_NOT_STICKY;
  }

  @Override
  public void planReady(GsonRoutePlan route) {
    Route r = route.getResult().getRoutes().get(0); //TODO: can there be more then one planned route?
    PathIndices pi = route.getResult().getPathIndices();
    mEstimatedSocValues = new ArrayList<>();
    mHeightValues = new ArrayList<>();
    mRouteLocations = new ArrayList<>();
    mRealSocValues = new ArrayList<>();
    double totalDist = r.getSteps().get(0).getPath().get(0).get(pi.getRemainingDist()); //very ugly but departure_dist is in m and remaining_dist is in km
    for (Step step : r.getSteps()) {
      if (step.getPath() == null) {
        //last step is target and has no path
        mHeightValues.add(new Entry((float) totalDist, mHeightValues.get(mHeightValues.size() - 1).getY())); //since we don't have elevation for the last step reuse the last one
        mEstimatedSocValues.add(new Entry((float) totalDist, step.getArrivalPerc()));
        mRouteLocations.add(new Location(step.getLat(), step.getLon(), mHeightValues.get(mHeightValues.size() - 1).getY(), totalDist));
        break;
      }
      for (List<Double> path : step.getPath()) {
        double elevation = path.get(pi.getElevation());
        double soc = path.get(pi.getSocPerc());
        double remainingDist = path.get(pi.getRemainingDist());
        mHeightValues.add(new Entry((float) (totalDist - remainingDist), (float) elevation));
        mEstimatedSocValues.add(new Entry((float) (totalDist - remainingDist), (float) soc));
        mRouteLocations.add(new Location(path.get(pi.getLat()), path.get(pi.getLon()), elevation, (totalDist - remainingDist) * 1000));
      }
    }

    for (IAbrpConsumptionService i : mListeners) {
      i.setChartData(mHeightValues, mEstimatedSocValues, mRealSocValues);
    }
    mScheduledFuture = mScheduledExecutorService.scheduleWithFixedDelay(new RealSocWatcher(mGreenCarManager), 1000L, 1000L, TimeUnit.MILLISECONDS);
  }

  @Override
  public void planFailed() {

  }

  public void addListener(IAbrpConsumptionService listener) {
    mListeners.add(listener);
  }

  public void removeListener(IAbrpConsumptionService listener) {
    mListeners.remove(listener);
  }

  private int getClosestLocation(Location currentLocation) {
    float closest = Float.MAX_VALUE;
    int closestIndex = -1;
    for (int i = 0; i < mRouteLocations.size(); i++) {
      float distance = mRouteLocations.get(i).getDistance(currentLocation);
      if (distance < closest) {
        closest = distance;
        closestIndex = i;
      }
    }

    sLog.info("closest distance: " + closest + " obj: [" + mRouteLocations.get(closestIndex).toString() + "]");
    return closestIndex;
  }

  public interface IAbrpConsumptionService {
    void setChartData(ArrayList<Entry> heightValues, ArrayList<Entry> estimatedSocValues, ArrayList<Entry> realSocValues);

    void updateChartData(ArrayList<Entry> realSocValues);
  }

  private class RealSocWatcher implements Runnable {
    private GreenCarManager mGreenCarManager;
    private int lastRoutePath;
    private int lastDemo = -1;

    public RealSocWatcher(GreenCarManager greenCarManager) {
      this.mGreenCarManager = greenCarManager;
      this.lastRoutePath = getClosestLocation(DEMO_COORDINATES.get(0)/*mCurrentLocation*/);
    }

    @Override
    public void run() {
      //int soc = mGreenCarManager.getBatteryChargePersent();
      int soc = 94 - this.lastRoutePath;
      lastDemo++;
      if (lastDemo == DEMO_COORDINATES.size()) {
        mScheduledExecutorService.shutdownNow();
      }
      Location currentLocation = DEMO_COORDINATES.get(lastDemo); /*mCurrentLocation*/

      if (mRouteLocations.size() != this.lastRoutePath + 1) {
        float nextDistance = mRouteLocations.get(this.lastRoutePath + 1).getDistance(currentLocation);
        if (nextDistance < 25f) {
          this.lastRoutePath++;
        }
      }

      Location closest = mRouteLocations.get(this.lastRoutePath);
      float distance = closest.getDistance(currentLocation);

      float x = (float) Math.floor((closest.getDistanceFromStart() + distance)) / 1000f;
      if (mRealSocValues.size() > 0) {
        Entry lastRealSoc = mRealSocValues.get(mRealSocValues.size() - 1);
        if (lastRealSoc.getX() > x) {
          x = lastRealSoc.getX();
        }
      }
      boolean addNew = true;
      for (Entry s : mRealSocValues) {
        if (s.getX() == x) {
          s.setY(soc);
          addNew = false;
          break;
        }
      }
      if (addNew) {
        mRealSocValues.add(new Entry(x, soc));
      }
      for (IAbrpConsumptionService i : mListeners) {
        i.updateChartData(mRealSocValues);
      }
    }
  }

  public class AbrpConsumptionBinder extends Binder {
    public AbrpConsumptionService getService() {
      return AbrpConsumptionService.this;
    }
  }
}
