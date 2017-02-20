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

public class StartUpActivity extends Activity implements SensorEventListener {
    private static final String TAG = "StartUpActivity";
    private TextView mTextView;
    private SensorManager mSensorManager;
    private Sensor mSensor;

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

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mTextView != null) mTextView.setText("onStart");
        startService(new Intent(getBaseContext(), AWSdService.class));
        if (mTextView != null) mTextView.setText("Service Started");

        //mSensorManager.registerListener(this, mSensor , SensorManager.SENSOR_DELAY_NORMAL);
    }




    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mSensor , SensorManager.SENSOR_DELAY_GAME
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
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

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}
