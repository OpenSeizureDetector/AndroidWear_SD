package uk.org.openseizuredetector.android_wear_sd;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

public class AWSdService extends Service implements SensorEventListener {
    private final static String TAG="AWSdService";
    private final static int NSAMP = 500;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private int mMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mStartEvent = null;
    private long mStartTs = 0;
    private int mNSamp = 0;
    private double mSampleFreq = 0;
    private double[] mAccData;

    public AWSdService() {
        Log.v(TAG,"AWSdService Constructor()");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG,"onStartCommand()");
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mSensor , SensorManager.SENSOR_DELAY_GAME);
        mAccData = new double[NSAMP];
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG,"onBind()");
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG,"onUnbind()");
        return super.onUnbind(intent);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG,"onDestroy()");
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.v(TAG,"onRebind()");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (mMode == 0) {
                if (mStartEvent==null) {
                    Log.v(TAG,"mMode=0 - checking Sample Rate - mNSamp = "+mNSamp);
                    Log.v(TAG,"saving initial event data");
                    mStartEvent = event;
                    mStartTs = event.timestamp;
                    mNSamp = 0;
                } else {
                    mNSamp ++;
                }
                if (mNSamp>=1000) {
                    Log.v(TAG,"Collected Data = final TimeStamp="+event.timestamp+", initial TimeStamp="+mStartTs);
                    double dT = 1e-9*(event.timestamp - mStartTs);
                    mSampleFreq = mNSamp/dT;
                    Log.v(TAG,"Collected data for "+dT+" sec - calculated sample rate as "+ mSampleFreq +" Hz");
                    mMode = 1;
                    mNSamp = 0;
                    mStartTs = event.timestamp;
                }
            } else if (mMode==1) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                //Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
                mAccData[mNSamp] = (x*x + y*y + z*z);
                mNSamp++;
                if (mNSamp==NSAMP) {
                    double dT = 1e-9*(event.timestamp - mStartTs);
                    double sampleFreq = mNSamp/dT;
                    Log.v(TAG,"Collected "+NSAMP+" data points in "+dT+" sec (="+sampleFreq+" Hz) - analysing...");
                    doAnalysis(mAccData,mNSamp,sampleFreq);
                    mNSamp = 0;
                    mStartTs = event.timestamp;
                } else if (mNSamp>NSAMP) {
                    Log.v(TAG,"Received data during analysis - ignoring sample");
                }

            } else {
                Log.v(TAG,"ERROR - Mode "+mMode+" unrecognised");
            }
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private void doAnalysis(double accArr[],int n,double sampleFreq) {
        Log.v(TAG,"doAnalysis()");
    }


}
