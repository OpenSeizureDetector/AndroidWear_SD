package uk.org.openseizuredetector;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
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
import android.os.health.HealthStats;
import android.text.format.Time;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.services.client.HealthServicesClient;
import androidx.health.services.client.MeasureCallback;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DeltaDataType;
import androidx.health.services.client.data.MeasureCapabilities;
import androidx.health.services.client.data.SampleDataPoint;
import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

public class AWSdService extends Service implements SensorEventListener,
        MessageClient.OnMessageReceivedListener,
        CapabilityClient.OnCapabilityChangedListener,
        MeasureClient {

    public boolean requestCreateNewChannelAndInit = false;
    private SharedPreferenceManagerToReplace sharedPreferenceManager;
    private final static String TAG = "AWSdService";
    public static final String ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION";
    private final String wearableAppCheckPayload = "AppOpenWearable";
    private final String wearableAppCheckPayloadReturnACK = "AppOpenWearableACK";
    private final String TAG_MESSAGE_RECEIVED = "SdDataSourceAw";
    private final String MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received";
    private final String MESSAGE_ITEM_OSD_TEST = "/testMsg";
    private final String MESSAGE_ITEM_OSD_DATA = "/data";
    private final String MESSAGE_ITEM_OSD_TEST_RECEIVED = "/testMsg-received";
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 16;
    private final String MESSAGE_ITEM_OSD_DATA_RECEIVED = "/data-received";
    private final String MESSAGE_ITEM_PATH = "/message-item";
    private final String APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD";
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "2";
    private static List<String> channelIDs = new ArrayList<>();
    private final String MESSAGE_ITEM_OSD_DATA_REQUESTED = "/data-requested";
    private final static int NSAMP = 250;
    private final static int SIMPLE_SPEC_FMAX = 10;   // simple spectrum maximum freq in Hz.
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private PowerManager pm;
    private int mMode = 0;   // 0=check data rate, 1=running
    private HealthStats healthStats;
    private SensorEvent mStartEvent = null;
    private SensorManager mHeartSensorManager;
    private HealthConnectClient mHealthConnectClient;
    private Sensor mHeartSensor;
    Vibrator mVibe;
    private Sensor mHeartBeatSensor;
    private Sensor mBloodPressure;
    private Sensor mStationaryDetectSensor;
    private SensorEvent mHeartStartEvent = null;
    private long mStartTs = 0;
    public int mNSamp = 0;
    public double mSampleFreq = 0d;
    public double[] mAccData;
    private double[] simpleSpec;
    public SdData mSdData;
    NotificationChannel channel;
    private MessageEvent mMessageEvent = null;
    private String mMobileNodeUri = null;
    private boolean successInitialSend;
    private boolean mSensorsBound = false;
    private float x;
    private float y;
    private float z;
    private DoubleFFT_1D fftDo;
    private double[] fft;

    @Override
    public void onCreate() {
        Log.v(TAG, "onCreate()");
    }

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
    private String currentAckFromWearForAppOpenCheck = null;
    // private Sensor mO2Sensor; disabled until privileged API Samsung is acquired
    private CapabilityInfo mMobileNodesWithCompatibility = null;
    private boolean logNotConnectedMessage;
    private boolean logNotConnectedMessagePf;
    public String appName;
    private double dT;
    NotificationCompat.Builder notificationCompatBuilder;
    private IBinder mBinder = null;

    public void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        try {
            if (mContext == null) mContext = this.getBaseContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CharSequence name = mContext.getResources().getString(R.string.app_name);
                String description = mContext.getResources().getString(R.string.hello_round);
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                String channelIdToUse = returnNewCHANNEL_ID();
                channel = new NotificationChannel(channelIdToUse, name, importance);
                channel.setDescription(description);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    channel.setBlockable(true);
                }
                // Register the channel with the system; you can't change the importance
                // or other notification behaviors after this
                if (mContext != null) {
                    notificationManager = NotificationManagerCompat.from(mContext);
                    notificationManager.createNotificationChannel(channel);
                    if (!notificationManager.areNotificationsEnabled()) {
                        Log.e(TAG, "createNotificationChannel() - Failure to use notifications. Not enabled", new Throwable());
                    }
                } else {
                    Log.e(TAG, "createNotificationChannel() cannot continue creating channel without context");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "CreateNotificationChannel()", e);
        }

    }


    private String mNodeFullName;
    private NodeClient mNodeListClient;
    private Context mContext;
    private Boolean mMobileDeviceConnected = false;
    // private Sensor mO2Sensor; disabled until privileged API Samsung is acquired
    private int mHeartMode = 0;   // 0=check data rate, 1=running
    private Intent notificationIntent = null;
    private Node mWearNode;
    private MessageClient mApiClient;
    private PowerManager.WakeLock mWakeLock;
    private HealthServicesClient healthServicesClient;
    private MeasureClient measureClient;
    IntentFilter ifilter;
    Intent batteryStatus;
    public String appDescription;
    NotificationManagerCompat notificationManager;

    public AWSdService() {
        Log.v(TAG, "AWSdService Constructor()");
        mContext = this;

        if (mSdData == null) mSdData = new SdData();

    }

    private static final String returnNewCHANNEL_ID() {
        String currentID = String.valueOf(R.string.app_name) + channelIDs.size();
        channelIDs.add(channelIDs.size(), currentID);
        return currentID;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");
        mContext = getApplicationContext();
        sharedPreferenceManager = new SharedPreferenceManagerToReplace(mContext);
        mAccData = new double[NSAMP];
        mSdData = new SdData();

        mVibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);


        // Prevent sleeping
        PowerManager pm = (PowerManager) (getApplicationContext().getSystemService(Context.POWER_SERVICE));
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A:WT");

        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire(24 * 60 * 60 * 1000L /*1 DAY*/);
        }
        try {
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
            Log.v(TAG, "onStartCommand()");
        } catch (Exception e) {
            Log.e(TAG, "onStartCommand(): Exception in updating capabilityClient and messageClient", e);
            ;
        }

        Timer appStartTimer = new Timer();
        appStartTimer.schedule(new TimerTask() {
                                   @Override
                                   public void run() {
                                       try {
                                           Log.v(TAG, "startWatchApp() - Timer as Timeout, fires if not connected...");
                                           if (mAccData == null) mAccData = new double[NSAMP];
                                           if (mNodeListClient instanceof List)
                                               // Initialise the Google API Client so we can use Android Wear messages.
                                               try {
                                                   if (!mSdData.serverOK) initConnection();
                                               } catch (Exception e) {
                                                   Log.e(TAG, "onStartCommand() ", e);
                                               }
                                           else {
                                               Log.e(TAG, "I should throw an exception; no nodes found");
                                           }


                                       } catch (Exception e) {
                                           Log.e(TAG, "onStartCommand() timerTask run()", e);
                                       }

                                   }

                               }
                , 5000);

        if (intent == null) return START_NOT_STICKY;
        return super.onStartCommand(intent, flags, startId);
    }

    private void mStartForegroundService(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(intent);
            } else { //old pre-O behaviour
                mContext.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }
    }


    public void prepareAndStartForeground() {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
            builder.setContentTitle(String.valueOf(R.string.app_name));
            builder.setSmallIcon(R.drawable.icon_24x24);
            builder.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, AWSdService.class), PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationCompat.WearableExtender extender = new NotificationCompat.WearableExtender();
            extender.setBackground(BitmapFactory.decodeResource(getResources(), R.drawable.card_background));
            extender.setContentIcon(R.drawable.icon_24x24);
            extender.setHintHideIcon(true);
            extender.extend(builder);

            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setContentText(String.valueOf(R.string.app_name));
            builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.icon_24x24));
            returnNewCHANNEL_ID();
            notificationManager.notify(channelIDs.size(), builder.build());
        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }


    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (mSdData == null) mSdData = new SdData();

        Log.v(TAG_MESSAGE_RECEIVED, "onMessageReceived event received");
        // Get the node id of the node that created the data item from the host portion of
        // the uri.
        mMobileNodeUri = messageEvent.getSourceNodeId();


        final String s1 = Arrays.toString(messageEvent.getData());
        final String messageEventPath = messageEvent.getPath();
        Log.v(TAG, "messageEvent string" + messageEvent.toString());
        Log.v(
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
                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;
                mSdData.dataTime.setToNow();
                //TODO: Decide what to do with the population of id and name. Nou this is being treated
                // as broadcast to all client watches.
                mSdData.mDataType = "settings";
                sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
                recallStartUpActivity();
            } catch (Exception e) {
                Log.e(TAG, "Received new settings failed to process", e);
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_TEST_RECEIVED)) {
            //TODO
            sendMessage(MESSAGE_ITEM_OSD_TEST, wearableAppCheckPayloadReturnACK);
            recallStartUpActivity();
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_DATA_RECEIVED)) {
            Log.v(TAG, "Received new settings");

            try {

                mSdData.fromJSON(s1);
                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;
                mSampleFreq = mSdData.mSampleFreq;

                //TODO: Decide what to do with the population of id and name. Nou this is being treated
                // as broadcast to all client watches.
                mSdData.mDataType = "settings";
                sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
                recallStartUpActivity();


            } catch (Exception e) {
                Log.e(TAG, "Received new settings failed to process", e);
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_DATA_REQUESTED)) {
            try {
                Log.v(TAG, "onMessageReived() : if receivedData ");
                mSdData.fromJSON(s1);

                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;

                //TODO: Decide what to do with the population of id and name. Nou this is being treated
                // as broadcast to all client watches.
                mSdData.mDataType = "settings";
                sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
                recallStartUpActivity();

            } catch (Exception e) {
                Log.e(TAG, "OnMessageReceived(): catch on Received new settings failed to process", e);
            }
        } else {
            Log.v(TAG + "nn", "Not processing received message, displaying in log: " + s1);
        }
        Log.v(TAG, "onMessageReceived(): initialising sensors if not initialized.");
        try {
            if (mSensorManager == null && !mSensorsBound) bindSensorListeners();
        } catch (Exception e) {
            Log.e(TAG, "onMessageReceived(): failed to initialize sensors.", e);
        }

    }

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(TAG, "CapabilityInfo received: " + capabilityInfo.toString());
        if (mSensorManager != null && !mSdData.serverOK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
                ArrayList<String> arrayList = new ArrayList<String>();
                for (Sensor sensor : sensors) {
                    arrayList.add(sensor.getName());
                }

                arrayList.forEach((n) -> Log.d(TAG + "SensorTest", n));
            }
        }
        if (capabilityInfo.equals(Uri.parse("wear://"))) {
            mMobileNodesWithCompatibility = capabilityInfo;
            if (mMobileNodesWithCompatibility.getNodes().isEmpty()) {
                Wearable.getMessageClient(mContext).removeListener(this);
            } else {
                Wearable.getMessageClient(mContext).addListener(this);
            }
        }
        recallStartUpActivity();
    }



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


    public void bindSensorListeners() {
        try {
            ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            batteryStatus = getApplicationContext().registerReceiver(null, ifilter);
            mSdData.watchSdVersion = BuildConfig.VERSION_NAME;
            mSdData.watchFwVersion = Build.DISPLAY;
            mSdData.watchPartNo = Build.BOARD;
            mSdData.watchSdName = Build.MODEL;

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


            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
            mHeartSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
            mHeartBeatSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_BEAT);
            mSensorManager.registerListener(this, mHeartBeatSensor, SensorManager.SENSOR_DELAY_UI);
            mBloodPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            mSensorManager.registerListener(this, mBloodPressure, SensorManager.SENSOR_DELAY_UI);
            mStationaryDetectSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
            mSensorManager.registerListener(this, mStationaryDetectSensor, SensorManager.SENSOR_DELAY_UI);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerMeasureCallback(DeltaDataType.VO2_MAX, new MeasureCallback() {
                    @Override
                    public void onRegistered() {

                    }

                    @Override
                    public void onRegistrationFailed(@NonNull Throwable throwable) {

                    }

                    @Override
                    public void onAvailabilityChanged(@NonNull DeltaDataType<?, ?> deltaDataType, @NonNull Availability availability) {

                    }

                    @Override
                    public void onDataReceived(@NonNull DataPointContainer dataPointContainer) {
                        List<SampleDataPoint<Double>> mO2Data = dataPointContainer.getData(DeltaDataType.VO2_MAX);
                        if (!mO2Data.isEmpty()) for (SampleDataPoint o2SamplePoint : mO2Data
                        ) {
                            mSdData.mO2Sat = (double) o2SamplePoint.getValue();

                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "onStartCommand(): Sensor declaration excepmted: ", e);
        }


    }


    public void initConnection() {
        try {
            if (mContext == null) mContext = this;
            if (mNodeListClient == null) mNodeListClient = Wearable.getNodeClient(mContext);
            Task<List<Node>> nodeliststask = mNodeListClient.getConnectedNodes();
            nodeliststask.addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful()) {
                        List<Node> nodelists = task.getResult();
                        for (Node connectedNode : nodelists) {
                            mSdData.mDataType = "watchConnect";
                            mSdData.watchAppRunning = true;
                            mSdData.watchConnected = true;
                            mSdData.haveSettings = true;
                            //TODO: Deside what to do with the population of id and name. Nou this is being treated
                            // as broadcast to all client watches.
                            mMobileNodeUri = connectedNode.getId();
                            mNodeFullName = connectedNode.getDisplayName();
                            mSdData.dataTime.setToNow();

                            sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toDataString(false));
                            successInitialSend = true;
                        }
                    } else {
                        Log.e(TAG,
                                "initConnection(): nodeliststask has endend with: isCancelled: "
                                        + task.isCanceled() + "or task.isFailed: " + !task.isSuccessful());
                        successInitialSend = false;
                    }

                } catch (Exception e) {
                    Log.e(TAG, "initConnection():  ", e);
                    successInitialSend = false;
                }

                    }
            );

        } catch (Exception e) {
            Log.e(TAG, "initConnection():  ", e);
            successInitialSend = false;
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "onBind()");
        if (mContext == null) mContext = this.getApplicationContext();
        if (sharedPreferenceManager == null)
            sharedPreferenceManager = new SharedPreferenceManagerToReplace(mContext);
        mSdData = new SdData();

        mVibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);


        // Prevent sleeping
        if (pm == null)
            pm = (PowerManager) (getApplicationContext().getSystemService(Context.POWER_SERVICE));
        if (mWakeLock == null) mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A:WT");

        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire(24 * 60 * 60 * 1000L /*1 DAY*/);
        }
        try {
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
            createNotificationChannel();
            prepareAndStartForeground();

            if (mSdData != null) if (mSdData.serverOK) if (mSensorsBound) bindSensorListeners();
            else try {
                    Log.v(TAG, "onBind(): no mSdData.serverOK, init connection from bind");
                    // Initialise the Google API Client so we can use Android Wear messages.
                    try {
                        if (!mSdData.serverOK) initConnection();
                    } catch (Exception e) {
                        Log.e(TAG, "onStartCommand() ", e);
                    }
                    Log.v(TAG, "onBind(): result of successInitialSend: " + successInitialSend);
                } catch (Exception e) {
                    Log.e(TAG, "Received new settings failed to process", e);
                }
        } catch (Exception e) {
            Log.e(TAG, "Received new settings failed to process", e);
        }
        return mBinder;
    }

    private void requestPermissions(String[] strings, int i) {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy()");

        try {
            if (mSensorsBound) mSensorManager.unregisterListener(this);
            mSdData.watchConnected = false;
            mSdData.watchAppRunning = false;
            mSdData.mDataType = "watchDisconnect";
            sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
            Wearable.getMessageClient(mContext).removeListener(this);
            Wearable.getCapabilityClient(mContext).removeListener(this);
            if (mWakeLock != null) if (mWakeLock.isHeld()) mWakeLock.release();
            if (mSensorsBound) mSensorsBound = false;
            Log.e(TAG, "onDestroy(): we should not fire onDestroy! However just did....: ");
        } catch (Exception e) {
            Log.e(TAG, "onDestroy(): we should not fire onDestroy! However just did and exempted: ", e);
        }

    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG, "onUnbind()");

        try {
            if (this.mWakeLock != null) {
                Log.v(TAG, "onUnbind() mWakeLock=" + mWakeLock.isHeld());
            }
            if (channel != null) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (channel.isBlockable()) channel.notify();
            }
        } catch (IllegalStateException illegalStateException) {
            Log.e(TAG, "onUnbind(): no owner of thread", illegalStateException);
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {

        try {
            createNotificationAndInitConnection();
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
            if (mSdData != null) if (mSdData.serverOK) if (!mSensorsBound) bindSensorListeners();
            Log.v(TAG, "onRebind()");
        } catch (Exception e) {
            Log.e(TAG, "onRebind(): Exception in updating capabilityClient and messageClient", e);

        }
        super.onRebind(intent);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        //check if mSdData is populated
        if (mSdData == null) mSdData = new SdData();
        if (mSdData.dataTime == null) mSdData.dataTime = new Time();
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

            mSdData.heartCur = curHeart;
            mSdData.mHR = curHeart;
            mSdData.heartAvg = avgHeart;
            checkAlarm();
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
                if (mNSamp == 1000) {
                    Log.v(TAG, "Collected Data = final TimeStamp=" + event.timestamp + ", initial TimeStamp=" + mStartTs);
                    dT = 1e-9 * (event.timestamp - mStartTs);
                    mSdData.dT = dT;
                    mSampleFreq = ((double) mNSamp) / dT;
                    mSdData.mSampleFreq = (long) mSampleFreq;
                    Log.v(TAG, "Collected data for " + dT + " sec - calculated sample rate as " + mSampleFreq + " Hz");
                    mMode = 1;
                    mNSamp = 0;
                    mStartTs = event.timestamp;
                }
            } else if (mMode == 1) {
                try {
                    x = event.values[0];
                    y = event.values[1];
                    z = event.values[2];
                    //Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
                    if (mAccData == null) {
                        mAccData = new double[NSAMP];
                    } else {
                        if (mAccData.length < mNSamp)
                            Log.v(TAG, "OnSensorChanged(): error in arraybuilder");
                    }

                    if (mAccData != null) {
                        mAccData[mNSamp] = (x * x + y * y + z * z);
                        mNSamp++;
                    }
                    if (mNSamp == NSAMP) {
                        // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                        // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                        // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                        // FIXME - we should do some sort of check and disregard samples with long delays in them.
                        double dT = 1e-9 * (event.timestamp - mStartTs);
                        int sampleFreq = (int) (mNSamp / dT);
                        Log.v(TAG, "Collected " + NSAMP + " data points in " + dT + " sec (=" + sampleFreq + " Hz) - analysing...");

                        doAnalysis();
                        mSdData.haveData = true;
                        mSdData.haveSettings = true;
                        mSdData.watchConnected = true;
                        mSdData.alarmThresh = mAlarmThresh;
                        mSdData.alarmRatioThresh = mAlarmRatioThresh;
                        mSdData.alarmTime = mAlarmTime;

                        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                        float batteryPct = 100 * level / (float) scale;
                        mSdData.batteryPc = (int) (batteryPct);
                        sendDataToPhone();

                        mNSamp = 0;
                        mStartTs = event.timestamp;
                        try {
                            mSdData.dataTime.setToNow();
                            //mSdData.maxVal =    // not used
                            //mSdData.maxFreq = 0;  // not usedx


                        } catch (Exception e) {
                            Log.e(TAG, "doAnalysis(): Try0 Failed to run analysis", e);
                        }
                    } else if (mNSamp > NSAMP) {
                        Log.v(TAG, "Received data during analysis - ignoring sample");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OnSensorChanged(): trying to process accelerationData failed", e);
                }
            } else {
                Log.v(TAG, "ERROR - Mode " + mMode + " unrecognised");
            }
        } else {
            Log.d(TAG, "SensorResult not caught sensor: " + mSensor.getName() + " including typeInt: " + mSensor.getType() + " including typeName: " + mSensor.getStringType());


        }


    }

    public boolean isCharging() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean bCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;
        return bCharging;
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
        if (mSdData.heartAvg != 0 && mSdData.heartCur > mSdData.heartAvg * mHeartPercentThresh) {
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
            if (!isCharging()) {
                long[] pattern = {0, 100, 200, 300};
                mVibe.vibrate(pattern, -1);
            }

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
            recallStartUpActivity();
        }


    }

    private void recallStartUpActivity() {
        Intent intent = new Intent(this.getApplicationContext(), StartUpActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
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

    // Send a MesageApi message text to all connected devices.
    private void sendMessage(final String path, final String text) {
        boolean returnResult = false;
        if (mNodeListClient == null)
            mNodeListClient = Wearable.getNodeClient(mContext);
        Log.v(TAG, "sendMessage(" + path + "," + text + ")");
        Task<Integer> sendMessageTask = null;
        if (mMobileNodeUri != null) {
            try {
                if (mMobileNodeUri.isEmpty()) {
                    Wearable.getCapabilityClient(mContext)
                            .addListener(
                                    this,
                                    Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                            );
                    Wearable.getMessageClient(mContext).addListener(this);
                    Log.e(TAG, "SendMessageFailed: No node-Id stored");
                } else {
                    String tmpFullname;
                    boolean createFromNode = false;
                    if (mNodeFullName == null) createFromNode = true;
                    else if (mNodeFullName.isEmpty()) createFromNode = true;
                    if (createFromNode) initConnection();
                    //mNodeFullName = nodelists.indexOf(mWearNode.getDisplayName())
                }

                Log.v(TAG,
                        "Sending message to "
                                + mMobileNodeUri + " And name: " + mNodeFullName
                );
                sendMessageTask = Wearable.getMessageClient(mContext)
                        .sendMessage(mMobileNodeUri, path, text.getBytes(StandardCharsets.UTF_8));


                // Asynchronous callback for result of sendMessageTask
                sendMessageTask.addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.v(TAG, "Message: {" + text + "} sent to: " + mMobileNodeUri);

                    } else {
                        // Log an error
                        Log.e(TAG, "ERROR: failed to send Message to: " + mMobileNodeUri);
                                mMobileDeviceConnected = false;
                                mMobileNodeUri = null;
                                mNodeFullName = null;
                                successInitialSend = false;
                            }
                        }
                );
                Log.d(TAG_MESSAGE_RECEIVED, "Ended task, result through callback.");
            } catch (Exception e) {
                Log.e(TAG, "sendMessage() if (mMobileNodeUri) try: Error encoding string to bytes", e);
            }


        } else {
            Log.e(TAG, "SendMessageFailed: No node-Id initialized");
            initConnection();
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
        }

    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    private void doAnalysis() {
        try {

            double freqRes = 1.0d * mSampleFreq / mNSamp;
            Log.v(TAG, "doAnalysis(): mSampleFreq=" + mSampleFreq + " mNSamp=" + mNSamp + ": freqRes=" + freqRes);
            // Set the frequency bounds for the analysis in fft output bin numbers.
            double nMin = mAlarmFreqMin / freqRes;
            double nMax = mAlarmFreqMax / freqRes;
            Log.v(TAG, "doAnalysis(): mAlarmFreqMin=" + mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            double nFreqCutoff = mFreqCutoff / freqRes;
            Log.v(TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            fftDo = new DoubleFFT_1D(mNSamp);
            fft = new double[mNSamp * 2];
            mSdData.rawData = new double[mNSamp * 2];
            System.arraycopy(mAccData, 0, fft, 0, mNSamp);
            System.arraycopy(mAccData, 0, mSdData.rawData, 0, mNSamp);
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
            simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                double binMin = 1.0 + ifreq / freqRes;    // add 1 to loose dc component
                double binMax = 1.0 + (ifreq + 1.0) / freqRes;
                simpleSpec[ifreq] = 0;
                for (int i = (int) binMin; i < binMax; i++) {
                    simpleSpec[ifreq] = simpleSpec[ifreq] + fft[2 * i] + fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1];
                }
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);

            }
            // Populate the mSdData structure to communicate with the main SdServer service.
            mSdData.specPower = (long) specPower;
            mSdData.roiPower = (long) roiPower;
            mSdData.roiRatio = (long) roiRatio;
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                mSdData.simpleSpec[i] = (int) simpleSpec[i];
            }
            //sending from analysis
            Log.v(TAG, "OnSensorChanged() doAnalasys() forced send");

            mSdData.mDataType = "raw";
            mSdData.dataTime.setToNow();
            //sendMessage(MESSAGE_ITEM_OSD_DATA, mSdData.toDataString(true));

        } catch (Exception e) {
            Log.e(TAG, "doAnalysis():  Failed Analysis internally: ", e);
            boolean ttt = false;
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
        if (mSdData.batteryPc > 0) {
            mSdData.haveSettings = true;
            mSdData.watchAppRunning = true;
        }
        sendMessage("/data", mSdData.toDataString(true));
    }

    public void createNotificationAndInitConnection() {
        mContext = this.getBaseContext();
        try {

            if (mSdData == null) mSdData = new SdData();

            //if (mTextView != null) mTextView.setText("Service Started");
            //if (mTextView != null) mTextView.setText("onStart");

            createNotificationChannel();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                prepareAndStartForeground();
            }
            mStartForegroundService(notificationIntent);
            // If the notification supports a direct reply action, use
            // PendingIntent.FLAG_MUTABLE instead.

        } catch (Exception e) {
            Log.e(TAG, " createNotificationAndInitConnection() - Starting Service failed", e);
        }
        Log.v(TAG, "createNotificationAndInitConnection() - populating mNodeList");
        try {
            if (mNodeListClient == null)
                mNodeListClient = Wearable.getNodeClient(mContext);
            Log.v(TAG, "createNotificationAndInitConnection() - checking permission for sensors and registering");
            if (mSdData.serverOK) bindSensorListeners();
            if (mSdData != null) if (mSdData.serverOK) bindSensorListeners();
            else try {
                    Log.v(TAG, "createNotificationAndInitConnection(): no mSdData.serverOK, init connection from bind");
                    initConnection();
                    Log.v(TAG, "createNotificationAndInitConnection(): result of successInitialSend: " + successInitialSend);
                } catch (Exception e) {
                    Log.e(TAG, "createNotificationAndInitConnection(): Received new settings failed to process", e);
                }
        } catch (Exception e) {
            Log.e(TAG, "createNotificationAndInitConnection(): failed to getNodeClient with mContext as: " + mContext.toString(), e);

        }

    }

    public class SharedPreferenceManagerToReplace {
        private final SharedPreferences prefs;

        public SharedPreferenceManagerToReplace(Context context) {
            this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
    }


    @Override
    public void registerMeasureCallback(@NonNull DeltaDataType<?, ?> deltaDataType, @NonNull MeasureCallback measureCallback) {

    }

    @Override
    public void registerMeasureCallback(@NonNull DeltaDataType<?, ?> deltaDataType, @NonNull Executor executor, @NonNull MeasureCallback measureCallback) {

    }


    @NonNull
    @Override
    public ListenableFuture<MeasureCapabilities> getCapabilitiesAsync() {
        return null;
    }

    @NonNull
    @Override
    public ListenableFuture<Void> unregisterMeasureCallbackAsync(@NonNull DeltaDataType<?, ?> deltaDataType, @NonNull MeasureCallback measureCallback) {
        return null;
    }

    public class Access extends Binder {
        public AWSdService getService() {
            return AWSdService.this;
        }

    }


}
