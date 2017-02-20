package uk.org.openseizuredetector.android_wear_sd;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.widget.TextView;

public class StartUpActivity extends Activity {
    private static final String TAG = "StartUpActivity";
    private TextView mTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);
        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.text);
            }
        });

    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mTextView != null) mTextView.setText("onStart");
        startService(new Intent(getBaseContext(), AWSdService.class));
        if (mTextView != null) mTextView.setText("Service Started");

    }




    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG,"onStop()");
        stopService(new Intent(getBaseContext(), AWSdService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}
