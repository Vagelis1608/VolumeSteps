/*
 * Copyright (C) 2014  P1nGu1n
 *
 * This file is part of VolumeSteps+.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.p1ngu1n.volumesteps;

import android.content.res.XResources;
import android.media.AudioManager;
import android.os.Build;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Changes the number of volume steps and disables the safe headset volume warning.
 * This class contains the code to be executed by Xposed.
 *
 * XDA-thread used as inspiration for changing the maximum volume:      http://forum.xda-developers.com/showthread.php?t=1411317
 * Source code of AudioService in which the max volumes are replaced:   https://github.com/android/platform_frameworks_base/blob/master/media/java/android/media/AudioService.java
 * Source code of the config file which values are replaced:            https://github.com/android/platform_frameworks_base/blob/master/core/res/res/values/config.xml
 */
public class AudioMod implements IXposedHookZygoteInit {
    private static final String AUDIO_SERVICE_CLASSNAME = "android.media.AudioService";
    private static final String AUDIO_SYSTEM_CLASSNAME = "android.media.AudioSystem";
    private static final String LOG_TAG = "VolumeSteps+: ";
    private static final boolean DEBUGGING = false;

    private static final int STREAM_ALARM_DEFAULT = 7;
    private static final int STREAM_MUSIC_DEFAULT = 15;
    private static final int STREAM_NOTIFICATION_DEFAULT = 7;
    private static final int STREAM_RING_DEFAULT = 7;
    private static final int STREAM_VOICECALL_DEFAULT = 5;


    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        // Load the user's preferences
        final XSharedPreferences prefs = new XSharedPreferences(BuildConfig.PACKAGE_NAME);

        if (DEBUGGING) {
            XposedBridge.log(LOG_TAG + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            Map<String, ?> sortedKeys = new TreeMap<String, Object>(prefs.getAll());
            for (Map.Entry<String, ?> entry : sortedKeys.entrySet()) {
                XposedBridge.log(LOG_TAG + entry.getKey() + "=" + entry.getValue().toString());
            }
        }

        final Class<?> audioServiceClass = XposedHelpers.findClass(AUDIO_SERVICE_CLASSNAME, null);
        final Class<?> audioSystemClass = XposedHelpers.findClass(AUDIO_SYSTEM_CLASSNAME, null);

        // Hook createAudioSystemThread, this method is called very early in the constructor of AudioService
        XposedHelpers.findAndHookMethod(audioServiceClass, "createAudioSystemThread", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Retrieve array containing the maximum stream volumes, depends on Android version
                int[] maxStreamVolume;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    maxStreamVolume = (int[]) XposedHelpers.getStaticObjectField(audioServiceClass, "MAX_STREAM_VOLUME");
                } else {
                    maxStreamVolume = (int[]) XposedHelpers.getObjectField(param.thisObject, "MAX_STREAM_VOLUME");
                }

                if (DEBUGGING) XposedBridge.log(LOG_TAG + "STREAM_MAX_VOLUME before: " + Arrays.toString(maxStreamVolume));

                // Get the max volumes and set them at the index of the right stream
                maxStreamVolume[AudioManager.STREAM_ALARM] = prefs.getInt("pref_stream_alarm", STREAM_ALARM_DEFAULT);
                maxStreamVolume[AudioManager.STREAM_MUSIC] = prefs.getInt("pref_stream_music", STREAM_MUSIC_DEFAULT);
                maxStreamVolume[AudioManager.STREAM_NOTIFICATION] = prefs.getInt("pref_stream_notification", STREAM_NOTIFICATION_DEFAULT);
                maxStreamVolume[AudioManager.STREAM_RING] = prefs.getInt("pref_stream_ring", STREAM_RING_DEFAULT);
                maxStreamVolume[AudioManager.STREAM_VOICE_CALL] = prefs.getInt("pref_stream_voicecall", STREAM_VOICECALL_DEFAULT);

                if (DEBUGGING) XposedBridge.log(LOG_TAG + "STREAM_MAX_VOLUME after: " + Arrays.toString(maxStreamVolume));
            }
        });


        // Whether the Safe Headset Volume Warning is disabled, default is false
        boolean saveHeadsetVolumeDisabled = prefs.getBoolean("pref_safe_headset_volume_disabled", false);

        if (saveHeadsetVolumeDisabled) {
            // Disable the safe headset volume warning
            XResources.setSystemWideReplacement("android", "bool", "config_safe_media_volume_enabled", false);
            if (DEBUGGING) XposedBridge.log(LOG_TAG + "Safe Headset Volume is disabled");
        } else {
            // Calculate the new headset volume warning to comply with the new maximum music volume
            int maxMusicSteps = prefs.getInt("pref_stream_music", 15);
            int safeHeadsetVolume = (int) Math.round(maxMusicSteps * (2.0 / 3.0));

            XResources.setSystemWideReplacement("android", "integer", "config_safe_media_volume_index", safeHeadsetVolume);
            if (DEBUGGING) XposedBridge.log(LOG_TAG + "Safe Headset Volume set to " + safeHeadsetVolume);
        }


        // Whether the volume keys control the music stream or the ringer volume
        boolean volumeKeysControlMusic = prefs.getBoolean("pref_volume_keys_control_music", false);
        if (DEBUGGING) XposedBridge.log(LOG_TAG + "Volume keys control " + (volumeKeysControlMusic ? "music" : "ringer"));

        if (volumeKeysControlMusic) {
            XposedHelpers.findAndHookMethod(audioServiceClass, "getActiveStreamType", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    boolean voiceCapable = XposedHelpers.getBooleanField(param.thisObject, "mVoiceCapable");
                    if (!voiceCapable) return;

                    boolean isInCommunication = (Boolean) XposedHelpers.callMethod(param.thisObject, "isInCommunication");
                    if (isInCommunication) return;

                    int suggestedStreamType = (Integer) param.args[0];
                    if (suggestedStreamType != AudioManager.USE_DEFAULT_STREAM_TYPE) return;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        boolean isAfMusicActiveRecently = (Boolean) XposedHelpers.callMethod(param.thisObject, "isAfMusicActiveRecently", 5000);
                        if (isAfMusicActiveRecently) return;
                    } else {
                        boolean musicStreamActive = (Boolean) XposedHelpers.callStaticMethod(audioSystemClass, "isStreamActive", AudioManager.STREAM_MUSIC, 5000);
                        if (musicStreamActive) return;
                    }

                    // 4.4 and higher call checkUpdateRemoteStateIfActive at the MediaFocusControl class instead of AudioService
                    Object objContainingRemoteStreamMethod = param.thisObject;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        objContainingRemoteStreamMethod = XposedHelpers.getObjectField(param.thisObject, "mMediaFocusControl");
                    }
                    boolean activeRemoteStream = (Boolean) XposedHelpers.callMethod(objContainingRemoteStreamMethod, "checkUpdateRemoteStateIfActive", AudioManager.STREAM_MUSIC);
                    if (activeRemoteStream) return;

                    param.setResult(AudioManager.STREAM_MUSIC);
                    if (DEBUGGING) XposedBridge.log(LOG_TAG + "Event: intercepted getActiveStreamType call; returned STREAM_MUSIC");
                }
            });
            if (DEBUGGING) XposedBridge.log(LOG_TAG + "Hooked: volume keys control music");
        }
    }
}
