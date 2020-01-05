package g4rb4g3.at.abrptransmitter.abrp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.bind.TypeAdapters;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import cz.msebera.android.httpclient.Header;
import g4rb4g3.at.abrptransmitter.gson.abrp.DoubleTypeAdapter;
import g4rb4g3.at.abrptransmitter.gson.abrp.GsonRoutePlan;

import static g4rb4g3.at.abrptransmitter.Constants.ABETTERROUTEPLANNER_API_KEY;
import static g4rb4g3.at.abrptransmitter.Constants.ABETTERROUTEPLANNER_PLAN_URL;
import static g4rb4g3.at.abrptransmitter.Constants.ABETTERROUTEPLANNER_URL_API_KEY;
import static g4rb4g3.at.abrptransmitter.Constants.ABETTERROUTEPLANNER_URL_TOKEN;
import static g4rb4g3.at.abrptransmitter.Constants.ROUTEPLAN_TIMEOUT;

public class RoutePlan {
  private static final Logger sLog = LoggerFactory.getLogger(RoutePlan.class.getSimpleName());

  private static AsyncHttpClient sAsyncHttpClient;
  private static RoutePlan sInstance;
  private List<IRoutePlan> mListeners = new ArrayList<>();
  private Gson mGson;

  private RoutePlan() {
    sAsyncHttpClient = new AsyncHttpClient(true, 80, 443);
    sAsyncHttpClient.setTimeout(ROUTEPLAN_TIMEOUT);

    GsonBuilder builder = new GsonBuilder();
    builder.registerTypeAdapterFactory(TypeAdapters.newFactory(double.class, Double.class, new DoubleTypeAdapter()));
    mGson = builder.create();
  }

  public static RoutePlan getInstance() {
    if (sInstance == null) {
      sInstance = new RoutePlan();
    }
    return sInstance;
  }

  public void addListener(IRoutePlan listener) {
    this.mListeners.add(listener);
  }

  public void removeListener(IRoutePlan listener) {
    this.mListeners.remove(listener);
  }

  private void parsePlanResponse(byte[] responseBody) {
    try {
      GsonRoutePlan gsonRoute = mGson.fromJson(new String(responseBody), GsonRoutePlan.class);
      for (IRoutePlan listener : mListeners) {
        listener.planReady(gsonRoute);
      }
    } catch (Exception e) {
      sLog.error("Failed to parse JSON", e);
      for (IRoutePlan listener : mListeners) {
        listener.planFailed();
      }
    }
  }

  public void requestPlan(String userToken) {
    StringBuilder url = new StringBuilder(ABETTERROUTEPLANNER_PLAN_URL)
        .append(ABETTERROUTEPLANNER_URL_TOKEN).append("=").append(userToken)
        .append("&").append(ABETTERROUTEPLANNER_URL_API_KEY).append("=").append(ABETTERROUTEPLANNER_API_KEY);
    sAsyncHttpClient.get(url.toString(), new AsyncHttpResponseHandler() {

      @Override
      public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
        parsePlanResponse(responseBody);
      }

      @Override
      public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
        sLog.error("failed to load plan, status code: " + statusCode, error);
        for (IRoutePlan listener : mListeners) {
          listener.planFailed();
        }
      }

      @Override
      public boolean getUseSynchronousMode() {
        return false;
      }
    });
  }
}
