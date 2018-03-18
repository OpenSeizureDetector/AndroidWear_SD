package uk.org.openseizuredetector;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.stats.WakeLockEvent;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import org.jtransforms.fft.DoubleFFT_1D;

import java.io.UnsupportedEncodingException;
import java.util.*;

public class AWSdService extends Service implements SensorEventListener {
    private final static String TAG="AWSdService";
    private final static int NSAMP = 500;
    private final static int SIMPLE_SPEC_FMAX = 10;   // simple spectrum maximum freq in Hz.
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private int mMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mStartEvent = null;
    private SensorManager mHeartSensorManager;
    private Sensor mHeartSensor;
    private int mHeartMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mHeartStartEvent = null;
    private long mStartTs = 0;
    public int mNSamp = 0;
    public double mSampleFreq = 0;
    public double[] mAccData;
    public SdData mSdData;

    private int mAlarmFreqMin = 3;  // Frequency ROI in Hz
    private int mAlarmFreqMax = 8;  // Frequency ROI in Hz
    private int mFreqCutoff = 12;   // High Frequency cutoff in Hz
    private int mAlarmThresh = 700000;
    private int mAlarmRatioThresh = 125;
    private int mAlarmTime = 3;
    private float mHeartPercentThresh = 1.3f;
    private int alarmCount = 0;
    private int curHeart = 0;
    private int avgHeart = 0;
    private ArrayList<Integer> heartRates = new ArrayList<Integer>(10);

    Vibrator mVibe;






    private GoogleApiClient mApiClient;
    private PowerManager.WakeLock mWakeLock;


    public class Access extends Binder {
        public AWSdService getService() {
            return AWSdService.this;
        }
    };
    public final Access binder = new Access();

    public AWSdService() {
        Log.v(TAG,"AWSdService Constructor()");
    }

    public void ClearAlarmCount() {
        alarmCount = 0;
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.v(TAG,"onStartCommand()");
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mSensor , SensorManager.SENSOR_DELAY_GAME);
        mHeartSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        mSensorManager.registerListener(this, mHeartSensor , SensorManager.SENSOR_DELAY_UI);

        mAccData = new double[NSAMP];
        mSdData = new SdData();
        mVibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);


        // Prevent sleeping
        PowerManager pm = (PowerManager)(getApplicationContext().getSystemService(Context.POWER_SERVICE));
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "systemService");
        mWakeLock.acquire();

        // Initialise the Google API Client so we can use Android Wear messages.
        mApiClient = new GoogleApiClient.Builder(this.getApplicationContext())
                .addApi(Wearable.API)
                .build();
        mApiClient.connect();

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG,"onBind()");
        return binder;
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
        mApiClient.disconnect();
        mSensorManager.unregisterListener(this);
        mWakeLock.release();
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.v(TAG,"onRebind()");
    }

    private void checkAlarm() {
        if(mSdData.alarmState == 6 || mSdData.alarmState == 10) {
            //ignore alarms when muted
            return;
        }
        boolean inAlarm = false;
        if(  mSdData.roiPower > mSdData.alarmThresh && mSdData.roiRatio > mSdData.alarmRatioThresh ) {
            inAlarm = true;
        }
        if( mSdData.heartAvg != 0 && mSdData.heartCur > mSdData.heartAvg*mHeartPercentThresh){
            inAlarm = true;
            alarmCount = (int)mSdData.alarmTime;
        }
        Log.v(TAG,"roiPower "+mSdData.roiPower+" roiRaTIO "+ mSdData.roiRatio);

        if (inAlarm) {
            alarmCount+=1;
            if (alarmCount > mSdData.alarmTime) {
                mSdData.alarmState = 2;
            } else if (alarmCount>mSdData.warnTime) {
                mSdData.alarmState = 1;
            }
            long[] pattern = {0, 100, 200, 300};
            mVibe.vibrate(pattern, -1);

            //
        } else {
            // If we are in an ALARM state, revert back to WARNING, otherwise
            // revert back to OK.
            if (mSdData.alarmState == 2) {
                mSdData.alarmState = 1;
            } else {
                mSdData.alarmState = 0;
                alarmCount = 0;
            }
        }
        if(mSdData.alarmState == 1 || mSdData.alarmState == 2) {
            Intent intent = new Intent(this.getApplicationContext(), StartUpActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }


    }

    private double calculateAverage(List <Integer> marks) {
        Integer sum = 0;
        if(!marks.isEmpty()) {
            for (Integer mark : marks) {
                sum += mark;
            }
            return sum.doubleValue() / marks.size();
        }
        return sum;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // is this a heartbeat event and does it have data?
        if(event.sensor.getType()==Sensor.TYPE_HEART_RATE && event.values.length>0 ) {
            int newValue = Math.round(event.values[0]);
            //Log.d(LOG_TAG,sensorEvent.sensor.getName() + " changed to: " + newValue);
            // only do something if the value differs from the value before and the value is not 0.
            if(curHeart != newValue && newValue!=0) {
                // save the new value
                curHeart = newValue;
                // add it to the list and computer a new average
                if(heartRates.size() == 10) {
                    heartRates.remove(0);
                }
                heartRates.add(curHeart);
            }
            avgHeart = (int)calculateAverage(heartRates);
            if(heartRates.size()<4) {
                avgHeart = 0;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
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
                    // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                    // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                    // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                    // FIXME - we should do some sort of check and disregard samples with long delays in them.
                    double dT = 1e-9*(event.timestamp - mStartTs);
                    int sampleFreq = (int)(mNSamp/dT);
                    Log.v(TAG,"Collected "+NSAMP+" data points in "+dT+" sec (="+sampleFreq+" Hz) - analysing...");
                    doAnalysis();
                    checkAlarm();
                    sendDataToPhone();
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

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    private void doAnalysis() {
        double freqRes = 1.0*mSampleFreq/mNSamp;
        Log.v(TAG,"doAnalysis(): mSampleFreq="+mSampleFreq+" mNSamp="+mNSamp+": freqRes="+freqRes);
        // Set the frequency bounds for the analysis in fft output bin numbers.
        int nMin = (int)(mAlarmFreqMin/freqRes);
        int nMax = (int)(mAlarmFreqMax /freqRes);
        Log.v(TAG,"doAnalysis(): mAlarmFreqMin="+mAlarmFreqMin+", nMin="+nMin
                +", mAlarmFreqMax="+mAlarmFreqMax+", nMax="+nMax);
        // Calculate the bin number of the cutoff frequency
        int nFreqCutoff = (int)(mFreqCutoff /freqRes);
        Log.v(TAG,"mFreqCutoff = "+mFreqCutoff+", nFreqCutoff="+nFreqCutoff);

        DoubleFFT_1D fftDo = new DoubleFFT_1D(mNSamp);
        double[] fft = new double[mNSamp * 2];
        System.arraycopy(mAccData, 0, fft, 0, mNSamp);
        fftDo.realForwardFull(fft);

        // Calculate the whole spectrum power (well a value equivalent to it that avoids suare root calculations
        // and zero any readings that are above the frequency cutoff.
        double specPower = 0;
        for (int i = 1; i < mNSamp / 2; i++) {
            if (i <= nFreqCutoff) {
                specPower = specPower + fft[2 * i] + fft[2*i] + fft[2*i +1] * fft[2*i+1];

            } else {
                fft[2*i] = 0.;
                fft[2*i+1] = 0.;
            }
        }
        specPower = specPower/mNSamp/2;

        // Calculate the Region of Interest power and power ratio.
        double roiPower = 0;
        for (int i=nMin;i<nMax;i++) {
            roiPower = roiPower + fft[2 * i] + fft[2*i] + fft[2*i +1] * fft[2*i+1];
        }
        roiPower = roiPower/(nMax - nMin);
        double roiRatio = 10 * roiPower / specPower;

        // Calculate the simplified spectrum - power in 1Hz bins.
        double[] simpleSpec = new double[SIMPLE_SPEC_FMAX+1];
        for (int ifreq=0;ifreq<SIMPLE_SPEC_FMAX;ifreq++) {
            int binMin = (int)(1 + ifreq/freqRes);    // add 1 to loose dc component
            int binMax = (int)(1 + (ifreq+1)/freqRes);
            simpleSpec[ifreq]=0;
            for (int i=binMin;i<binMax;i++) {
                simpleSpec[ifreq] = simpleSpec[ifreq] + fft[2 * i] + fft[2*i] + fft[2*i +1] * fft[2*i+1];
            }
            simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax-binMin);
        }

        // Populate the mSdData structure to communicate with the main SdServer service.
        mSdData.specPower = (long)specPower;
        mSdData.roiPower = (long)roiPower;
        mSdData.roiRatio = (long)roiRatio;
        mSdData.dataTime.setToNow();
        mSdData.maxVal = 0;   // not used
        mSdData.maxFreq = 0;  // not used
        mSdData.haveData = true;
        mSdData.alarmThresh = mAlarmThresh;
        mSdData.alarmRatioThresh = mAlarmRatioThresh;
        mSdData.alarmTime = mAlarmTime;
        mSdData.heartCur = curHeart;
        mSdData.heartAvg = avgHeart;
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = getApplicationContext().registerReceiver(null, ifilter);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = 100*level / (float)scale;
        mSdData.batteryPc = (int)(batteryPct);
        for(int i=0;i<SIMPLE_SPEC_FMAX;i++) {
            mSdData.simpleSpec[i] = (int)simpleSpec[i];
        }
    }

    public void handleSendingIAmOK() {
        if(mSdData != null && mSdData.alarmState == 10) {
            sendDataToPhone();
        }
    };

    private void sendDataToPhone() {
        Log.v(TAG,"sendDataToPhone()");
        sendMessage("/testMsg", "Test Message");
        sendMessage("/data",mSdData.toDataString());
    }

    // Send a MesageApi message text to all connected devices.
    private void sendMessage( final String path, final String text ) {
        new Thread( new Runnable() {
            @Override
            public void run() {
                Log.v(TAG,"sendMessage("+path+","+text+")");
                NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes( mApiClient ).await();
                for(Node node : nodes.getNodes()) {
                    Log.v(TAG,"Sending to "+node.getDisplayName());
                    MessageApi.SendMessageResult result = null;
                    try {
                        result = Wearable.MessageApi.sendMessage(
                                mApiClient, node.getId(), path, text.getBytes("UTF-8") ).await();
                    } catch (UnsupportedEncodingException e) {
                        Log.e(TAG,"Error encoding string to bytes");
                        e.printStackTrace();
                    }
                    if (result.getStatus().isSuccess()) {
                        Log.v(TAG, "Message: {" + text + "} sent to: " + node.getDisplayName());
                    }
                    else {
                        // Log an error
                        Log.e(TAG, "ERROR: failed to send Message");
                    }
                }
            }
        }).start();
    }

}
