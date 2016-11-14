package com.team3.ergency;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.arlib.floatingsearchview.FloatingSearchView;
import com.arlib.floatingsearchview.suggestions.model.SearchSuggestion;
import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.appindexing.Thing;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.location.places.AutocompletePrediction;
import com.google.android.gms.location.places.AutocompletePredictionBuffer;
import com.google.android.gms.location.places.PlaceBuffer;
import com.google.android.gms.location.places.Places;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.team3.ergency.helper.PlaceWrapper;
import com.team3.ergency.helper.PlaceSuggestion;

import java.util.ArrayList;
import java.util.List;

public class LocationActivity extends AppCompatActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback, OnMapReadyCallback {

    /**
     * Log tag for LocationActivity
     */
    public static final String TAG = "LocationActivity";

    /**
     * GoogleAPIClient to use for predicting addresses
     */
    private GoogleApiClient mGoogleApiClient;

    /**
     * Id for the request location id
     */
    private final int REQUEST_LOCATION_ID = 0;

    /**
     * Location to use for getting current location
     */
    private Location mLocation;

    /**
     * Coordinates to use for getting surrounding hospitals
     */
    private LatLng mCoordinates;

    /**
     * Map to use for displaying location
     */
    private GoogleMap mMap;

    /**
     * SearchView, SearchResultsList, SearchResultsAdapters for the search bar
     */
    private FloatingSearchView mSearchView;
    private RecyclerView mSearchResultsList;
//    private SearchResultsListAdapter mSearchResultsAdapter;

    @Override
    protected void onStart() {
        super.onStart();
        if (mGoogleApiClient != null) {
            mGoogleApiClient.connect();
        }

        AppIndex.AppIndexApi.start(mGoogleApiClient, getIndexApiAction());
    }

    @Override
    protected void onStop() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onStop();
        AppIndex.AppIndexApi.end(mGoogleApiClient, getIndexApiAction());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location);

        // Create GoogleApiClient
        mGoogleApiClient = new GoogleApiClient
                .Builder(this)
                .addApi(Places.GEO_DATA_API)
                .addApi(AppIndex.API).build();

        // Create a map fragment
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(
                        R.id.map_fragment);
        mapFragment.getMapAsync(this);

        // Setup floating search bar
        mSearchView = (FloatingSearchView) findViewById(R.id.search_bar_floatingsearchview);
        mSearchResultsList = (RecyclerView) findViewById(R.id.search_results_list);

        setupSearchView();
//        setupResultsList();
//        setupDrawer();
    }

    /**
     * Override callback when map is created
     */
    @Override
    public void onMapReady(GoogleMap map) {
        mMap = map;
    }

    /**
     * Setup the search view:
     * (1) Listen to user typing text into the search and display suggestions
     * (2) Change camera view and place marker on map when user selects a location
     */
    private void setupSearchView() {
        mSearchView.setOnQueryChangeListener(new FloatingSearchView.OnQueryChangeListener() {
            @Override
            public void onSearchTextChanged(String oldQuery, final String newQuery) {

                if (!oldQuery.equals("") && newQuery.equals("")) {
                    // If text is erased, clear search suggestions
                    mSearchView.clearSuggestions();
                }
                else {
                    // Show loading animation on the left side of the search view
                    mSearchView.showProgress();

                    getPredictions(newQuery, new ResultCallback<AutocompletePredictionBuffer>() {
                        @Override
                        public void onResult(AutocompletePredictionBuffer buffer) {
                            if (buffer == null) { return; }

                            List<PlaceSuggestion> suggestionList = new ArrayList<>();

                            if (buffer.getStatus().isSuccess()) {
                                for (AutocompletePrediction p : buffer) {
                                    // Limit number of predictions to 5
                                    if (suggestionList.size() > 5) {
                                        break;
                                    }
                                    // Add new PlaceSuggestion to suggestion list
                                    suggestionList.add(new PlaceSuggestion(new PlaceWrapper(
                                            p.getFullText(null), p.getPlaceId())));
                                }
                            }
                            // Release buffer to prevent memory leak
                            buffer.release();
                            mSearchView.swapSuggestions(suggestionList);
                            mSearchView.hideProgress();
                        }
                    });
                }
            }
        });

        mSearchView.setOnSearchListener(new FloatingSearchView.OnSearchListener() {
            @Override
            public void onSuggestionClicked(final SearchSuggestion searchSuggestion) {
                PlaceWrapper place = ((PlaceSuggestion) searchSuggestion).getPlaceWrapper();
                if (mGoogleApiClient == null) {
                    return;
                }
                setMapFromSearch(place);
            }

            @Override
            public void onSearchAction(String query) {
                getPredictions(query, new ResultCallback<AutocompletePredictionBuffer>() {
                    @Override
                    public void onResult(AutocompletePredictionBuffer buffer) {
                        if (buffer == null) { return; }

                        if (buffer.getStatus().isSuccess()) {
                            AutocompletePrediction prediction = buffer.get(0);
                            PlaceWrapper place = new PlaceWrapper(
                                    prediction.getFullText(null), prediction.getPlaceId());
                            setMapFromSearch(place);
                        }
                        // Release buffer to prevent memory leak
                        buffer.release();
                    }
                });
            }
        });
    }

    private void getPredictions(String query, ResultCallback<AutocompletePredictionBuffer> callback) {
        if (query != null && query.length() != 0 &&
                mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            // Create and set LatLng bounds to the entire United States
            LatLngBounds bounds = new LatLngBounds(new LatLng(28.70, -127.50),
                    new LatLng(48.85, -55.90));

            Places.GeoDataApi.getAutocompletePredictions(mGoogleApiClient, query.toString(), bounds, null)
                    .setResultCallback(callback);
        }
    }

    private void setMapFromSearch(PlaceWrapper place) {
        Places.GeoDataApi.getPlaceById(mGoogleApiClient, place.getPlaceId())
                .setResultCallback(
                        new ResultCallback<PlaceBuffer>() {
                            @Override
                            public void onResult(PlaceBuffer buffer) {
                                if (buffer == null) { return; }

                                if (buffer.getStatus().isSuccess()) {
                                    mCoordinates = buffer.get(0).getLatLng();
                                    setMap(mCoordinates, "You", true);
                                }
                                // Release buffer to prevent memory leak
                                buffer.release();
                            }
                        }
                );
    }

    /**
     * OnClick method for location request button
     */
    public void requestLocation(View view) {
        // Check if location permissions are granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission();
        }
        else {
            Log.d(TAG, "Location permission has already been granted.");
            getLocation();
            setMap(mCoordinates, "YOU", true);
        }
    }

    /**
     * Requests Location permissions
     */
    private void requestLocationPermission() {
        Log.d(TAG, "Location permission NOT granted. Requesting permission.");
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Display toast explanation for location permission
            Log.d(TAG, "Displaying location permission rationale");
            Toast.makeText(this, R.string.permissions_needed_location, Toast.LENGTH_LONG).show();
        }
        else {
            // Request location permissions
            ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_ID);
        }
    }

    /**
     * Override callback received when permission response is received.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                          String permissions[], int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_ID) {
            Log.d(TAG, "Location permission response received.");

            //  Check if location permission was granted
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Location permission granted.");
                getLocation();
                if (mCoordinates != null) {
                    setMap(mCoordinates, "You", true);
                }
            }
            else {
                // Permission not granted. Disable location button
                Button requestLocationButton = (Button) findViewById(R.id.request_location_button);
                requestLocationButton.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Toast.makeText(getApplicationContext(), R.string.permissions_needed_location,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    /**
     * Request a single location update and store that location
     */
    private void getLocation() {
        // Acquire reference to system Location Manager
        LocationManager mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        final LocationListener mLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                mLocation = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };
        mLocation = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        mLocationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, mLocationListener, null);
        if (mLocation != null) {
            mCoordinates = new LatLng(mLocation.getLatitude(), mLocation.getLongitude());
        }
    }

    /**
     * Set the map the passed LatLng coordinates and adjust camera to its location
     */
    private void setMap(LatLng coordinates, String text, boolean isClear) {
        // Clear map of markers if needed
        if (isClear) {
            mMap.clear();
        }

//        // Add a marker to coordinates
//        mMap.addMarker(new MarkerOptions()
//                .position(coordinates)
//                .title(text));

        // Adjust camera to new coordinates
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinates, 15));
    }

    /**
     * ATTENTION: This was auto-generated to implement the App Indexing API.
     * See https://g.co/AppIndexing/AndroidStudio for more information.
     */
    public Action getIndexApiAction() {
        Thing object = new Thing.Builder()
                .setName("Location Page") // TODO: Define a title for the content shown.
                // TODO: Make sure this auto-generated URL is correct.
                .setUrl(Uri.parse("http://[ENTER-YOUR-URL-HERE]"))
                .build();
        return new Action.Builder(Action.TYPE_VIEW)
                .setObject(object)
                .setActionStatus(Action.STATUS_TYPE_COMPLETED)
                .build();
    }
}
