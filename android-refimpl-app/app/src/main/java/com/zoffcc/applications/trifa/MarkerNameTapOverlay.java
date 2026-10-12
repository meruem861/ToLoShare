package com.zoffcc.applications.trifa;

import android.view.MotionEvent;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;

import static com.zoffcc.applications.trifa.CaptureService.remote_location_data;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;

/*
 * shows the name of a friend or location group member when their marker on the map is tapped
 */
public class MarkerNameTapOverlay extends Overlay
{
    private static final float TAP_RADIUS_DP = 30;

    @Override
    public boolean onSingleTapConfirmed(final MotionEvent e, final MapView mapView)
    {
        try
        {
            final Projection pj = mapView.getProjection();
            final float radius_px = TAP_RADIUS_DP * mapView.getContext().getResources().getDisplayMetrics().density;
            final IGeoPoint tap_p = pj.fromPixels((int) e.getX(), (int) e.getY());
            final IGeoPoint edge_p = pj.fromPixels((int) (e.getX() + radius_px), (int) e.getY());
            final GeoPoint tap = new GeoPoint(tap_p.getLatitude(), tap_p.getLongitude());
            final double radius_m = tap.distanceToAsDouble(edge_p);

            String nearest_name = null;
            double nearest_m = radius_m;
            for (CaptureService.remote_location_entry re : new ArrayList<>(remote_location_data.values()))
            {
                if ((re == null) || (re.remoteBestLocation == null))
                {
                    continue;
                }
                final double d = tap.distanceToAsDouble(
                        new GeoPoint(re.remoteBestLocation.getLatitude(), re.remoteBestLocation.getLongitude()));
                if (d <= nearest_m)
                {
                    nearest_m = d;
                    nearest_name = re.friend_name;
                }
            }

            if (nearest_name != null)
            {
                display_toast(nearest_name, false, 0);
                return true;
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }
}
