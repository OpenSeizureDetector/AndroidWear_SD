package uk.org.openseizuredetector.aw;

import android.hardware.SensorEvent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlin.jvm.functions.Function1;

public interface MeasurableSensor {

    @Nullable
    abstract void onSensorValuesChanged(SensorEvent event);



    boolean doesSensorExist = false;
    abstract boolean getDoesSensorExist();

    abstract void startListening();
    abstract void stopListening();

    public void setOnSensorValuesChangedListener(@NotNull Function1 listener) ;


}
