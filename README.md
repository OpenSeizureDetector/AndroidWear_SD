# AndroidWear_SD
Seizure Detector using Android Wear watch.
The intention is that this Android Wear App will be functionally similar to the Pebble Watch Seizure Detector (see http://github.com/OpenSeizureDetector/Pebble_SD.git).
The App will run on an Android Wear watch, and communicate with a phone running OpenSeizureDetector (http://github.com/OpenSeizureDetector/Android_Pebble_SD), or a future Raspberry Pi based alarm system.

A heart rate detection routine has been added for Android Wear watches which have heart rate sensors.  The algorithm will trigger an alarm if the heart rate of the wearer is 30% higher than average.

Pressing I'm OK on the watch face will reset the alarm counter.

## Code Structure
The App contains the following major classes to provide the required functionality:
* AWSdService - a background service that continuously collects accelerometer data and performs seizure detection analysis periodically.   It communicates with the alarm system periodically, or when a seizure is detected.    Seizure Detection system parameters are read from persistent storage (preferences)
* AWSdComms - a class that handles bluetooth communications between the Android Wear watch and the alarm system (such as OpenSeizureDetector).   It handles the sending of routine information updates to the alarm system and sending alarm notifications.  It also receives settings from the alarm system and updates the persistent storage (preferences) to match the requested settings.
* AWSdMainActivity - the main activity for the watch app - Starts the background service AWSdService if it is not running, and displays data from the service on the screen.   Provides user interface functionality to start and stop the background service, mute alarms, and manually raise an alarm.
* PhoneAppMainActivity - we need a phone app to install the watch app on the watch - details to follow once I know how to do that!

## Building
* This app is intended to be compiled using Android version 4.4W (API Level 20)
* It uses the Gradle build system set up using Android Studio
* To build it, clone the repository, and import it into Android Studio.  That should be enough.....

## Running
* Enable developer mode - select Settings->About and press Build Number 7 times to enable developer menu.
* Go back to settings and select Developer Options and enable ADB debugging.
* Connect Android wear device to computer using USB.
* type adb devices and check it is there.
* I had permissions issues and I had to add the following line to /etc/udev/rules.d/51-android.rules:
  SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
  then re-start udev with sudo /etc/init.d/udev restart.
  and re-connect the watch - accept the prompt on the watch to enable adb debugging.
* Indroid studio select the "wear" module and run it - you should get a "hello world" message on the screen.


## Licence
All code is licenced under GPL Version 3, unless stated otherwise within the code.

## Credits
The following libraries and other media are used in AndroidWear_SD
* xxxx
* xxxx
