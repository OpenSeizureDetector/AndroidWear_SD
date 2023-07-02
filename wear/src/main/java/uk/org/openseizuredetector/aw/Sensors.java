package uk.org.openseizuredetector.aw;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;


abstract class AccelerationSensor extends AndroidSensor {
    AccelerationSensor(Context context,
                       int sensorDefaultSampleTimeUs,
                       int sensorDefaultMeasurementReportLatency) {
        super(context,
                PackageManager.FEATURE_SENSOR_ACCELEROMETER,
                Sensor.TYPE_ACCELEROMETER,
                sensorDefaultSampleTimeUs,
                sensorDefaultMeasurementReportLatency);

    }
}

abstract class HeartRateSensor extends AndroidSensor {
    HeartRateSensor(Context context,
                    int sensorDefaultSampleTimeUs,
                    int sensorDefaultMeasurementReportLatency) {
        super(context,
                PackageManager.FEATURE_SENSOR_HEART_RATE,
                Sensor.TYPE_HEART_RATE,
                sensorDefaultSampleTimeUs,
                sensorDefaultMeasurementReportLatency);
    }


}

abstract class HeartBeatSensor extends AndroidSensor {
    HeartBeatSensor(Context context,
                    int sensorSamplingPeriodUs,
                    int sensorDefaultMeasurementReportLatency) {
        super(context,
                PackageManager.FEATURE_SENSOR_HEART_RATE_ECG,
                Sensor.TYPE_HEART_BEAT,
                sensorSamplingPeriodUs,
                sensorDefaultMeasurementReportLatency);
    }



}

abstract class MotionDetectSensor extends AndroidSensor {
    MotionDetectSensor(Context context,
                       int sensorDefaultSampleTimeUs,
                       int sensorDefaultMeasurementReportLatency) {
        super(context,
                PackageManager.FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED,
                Sensor.TYPE_MOTION_DETECT,
                sensorDefaultSampleTimeUs,
                sensorDefaultMeasurementReportLatency);
    }
}



