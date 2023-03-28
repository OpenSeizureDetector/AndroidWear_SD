package uk.org.openseizuredetector.aw;


import static java.lang.Math.sqrt;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.multidex.BuildConfig;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.multiprocess.RemoteWorkerService;

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
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import uk.org.openseizuredetector.SdData;

public class AWSdService extends RemoteWorkerService implements SensorEventListener, MessageClient.OnMessageReceivedListener, CapabilityClient.OnCapabilityChangedListener {
    // Manifest.permission.ACTIVITY_RECOGNITION
    private final static String TAG = Constants.TAGS.AWSDService;
    private final static int SIMPLE_SPEC_FMAX = 10;   // simple spectrum maximum freq in Hz.
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "2";
    private static int NSAMP = 0;
    private static List<String> channelIDs = new ArrayList<>();
    // Notification ID
    private final int NOTIFICATION_ID = 1;
    private final int EVENT_NOTIFICATION_ID = 2;
    private final int DATASHARE_NOTIFICATION_ID = 3;
    private final IBinder mBinder = new SdBinder();
    public boolean requestCreateNewChannelAndInit;
    //public StartUpActivity.Connection parentConnection;
    public Context parentContext;
    public int mNSamp = 0;
    public double mSampleFreq = 0d;
    public double[] mAccData;
    public double sampleConversionFactor;
    public SdData mSdData;
    public String mMobileNodeUri = null;
    public String mMobileNodeDisplayName = null;
    public CapabilityClient capabilityClient = null;
    public List<Node> allNodes = null;
    public boolean mBound = false;
    protected double accelerationCombined = -1d;
    protected double gravityScaleFactor;
    protected double miliGravityScaleFactor;
    Vibrator mVibe;

    public ObservableEmitter<SdData> mSdDataObserver;
    public Observable<SdData> mSdDataObservable;
    private Context mContext;
    private Boolean mMobileDeviceConnected = false;
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
    private SdData mSdDataSettings;
    // private Sensor mO2Sensor; disabled until privileged API Samsung is acquired
    private int mHeartMode = 0;   // 0=check data rate, 1=running
    private MessageEvent mMessageEvent = null;
    private double[] fft;
    private DoubleFFT_1D fftDo;
    private double[] simpleSpec;
    private CharSequence mNotChName = "OSD Notification Channel";
    private float sampleTime;
    private float sampleDiff;
    private double defaultSampleTime = 10d;
    private double mSampleTimeUs;
    private double conversionSampleFactor = 1d;
    private int mCurrentMaxSampleCount = -1;
    private double mConversionSampleFactor;
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
    private float batteryPct = -1f;
    private ArrayList<Integer> heartRates = new ArrayList<Integer>(10);
    private CapabilityInfo mMobileNodesWithCompatibility = null;
    private boolean logNotConnectedMessage;
    private boolean logNotConnectedMessagePf;
    private double dT;
    private String mEventNotChId = "OSD Event Notification Channel";
    private String mNodeFullName;
    private NodeClient mNodeListClient;
    private Node mWearNode;
    private MessageClient mApiClient;
    private PowerManager.WakeLock mWakeLock;
    private Intent notificationIntent = null;
    private NotificationChannel channel;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationCompatBuilder;
    private Notification mNotification = null;
    public ServiceLiveData serviceLiveData = null;
    public ServiceConnection parentConnection = null;
    private Intent intentFromOnBind;
    private Intent intentFromOnRebind;
    private NotificationManager mNM;
    private NotificationCompat.Builder mNotificationBuilder;
    private Handler mHandler;
    private OsdUtil mUtil;
    private boolean prefValHrAlarmActive;
    private boolean mNetworkConnected = false;
    private ObservableEmitter<SdData> userIOSdDataEmitter;
    private Observable<SdData> userIoSdDataObservable;
    private Intent applicationIntent = null;
    private Intent intentFromOnStart;
    public BroadcastReceiver connectionUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.journaldev.broadcastreceiver.SOME_ACTION"))
                Toast.makeText(mContext, "SOME_ACTION is received", Toast.LENGTH_LONG).show();

            else if (intent.getAction().equals("android.net.conn.CONNECTIVITY_CHANGE")) {
                ConnectivityManager cm =
                        (ConnectivityManager) mContext.getSystemService(mContext.CONNECTIVITY_SERVICE);

                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                mNetworkConnected = activeNetwork != null &&
                        activeNetwork.isConnectedOrConnecting();
                if (mNetworkConnected) {
                    try {
                        Toast.makeText(mContext, "Network is connected", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    Toast.makeText(mContext, "Network is changed or reconnected", Toast.LENGTH_LONG).show();
                }
            }
        }
    };
    private List<Double> rawDataList;
    private List<Double> rawDataList3D;
    private int mChargingState = 0;
    private boolean mIsCharging = false;
    private int chargePlug = 0;
    private boolean usbCharge = false;
    private boolean acCharge = false;
    private boolean sensorsActive = false;
    private IntentFilter batteryStatusIntentFilter = null;
    private Intent batteryStatusIntent;
    private Thread mBlockingThread = null;

    public AWSdService() {
        super();

        mContext = this;
    }


    @Override
    public void onCreate() {
        super.onCreate();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "AWSdService Constructor()");
        Log.d(TAG, "AWSdSevice Constructor result of context compare: " + mContext +
                " and parentContext: " + parentContext + " result compare: " +
                Objects.equals(mContext, parentContext));


        notificationManager = (NotificationManager)
                mContext.getSystemService(NOTIFICATION_SERVICE);

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

        mHandler = new Handler(Looper.getMainLooper());
        mSdData = new SdData();
        mUtil = new OsdUtil(mContext, mHandler);
        serviceLiveData = new ServiceLiveData();
        applicationIntent = intent;


        Log.v(TAG, "onStartCommand() and intent -name: \"->{intent}");
        if (batteryStatusIntent == null) bindBatteryEvents();

        if (Objects.equals(allNodes, null)) {
            if (Objects.equals(mNodeListClient, null))
                mNodeListClient = Wearable.getNodeClient(mContext);
            Task getAllNodes = mNodeListClient.getConnectedNodes();
            getAllNodes.addOnSuccessListener(result -> {
                allNodes = (List<Node>) result;
            });
        }

        if (Objects.nonNull(intent))
            intentFromOnStart = intent;
        else return START_NOT_STICKY;

        if (!Constants.ACTION.BIND_ACTION.equals(intent.getAction())) {
            createNotificationChannel();
            //prepareAndStartForeground();
            //mStartForegroundService(intent);
            showNotification(0);
            mStartForegroundService(intent);

            mHandler.post(() -> serviceRunner(intent));
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void serviceRunner(Intent intent) {
        if (Objects.isNull(intentFromOnStart)) intentFromOnStart = intent;

        if (intentFromOnStart != null) {
            if (Uri.parse("Start").equals(intentFromOnStart.getBundleExtra(Constants.GLOBAL_CONSTANTS.intentAction))) {
                Log.i(TAG, "Received Start Foreground Intent ");
                // your start service code
                try {

                    //if (mTextView != null) mTextView.setText("Service Started");
                    //if (mTextView != null) mTextView.setText("onStart");

                    // Initialise Notification channel for API level 26 and over
                    // from https://stackoverflow.com/questions/44443690/notificationcompat-with-api-26
                    mNM = (NotificationManager) mContext.getSystemService(NOTIFICATION_SERVICE);
                    String mNotChId = "OSD Notification Channel";
                    mNotificationBuilder = new NotificationCompat.Builder(mContext, mNotChId);
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
                        WorkManager.getInstance(mContext);
                        //setForegroundAsync(createForegroundInfo(Constants.GLOBAL_CONSTANTS.mAppPackageName));
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

                if (mSdData.serverOK && !sensorsActive) bindSensorListeners();


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
                    capabilityClient = Wearable.getCapabilityClient(mContext);
                    capabilityClient.addLocalCapability(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearSD);
                    capabilityClient.addListener(this, Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
                    Wearable.getMessageClient(mContext).addListener(this);
                    Log.v(TAG, "onRebind()");
                } catch (Exception e) {
                    Log.e(TAG, "onRebind(): Exception in updating capabilityClient and messageClient", e);

                }
                // Initialise the Google API Client so we can use Android Wear messages.
                try {
                    if (mSdData.serverOK) {

                        sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
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
                                                               sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
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
        } else if (Constants.ACTION.STOPFOREGROUND_ACTION.equals(intentFromOnStart.getStringExtra(Constants.GLOBAL_CONSTANTS.intentAction)) ||
                Constants.GLOBAL_CONSTANTS.mStopUri.equals(intentFromOnStart.getData()) ||
                Constants.ACTION.STOP_WEAR_SD_ACTION.equals(intentFromOnStart.getAction())) {
            Log.i(TAG, "Received Stop Foreground Intent");
            //your end servce code
            unBindSensorListeners();
            mContext.unregisterReceiver(powerUpdateReceiver);
            mContext.unregisterReceiver(connectionUpdateReceiver);
            stopSelf();
            stopForeground(true);

            /*
            mContext.stopSelfResult(startId);*/
        }
    }


    private void connectObserver(UUID connectingUuid) {
        mHandler.postDelayed(() -> {
            LiveData<WorkInfo> connectingLiveData = serviceLiveData;
            if (!connectingLiveData.hasObservers())
                if (!connectingLiveData.hasActiveObservers()) {
                    connectingLiveData.observe((LifecycleOwner) this, this::onChangedObserver);
                    connectingLiveData.observeForever(this::onChangedObserver);

                }
        }, 200);
    }

    private void disConnectObserver(UUID disconnectingUuid) {
        LiveData disconnectingLiveData = serviceLiveData;
        if (Objects.isNull(disconnectingLiveData)) return;
        if (disconnectingLiveData.hasObservers())
            disconnectingLiveData.removeObservers((LifecycleOwner) this);
    }

    private void onChangedObserver(@Nullable Object workInfo) {
        if (workInfo != null) {
            Log.d("oneTimeWorkRequest", "Status changed to : " + ((WorkInfo) workInfo).getState());
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createChannel() {
        // Create a Notification channel
        createNotificationChannel();
    }


    private static final String returnNewCHANNEL_ID() {
        String currentID = String.valueOf(R.string.app_name) + channelIDs.size();
        channelIDs.add(channelIDs.size(), currentID);
        return currentID;
    }

    /**
     * Show a notification while this service is running.
     */
    private void showNotification(int alarmLevel) {
        Log.v(TAG, "showNotification() - alarmLevel=" + alarmLevel);
        int iconId = getNotificationIcon();

        String titleStr;
        Uri soundUri = null;
        String smsStr;


        switch (alarmLevel) {
            case 0:
                //iconId = R.drawable.star_of_life_24x24;
                titleStr = "OK";
                smsStr = "OSD Active";
                break;
            case 1:
                titleStr = "WARNING";
                smsStr = "OSD Active: " + mSdData.mHR + " bpm";
            case 2:
                titleStr = "ALARM";
                smsStr = "OSD Active: " + mSdData.mHR + " bpm";
            case -1:
                titleStr = "FAULT";
                smsStr = "OSD Active: " + mSdData.mHR + " bpm";
            default:
                titleStr = "OK";
                smsStr = "OSD Active: " + mSdData.mHR + " bpm";
        }

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
                PendingIntent.getActivity(mContext,
                        0, i, Flag_Intend);


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

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = mContext.getString(R.string.app_name);
            String description = mContext.getString(R.string.app_name);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            channel = new NotificationChannel("Default notification", name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager = mContext.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            if (!notificationManager.areNotificationsEnabled()) {
                Log.e(TAG, "createNotificationChannel() - Failure to use notifications. Not enabled", new Throwable());
            } else Log.d(TAG, "createNotificationChannel(): notifications are enabled");
        }
    }

    private void mStartForegroundService(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(1, mNotification);
            } else { //old pre-O behaviour
                Log.i(TAG, "tbi: startForeground pre O");
            }
        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }
    }


    public void prepareAndStartForeground() {
        try {
            requestCreateNewChannelAndInit = false;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, Constants.GLOBAL_CONSTANTS.mAppPackageNameWearSD);
            builder.setContentTitle(String.valueOf(R.string.app_name));
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_24x24);
            builder.setLargeIcon(bitmap);

            builder.setContentIntent(PendingIntent.getActivity(mContext, 1, new Intent(mContext, AWSdService.class), PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationCompat.WearableExtender extender = new NotificationCompat.WearableExtender();
//            extender.setBackground(BitmapFactory.decodeResource(mContext.getResources(), R.drawable.card_background));
//            extender.setContentIcon(R.drawable.icon_24x24);
            extender.setHintHideIcon(true);
            extender.extend(builder);

            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setContentText(String.valueOf(R.string.app_name));
//            builder.setLargeIcon(BitmapFactory.decodeResource(mContext.getResources(), R.drawable.star_of_life_24x24));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setSmallIcon(getNotificationIcon());
            }
            mNotification = builder.build();
            notificationManager.notify(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE, mNotification);

        } catch (Exception e) {
            Log.e(TAG, "prepareAndStartForeground(): Failed.", e);
        }


    }

    private int getNotificationIcon() {
        boolean useWhiteIcon = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP);
        return useWhiteIcon ? R.drawable.googleg_standard_color_18 : R.drawable.star_of_life_24x24;
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (mSdData == null) mSdData = new SdData();
        if (Objects.equals(allNodes, null) ||
                allNodes.size() == 0) {
            Task<List<Node>> getAllNodes = mNodeListClient.getConnectedNodes();
            getAllNodes.addOnSuccessListener(resultNodeList -> {
                allNodes = resultNodeList;
                onMessageReceived(messageEvent);
            });
            return;
        }
        Log.v(Constants.GLOBAL_CONSTANTS.TAG_MESSAGE_RECEIVED, "onMessageReceived event received");
        // Get the node id of the node that created the data item from the host portion of
        // the uri.
        if (
                Objects.equals(mMobileNodeUri, null) ||
                        Objects.equals(mWearNode, null) ||
                        Objects.equals(mNodeFullName, null)
        ) {
            mMobileNodeUri = messageEvent.getSourceNodeId();
            mWearNode = (Node) allNodes
                    .stream()
                    .filter(node -> node.getId().equals(mMobileNodeUri))
                    .collect(Collectors.toList())
                    .get(0);
            mNodeFullName = mWearNode.getDisplayName();
        }


        final String s1 = new String(messageEvent.getData(), StandardCharsets.UTF_8);
        final String messageEventPath = messageEvent.getPath();
        Log.v(
                Constants.GLOBAL_CONSTANTS.TAG_MESSAGE_RECEIVED,
                "onMessageReceived() A message from watch was received:"
                        + messageEvent.getRequestId()
                        + " "
                        + messageEventPath
                        + " "
                        + s1
        );
        //Send back a message back to the source node
        //This acknowledges that the receiver activity is open
        if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, Constants.GLOBAL_CONSTANTS.APP_OPEN_WEARABLE_PAYLOAD_PATH)) {

            String input;
            try {
                input = new String(messageEvent.getData());
                // Set the data of the message to be the bytes of the Uri.
                Log.v(TAG, "Sending return message: " + Constants.GLOBAL_CONSTANTS.wearableAppCheckPayloadReturnACK);
                if (Objects.equals(input, Constants.GLOBAL_CONSTANTS.wearableAppCheckPayload))
                    sendMessage(Constants.GLOBAL_CONSTANTS.APP_OPEN_WEARABLE_PAYLOAD_PATH, Constants.GLOBAL_CONSTANTS.wearableAppCheckPayloadReturnACK);
                if (Objects.equals(input, Constants.GLOBAL_CONSTANTS.wearableAppCheckPayloadReturnACK))
                    if (mSdData.analysisPeriod == 0)
                        sendMessage(Constants.GLOBAL_CONSTANTS.APP_OPEN_WEARABLE_PAYLOAD_PATH, Constants.ACTION.PULL_SETTINGS_ACTION);

                Log.d(TAG, "!messageEventPath.isEmpty() && Objects.equals(messageEventPath, APP_OPEN_WEARABLE_PAYLOAD_PATH) We returned from sending message.");

            } catch (Exception e) {
                Log.v(TAG, "onMessageReceived() init_message " + s1 + " Received new settings failed to process", e);
            }
            input = null;
        } else if ((!messageEventPath.isEmpty()) && Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_TEST_RECEIVED.equals(messageEventPath)) {
            //TODO
        } else if ((!messageEventPath.isEmpty()) && Constants.GLOBAL_CONSTANTS.MESSAGE_OSD_FUNCTION_RESTART.equals(messageEventPath)) {
            if (sensorsActive) unBindSensorListeners();
            bindSensorListeners();
            //TODO
        } else if (!messageEventPath.isEmpty() && Constants.ACTION.PUSH_SETTINGS_ACTION.equals(messageEventPath)) {
            Log.v(TAG, "Received new settings");

            try {
                mSdData.fromJSON(s1);
                prefValHrAlarmActive = mSdData.mHRAlarmActive;
                if (!Objects.equals(mNodeFullName, null))
                    if (mNodeFullName.isEmpty()) {
                        String nodeFullName = mNodeFullName;
                        mNodeFullName = mSdData.phoneName;
                    }
                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;
                mSampleFreq = mSdData.mSampleFreq;


                if (sensorsActive) unBindSensorListeners();
                bindSensorListeners();
            } catch (Exception e) {
                Log.v(TAG, "Received new settings failed to process", new Throwable());
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_REQUESTED)) {
            try {
                Log.v(TAG, "onMessageReived() : if receivedData ");

                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;

                //TODO: Deside what to do with the population of id and name. Nou this is being treated
                // as broadcast to all client watches.

                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());


            } catch (Exception e) {
                Log.e(TAG, "OnMessageReceived(): catch on Received new settings failed to process", e);
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED)) {
            try {
                SdData sdData = new SdData();
                sdData.fromJSON(s1);
                if (sdData.serverOK) {
                    mSdData = sdData;
                }
            } catch (Exception e) {
                Log.e(TAG, "onMessageReceived()", e);
            }
        } else {
            Log.v(TAG + "nn", "Not processing received message, displaying in log: " + s1);
        }

    }

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(TAG, "CapabilityInfo received: " + capabilityInfo.toString());
        Set<Node> changedNodeSet = null;
        Node changedNode = null;
        try {
            changedNodeSet = capabilityInfo.getNodes();
            if (changedNodeSet.size() > 0) {
                Log.d(TAG, "onCapabilityChanged(): count of set changedCapabilities: " + changedNodeSet.size());
                changedNode = changedNodeSet.stream().findFirst().get();

                if (capabilityInfo.equals(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver)) {
                    if (capabilityInfo.equals(Uri.parse("wear://"))) {
                        mMobileNodesWithCompatibility = capabilityInfo;
                    }
                    if (!Objects.equals(mWearNode, null)) if (mWearNode.equals(changedNode)) {
                        mSdData.watchConnected = true;
                        mSdData.serverOK = true;
                        bindSensorListeners();
                    }
                }
            } else {
                Log.d(TAG, "onCapabilityChanged(): count of set changedCapabilities: " + changedNodeSet.size());
                mSdData.watchConnected = false;
                mSdData.serverOK = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "onCapabilityChanged(): error", e);
        } finally {
            changedNodeSet = null;
            changedNode = null;
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

    /*   *//**
     * onCreate() - called when services is created.  Starts message
     * handler process to listen for messages from other processes.
     *//*
    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        mSdData = new SdData();
        mContext = this;
        mUtil = new OsdUtil(getApplicationContext(), mHandler);

    }*/

    /**
     * Calculate the static values of requested mSdData.mSampleFreq, mSampleTimeUs and factorDownSampling  through
     * mSdData.analysisPeriod and mSdData.mDefaultSampleCount .
     */
    private void calculateStaticTimings() {
        // default sampleCount : mSdData.mDefaultSampleCount
        // default sampleTime  : mSdData.analysisPeriod
        // sampleFrequency = sampleCount / sampleTime:
        if (Double.isNaN(mSdData.mSampleFreq) || Double.isInfinite(mSdData.mSampleFreq) || mSdData.mSampleFreq == 0 || mSdData.analysisPeriod == 0) {
            mSdData.mSampleFreq = Constants.SD_SERVICE_CONSTANTS.defaultSampleRate;
            mSdData.analysisPeriod = Constants.SD_SERVICE_CONSTANTS.defaultSampleTime;
        }
        mSdData.mSampleFreq = (long) mSdData.mDefaultSampleCount / mSdData.analysisPeriod;

        // now we have mSampleFreq in number samples / second (Hz) as default.
        // to calculate sampleTimeUs: (1 / mSampleFreq) * 1000 [1s == 1000000us]
        mSampleTimeUs = (1 / mSdData.mSampleFreq) * 1000;

        // num samples == fixed final 250 (NSAMP)
        // time seconds in default == 10 (SIMPLE_SPEC_FMAX)
        // count samples / time = 25 samples / second == 25 Hz max.
        // 1 Hz == 1 /s
        // 25 Hz == 0,04s
        // 1s == 1.000.000 us (sample interval)
        // sampleTime = 40.000 uS == (SampleTime (s) * 1000)
        double mSDDataSampleTimeUs = (double) (mSdData.mNsamp / dT) * 1000f;
        conversionSampleFactor = mSampleTimeUs / mSDDataSampleTimeUs;
    }

    public void bindSensorListeners() {
        try {
            if (mSampleTimeUs < (double) SensorManager.SENSOR_DELAY_NORMAL ||
                    Double.isInfinite(mSampleTimeUs) ||
                    Double.isNaN(mSampleTimeUs)) {
                calculateStaticTimings();
                if (mSampleTimeUs <= 0d)
                    mSampleTimeUs = SensorManager.SENSOR_DELAY_NORMAL;
            }
            mSdData.watchSdVersion = BuildConfig.VERSION_NAME;
            mSdData.watchFwVersion = Build.DISPLAY;
            mSdData.watchPartNo = Build.BOARD;
            mSdData.watchSdName = Build.MODEL;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (mContext.checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
                    ActivityCompat.requestPermissions(getActivity(mContext),
                            new String[]{Manifest.permission.BODY_SENSORS},
                            Constants.GLOBAL_CONSTANTS.PERMISSION_REQUEST_BODY_SENSORS);


                } else {
                    Log.d(TAG, "ALREADY GRANTED");
                }
            }

            mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mSensorManager.registerListener(this, mSensor, (int) mSampleTimeUs, (int) mSampleTimeUs * 3, mHandler);
            mHeartSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            mSensorManager.registerListener(this, mHeartSensor, (int) Constants.GLOBAL_CONSTANTS.getMaxHeartRefreshRate);
            mHeartBeatSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_BEAT);
            mSensorManager.registerListener(this, mHeartSensor, (int) Constants.GLOBAL_CONSTANTS.getMaxHeartRefreshRate, (int) Constants.GLOBAL_CONSTANTS.getMaxHeartRefreshRate * 3);
            mBloodPressure = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            mSensorManager.registerListener(this, mHeartSensor, SensorManager.SENSOR_DELAY_UI);
            mStationaryDetectSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
            mSensorManager.registerListener(this, mStationaryDetectSensor, SensorManager.SENSOR_DELAY_UI);
            sensorsActive = true;
            if (batteryStatusIntent == null) {
                bindBatteryEvents();

            }
        } catch (Exception e) {
            Log.e(TAG, "bindSensorListners(): Sensor declaration excepted: ", e);
        }
    }

    private void unBindSensorListeners() {
        mSensorManager.unregisterListener(this);
        sensorsActive = false;
    }

    protected void powerUpdateReceiveAction(Intent intent) {
        try {
            if (intent.getAction() != null) {
                Log.d(TAG, "onReceive(): Received action:  " + intent.getAction());
                // Are we charging / charged?
                if (
                        intent.getAction().equals(Intent.ACTION_POWER_CONNECTED) ||
                                intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
                    mChargingState = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    mIsCharging = mChargingState == BatteryManager.BATTERY_STATUS_CHARGING ||
                            mChargingState == BatteryManager.BATTERY_STATUS_FULL;

                    // How are we charging?
                    chargePlug = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                    acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;

                    if (mIsCharging && sensorsActive)
                        unBindSensorListeners();
                    if (!mIsCharging && mMobileDeviceConnected && mBound && !sensorsActive)
                        bindSensorListeners();

                }

                if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                    if (Objects.isNull(batteryStatusIntent)) return;
                    int level = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);

                    int scale = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batteryPct = 100 * level / (float) scale;
                    mSdData.batteryPc = (int) (batteryPct);

                    mChargingState = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    mIsCharging = mChargingState == BatteryManager.BATTERY_STATUS_CHARGING ||
                            mChargingState == BatteryManager.BATTERY_STATUS_FULL;

                    // How are we charging?
                    chargePlug = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                    acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;
                    boolean wirelessCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    if (mIsCharging && sensorsActive)
                        unBindSensorListeners();
                    if (!mIsCharging && mMobileDeviceConnected && mBound && !sensorsActive)
                        bindSensorListeners();
                }
                if (intent.getAction().equals(Intent.ACTION_BATTERY_LOW) ||
                        intent.getAction().equals(Intent.ACTION_BATTERY_OKAY)) {

                    if (sensorsActive && batteryPct < 15f)
                        unBindSensorListeners();
                }
                mUtil.runOnUiThread(() -> {
                    if (Objects.nonNull(serviceLiveData))
                        if (serviceLiveData.hasActiveObservers())
                            serviceLiveData.signalChangedData();
                    Log.d(TAG, "onBatteryChanged(): runOnUiThread(): updateUI");

                });

            }
        } catch (Exception e) {
            Log.e(TAG, "powerUpdateReceiveAction() : error in type", e);
        }

    }


    private void bindBatteryEvents() {
        batteryStatusIntentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(powerUpdateReceiver, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
        mContext.registerReceiver(powerUpdateReceiver, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
        mContext.registerReceiver(powerUpdateReceiver, new IntentFilter(Intent.ACTION_BATTERY_LOW));
        mContext.registerReceiver(powerUpdateReceiver, new IntentFilter(Intent.ACTION_BATTERY_OKAY));
        batteryStatusIntent = mContext.registerReceiver(powerUpdateReceiver, batteryStatusIntentFilter);
        mContext.registerReceiver(connectionUpdateReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));

    }

    public BroadcastReceiver powerUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            powerUpdateReceiveAction(intent);
        }

    };


    public void setReceiverAndUpdateMobileUri() {
        NodeClient nodeClient = Wearable.getNodeClient(mContext);
        Task<List<Node>> nodeList = nodeClient.getConnectedNodes();
        nodeList.addOnCompleteListener(task ->
        {
            if (task.isSuccessful()) {
                if (task.getResult().size() > 0) {

                    mMobileNodeUri = task.getResult().get(0).getId();
                    mMobileNodeDisplayName = task.getResult().get(0).getDisplayName();
                    capabilityClient = Wearable.getCapabilityClient(mContext);
                    capabilityClient.addLocalCapability(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
                    capabilityClient.addListener(this, Constants.GLOBAL_CONSTANTS.mAppPackageName);

                }

            }

        });

    }

    public boolean isCharging() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent lIbatteryStatus = mContext.registerReceiver(null, ifilter);
        int status = lIbatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean bCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;
        return bCharging;
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

    /**
     * Return the communication channel to the service.  May return null if
     * clients can not bind to the service.  The returned
     * {@link IBinder} is usually for a complex interface
     * that has been <a href="{@docRoot}guide/components/aidl.html">described using
     * aidl</a>.
     *
     * <p><em>Note that unlike other application components, calls on to the
     * IBinder interface returned here may not happen on the main thread
     * of the process</em>.  More information about the main thread can be found in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html">Processes and
     * Threads</a>.</p>
     *
     * @param intent The Intent that was used to bind to this service,
     *               as given to {@link Context#bindService
     *               Context.bindService}.  Note that any extras that were included with
     *               the Intent at that point will <em>not</em> be seen here.
     * @return Return an IBinder through which clients can call on to the
     * service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "onBind()");
        intentFromOnBind = intent;
        try {
            Wearable.getCapabilityClient(mContext)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(mContext).addListener(this);
            if (mSdData != null) if (mSdData.serverOK) bindSensorListeners();

        } catch (Exception e) {
            Log.e(TAG, "onBind(): Error in reloading vars ", e);
        }
        return mBinder;
    }



    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG, "onUnbind()");
        mBound = false;
        if (Objects.nonNull(notificationManager) && !Constants.ACTION.BIND_ACTION.equals(intent.getAction()) && Constants.ACTION.STOP_WEAR_SD_ACTION.equals(mSdData.mDataType))
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
                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());
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
        }

    }


    @Override
    public void onSensorChanged(SensorEvent event) {
        try {//
            // is this a heartbeat event and does it have data?
            if (!isCharging() || mIsCharging) {
                if (event.sensor.getType() == Sensor.TYPE_HEART_RATE && event.values.length > 0) {
                    int newValue = Math.round(event.values[0]);
                    //Log.d(LOG_TAG,sensorEvent.sensor.getName() + " changed to: " + newValue);
                    // only do something if the value differs from the value before and the value is not 0.
                    if (mSdData.mHR != newValue && newValue != 0) {
                        // save the new value
                        mSdData.mHR = newValue;
                        // add it to the list and computer a new average
                        if (heartRates.size() == 10) {
                            heartRates.remove(0);
                        }
                        heartRates.add(curHeart);
                    }
                    mSdData.mHRAvg = (int) calculateAverage(heartRates);
                    if (heartRates.size() < 4) {
                        mSdData.mHRAvg = 0;
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
                        if (mSdData.mNsamp >= mSdDataSettings.mDefaultSampleCount) {
                            Log.v(TAG, "onSensorChanged(): Collected Data = final TimeStamp=" + event.timestamp + ", initial TimeStamp=" + mStartTs);
                            mSdData.dT = 1.0e-9 * (event.timestamp - mStartTs);
                            mCurrentMaxSampleCount = mSdData.mNsamp;
                            mSdData.mSampleFreq = (int) (mSdData.mNsamp / mSdData.dT);
                            mSdData.haveSettings = true;
                            Log.v(TAG, "onSensorChanged(): Collected data for " + mSdData.dT + " sec - calculated sample rate as " + mSampleFreq + " Hz");
                            accelerationCombined = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
                            calculateStaticTimings();
                            mMode = 1;
                            mSdData.mNsamp = 0;
                            mStartTs = event.timestamp;
                        }
                    } else if (mMode == 1) {
                        // mMode=1 is normal operation - collect NSAMP accelerometer data samples, then analyse them by calling doAnalysis().

                        if (mSdData.mNsamp == mCurrentMaxSampleCount) {


                            // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                            // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                            // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                            // FIXME - we should do some sort of check and disregard samples with long delays in them.
                            mSdData.dT = 1e-9 * (event.timestamp - mStartTs);
                            int sampleFreq = (int) (mSdData.mNsamp / mSdData.dT);
                            Log.v(TAG, "onSensorChanged(): Collected " + NSAMP + " data points in " + mSdData.dT + " sec (=" + sampleFreq + " Hz) - analysing...");

                            // DownSample from the **Hz received frequency to 25Hz and convert to mg.
                            // FIXME - we should really do this properly rather than assume we are really receiving data at 50Hz.
                            int readPosition = 1;

                            for (int i = 0; i < Constants.SD_SERVICE_CONSTANTS.defaultSampleCount; i++) {
                                readPosition = (int) (i / mConversionSampleFactor);
                                if (readPosition < rawDataList.size()) {
                                    mSdData.rawData[i] = gravityScaleFactor * rawDataList.get(readPosition) / SensorManager.GRAVITY_EARTH;
                                    mSdData.rawData3D[i] = gravityScaleFactor * rawDataList3D.get(readPosition) / SensorManager.GRAVITY_EARTH;
                                    mSdData.rawData3D[i + 1] = gravityScaleFactor * rawDataList3D.get(readPosition + 1) / SensorManager.GRAVITY_EARTH;
                                    mSdData.rawData3D[i + 2] = gravityScaleFactor * rawDataList3D.get(readPosition + 2) / SensorManager.GRAVITY_EARTH;
                                    //Log.v(TAG,"i="+i+", rawData="+mSdData.rawData[i]+","+mSdData.rawData[i/2]);
                                }
                            }
                            rawDataList.clear();
                            rawDataList3D.clear();
                            mSdData.mNsamp = Constants.SD_SERVICE_CONSTANTS.defaultSampleCount;
                            mSdData.mHR = -1;
                            mSdData.mHRAlarmActive = false;
                            mSdData.mHRAlarmStanding = false;
                            mSdData.mHRNullAsAlarm = false;
                            doAnalysis();
                            mSdData.mNsamp = 0;
                            mStartTs = event.timestamp;

                        } else if (!Objects.equals(rawDataList, null) && rawDataList.size() <= mCurrentMaxSampleCount) {

                            float x = event.values[0];
                            float y = event.values[1];
                            float z = event.values[2];
                            //Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
                            rawDataList.add(sqrt(x * x + y * y + z * z));
                            rawDataList3D.add((double) x);
                            rawDataList3D.add((double) y);
                            rawDataList3D.add((double) z);
                            mSdData.mNsamp++;

                        } else if (mSdData.mNsamp > mCurrentMaxSampleCount - 1) {
                            Log.v(TAG, "onSensorChanged(): Received data during analysis - ignoring sample");

                        } else if (mSdData.mNsamp != rawDataList.size()) {
                            Log.v(TAG, "onSensorChanged(): mSdData.mNSamp and mCurrentMaxSampleCount differ in size");
                            mSdData.mNsamp = rawDataList.size();

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
        } finally {
            mUtil.runOnUiThread(() -> {
                if (Objects.nonNull(serviceLiveData))
                    if (serviceLiveData.hasActiveObservers())
                        serviceLiveData.signalChangedData();
                Log.d(TAG, "onSensorChanged(): runOnUiThread(): updateUI");

            });

        }

    }

    private void checkAlarm() {
        if (mSdData.alarmState == 6 || mSdData.alarmState == 10 || isCharging()) {
            //ignore alarms when muted
            return;
        }
        //temporary force true mHrAlarmActive
        if (mSdData.mHR > 0d)
            mSdData.mHRAlarmActive = (mSdData.alarmState != 6 && mSdData.alarmState != 10);

        boolean inAlarm = false;
        if (mSdData.roiPower > mSdData.alarmThresh && mSdData.roiRatio > mSdData.alarmRatioThresh) {
            inAlarm = true;
        }
        if (mSdData.mHRAlarmActive && mSdData.mHRAvg != 0d && mSdData.mHR > mSdData.mHRAvg * mHeartPercentThresh) {
            inAlarm = true;
            alarmCount = (int) mSdData.alarmTime;
        }
        //Log.v(TAG, "checkAlarm() roiPower " + mSdData.roiPower + " roiRaTIO " + mSdData.roiRatio);

        showNotification((int) mSdData.alarmState);
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
            mContext.startActivity(intent);
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
        sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_TEST, "Test Message");
        sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA, mSdData.toJSON(false));
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
                    Log.d(Constants.GLOBAL_CONSTANTS.TAG_MESSAGE_RECEIVED, "Ended task, result through callback.");
                } catch (Exception e) {
                    Log.e(TAG, "Error encoding string to bytes", e);
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

/*
//
//  /**
//     * Override this method to do your actual background processing.  This method is called on a
//     * background thread - you are required to <b>synchronously</b> do your work and return the
//     * {@link Result} from this method.  Once you return from this
//     * method, the Worker is considered to have finished what its doing and will be destroyed.  If
//     * you need to do your work asynchronously on a thread of your own choice, see
//     * {@link ListenableWorker}.
//     * <p>
//     * A Worker has a well defined
//     * <a href="https://d.android.com/reference/android/app/job/JobScheduler">execution window</a>
//     * to finish its execution and return a {@link Result}.  After
//     * this time has expired, the Worker will be signalled to stop.
//     *
//     * @return The {@link Result} of the computation; note that
//     * dependent work will not execute if you use
//     * {@link Result#failure()} or
//     * {@link Result#failure(Data)}
//     * //

    @NonNull
    @Override
    public Result doWork() {
        WorkManager workManager = WorkManager.getInstance(mContext);
        


if (Objects.equals(intent, null)) return START_NOT_STICKY;
        return returnFromSuper;*//*

        if (Objects.isNull(mBlockingThread))
            mBlockingThread = new Thread(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearSD);
        mBlockingThread.start();
        synchronized (mBlockingThread) {
            try {
                mBlockingThread.wait();
            } catch (InterruptedException interruptedException) {
                Log.e(this.getClass().getName(), "mBlockingThread interrupted.", interruptedException);
                return Result.failure();
            }
        }

        return Result.retry();
    }
*/


/*
    @NonNull
    private ForegroundInfo createForegroundInfo(@NonNull String progress) {
        // Build a notification using bytesRead and contentLength

        Context context = getApplicationContext();
        String id = context.getString(R.string.app_name);
        String title = context.getString(R.string.app_name);
        String cancel = "Cancel " + context.getString(R.string.app_name);
        // This PendingIntent can be used to cancel the worker
        PendingIntent intent = WorkManager.getInstance(context)
                .createCancelPendingIntent(getId());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel();
        }

        Notification notification = new NotificationCompat.Builder(context, id)
                .setContentTitle(title)
                .setTicker(title)
                .setSmallIcon(R.drawable.icon_24x24)
                .setOngoing(true)
                // Add the cancel action to the notification which can
                // be used to cancel the worker
                .addAction(android.R.drawable.ic_delete, cancel, intent)
                .build();

        return new ForegroundInfo(Constants.SD_SERVICE_CONSTANTS.defaultSampleCount, notification);
    }
*/


    /**
     * class to handle binding the MainApp activity to this service
     * so it can access mSdData.
     */
    public class SdBinder extends Binder {
        AWSdService getService() {
            return AWSdService.this;
        }
    }

    public class Access extends Binder {
        public AWSdService getService() {
            return AWSdService.this;
        }
    }

    public class ServiceLiveData extends LiveData {
        private boolean signalChangedData = false;

        public void signalChangedData() {
            this.postValue(mSdData);
        }
    }


}






