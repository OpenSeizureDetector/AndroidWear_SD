package uk.org.openseizuredetector;


import static java.lang.Math.sqrt;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.text.format.Time;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.Wearable;

import org.jtransforms.fft.DoubleFFT_1D;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

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
    public boolean requestCreateNewChannelAndInit;
    public StartUpActivity.Connection parentConnection;
    public Context parentContext;
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
    // Notification ID
    private final int NOTIFICATION_ID = 1;
    private final int EVENT_NOTIFICATION_ID = 2;
    private final int DATASHARE_NOTIFICATION_ID = 3;
    public SdData mSdData;
    // private Sensor mO2Sensor; disabled until privileged API Samsung is acquired
    private int mHeartMode = 0;   // 0=check data rate, 1=running
    private MessageEvent mMessageEvent = null;
    private String mMobileNodeUri = null;
    private final IBinder mBinder = new SdBinder();
    private double[] fft;
    private DoubleFFT_1D fftDo;
    private double[] simpleSpec;
    private CharSequence mNotChName = "OSD Notification Channel";

    private int mAlarmFreqMin = 3;  // Frequency ROI in Hz
    private int mAlarmFreqMax = 8;  // Frequency ROI in Hz
    private int mFreqCutoff = 12;   // High Frequency cutoff in Hz
    private int mAlarmThresh = 900000;
    private int mAlarmRatioThresh = 275;
    private int mAlarmTime = 3;
    private double mHeartPercentThresh = 1.3;
    private int alarmCount = 0;
    private int curHeart = 0;
    private int avgHeart = 0;
    private ArrayList<Integer> heartRates = new ArrayList<Integer>(10);
    private CapabilityInfo mMobileNodesWithCompatibility = null;
    private boolean logNotConnectedMessage;
    private boolean logNotConnectedMessagePf;
    public static final String ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION";
    private double dT;
    Vibrator mVibe;
    private String mEventNotChId = "OSD Event Notification Channel";

    public boolean mBound = false;
    private String mNodeFullName;
    private NodeClient mNodeListClient;
    private Node mWearNode;
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "2";
    private static List<String> channelIDs = new ArrayList<>();

    private MessageClient mApiClient;
    private PowerManager.WakeLock mWakeLock;
    private Intent notificationIntent = null;
    private NotificationChannel channel;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationCompatBuilder;
    private Notification mNotification = null;
    private Intent intentFromOnStart;
    private Intent intentFromOnBind;
    private Intent intentFromOnRebind;
    private NotificationManager mNM;
    private NotificationCompat.Builder mNotificationBuilder;
    private Handler mHandler;
    private OsdUtil mUtil;


    public AWSdService() {
        Log.v(TAG, "AWSdService Constructor()");
        mContext = this;
        Log.d(TAG, "AWSdSevice Constructor result of context compare: " + mContext +
                " and parentContext: " + parentContext + " result compare: " +
                Objects.equals(mContext, parentContext));
        if (mSdData == null) mSdData = new SdData();
        if (requestCreateNewChannelAndInit) {
            createNotificationChannel();
            prepareAndStartForeground();
            mStartForegroundService(notificationIntent);
        }


    }


    /**
     * onCreate() - called when services is created.  Starts message
     * handler process to listen for messages from other processes.
     */
    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        mHandler = new Handler(Looper.getMainLooper());
        mSdData = new SdData();
        mContext = this;
        mUtil = new OsdUtil(getApplicationContext(), mHandler);

    }

    /**
     * Show a notification while this service is running.
     */
    private void showNotification(int alarmLevel) {
        Log.v(TAG, "showNotification() - alarmLevel=" + alarmLevel);
        int iconId;
        String titleStr;
        Uri soundUri = null;

        iconId = R.drawable.star_of_life_24x24;
        titleStr = "OK";
        //       if (mAudibleWarning)
        //         soundUri = Uri.parse("android.resource://" + getPackageName() + "/raw/warning");


        Intent i = new Intent(getApplicationContext(), StartUpActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int Flag_Intend;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Flag_Intend = PendingIntent.FLAG_IMMUTABLE;
        } else {
            Flag_Intend = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent contentIntent =
                PendingIntent.getActivity(this,
                        0, i, Flag_Intend);
        String smsStr = " TEST ";


        if (mNotificationBuilder != null) {
            mNotification = mNotificationBuilder.setContentIntent(contentIntent)
                    .setSmallIcon(iconId)
                    .setColor(0x00ffffff)
                    .setAutoCancel(false)
                    .setContentTitle(titleStr)
                    .setContentText(smsStr)
                    .setOnlyAlertOnce(true)
                    .build();

            mNM.notify(NOTIFICATION_ID, mNotification);
        } else {
            Log.i(TAG, "showNotification() - notification builder is null, so not showing notification.");
        }
    }

    private Date returnDateFromDouble(double valueToConvert) {
        Date returnResult = Calendar.getInstance().getTime();
        try {
            returnResult.setTime((long) (valueToConvert * (60 * 60 * 24 * 1000)));
        } catch (Exception e) {
            Log.e(TAG, "returnDateFromDouble(): FAiled converting double To Date", e);
        }
        return returnResult;
    }

    private double returnDoubleFromDate(Date valueToConvert) {
        return ((double) valueToConvert.getTime() / (60 * 60 * 24 * 1000));
    }

    private static final String returnNewCHANNEL_ID() {
        String currentID = String.valueOf(R.string.app_name) + channelIDs.size();
        channelIDs.add(channelIDs.size(), currentID);
        return currentID;
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            String description = getString(R.string.app_name);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            channel = new NotificationChannel("Default notification", name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            if (!notificationManager.areNotificationsEnabled()) {
                Log.e(TAG, "createNotificationChannel() - Failure to use notifications. Not enabled", new Throwable());
            } else Log.d(TAG, "createNotificationChannel(): notifications are enabled");
        }
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
            requestCreateNewChannelAndInit = false;
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext);
            builder.setContentTitle(String.valueOf(R.string.app_name));
            builder.setSmallIcon(R.drawable.icon_24x24);
            builder.setContentIntent(PendingIntent.getActivity(mContext, 0, new Intent(this, AWSdService.class), PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationCompat.WearableExtender extender = new NotificationCompat.WearableExtender();
            extender.setBackground(BitmapFactory.decodeResource(getResources(), R.drawable.card_background));
            extender.setContentIcon(R.drawable.icon_24x24);
            extender.setHintHideIcon(true);
            extender.extend(builder);

            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setContentText(String.valueOf(R.string.app_name));
            builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.icon_24x24));
            mNotification = builder.build();
            notificationManager.notify(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE, mNotification);
        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }


    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (mSdData == null) mSdData = new SdData();
        if (mSensorManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
                ArrayList<String> arrayList = new ArrayList<String>();
                for (Sensor sensor : sensors) {
                    arrayList.add(sensor.getName());
                }

                arrayList.forEach((n) -> Log.d(TAG + "SensorTest", n));
            }
        }
        Log.v(TAG_MESSAGE_RECEIVED, "onMessageReceived event received");
        // Get the node id of the node that created the data item from the host portion of
        // the uri.
        mMobileNodeUri = messageEvent.getSourceNodeId();
        final String s1 = Arrays.toString(messageEvent.getData());
        final String messageEventPath = messageEvent.getPath();
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
                bindSensorListeners();
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
                mNodeFullName = mSdData.phoneName;
                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;
                mSampleFreq = mSdData.mSampleFreq;
            } catch (Exception e) {
                Log.v(TAG, "Received new settings failed to process", new Throwable());
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, MESSAGE_ITEM_OSD_DATA_REQUESTED)) {
            try {
                Log.v(TAG, "onMessageReived() : if receivedData ");

                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;

                //TODO: Deside what to do with the population of id and name. Nou this is being treated
                // as broadcast to all client watches.

                sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());


            } catch (Exception e) {
                Log.e(TAG, "OnMessageReceived(): catch on Received new settings failed to process", e);
            }
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
            mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
            mBloodPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
            mStationaryDetectSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
            mSensorManager.registerListener(this, mStationaryDetectSensor, SensorManager.SENSOR_DELAY_UI);

        } catch (Exception e) {
            Log.e(TAG, "bindSensorListners(): Sensor declaration excepmted: ", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        intentFromOnStart = intent;
        Log.v(TAG, "onStartCommand() and intent -name: \"->{intent}");
        int returnFromSuper = super.onStartCommand(intent, flags, startId);
        mContext = this;

        if (intent == null) return START_NOT_STICKY;
        if (intent.getData() != null) {
            if (Objects.equals(intent.getData(), Uri.parse("Start"))) {
                Log.i(TAG, "Received Start Foreground Intent ");
                // your start service code
                try {

                    if (mSdData == null) mSdData = new SdData();
                    //if (mTextView != null) mTextView.setText("Service Started");
                    //if (mTextView != null) mTextView.setText("onStart");

                    // Initialise Notification channel for API level 26 and over
                    // from https://stackoverflow.com/questions/44443690/notificationcompat-with-api-26
                    mNM = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
                    String mNotChId = "OSD Notification Channel";
                    mNotificationBuilder = new NotificationCompat.Builder(this, mNotChId);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        NotificationChannel channel = new NotificationChannel(mNotChId,
                                mNotChName,
                                NotificationManager.IMPORTANCE_DEFAULT);
                        String mNotChDesc = "OSD Notification Channel Description";
                        channel.setDescription(mNotChDesc);
                        mNM.createNotificationChannel(channel);
                    }

                    Log.d(TAG, "onStartCommand(): logging before call startForeground");
                    // Display a notification icon in the status bar of the phone to
                    // show the service is running.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.v(TAG, "showing Notification and calling startForeground (Android 8 and higher)");
                        if (mNM.areNotificationsEnabled()) showNotification(0);
                        startForeground(NOTIFICATION_ID, mNotification);
                    } else {
                        Log.v(TAG, "showing Notification");
                        if (mNM.areNotificationsEnabled()) showNotification(0);
                    }
                    // If the notification supports a direct reply action, use
                    // PendingIntent.FLAG_MUTABLE instead.

                } catch (Exception e) {
                    Log.e(TAG, " OnCreate - Starting Service failed", e);
                }
                Log.v(TAG, "onStartCommand() - populating mNodeList");
                mNodeListClient = Wearable.getNodeClient(mContext);

                Log.v(TAG, "onStartCommand() - checking permission for sensors and registering");

                if (mSdData.serverOK) bindSensorListeners();


                //mAccData = new double[NSAMP];
                // mSdData = new SdData();

                mVibe = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);


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
                    Log.v(TAG, "onRebind()");
                } catch (Exception e) {
                    Log.e(TAG, "onRebind(): Exception in updating capabilityClient and messageClient", e);
                    ;
                }
                // Initialise the Google API Client so we can use Android Wear messages.
                try {
                    if (mSdData.serverOK) {

                        sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onStartCommand() ", e);
                }//Eval cutting From Here Until End Function
                Timer appStartTimer = new Timer();
                appStartTimer.schedule(new TimerTask() {
                                           @Override
                                           public void run() {
                                               try {
                                                   Log.v(TAG, "startWatchApp() - Timer as Timeout, fires if not connected...");
                                                   if (mNodeListClient instanceof List && !mSdData.serverOK) {
                                                       Log.v(TAG, "OnStartCommand(): We only get here, if Wear Watch starts OSD first.");
                                                       List<Node> connectedNodes = mNodeListClient.getConnectedNodes().getResult();
                                                       if (connectedNodes.size() > 0) {
                                                           for (Node node : connectedNodes) {
                                                               Log.d(TAG, "OnStartCommand() - in client for initiation of device Paring with id " + node.getId() + " " + node.getDisplayName());
                                                               mSdData.watchConnected = true;
                                                               mSdData.watchAppRunning = true;
                                                               mSdData.mDataType = "watchConnect";
                                                               mMobileNodeUri = node.getId();
                                                               mNodeFullName = node.getDisplayName();
                                                               sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
                                                               //TODO: Deside what to do with the population of id and name. Nou this is being treated
                                                               // as broadcast to all client watches.
                                                           }
                                                       } else {
                                                           Log.e(TAG, "TimerTask/Run() :  no nodes found");
                                                       }
                                                       //try shift


                                                   }
                                               } catch (Exception e) {
                                                   Log.e(TAG, "onStartCommand() /TimerTask/Run(): Excempted:", e);
                                               }

                                           }

                                       }
                        , 5000); //end timerTask


            }
        } else if (intent.getAction().equals(Constants.ACTION.STOPFOREGROUND_ACTION)) {
            Log.i(TAG, "Received Stop Foreground Intent");
            //your end servce code
            stopForeground(true);
            stopSelfResult(startId);
        }
        return returnFromSuper;
    }

    /**
     * Calculate the magnitude of entry i in the fft array fft
     *
     * @param fft
     * @param i
     * @return magnitude ( Re*Re + Im*Im )
     */
    private double getMagnitude(final double[] fft, final int i) {
        double mag;
        mag = fft[2 * i] * fft[2 * i] + fft[2 * i + 1] * fft[2 * i + 1];
        //Log.d(TAG,"getMagnitude(): returning magnitude of: " + mag);
        return mag;
    }

    private void requestPermissions(String[] strings, int i) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "onBind()");
        intentFromOnBind = intent;
        try {
            if (mSdData == null) mSdData = new SdData();
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
                if (mSdData != null) if (mSdData.serverOK) bindSensorListeners();
        } catch (Exception e) {
                Log.e(TAG,"onBind(): Error in reloading vars ",e);
        }
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG, "onUnbind()");
        mBound = false;
        notificationManager.notify(TAG, channelIDs.lastIndexOf(channelIDs), mNotification);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy()");
        try {
            if (mSensorManager != null) mSensorManager.unregisterListener(this);
            if (mSdData != null) {
                mSdData.watchConnected = false;
                mSdData.watchAppRunning = false;
                sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
            }
            Wearable.getMessageClient(mContext).removeListener(this);
            Wearable.getCapabilityClient(mContext).removeListener(this);
            if (mWakeLock != null) if (mWakeLock.isHeld()) mWakeLock.release();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy(): error unregistering", e);
        }
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.v(TAG, "onRebind()");
        intentFromOnRebind = intent;
        try {
            createNotificationChannel();
            prepareAndStartForeground();
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
            if (mSdData != null) if (mSdData.serverOK) bindSensorListeners();
            mBound = true;
        } catch (Exception e) {
            Log.e(TAG, "onRebind(): Exception in updating capabilityClient and messageClient", e);
            ;
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
        if (mSdData.alarmState == 6 || mSdData.alarmState == 10 || isCharging()) {
            //ignore alarms when muted
            return;
        }
        boolean inAlarm = false;
        if (mSdData.roiPower > mSdData.alarmThresh && mSdData.roiRatio > mSdData.alarmRatioThresh) {
            inAlarm = true;
        }
        if (mSdData.mHRAlarmActive && mSdData.heartAvg != 0d && mSdData.heartCur > mSdData.heartAvg * mHeartPercentThresh) {
            inAlarm = true;
            alarmCount = (int) mSdData.alarmTime;
        }
        //Log.v(TAG, "checkAlarm() roiPower " + mSdData.roiPower + " roiRaTIO " + mSdData.roiRatio);

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
        if (!isCharging()) {
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
                // we initially start in mMode=0, which calculates the sample frequency returned by the sensor, then enters mMode=1, which is normal operation.
                if (mMode == 0) {
                    if (mStartEvent == null) {
                        Log.v(TAG, "onSensorChanged(): mMode=0 - checking Sample Rate - mNSamp = " + mSdData.mNsamp);
                        Log.v(TAG, "onSensorChanged(): saving initial event data");
                        mStartEvent = event;
                        mStartTs = event.timestamp;
                        mSdData.mNsamp = 0;
                    } else {
                        mSdData.mNsamp++;
                    }
                    if (mSdData.mNsamp >= 250) {
                        Log.v(TAG, "onSensorChanged(): Collected Data = final TimeStamp=" + event.timestamp + ", initial TimeStamp=" + mStartTs);
                        double dT = 1e-9 * (event.timestamp - mStartTs);
                        mSdData.mSampleFreq = (int) (mSdData.mNsamp / dT);
                        mSdData.haveSettings = true;
                        Log.v(TAG, "onSensorChanged(): Collected data for " + dT + " sec - calculated sample rate as " + mSampleFreq + " Hz");
                        mMode = 1;
                        mSdData.mNsamp = 0;
                        mStartTs = event.timestamp;
                    }
                } else if (mMode == 1) {
                    // mMode=1 is normal operation - collect NSAMP accelerometer data samples, then analyse them by calling doAnalysis().
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    //Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
                    if (!Objects.equals(mSdData.rawData, null) && mSdData.rawData.length > 0 && mSdData.rawData3D.length > 0) {
                        mSdData.rawData[mSdData.mNsamp] = sqrt(x * x + y * y + z * z);
                        mSdData.rawData3D[3 * mSdData.mNsamp] = x;
                        mSdData.rawData3D[3 * mSdData.mNsamp + 1] = y;
                        mSdData.rawData3D[3 * mSdData.mNsamp + 2] = z;
                        mSdData.mNsamp++;
                        if (mSdData.mNsamp == NSAMP) {
                            // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                            // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                            // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                            // FIXME - we should do some sort of check and disregard samples with long delays in them.
                            double dT = 1e-9 * (event.timestamp - mStartTs);
                            int sampleFreq = (int) (mSdData.mNsamp / dT);
                            Log.v(TAG, "onSensorChanged(): Collected " + NSAMP + " data points in " + dT + " sec (=" + sampleFreq + " Hz) - analysing...");
                            // DownSample from the 50Hz received frequency to 25Hz and convert to mg.
                            // FIXME - we should really do this properly rather than assume we are really receiving data at 50Hz.
                            for (int i = 0; i < mSdData.mNsamp; i++) {
                                mSdData.rawData[i / 2] = 1000. * mSdData.rawData[i] / 9.81;
                                mSdData.rawData3D[i / 2] = 1000. * mSdData.rawData3D[i] / 9.81;
                                mSdData.rawData3D[i / 2 + 1] = 1000. * mSdData.rawData3D[i + 1] / 9.81;
                                mSdData.rawData3D[i / 2 + 2] = 1000. * mSdData.rawData3D[i + 2] / 9.81;
                                //Log.v(TAG,"i="+i+", rawData="+mSdData.rawData[i]+","+mSdData.rawData[i/2]);
                            }
                            mSdData.mNsamp /= 2;
                            doAnalysis();
                            try {
                                if (mSdData == null) mSdData = new SdData();
                                if (mSdData.dataTime == null) mSdData.dataTime = new Time();
                                mSdData.dataTime.setToNow();
                                //mSdData.maxVal =    // not used
                                //mSdData.maxFreq = 0;  // not usedx
                                mSdData.haveData = true;
                                mSdData.haveSettings = true;
                                mSdData.watchConnected = true;
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
                                checkAlarm();

                            } catch (Exception e) {
                                Log.d(TAG, "doAnalysis(): Try0 Failed to run analysis", e);
                            }

                            try {
                                sendMessage(MESSAGE_ITEM_OSD_DATA, mSdData.toDataString(true));
                            } catch (Exception e) {
                                Log.e(TAG, "sendDataToPhone(): Failed to run analysis", e);
                            }
                            mSdData.mNsamp = 0;
                            mStartTs = event.timestamp;
                        } else if (mSdData.mNsamp > NSAMP) {
                            Log.v(TAG, "onSensorChanged(): Received data during analysis - ignoring sample");
                        }
                    } else {
                        Log.v(TAG, "onSensorChanged(): Received empty data during analysis - ignoring sample");
                    }

                } else {
                    Log.v(TAG, "onSensorChanged(): ERROR - Mode " + mMode + " unrecognised");
                }

            } else {
                Log.d(TAG + "SensorResult", String.valueOf(mSensor.getType()));
            }
        } else Log.d(TAG, "onSensorChanged() : is_Charging is true, ignoring sample");

    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    private void doAnalysis() {
        double nMin = 0d;
        double nMax = 0d;
        double nFreqCutoff = 0d;
        fft = null;
        fftDo = null;
        simpleSpec = null;
        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            final int sampleFreq;
            if (this.mSdData.mSampleFreq != 0) this.mSampleFreq = (short) this.mSdData.mSampleFreq;
            else sampleFreq = (int) ((double) this.mSdData.mNsamp / this.mSdData.dT);
            final double freqRes = 1.0d * this.mSampleFreq / ((double) this.mSdData.mNsamp);
            Log.v(this.TAG, "doAnalysis(): mSampleFreq=" + this.mSampleFreq + " mNSamp=" + this.mSdData.mNsamp + ": freqRes=" + freqRes);
            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = this.mAlarmFreqMin / freqRes;
            nMax = this.mAlarmFreqMax / freqRes;
            Log.v(this.TAG, "doAnalysis(): mAlarmFreqMin=" + this.mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + this.mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            final short mFreqCutoff = 12;
            nFreqCutoff = (double) mFreqCutoff / freqRes;
            Log.v(this.TAG, "mFreqCutoff = " + ((short) mFreqCutoff) + ", nFreqCutoff=" + nFreqCutoff);

            fftDo = new DoubleFFT_1D(this.mSdData.mNsamp);
            fft = new double[this.mSdData.mNsamp * 2];
            ///System.arraycopy(mAccData, 0, fft, 0, mNsamp);
            System.arraycopy(this.mSdData.rawData, 0, fft, 0, this.mSdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
            // and zero any readings that are above the frequency cutoff.
            double specPower = 0;
            for (int i = 1; i < this.mSdData.mNsamp / 2; i++)
                if (i <= nFreqCutoff) specPower = specPower + this.getMagnitude(fft, i);
                else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            //Log.v(TAG,"specPower = "+specPower);
            //specPower = specPower/(mSdData.mNsamp/2);
            specPower = specPower / this.mSdData.mNsamp / 2;
            //Log.v(TAG,"specPower = "+specPower);

            // Calculate the Region of Interest power and power ratio.
            double roiPower = 0;
            for (int i = (int) Math.floor(nMin); i < (int) Math.ceil(nMax); i++)
                roiPower = roiPower + this.getMagnitude(fft, i);
            roiPower = roiPower / (nMax - nMin);
            final double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins.
            // Values for SD_MODE
            final int SIMPLE_SPEC_FMAX = 10;
            simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                final int binMin = (int) (1 + ifreq / freqRes);    // add 1 to loose dc component
                final int binMax = (int) (1 + (ifreq + 1) / freqRes);
                simpleSpec[ifreq] = 0;
                for (int i = binMin; i < binMax; i++)
                    simpleSpec[ifreq] = simpleSpec[ifreq] + this.getMagnitude(fft, i);
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);
            }

            // Populate the mSdData structure to communicate with the main SdServer service.
            if (this.mSdData.dataTime == null) this.mSdData.dataTime = new Time();
            this.mSdData.dataTime.setToNow();
            // Amount by which to reduce analysis results to scale to be comparable to analysis on Pebble.
            final int ACCEL_SCALE_FACTOR = 1000;
            this.mSdData.specPower = (long) specPower / ACCEL_SCALE_FACTOR;
            this.mSdData.roiPower = (long) roiPower / ACCEL_SCALE_FACTOR;
            this.mSdData.dataTime.setToNow();
            this.mSdData.maxVal = 0;   // not used
            this.mSdData.maxFreq = 0;  // not used
            this.mSdData.haveData = true;
            this.mSdData.alarmThresh = this.mAlarmThresh;
            this.mSdData.alarmRatioThresh = this.mAlarmRatioThresh;
            this.mSdData.alarmFreqMin = this.mAlarmFreqMin;
            this.mSdData.alarmFreqMax = this.mAlarmFreqMax;
            // note mSdData.batteryPc is set from settings data in updateFromJSON()
            // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++)
                this.mSdData.simpleSpec[i] = (int) simpleSpec[i] / ACCEL_SCALE_FACTOR;
            Log.v(this.TAG, "simpleSpec = " + Arrays.toString(this.mSdData.simpleSpec));


        } catch (final Exception e) {
            Log.e(this.TAG, "doAnalysis - Exception during Analysis", e);

        }
    }

    /**
     * class to handle binding the MainApp activity to this service
     * so it can access mSdData.
     */
    public class SdBinder extends Binder {
        AWSdService getService() {
            return AWSdService.this;
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
        }
        sendMessage(MESSAGE_ITEM_OSD_TEST, "Test Message");
        sendMessage(MESSAGE_ITEM_OSD_DATA, mSdData.toJSON(false));
    }

    // Send a MesageApi message text to all connected devices.
    private void sendMessage(final String path, final String text) {
        boolean returnResult = false;
        Log.v(TAG, "sendMessage(" + path + "," + text + ")");
        final byte[] payload = (text.getBytes(StandardCharsets.UTF_8));
        Task<Integer> sendMessageTask = null;
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
                                + mMobileNodeUri + " And name: " + mNodeFullName
                );
                sendMessageTask = Wearable.getMessageClient(mContext)
                        .sendMessage(mMobileNodeUri, path, text.getBytes(StandardCharsets.UTF_8));

                try {
                    // Asynchronous callback for result of sendMessageTask
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
                    Log.e(TAG, "Error encoding string to bytes",e);
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
