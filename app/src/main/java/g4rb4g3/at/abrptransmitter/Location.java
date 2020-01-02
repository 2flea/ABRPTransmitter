package g4rb4g3.at.abrptransmitter;

import static g4rb4g3.at.abrptransmitter.Constants.ACTION_GPS_CHANGED;

public class Location extends android.location.Location {
  private double distanceFromStart;
  private float distance;

  public Location(double lat, double lon, double alt) {
    super(ACTION_GPS_CHANGED);
    setLatitude(lat);
    setLongitude(lon);
    setAltitude(alt);
  }

  public Location(double lat, double lon, double alt, double distanceFromStart) {
    super(ACTION_GPS_CHANGED);
    setLatitude(lat);
    setLongitude(lon);
    setAltitude(alt);
    this.distanceFromStart = distanceFromStart;
  }

  public String toString() {
    return "lat: " + getLatitude() + " lon: " + getLongitude() + " alt: " + getAltitude() + " distanceFromStart: " + this.distanceFromStart;
  }

  public float getDistance(android.location.Location dest) {
    this.distance = super.distanceTo(dest);
    return this.distance;
  }

  public double getDistanceFromStart() {
    return distanceFromStart;
  }
}
