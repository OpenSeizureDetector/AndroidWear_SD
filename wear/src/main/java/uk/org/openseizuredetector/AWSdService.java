package uk.org.openseizuredetector;

import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class AWSdService extends Service implements SensorEventListener, MessageClient.OnMessageReceivedListener, CapabilityClient.OnCapabilityChangedListener {

    private final static String TAG = "AWSdService";
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 16;
    private final String wearableAppCheckPayload = "AppOpenWearable";
    private final String wearableAppCheckPayloadReturnACK = "AppOpenWearableACK";
    private final String TAG_MESSAGE_RECEIVED = "SdDataSourceAw";
    private final String MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received";
    private final String MESSAGE_ITEM_OSD_TEST = "/testMsg";
    private final String MESSAGE_ITEM_OSD_DATA = "/data";
    private final String MESSAGE_ITEM_OSD_TEST_RECEIVED = "/testMsg-received";
    private final String MESSAGE_ITEM_OSD_DATA_REQUESTED = "/data-requested";
    private final String MESSAGE_ITEM_OSD_DATA_RECEIVED = "/data-received";
    private final String MESSAGE_ITEM_PATH = "/message-item";
    private final String APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD";
    private Context mContext;
    private Boolean mMobileDeviceConnected = false;
    private final static int NSAMP = 250;
    private final static int SIMPLE_SPEC_FMAX = 10;   // simple spectrum maximum freq in Hz.
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private int mMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mStartEvent = null;
    private SensorManager mHeartSensorManager;
    private Sensor mHeartSensor;
    private String currentAckFromWearForAppOpenCheck = null;
    private Sensor mHeartBeatSensor;
    private Sensor mBloodPressure;
    private Sensor mStationaryDetectSensor;
    private SensorEvent mHeartStartEvent = null;
    private long mStartTs = 0;
    public int mNSamp = 0;
    public double mSampleFreq = 0d;
    public double[] mAccData;
    public SdData mSdData;
    // private Sensor mO2Sensor; disabled until privileged API Samsung is acquired
    private int mHeartMode = 0;   // 0=check data rate, 1=running
    private MessageEvent mMessageEvent = null;
    private String mMobileNodeUri = null;

    private int mAlarmFreqMin = 3;  // Frequency ROI in Hz
    private int mAlarmFreqMax = 8;  // Frequency ROI in Hz
    private int mFreqCutoff = 12;   // High Frequency cutoff in Hz
    private int mAlarmThresh = 900000;
    private int mAlarmRatioThresh = 275;
    private int mAlarmTime = 3;
    private float mHeartPercentThresh = 1.3f;
    private int alarmCount = 0;
    private int curHeart = 0;
    private int avgHeart = 0;
    private ArrayList<Integer> heartRates = new ArrayList<Integer>(10);
    private CapabilityInfo mMobileNodesWithCompatibility = null;
    private boolean logNotConnectedMessage;
    private boolean logNotConnectedMessagePf;

    Vibrator mVibe;


    private Task mNodeListClient;
    private Node mWearNode;


    private MessageClient mApiClient;
    private PowerManager.WakeLock mWakeLock;

    public AWSdService() {
        Log.v(TAG, "AWSdService Constructor()");
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d(TAG_MESSAGE_RECEIVED, "onMessageReceived event received");
        // Get the node id of the node that created the data item from the host portion of
        // the uri.
        mMobileNodeUri = messageEvent.getSourceNodeId();
        final String s1 = Arrays.toString(messageEvent.getData());
        final String messageEventPath = messageEvent.getPath();
        Log.d(
                TAG_MESSAGE_RECEIVED,
                "onMessageReceived() A message from watch was received:"
                        + messageEvent.getRequestId()
                        + " "
                        + messageEventPath
                        + " "
                        + s1
        );
        //Send back a message back to the source node
        //This acknowledges that the receiver activity is open
        if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, APP_OPEN_WEARABLE_PAYLOAD_PATH)) {
            try {
                // Set the data of the message to be the bytes of the Uri.
                Log.v(TAG, "Sending return message: " + wearableAppCheckPayloadReturnACK);
                sendMessage(APP_OPEN_WEARABLE_PAYLOAD_PATH, wearableAppCheckPayloadReturnACK);
                Log.d(TAG, "We returned from sending message.");
            } catch (Exception e) {
                Log.v(TAG, "Received new settings failed to process", new Throwable());
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_TEST_RECEIVED)) {
            //TODO
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_DATA_RECEIVED)) {
            Log.v(TAG, "Received new settings");

            try {
                mSdData.fromJSON(s1);
            } catch (Exception e) {
                Log.v(TAG, "Received new settings failed to process", new Throwable());
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_DATA_REQUESTED)) {
            try {
                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mMobileDeviceConnected = true;
                logNotConnectedMessage = false;
                sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toDataString(true));

            } catch (Exception e) {
                logNotConnectedMessage = false;
                mSdData.watchConnected = false;
                Log.e(TAG, "Received new settings failed to process", new Throwable());
            }
            logNotConnectedMessage = false;
        } else {
            Log.v(TAG + "nn", "Not processing received message, displaying in log: " + s1);
        }

    }

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(TAG, "CapabilityInfo received: " + capabilityInfo.toString());
        if (capabilityInfo.equals(Uri.parse("wear://"))) {
            mMobileNodesWithCompatibility = capabilityInfo;
            if (mMobileNodesWithCompatibility.getNodes().isEmpty()) {
                Wearable.getMessageClient(mContext).removeListener(this);
            } else {
                Wearable.getMessageClient(mContext).addListener(this);
            }
        }
    }

    ;
    public final Access binder = new Access();

    public Activity getActivity(Context context) {
        if (context == null) {
            return null;
        } else if (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            } else {
                return getActivity(((ContextWrapper) context).getBaseContext());
            }
        }
        return null;
    }

    public void ClearAlarmCount() {
        alarmCount = 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mContext = this;
        mMobileDeviceConnected = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
                ActivityCompat.requestPermissions(getActivity(this),
                        new String[]{Manifest.permission.BODY_SENSORS},
                        PERMISSION_REQUEST_BODY_SENSORS);


            } else {
                Log.d(TAG, "ALREADY GRANTED");
            }
        }

        Log.v(TAG, "onStartCommand()");
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
        mHeartSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
        mHeartBeatSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_BEAT);
        mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
        mBloodPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
        mStationaryDetectSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
        mSensorManager.registerListener(this, mStationaryDetectSensor, SensorManager.SENSOR_DELAY_UI);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            ArrayList<String> arrayList = new ArrayList<String>();
            for (Sensor sensor : sensors) {
                arrayList.add(sensor.getName());
            }

            arrayList.forEach((n) -> Log.d(TAG + "SensorTest", n));
        }


        mAccData = new double[NSAMP];
        mSdData = new SdData();
        mVibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);


        // Prevent sleeping
        PowerManager pm = (PowerManager) (getApplicationContext().getSystemService(Context.POWER_SERVICE));
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A:WT");

        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire(24 * 60 * 60 * 1000L /*1 DAY*/);
        }

        // Initialise the Google API Client so we can use Android Wear messages.
        try {
            if (mMobileDeviceConnected) {
                sendDataToPhone();
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void requestPermissions(String[] strings, int i) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "onBind()");
        try {
            Log.v(TAG, "onRebind()");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG, "onUnbind()");

        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy()");
        mSensorManager.unregisterListener(this);
        try {
            Wearable.getMessageClient(mContext).removeListener(this);
            Wearable.getCapabilityClient(mContext).removeListener(this);
        } catch (Exception e) {

        }
        mWakeLock.release();
    }

    @Override
    public void onRebind(Intent intent) {

        try {
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
            Log.v(TAG, "onRebind()");
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onRebind(intent);
    }

    private void checkAlarm() {
        if (mSdData.alarmState == 6 || mSdData.alarmState == 10) {
            //ignore alarms when muted
            return;
        }
        boolean inAlarm = false;
        if (mSdData.roiPower > mSdData.alarmThresh && mSdData.roiRatio > mSdData.alarmRatioThresh) {
            inAlarm = true;
        }
        if (mSdData.heartAvg != 0 && ((float) mSdData.heartCur) > ((float) mSdData.heartAvg) * mHeartPercentThresh) {
            inAlarm = true;
            alarmCount = (int) mSdData.alarmTime;
        }
        Log.v(TAG, "roiPower " + mSdData.roiPower + " roiRaTIO " + mSdData.roiRatio);

        if (inAlarm) {
            alarmCount += 1;
            if (alarmCount > mSdData.alarmTime) {
                mSdData.alarmState = 2;
            } else if (alarmCount > mSdData.warnTime) {
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
        if (mSdData.alarmState == 1 || mSdData.alarmState == 2) {
            Intent intent = new Intent(this.getApplicationContext(), StartUpActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }


    }

    private double calculateAverage(List<Integer> marks) {
        Integer sum = 0;
        if (!marks.isEmpty()) {
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

        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE && event.values.length > 0) {
            int newValue = Math.round(event.values[0]);
            //Log.d(LOG_TAG,sensorEvent.sensor.getName() + " changed to: " + newValue);
            // only do something if the value differs from the value before and the value is not 0.
            if (curHeart != newValue && newValue != 0) {
                // save the new value
                curHeart = newValue;
                // add it to the list and computer a new average
                if (heartRates.size() == 10) {
                    heartRates.remove(0);
                }
                heartRates.add(curHeart);
            }
            avgHeart = (int) calculateAverage(heartRates);
            if (heartRates.size() < 4) {
                avgHeart = 0;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (mMode == 0) {
                if (mStartEvent == null) {
                    Log.v(TAG, "mMode=0 - checking Sample Rate - mNSamp = " + mNSamp);
                    Log.v(TAG, "saving initial event data");
                    mStartEvent = event;
                    mStartTs = event.timestamp;
                    mNSamp = 0;
                } else {
                    mNSamp++;
                }
                if (mNSamp >= 1000) {
                    Log.v(TAG, "Collected Data = final TimeStamp=" + event.timestamp + ", initial TimeStamp=" + mStartTs);
                    double dT = 1e-9 * (event.timestamp - mStartTs);
                    mSampleFreq = mNSamp / dT;
                    Log.v(TAG, "Collected data for " + dT + " sec - calculated sample rate as " + mSampleFreq + " Hz");
                    mMode = 1;
                    mNSamp = 0;
                    mStartTs = event.timestamp;
                }
            } else if (mMode == 1) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                //Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
                mAccData[mNSamp] = (x * x + y * y + z * z);
                mNSamp++;
                if (mNSamp == NSAMP) {
                    // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                    // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                    // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                    // FIXME - we should do some sort of check and disregard samples with long delays in them.
                    double dT = 1e-9 * (event.timestamp - mStartTs);
                    int sampleFreq = (int) (mNSamp / dT);
                    Log.v(TAG, "Collected " + NSAMP + " data points in " + dT + " sec (=" + sampleFreq + " Hz) - analysing...");

                    doAnalysis();
                    checkAlarm();
                    mNSamp = 0;
                    mStartTs = event.timestamp;
                } else if (mNSamp > NSAMP) {
                    Log.v(TAG, "Received data during analysis - ignoring sample");
                }

            } else {
                Log.v(TAG, "ERROR - Mode " + mMode + " unrecognised");
            }
        } else {
            Log.d(TAG + "SensorResult", String.valueOf(mSensor.getType()));
        }
        try {
            mSdData.dataTime.setToNow();
            //mSdData.maxVal =    // not used
            //mSdData.maxFreq = 0;  // not used
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
            float batteryPct = 100 * level / (float) scale;
            mSdData.batteryPc = (int) (batteryPct);
        } catch (Exception e) {
            Log.d(TAG, "doAnalysis(): Try0 Failed to run analysis", e);
        }
        //Only send to phone when connected.
        if (mMobileDeviceConnected) {
            logNotConnectedMessage = true;

            try {
                sendDataToPhone();
            } catch (Exception e) {
                Log.e(TAG, "sendDataToPhone(): Failed to run analysis", new Throwable());
            }
        } else if (logNotConnectedMessage && !logNotConnectedMessagePf)
            Log.d(TAG, "Not sending data. Reason: Not connected");
        else if (logNotConnectedMessage) logNotConnectedMessagePf = true;
        else logNotConnectedMessagePf = false;
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    private void doAnalysis() {
        try {

            double freqRes = 1.0 * mSampleFreq / mNSamp;
            Log.v(TAG, "doAnalysis(): mSampleFreq=" + mSampleFreq + " mNSamp=" + mNSamp + ": freqRes=" + freqRes);
            // Set the frequency bounds for the analysis in fft output bin numbers.
            double nMin = mAlarmFreqMin / freqRes;
            double nMax = mAlarmFreqMax / freqRes;
            Log.v(TAG, "doAnalysis(): mAlarmFreqMin=" + mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            double nFreqCutoff = mFreqCutoff / freqRes;
            Log.v(TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            DoubleFFT_1D fftDo = new DoubleFFT_1D(mNSamp);
            double[] fft = new double[mNSamp * 2];
            System.arraycopy(mAccData, 0, fft, 0, mNSamp);
            fftDo.realForwardFull(fft);

            // Calculate the whole spectrum power (well a value equivalent to it that avoids suare root calculations
            // and zero any readings that are above the frequency cutoff.
            double specPower = 0;
            for (int i = 1; i < mNSamp / 2; i++) {
                if (i <= nFreqCutoff) {
                    specPower = specPower + fft[2 * i] + fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1];

                } else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            }
            specPower = specPower / mNSamp / 2;

            // Calculate the Region of Interest power and power ratio.
            double roiPower = 0d;
            for (int i = (int) nMin; i < nMax; i++) {
                roiPower = roiPower + fft[2 * i] + fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1];
            }
            roiPower = roiPower / (nMax - nMin);
            double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins.
            double[] simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                double binMin = 1.0 + ifreq / freqRes;    // add 1 to loose dc component
                double binMax = 1.0 + (ifreq + 1.0) / freqRes;
                simpleSpec[ifreq] = 0;
                for (int i = (int) binMin; i < binMax; i++) {
                    simpleSpec[ifreq] = simpleSpec[ifreq] + fft[2 * i] + fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1];
                }
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);

                // Populate the mSdData structure to communicate with the main SdServer service.
                mSdData.specPower = (long) specPower;
                mSdData.roiPower = (long) roiPower;
                mSdData.roiRatio = (long) roiRatio;

            }
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                mSdData.simpleSpec[i] = (int) simpleSpec[i];
            }


        } catch (Exception e) {
            Log.e(TAG, "Failed Analysis internally: ", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public void handleSendingIAmOK() {
        if (mSdData != null && mSdData.alarmState == 10) {
            sendDataToPhone();
        }
    }

    public void handleSendingHelp() {
        if (mSdData != null && mSdData.alarmState == 11) {
            sendDataToPhone();
        }
    }

    private void sendDataToPhone() {
        Log.v(TAG, "sendDataToPhone()");
        sendMessage(MESSAGE_ITEM_OSD_TEST, "Test Message");
        sendMessage(MESSAGE_ITEM_OSD_DATA, mSdData.toDataString(true));
    }

    // Send a MesageApi message text to all connected devices.
    private void sendMessage(final String path, final String text) {
        boolean returnResult = false;
        Log.v(TAG, "sendMessage(" + path + "," + text + ")");
        final byte[] payload = (text.getBytes(StandardCharsets.UTF_8));

        if (mMobileNodeUri != null) {
            if (mMobileNodeUri.isEmpty()) {
                Wearable.getCapabilityClient(mContext)
                        .addListener(
                                this,
                                Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                        );
                Wearable.getMessageClient(mContext).addListener(this);
                Log.e(TAG, "SendMessageFailed: No node-Id stored");
            } else {
                Log.v(TAG,
                        "Sending message to "
                                + mMobileNodeUri
                );
                final Task sendMessageTask = Wearable.getMessageClient(mContext)
                        .sendMessage(mMobileNodeUri, path, text.getBytes(StandardCharsets.UTF_8));

                try {
                    // Block on a task and get the result synchronously (because this is on a background thread).
                    sendMessageTask.addOnCompleteListener(task -> {
                                if (task.isSuccessful()) {
                                    Log.v(TAG, "Message: {" + text + "} sent to: " + mMobileNodeUri);

                                } else {
                                    // Log an error
                                    Log.e(TAG, "ERROR: failed to send Message to :" + mMobileNodeUri);
                                    mMobileDeviceConnected = false;
                                    mMobileNodeUri = null;
                                }
                            }
                    );
                    Log.d(TAG_MESSAGE_RECEIVED, "Ended task, result through callback.");
                } catch (Exception e) {
                    Log.e(TAG, "Error encoding string to bytes");
                    e.printStackTrace();
                }
            }

        } else {
            Log.e(TAG, "SendMessageFailed: No node-Id initialized");
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
        }

    }

    public class Access extends Binder {
        public AWSdService getService() {
            return AWSdService.this;
        }
    }

}
