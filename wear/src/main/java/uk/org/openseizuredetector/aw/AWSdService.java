package uk.org.openseizuredetector.aw;


import static java.lang.Math.sqrt;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import kotlin.jvm.functions.Function1;
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
    public int serverBatteryPct = -1;
    private ArrayList<Double> heartRates = new ArrayList<Double>(10);
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
    private boolean connectedConnectionUpdates;

    public BroadcastReceiver connectionUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.net.conn.CONNECTIVITY_CHANGE")) {
                ConnectivityManager cm =
                        (ConnectivityManager) context.getSystemService(context.CONNECTIVITY_SERVICE);

                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                mNetworkConnected = activeNetwork != null &&
                        activeNetwork.isConnectedOrConnecting();
                if (mNetworkConnected) {
                    try {
                        Toast.makeText(context, "Network is connected", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    Toast.makeText(context, "Network is changed or reconnected", Toast.LENGTH_LONG).show();
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
    private BroadcastReceiver powerUpdateReceiver = null;
    private PowerUpdateReceiver powerUpdateReceiverPowerConnected = null;
    private PowerUpdateReceiver powerUpdateReceiverPowerDisConnected = null;
    private PowerUpdateReceiver powerUpdateReceiverPowerUpdated = null;
    private PowerUpdateReceiver powerUpdateReceiverPowerLow = null;
    private PowerUpdateReceiver powerUpdateReceiverPowerOkay = null;
    private AccelerationSensor accelerationSensor ;
    private HeartRateSensor heartRateSensor;
    private static HeartBeatSensor heartBeatSensor;
    private MotionDetectSensor motionDetectSensor;
    public AWSdService() {
        super();
        Log.d(TAG, "AWSdService(): in constructor");

    }


    @Override
    public void onCreate() {

        Log.d(TAG, "OnCreate()");
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        if (Objects.isNull(mSdData)) mSdData = new SdData();
        mUtil = new OsdUtil(this, mHandler);
        serviceLiveData = new ServiceLiveData();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "AWSdService Constructor()");
        Log.d(TAG, "AWSdSevice Constructor result of context compare: " + this +
                " and parentContext: " + parentContext + " result compare: " +
                Objects.equals(this, parentContext));


        notificationManager = (NotificationManager)
                this.getSystemService(NOTIFICATION_SERVICE);

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


        Log.v(TAG, "onStartCommand() and intent -name: \"->{intent}");

        if (Objects.nonNull(intent)) {
            if (Objects.nonNull(powerUpdateReceiverPowerUpdated)) {
                if (!powerUpdateReceiverPowerUpdated.isRegistered &&
                        !Constants.ACTION.STOPFOREGROUND_ACTION.equals(intent.getAction()))
                    unBindBatteryEvents();

                //error getaction null
                if (!powerUpdateReceiverPowerUpdated.isRegistered &&
                        !Constants.ACTION.STOPFOREGROUND_ACTION.equals(intent.getAction()))
                    bindBatteryEvents();
            }
        }

        if (Objects.equals(allNodes, null)) {
            if (Objects.equals(mNodeListClient, null))
                mNodeListClient = Wearable.getNodeClient(this);
            Task getAllNodes = mNodeListClient.getConnectedNodes();
            getAllNodes.addOnSuccessListener(result -> {
                allNodes = (List<Node>) result;
            });
        }
         if (Objects.nonNull(intent))
                intentFromOnStart = intent;
            else return START_NOT_STICKY;

        if (!Constants.ACTION.BIND_ACTION.equals(intent.getAction())) {
            //createNotificationChannel();
            //prepareAndStartForeground();
            //mStartForegroundService(intent);
            //prepareAndStartForeground();
            //mStartForegroundService(intent);

            serviceRunner(intent);
            //showNotification(0);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void bindMobileRunner() {
        Log.v(TAG, "bindMobileRunner()");
        try {
            Wearable.getCapabilityClient(this)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            capabilityClient = Wearable.getCapabilityClient(this);
            capabilityClient.addLocalCapability(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearSD);
            capabilityClient.addListener(this, Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
            Wearable.getMessageClient(this).addListener(this);
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
    }


    private void unBindMobileRunner() {
        Log.d(TAG, "unBindMobileRunner() :  running unbind mobile");
        Wearable.getMessageClient(this).removeListener(this);
        Wearable.getCapabilityClient(this).removeListener(this);
    }

    private void serviceRunner(Intent intent) {
        Log.d(TAG, "serviceRunner() :  running bind service ");
        if (Objects.isNull(intentFromOnStart)) intentFromOnStart = intent;

        if (Objects.nonNull(intentFromOnStart)
        ) {
            Log.d(TAG, "got intent: " + intent);
            if (Constants.GLOBAL_CONSTANTS.mStartUri.equals(intentFromOnStart.getData())) {
                Log.i(TAG, "Received Start Foreground Intent ");
                // your start service code
                try {

                    //if (mTextView != null) mTextView.setText("Service Started");
                    //if (mTextView != null) mTextView.setText("onStart");

                    // Initialise Notification channel for API level 26 and over
                    // from https://stackoverflow.com/questions/44443690/notificationcompat-with-api-26
                    mNM = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);
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
                        WorkManager.getInstance(this);
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
                mNodeListClient = Wearable.getNodeClient(this);

                Log.v(TAG, "onStartCommand() - checking permission for sensors and registering");

                if (mSdData.serverOK && !sensorsActive) bindSensorListeners();


                //mAccData = new double[NSAMP];
                // mSdData = new SdData();

                mVibe = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);


                // Prevent sleeping
                PowerManager pm = (PowerManager) (getApplicationContext().getSystemService(Context.POWER_SERVICE));
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A:WT");

                if (!mWakeLock.isHeld()) {
                    mWakeLock.acquire(24 * 60 * 60 * 1000L /*1 DAY*/);
                }

                // Bind Android mobile through android-wear libraries.
                bindMobileRunner();

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


            } else if (Constants.ACTION.STOPFOREGROUND_ACTION.equals(intentFromOnStart.getAction()) ||
                    Constants.GLOBAL_CONSTANTS.mStopUri.equals(intentFromOnStart.getData()) ||
                    Constants.ACTION.STOP_WEAR_SD_ACTION.equals(intentFromOnStart.getAction())) {
                Log.i(TAG, "Received Stop Foreground Intent");
                //your end service code

                unBindBatteryEvents();
                unBindSensorListeners();
                unBindMobileRunner();
                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, Constants.ACTION.STOP_WEAR_SD_ACTION);
                stopSelf();
                stopForeground(true);

            /*
            this.stopSelfResult(startId);*/
            }
        }
    }


    private void connectObserver(UUID connectingUuid) {
        mHandler.postDelayed(() -> {
            ServiceLiveData connectingLiveData = serviceLiveData;
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
                PendingIntent.getActivity(this,
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
            CharSequence name = this.getString(R.string.app_name);
            String description = this.getString(R.string.app_name);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            channel = new NotificationChannel("Default notification", name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager = this.getSystemService(NotificationManager.class);
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

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.GLOBAL_CONSTANTS.mAppPackageNameWearSD);
            builder.setContentTitle(String.valueOf(R.string.app_name));
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_24x24);
            builder.setLargeIcon(bitmap);

            builder.setContentIntent(PendingIntent.getActivity(this, 1, new Intent(this, AWSdService.class), PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationCompat.WearableExtender extender = new NotificationCompat.WearableExtender();
//            extender.setBackground(BitmapFactory.decodeResource(this.getResources(), R.drawable.card_background));
//            extender.setContentIcon(R.drawable.icon_24x24);
            extender.extend(builder);

            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setContentText(String.valueOf(R.string.app_name));
//            builder.setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.drawable.star_of_life_24x24));
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
        return useWhiteIcon ? R.drawable.star_of_life_24x24 : R.drawable.icon_24x24;
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (mSdData == null)
            mSdData = new SdData();
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
            calculateStaticTimings();
            bindSensorListeners();
            //TODO
        } else if (!messageEventPath.isEmpty() && Constants.ACTION.STOP_WEAR_SD_ACTION.equals(messageEventPath)) {
            mUtil.stopServer();
        } else if (!messageEventPath.isEmpty() && Constants.ACTION.PUSH_SETTINGS_ACTION.equals(messageEventPath)) {
            Log.v(TAG, "Received new settings");

            try {
                mSdData.fromJSON(s1);
                mSdDataSettings = mSdData;
                calculateStaticTimings();
                prefValHrAlarmActive = mSdData.mHRAlarmActive;
                if (!Objects.equals(mNodeFullName, null))
                    if (mNodeFullName.isEmpty()) {
                        String nodeFullName = mNodeFullName;
                        mNodeFullName = mSdData.phoneName;
                    }
                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.serverOK = true;
                mSdData.haveData = true;
                mSampleFreq = mSdData.mSampleFreq;


                if (sensorsActive) unBindSensorListeners();
                bindSensorListeners();
            } catch (Exception e) {
                Log.v(TAG, "Received new settings failed to process", new Throwable());
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_REQUESTED)) {
            try {
                Log.v(TAG, "onMessageReceived() : if receivedData ");

                mSdData.haveSettings = true;
                mSdData.watchAppRunning = true;
                mSdData.watchConnected = true;
                mSdData.haveData = true;

                //TODO: Decide what to do with the population of id and name. Nou this is being treated
                // as broadcast to all client watches.

                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, mSdData.toSettingsJSON());


            } catch (Exception e) {
                Log.e(TAG, "OnMessageReceived(): catch on Received new settings failed to process", e);
            }
        } else if (!messageEventPath.isEmpty() && Objects.equals(messageEventPath, Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED)) {
            try {
                SdData sdData = new SdData();
                sdData.fromJSON(s1);
                sdData.serverOK = true;

                mSdData = sdData;
                mSdDataSettings = sdData;
                calculateStaticTimings();

            } catch (Exception e) {
                Log.e(TAG, "onMessageReceived()", e);
            }
        } else if (!messageEventPath.isEmpty() && Constants.ACTION.BATTERYUPDATE_ACTION.equals(messageEventPath)) {
            serverBatteryPct = Integer.parseInt(s1);
            serviceLiveData.signalChangedData();
        } else {
            Log.v(TAG + "nn", "Not processing received message, displaying in log: " + s1);
        }

    }

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(TAG, "CapabilityInfo received: " + capabilityInfo.toString());
        Set<Node> changedNodeSet = capabilityInfo.getNodes();
        Node changedNode = null;
        try {
            if (Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver.equalsIgnoreCase(capabilityInfo.getName())) {
                Log.v(TAG, "Received: " + capabilityInfo.getName());
                if (changedNodeSet.size() == 0) return;
                changedNode = changedNodeSet.stream().findFirst().get();
                mMobileNodesWithCompatibility = capabilityInfo;
                if (!Objects.equals(mWearNode, null)) if (mWearNode.equals(changedNode)) {
                    mSdData.watchConnected = true;
                    mMobileDeviceConnected = true;
                }
            }
            if (Constants.GLOBAL_CONSTANTS.mAppPackageNameWearSD.equalsIgnoreCase(capabilityInfo.getName())) {
                Log.v(TAG, "Received: " + capabilityInfo.getName());
                if (changedNodeSet.size() == 0) return;
            }

            if ("is_connection_lost".equals(capabilityInfo.getName())) {
                mSdData.serverOK = false;
                mSdData.watchConnected = false;
                unBindSensorListeners();

            } else {
                Log.d(TAG, "onCapabilityChanged(): count of set changedCapabilities: " + changedNodeSet.size());
                mSdData.watchConnected = false;
                //mSdData.serverOK = false;
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
        mSdData.mSampleFreq = (long) mCurrentMaxSampleCount / mSdDataSettings.analysisPeriod;

        // now we have mSampleFreq in number samples / second (Hz) as default.
        // to calculate sampleTimeUs: (1 / mSampleFreq) * 1000 [1s == 1000000us]
        mSampleTimeUs = (1d / (double) mSdData.mSampleFreq) * 1e6d;

        // num samples == fixed final 250 (NSAMP)
        // time seconds in default == 10 (SIMPLE_SPEC_FMAX)
        // count samples / time = 25 samples / second == 25 Hz max.
        // 1 Hz == 1 /s
        // 25 Hz == 0,04s
        // 1s == 1.000.000 us (sample interval)
        // sampleTime = 40.000 uS == (SampleTime (s) * 1000)
        if (mSdDataSettings.rawData.length > 0 && mSdDataSettings.dT > 0d) {
            double mSDDataSampleTimeUs = 1d / (double) (Constants.SD_SERVICE_CONSTANTS.defaultSampleCount / Constants.SD_SERVICE_CONSTANTS.defaultSampleTime) * 1.0e6;
            mConversionSampleFactor = mSampleTimeUs / mSDDataSampleTimeUs;
        } else
            mConversionSampleFactor = 1d;
        if (accelerationCombined != -1d) {
            gravityScaleFactor = (Math.round(accelerationCombined / SensorManager.GRAVITY_EARTH) % 10d);

        } else {
            gravityScaleFactor = 1d;
        }
        miliGravityScaleFactor = gravityScaleFactor * 1e3;

    }

    public void bindSensorListeners() {
        try {

            if (Objects.nonNull(mSdData)) {
                if (mSdData.serverOK){
                    if (mSampleTimeUs < (double) SensorManager.SENSOR_DELAY_NORMAL ||
                            Double.isInfinite(mSampleTimeUs) ||
                            Double.isNaN(mSampleTimeUs)) {
                        calculateStaticTimings();
                        if (mSampleTimeUs <= 0d ||
                                Double.isInfinite(mSampleTimeUs) ||
                                Double.isNaN(mSampleTimeUs))
                            mSampleTimeUs = SensorManager.SENSOR_DELAY_NORMAL;
                    }
                    mSdData.watchSdVersion = BuildConfig.VERSION_NAME;
                    mSdData.watchFwVersion = Build.DISPLAY;
                    mSdData.watchPartNo = Build.BOARD;
                    mSdData.watchSdName = Build.MODEL;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (this.checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 1);
                            ActivityCompat.requestPermissions(getActivity(this),
                                    new String[]{Manifest.permission.BODY_SENSORS},
                                    Constants.GLOBAL_CONSTANTS.PERMISSION_REQUEST_BODY_SENSORS);


                        } else {
                            Log.d(TAG, "ALREADY GRANTED");
                        }
                    }

                    if (Objects.isNull(heartBeatSensor))
                        heartBeatSensor = new HeartBeatSensor((Context) this,
                                (int) Constants.GLOBAL_CONSTANTS.getMaxHeartRefreshRate,
                                (int) (Constants.GLOBAL_CONSTANTS.getMaxHeartRefreshRate * 4)) {
                            @Nullable
                            @Override
                            public void onSensorValuesChanged(SensorEvent event) {
                                onSensorChanged(event);
                            }

                            @Override
                            public void setOnSensorValuesChangedListener(@NonNull Function1 listener) {

                            }
                        };
                    mSensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
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
                    unBindBatteryEvents();
                    bindBatteryEvents();

                    mSdData.watchAppRunning = true;
                }
            }
            mHandler.postDelayed(AWSdService.this::bindSensorListeners,100);
        } catch (Exception e) {
            Log.e(TAG, "bindSensorListners(): Sensor declaration excepted: ", e);
        }
    }

    private void unBindSensorListeners() {
        if (Objects.nonNull(mSensor))
            mSensorManager.unregisterListener(this, mSensor);
        if (Objects.nonNull(mHeartSensor))
            mSensorManager.unregisterListener(this, mHeartSensor);
        if (Objects.nonNull(mHeartBeatSensor))
            mSensorManager.unregisterListener(this, mHeartBeatSensor);
        if (Objects.nonNull(mBloodPressure))
            mSensorManager.unregisterListener(this, mBloodPressure);
        if (Objects.nonNull(mStationaryDetectSensor))
            mSensorManager.unregisterListener(this, mStationaryDetectSensor);
        sensorsActive = false;
    }

    private void unBindBatteryEvents() {

        if (Objects.nonNull(powerUpdateReceiverPowerUpdated)) {
            if (powerUpdateReceiverPowerUpdated.isRegistered)
                powerUpdateReceiverPowerUpdated.unregister(this);
        }
        if (Objects.nonNull(powerUpdateReceiverPowerLow)) {
            if (powerUpdateReceiverPowerLow.isRegistered)
                powerUpdateReceiverPowerLow.unregister(this);
        }
        if (Objects.nonNull(powerUpdateReceiverPowerOkay)) {
            if (powerUpdateReceiverPowerOkay.isRegistered)
                powerUpdateReceiverPowerOkay.unregister(this);
        }
        if (Objects.nonNull(powerUpdateReceiverPowerConnected)) {
            if (powerUpdateReceiverPowerConnected.isRegistered)
                powerUpdateReceiverPowerConnected.unregister(this);
        }
        if (Objects.nonNull(powerUpdateReceiverPowerDisConnected)) {
            if (powerUpdateReceiverPowerDisConnected.isRegistered)
                powerUpdateReceiverPowerDisConnected.unregister(this);
        }
        if (Objects.nonNull(powerUpdateReceiver))
            if (((PowerUpdateReceiver) powerUpdateReceiver).isRegistered)
                this.unregisterReceiver(powerUpdateReceiver);
        if (Objects.nonNull(connectionUpdateReceiver))
            if (connectedConnectionUpdates) {
                this.unregisterReceiver(connectionUpdateReceiver);
                connectedConnectionUpdates = false;
            }

        batteryStatusIntent = null;
    }

    protected void powerUpdateReceiveAction(Intent intent) {
        try {
            //mBound = serviceLiveData.hasActiveObservers(); // change out global mbound with serviceLiveData.hasActiveObservers())
            if (intent.getAction() != null) {
                Log.d(TAG, "onReceive(): Received action:  " + intent.getAction());
                // Are we charging / charged?
                if (
                        Intent.ACTION_POWER_CONNECTED.equals(intent.getAction()) ||
                                Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                    mChargingState = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    mIsCharging = mChargingState == BatteryManager.BATTERY_STATUS_CHARGING ||
                            mChargingState == BatteryManager.BATTERY_STATUS_FULL;

                    // How are we charging?
                    chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                    acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;

                    if (mIsCharging && sensorsActive && Intent.ACTION_POWER_CONNECTED.equals(intent.getAction()))
                        unBindSensorListeners();
                    if (!mIsCharging && mMobileDeviceConnected && !sensorsActive && Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction()))
                        bindSensorListeners();

                }

                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    if (Objects.isNull(intent)) return;
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);

                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batteryPct = 100 * level / (float) scale;
                    mSdData.batteryPc = (int) (batteryPct);

                    mChargingState = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    mIsCharging = mChargingState == BatteryManager.BATTERY_STATUS_CHARGING ||
                            mChargingState == BatteryManager.BATTERY_STATUS_FULL;

                    // How are we charging?
                    chargePlug = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                    acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;
                    boolean batcap = chargePlug == BatteryManager.BATTERY_PROPERTY_CAPACITY;
                    boolean wirelessCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    if (mIsCharging && sensorsActive && Intent.ACTION_POWER_CONNECTED.equals(intent.getAction()))
                        unBindSensorListeners();
                    if (!mIsCharging && mMobileDeviceConnected && mBound && !sensorsActive && Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction()))
                        bindSensorListeners();
                }
                if (intent.getAction().equals(Intent.ACTION_BATTERY_LOW) ||
                        intent.getAction().equals(Intent.ACTION_BATTERY_OKAY)) {

                    if (sensorsActive && batteryPct < 15f)
                        unBindSensorListeners();
                }
                if (mBound) {
                    mUtil.runOnUiThread(() -> {
                        if (Objects.nonNull(serviceLiveData))
                            if (serviceLiveData.hasActiveObservers())
                                serviceLiveData.signalChangedData();
                        Log.d(TAG, "onBatteryChanged(): runOnUiThread(): updateUI");

                    });

                }
            }
        } catch (Exception e) {
            Log.e(TAG, "powerUpdateReceiveAction() : error in type", e);
        }

    }


    private void bindBatteryEvents() {

        if (Objects.isNull(powerUpdateReceiverPowerConnected))
            powerUpdateReceiverPowerConnected = new PowerUpdateReceiver();
        if (Objects.isNull(powerUpdateReceiverPowerDisConnected))
            powerUpdateReceiverPowerDisConnected = new PowerUpdateReceiver();
        if (Objects.isNull(powerUpdateReceiverPowerOkay))
            powerUpdateReceiverPowerOkay = new PowerUpdateReceiver();
        if (Objects.isNull(powerUpdateReceiverPowerLow))
            powerUpdateReceiverPowerLow = new PowerUpdateReceiver();
        if (Objects.isNull(powerUpdateReceiverPowerUpdated))
            powerUpdateReceiverPowerUpdated = new PowerUpdateReceiver();
        if (Objects.isNull(powerUpdateReceiver)) powerUpdateReceiver = new PowerUpdateReceiver();
        batteryStatusIntentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);


        if (Objects.isNull(batteryStatusIntent) && !powerUpdateReceiverPowerUpdated.isRegistered) {
            batteryStatusIntent = powerUpdateReceiverPowerUpdated.register(this, batteryStatusIntentFilter);//this.registerReceiver(powerUpdateReceiver, batteryStatusIntentFilter);
            mSdData.batteryPc = (long) ((batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) / (float) batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) * 100f);
            powerUpdateReceiverPowerUpdated.isRegistered = true;

        }
        powerUpdateReceiverPowerConnected.register(this, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        powerUpdateReceiverPowerConnected.isRegistered = true;
        powerUpdateReceiverPowerDisConnected.register(this, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
        powerUpdateReceiverPowerDisConnected.isRegistered = true;
        powerUpdateReceiverPowerOkay.register(this, new IntentFilter(Intent.ACTION_BATTERY_LOW));
        powerUpdateReceiverPowerLow.register(this, new IntentFilter(Intent.ACTION_BATTERY_OKAY));

        if (Objects.nonNull(connectionUpdateReceiver) && !connectedConnectionUpdates)
            this.registerReceiver(connectionUpdateReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        connectedConnectionUpdates = true;


    }




    public void setReceiverAndUpdateMobileUri() {
        NodeClient nodeClient = Wearable.getNodeClient(this);
        Task<List<Node>> nodeList = nodeClient.getConnectedNodes();
        nodeList.addOnCompleteListener(task ->
        {
            if (task.isSuccessful()) {
                if (task.getResult().size() > 0) {

                    mMobileNodeUri = task.getResult().get(0).getId();
                    mMobileNodeDisplayName = task.getResult().get(0).getDisplayName();
                    capabilityClient = Wearable.getCapabilityClient(this);
                    capabilityClient.addLocalCapability(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
                    capabilityClient.addListener(this, Constants.GLOBAL_CONSTANTS.mAppPackageName);

                }

            }

        });

    }

    public boolean isCharging() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent lIbatteryStatus = this.registerReceiver(null, ifilter);
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

            if (mSdData != null) if (mSdData.serverOK) bindSensorListeners();
            unBindBatteryEvents();
            bindBatteryEvents();
            mHandler.postDelayed(() -> serviceRunner(intent), 100);

        } catch (Exception e) {
            Log.e(TAG, "onBind(): Error in reloading vars ", e);
        }
        mBound = true;
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
                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_RECEIVED, Constants.ACTION.STOP_WEAR_SD_ACTION);
            }

            //Unbind before Destroying.
            unBindMobileRunner();

            if (mWakeLock != null) if (mWakeLock.isHeld()) mWakeLock.release();
            unBindBatteryEvents();
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
            Wearable.getCapabilityClient(this)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(this).addListener(this);
            unBindBatteryEvents();
            bindBatteryEvents();
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
            if (!powerUpdateReceiverPowerUpdated.isRegistered)
                bindBatteryEvents();
            if (mSdData.batteryPc == 0 && Objects.nonNull(batteryStatusIntent)) {
                int level = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);

                int scale = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                batteryPct = 100 * level / (float) scale;
                mSdData.batteryPc = (int) (batteryPct);
            }
            if (Objects.nonNull(mSdDataSettings))
                if (!isCharging() ) {
                    if (Objects.isNull(rawDataList)) rawDataList = new ArrayList<>();
                    if (Objects.isNull(rawDataList3D)) rawDataList3D = new ArrayList<>();
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
                                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA, mSdData.toDataString(true));
                            }
                            heartRates.add(mSdData.mHR);
                        }
                        mSdData.mHRAvg = calculateAverage(heartRates);
                        if (heartRates.size() < 4) {
                            mSdData.mHRAvg = 0;
                            sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA, mSdData.toDataString(true));
                        }
                        checkAlarm();
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
                                doAnalysis();
                                checkAlarm();
                                sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA, mSdData.toDataString(true));
                                mSdData.mNsamp = 0;
                                mStartTs = event.timestamp;
                                return;
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
                                return;
                            } else if (mSdData.mNsamp > mCurrentMaxSampleCount - 1) {
                                Log.v(TAG, "onSensorChanged(): Received data during analysis - ignoring sample");
                                return;
                            } else if (rawDataList.size() >= mCurrentMaxSampleCount) {
                                Log.v(TAG, "onSensorChanged(): mSdData.mNSamp and mCurrentMaxSampleCount differ in size");
                                rawDataList.remove(0);
                                rawDataList3D.remove(0);
                                rawDataList3D.remove(0);
                                rawDataList3D.remove(0);
                                return;
                            } else {
                                Log.v(TAG, "onSensorChanged(): Received empty data during analysis - ignoring sample");
                            }

                        } else {
                            Log.v(TAG, "onSensorChanged(): ERROR - Mode " + mMode + " unrecognised");
                        }
                    }
                } else Log.d(TAG, "onSensorChanged() : is_Charging is true, ignoring sample");
        } finally {
            if (mBound) mUtil.runOnUiThread(() -> {
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
        //mSdData.mHRAlarmActive = (mSdData.alarmState != 6 && mSdData.alarmState != 10);

        boolean inAlarm = false;
        Long alarmHoldOfTime = (mSdData.alarmTime + (mAlarmTime * 25000L));
        if (Calendar.getInstance().getTimeInMillis() > alarmHoldOfTime) {
            if (mSdData.roiPower > mSdData.alarmThresh && mSdData.roiRatio > mSdData.alarmRatioThresh) {
                inAlarm = true;
                mAlarmTime = (int) mSdData.alarmTime;
                mSdData.mHRAlarmStanding = true;
            }

            if (mSdData.mHRAvg > 0d)
                if (mSdData.mHRAlarmActive && ((mSdData.mHRAvg < mSdData.mHRThreshMin) || (mSdData.mHRAvg > mSdData.mHRThreshMax))) {
                    inAlarm = true;
                    mSdData.mHRAlarmStanding = true;
                    mAlarmTime = (int) mSdData.alarmTime;
                }
            if (mSdData.mHRAlarmActive && mSdData.mHRAvg != 0d && mSdData.mHR > mSdData.mHRAvg * mHeartPercentThresh) {
                inAlarm = true;
                mAlarmTime = (int) mSdData.alarmTime;
                mSdData.mHRAlarmStanding = true;
            }
            //Log.v(TAG, "checkAlarm() roiPower " + mSdData.roiPower + " roiRaTIO " + mSdData.roiRatio);
        }
        showNotification((int) mSdData.alarmState);
        if (inAlarm) {
            alarmCount += 1;

            if (alarmHoldOfTime > mSdData.alarmTime) {
                mSdData.alarmState = 2;
                long[] pattern = {0, 100, 200, 300};
                mVibe.vibrate(pattern, -1);
            } else if (alarmCount > 2) {
                mSdData.alarmState = 1;
            }


            //
        } else {
            // If we are in an ALARM state, revert back to WARNING, otherwise
            // revert back to OK.
            if (alarmCount >= 2) {
                alarmCount -= 1;
            } else {
                mSdData.alarmState = 0;
                alarmCount = 0;
            }
        }
        if (mSdData.alarmState == 1 || mSdData.alarmState == 2) {
            Intent intent = new Intent(this.getApplicationContext(), StartUpActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            this.startActivity(intent);
        }


    }

    private double calculateAverage(List<Double> marks) {
        double sum = 0;
        if (!marks.isEmpty()) {
            for (Double mark : marks) {
                sum += mark;
            }
            return sum / marks.size();
        }
        return sum;
    }

    /**
     * doAnalysis() - analyse the data if the accelerometer data array mAccData
     * and populate the output data structure mSdData
     */
    private void doAnalysis() {
        int nMin = 0;
        int nMax = 0;
        int nFreqCutoff = 0;
        fft = null;
        try {
            // FIXME - Use specified sampleFreq, not this hard coded one
            mSampleFreq = Constants.SD_SERVICE_CONSTANTS.defaultSampleRate;
            double freqRes = 1.0 * mSampleFreq / mSdData.mNsamp;
            Log.v(TAG, "doAnalysis(): mSampleFreq=" + mSampleFreq + " mNSamp=" + mSdData.mNsamp + ": freqRes=" + freqRes);
            Log.v(TAG, "doAnalysis(): rawData=" + Arrays.toString(mSdData.rawData));
            // Set the frequency bounds for the analysis in fft output bin numbers.
            nMin = (int) (mAlarmFreqMin / freqRes);
            nMax = (int) (mAlarmFreqMax / freqRes);
            Log.v(TAG, "doAnalysis(): mAlarmFreqMin=" + mAlarmFreqMin + ", nMin=" + nMin
                    + ", mAlarmFreqMax=" + mAlarmFreqMax + ", nMax=" + nMax);
            // Calculate the bin number of the cutoff frequency
            nFreqCutoff = (int) (mFreqCutoff / freqRes);
            Log.v(TAG, "mFreqCutoff = " + mFreqCutoff + ", nFreqCutoff=" + nFreqCutoff);

            DoubleFFT_1D fftDo = new DoubleFFT_1D(mSdData.mNsamp);
            fft = new double[mSdData.mNsamp * 2];
            ///System.arraycopy(mAccData, 0, fft, 0, mNsamp);
            System.arraycopy(mSdData.rawData, 0, fft, 0, mSdData.mNsamp);
            fftDo.realForward(fft);

            // Calculate the whole spectrum power (well a value equivalent to it that avoids square root calculations
            // and zero any readings that are above the frequency cutoff.
            double specPower = 0;
            for (int i = 1; i < mSdData.mNsamp / 2; i++) {
                if (i <= nFreqCutoff) {
                    specPower = specPower + getMagnitude(fft, i);
                } else {
                    fft[2 * i] = 0.;
                    fft[2 * i + 1] = 0.;
                }
            }
            //Log.v(TAG,"specPower = "+specPower);
            //specPower = specPower/(mSdData.mNsamp/2);
            specPower = specPower / mSdData.mNsamp / 2;
            //Log.v(TAG,"specPower = "+specPower);

            // Calculate the Region of Interest power and power ratio.
            double roiPower = 0;
            for (int i = nMin; i < nMax; i++) {
                roiPower = roiPower + getMagnitude(fft, i);
            }
            roiPower = roiPower / (nMax - nMin);
            double roiRatio = 10 * roiPower / specPower;

            // Calculate the simplified spectrum - power in 1Hz bins.
            simpleSpec = new double[SIMPLE_SPEC_FMAX + 1];
            for (int ifreq = 0; ifreq < SIMPLE_SPEC_FMAX; ifreq++) {
                int binMin = (int) (1 + ifreq / freqRes);    // add 1 to loose dc component
                int binMax = (int) (1 + (ifreq + 1) / freqRes);
                simpleSpec[ifreq] = 0;
                for (int i = binMin; i < binMax; i++) {
                    simpleSpec[ifreq] = simpleSpec[ifreq] + getMagnitude(fft, i);
                }
                simpleSpec[ifreq] = simpleSpec[ifreq] / (binMax - binMin);
            }

            // Populate the mSdData structure to communicate with the main SdServer service.
            mSdData.dataTime.setToNow();
            mSdData.specPower = (long) (specPower / gravityScaleFactor);
            mSdData.roiPower = (long) (roiPower / gravityScaleFactor);
            mSdData.dataTime.setToNow();
            mSdData.maxVal = 0;   // not used
            mSdData.maxFreq = 0;  // not used
            mSdData.haveData = true;
            mSdData.alarmThresh = mAlarmThresh;
            mSdData.alarmRatioThresh = mAlarmRatioThresh;
            mSdData.alarmFreqMin = mAlarmFreqMin;
            mSdData.alarmFreqMax = mAlarmFreqMax;
            // note mSdData.batteryPc is set from settings data in updateFromJSON()
            // FIXME - I haven't worked out why dividing by 1000 seems necessary to get the graph on scale - we don't seem to do that with the Pebble.
            for (int i = 0; i < SIMPLE_SPEC_FMAX; i++) {
                mSdData.simpleSpec[i] = (int) (simpleSpec[i] / gravityScaleFactor);
            }
            Log.v(TAG, "simpleSpec = " + Arrays.toString(mSdData.simpleSpec));

            // Because we have received data, set flag to show watch app running.
        } catch (Exception e) {
            Log.e(TAG, "doAnalysis - Exception during Analysis", e);
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
                Wearable.getCapabilityClient(this)
                        .addListener(
                                this,
                                Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                        );
                Wearable.getMessageClient(this).addListener(this);
                Log.e(TAG, "SendMessageFailed: No node-Id stored");
            } else {
                Log.v(TAG,
                        "Sending message to "
                                + mMobileNodeUri + " And name: " + mNodeFullName
                );
                sendMessageTask = Wearable.getMessageClient(this)
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
            Wearable.getCapabilityClient(this)
                    .addListener(
                            this,
                            Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE
                    );
            Wearable.getMessageClient(this).addListener(this);
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
        WorkManager workManager = WorkManager.getInstance(this);
        


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

    /**
     * class to handle signaling listening Service of changed data.
     * binding Activity has to subscribe using:
     * serviceLiveData.observe(this, this::onChangedObserver);
     * this can also be used for events from binding Activity to
     * Service calls.
     */
    public class ServiceLiveData extends LiveData<SdData>{


        public void signalChangedData() {
            this.postValue(mSdData);
        }


        /*

         * 1: create Intent ,
         * 2: start service with intent,
         * 3: bind started Service,
         * 4: assign ServiceLiveData,
         * 5: observe ServiceLiveData from binding Activity and from service
         * */

        //TODO: clean redundant code: replace intent actions with ServiceLiveData
        // Try to change Pebble_SD -> WearReceiver  
    }

    public class PowerUpdateReceiver extends BroadcastReceiver {

        public boolean isRegistered = false;

        /**
         * register receiver
         *
         * @param context - Context
         * @param filter  - Intent Filter
         * @return see Context.registerReceiver(BroadcastReceiver,IntentFilter)
         */
        public Intent register(Context context, IntentFilter filter) {
            try {
                // ceph3us note:
                // here I propose to create
                // a isRegistered(Context) method
                // as you can register receiver on different context
                // so you need to match against the same one :)
                // example  by storing a list of weak references
                // see LoadedApk.class - receiver dispatcher
                // its and ArrayMap there for example
                return !isRegistered
                        ? context.registerReceiver(this, filter)
                        : null;


            } finally {
                isRegistered = true;
            }
        }


        /**
         * unregister received
         *
         * @param context - context
         * @return true if was registered else false
         */
        public boolean unregister(Context context) {
            // additional work match on context before unregister
            // eg store weak ref in register then compare in unregister
            // if match same instance
            return isRegistered
                    && unregisterInternal(context);
        }

        private boolean unregisterInternal(Context context) {
            context.unregisterReceiver(this);
            isRegistered = false;
            return true;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            powerUpdateReceiveAction(intent);
        }

    }

}
