# AndroidWear_SD
Seizure Detector using Android Wear watch.
The intention is that this Android Wear App will be functionally similar to the Pebble Watch Seizure Detector (see http://github.com/OpenSeizureDetector/Pebble_SD.git).
The App will run on an Android Wear watch, and communicate with a phone running OpenSeizureDetector (http://github.com/OpenSeizureDetector/Android_Pebble_SD), or a future Raspberry Pi based alarm system.

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

## Licence
All code is licenced under GPL Version 3, unless stated otherwise within the code.

## Credits
The following libraries and other media are used in AndroidWear_SD
* xxxx
* xxxx
