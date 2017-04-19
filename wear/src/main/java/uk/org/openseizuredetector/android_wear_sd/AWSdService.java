package uk.org.openseizuredetector.android_wear_sd;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import org.jtransforms.fft.DoubleFFT_1D;

public class AWSdService extends Service implements SensorEventListener {
    private final static String TAG="AWSdService";
    private final static int NSAMP = 500;
    private final static int SIMPLE_SPEC_FMAX = 10;   // simple spectrum maximum freq in Hz.
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private int mMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mStartEvent = null;
    private long mStartTs = 0;
    public int mNSamp = 0;
    public double mSampleFreq = 0;
    public double[] mAccData;
    public SdData mSdData;

    private int mAlarmFreqMin = 3;  // Frequency ROI in Hz
    private int mAlarmFreqMax = 8;  // Frequency ROI in Hz
    private int mFreqCutoff = 12;   // High Frequency cutoff in Hz

    public class Access extends Binder {
        public AWSdService getService() {
            return AWSdService.this;
        }
    };
    public final Access binder = new Access();

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
        mSdData = new SdData();
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
                    mSampleFreq = mNSamp/dT;
                    Log.v(TAG,"Collected "+NSAMP+" data points in "+dT+" sec (="+mSampleFreq+" Hz) - analysing...");
                    doAnalysis();
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
        mSdData.dataTime.setToNow();
        mSdData.maxVal = 0;   // not used
        mSdData.maxFreq = 0;  // not used
        mSdData.haveData = true;
        for(int i=0;i<SIMPLE_SPEC_FMAX;i++) {
            mSdData.simpleSpec[i] = (int)simpleSpec[i];
        }
    }


}
