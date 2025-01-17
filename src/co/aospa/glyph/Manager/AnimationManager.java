/*
 * Copyright (C) 2022-2024 Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.aospa.glyph.Manager;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import co.aospa.glyph.Constants.Constants;
import co.aospa.glyph.Utils.FileUtils;
import co.aospa.glyph.Utils.ResourceUtils;

public final class AnimationManager {

    private static final String TAG = "GlyphAnimationManager";
    private static final boolean DEBUG = true;

    private static Future<?> submit(Runnable runnable) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        return executorService.submit(runnable);
    }

    private static boolean check(String name, boolean wait) {
        if (DEBUG) Log.d(TAG, "Playing animation | name: " + name + " | waiting: " + Boolean.toString(wait));

        if (StatusManager.isAllLedActive()) {
            if (DEBUG) Log.d(TAG, "All LEDs are active, exiting animation | name: " + name);
            return false;
        }

        if (StatusManager.isCallLedActive()) {
            if (DEBUG) Log.d(TAG, "Call animation is currently active, exiting animation | name: " + name);
            return false;
        }

        if (StatusManager.isAnimationActive()) {
            long start = System.currentTimeMillis();
            if (name == "volume" && StatusManager.isVolumeLedActive()) {
                if (DEBUG) Log.d(TAG, "There is already a volume animation playing, update");
                StatusManager.setVolumeLedUpdate(true);
                while (StatusManager.isVolumeLedUpdate()) {
                    if (System.currentTimeMillis() - start >= 2500) return false;
                }
            } else if (wait) {
                if (DEBUG) Log.d(TAG, "There is already an animation playing, wait | name: " + name);
                while (StatusManager.isAnimationActive()) {
                    if (System.currentTimeMillis() - start >= 2500) return false;
                }
            } else {
                if (DEBUG) Log.d(TAG, "There is already an animation playing, exiting | name: " + name);
                return false;
            }
        }

        return true;
    }

    private static boolean checkInterruption(String name) {
        if (StatusManager.isAllLedActive()
                || (name != "call" && StatusManager.isCallLedEnabled())
                || (name == "call" && !StatusManager.isCallLedEnabled())
                || (name == "volume" && StatusManager.isVolumeLedUpdate())) {
            return true;
        }
        return false;
    }

    public static void playCsv(String name) {
        playCsv(name, false);
    }

    public static void playCsv(String name, boolean wait) {
        submit(() -> {
            if (!check(name, wait))
                return;

            StatusManager.setAnimationActive(true);

            String device = Constants.getDevice();
            String[] slugs = ResourceUtils.getStringArray("glyph_settings_animations_slugs");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ResourceUtils.getAnimation(name)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (checkInterruption("csv")) throw new InterruptedException();
                    line = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                    String[] pattern = line.split(",");
                    long start = System.currentTimeMillis();
                    if ((device.equals("phone1") && pattern.length == 5) // Phone (1) pattern on Phone (1)
                        || (device.equals("phone2") && pattern.length == 5) // Phone (1) pattern on Phone (2)
                        || (device.equals("phone2") && pattern.length == 33)) { // Phone (2) pattern on Phone (2)
                        updateLedFrame(pattern);
                    } else {
                        if (DEBUG) Log.d(TAG, "Animation line length mismatch | name: " + name + " | line: " + line);
                        throw new InterruptedException();
                    }
                    long delay = 16666 - (System.currentTimeMillis() - start);
                    if (device.equals("phone1")) {
                        Thread.sleep(10);
		    } else if (device.equals("phone2")) {
                        Thread.sleep(delay/1000);
		    }
                }
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation | name: " + name + " | exception: " + e);
            } finally {
                updateLedFrame(new float[5]);
                StatusManager.setAnimationActive(false);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: " + name);
            }
        });
    }

    public static void playCharging(int batteryLevel, boolean wait) {
        submit(() -> {
            if (!check("charging", wait))
                return;

            StatusManager.setAnimationActive(true);

            int batteryBase = ResourceUtils.getInteger("glyph_settings_battery_dot");
            int[] batteryArrayAll = ResourceUtils.getIntArray("glyph_settings_battery_levels");
            int amount = (int) (Math.floor((batteryLevel / 100.0) * (batteryArrayAll.length - 1)) + 1);
            int[] batteryArray = Arrays.copyOfRange(batteryArrayAll, 0, amount);

            try {
                if (checkInterruption("charging")) throw new InterruptedException();
                FileUtils.writeSingleLed(batteryBase, Constants.getBrightness());
                for (int i : batteryArray) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                    FileUtils.writeSingleLed(i, Constants.getBrightness());
                    Thread.sleep(15);
                }
                Thread.sleep(1000);
                for (int i = batteryArray.length - 1; i >= 0; i--) {
                    if (checkInterruption("charging")) throw new InterruptedException();
                    FileUtils.writeSingleLed(batteryArray[i], 0);
                    Thread.sleep(15);
                }
                FileUtils.writeSingleLed(batteryBase, 0);
                Thread.sleep(730);
            } catch (InterruptedException e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation, interrupted | name: charging");
                if (!StatusManager.isAllLedActive()) {
                    for (int i : batteryArrayAll) {
                        FileUtils.writeSingleLed(i, 0);
                    }
                    FileUtils.writeSingleLed(batteryBase, 0);
                }
            } finally {
                StatusManager.setAnimationActive(false);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: charging");
            }
        });
    }

    public static void playVolume(int volumeLevel, boolean wait) {
        submit(() -> {
            if (!check("volume", wait))
                return;

            StatusManager.setVolumeLedActive(true);
            StatusManager.setAnimationActive(true);

            int[] volumeArray = null;
            String device = Constants.getDevice();

            if (device.equals("phone1")) {
                volumeArray = new int[8];
            } else if (device.equals("phone2")) {
                volumeArray = new int[16];
            }

            int amount = (int) (Math.floor((volumeLevel / 100.0) * (volumeArray.length + 1)) + 1);

            try {
                if (checkInterruption("volume")) throw new InterruptedException();
                for (int i = 0; i < volumeArray.length; i++) {
                    if ( i <= amount - 1 && volumeLevel > 0) {
                        volumeArray[i] = Constants.getBrightness();
                    } else {
                        volumeArray[i] = 0;
                    }
                }
                if (checkInterruption("volume")) throw new InterruptedException();
		updateLedFrame(volumeArray);
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start <= 1800) {
                    if (checkInterruption("volume")) throw new InterruptedException();
                }
                for (int i = volumeArray.length - 1; i >= 0; i--) {
                    if (checkInterruption("volume")) throw new InterruptedException();
		    volumeArray[i] = 0;
                    updateLedFrame(volumeArray);
                    Thread.sleep(15);
                }
                long start2 = System.currentTimeMillis();
                while (System.currentTimeMillis() - start2 <= 800) {
                    if (checkInterruption("volume")) throw new InterruptedException();
                }
            } catch (InterruptedException e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation, interrupted | name: volume");
                if (!StatusManager.isAllLedActive() && !StatusManager.isVolumeLedUpdate()) {
                    for (int i = volumeArray.length - 1; i >= 0; i--) {
                        long start = System.currentTimeMillis();
		        volumeArray[i] = 0;
                        updateLedFrame(volumeArray);
                        while (System.currentTimeMillis() - start <= 15) {
                            // Do nothing.
			}
                    }
                }
            } finally {
                if (!StatusManager.isVolumeLedUpdate()) {
                    StatusManager.setAnimationActive(false);
                    StatusManager.setVolumeLedActive(false);
                }
                StatusManager.setVolumeLedUpdate(false);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: volume");
            }
        });
    }

    public static void playCall(String name) {
        submit(() -> {
            StatusManager.setCallLedEnabled(true);

            if (!check("call: " + name, true))
                return;

            StatusManager.setCallLedActive(true);

            String device = Constants.getDevice();
            String[] slugs = ResourceUtils.getStringArray("glyph_settings_animations_slugs");

            while (StatusManager.isCallLedEnabled()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        ResourceUtils.getCallAnimation(name)))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (checkInterruption("call")) throw new InterruptedException();
                        line = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                        String[] pattern = line.split(",");
                        long start = System.currentTimeMillis();
                        if ((device.equals("phone1") && pattern.length == 5) // Phone (1) pattern on Phone (1)
                            || (device.equals("phone2") && pattern.length == 5) // Phone (1) pattern on Phone (2)
                            || (device.equals("phone2") && pattern.length == 33)) { // Phone (2) pattern on Phone (2)
                            updateLedFrame(pattern);
                        } else {
                            if (DEBUG) Log.d(TAG, "Animation line length mismatch | name: " + name + " | line: " + line);
                            throw new InterruptedException();
                        }
                        long delay = 16666 - (System.currentTimeMillis() - start);
                        if (device.equals("phone1")) { 
                    	    Thread.sleep(10);
			} else if (device.equals("phone2")) { 
                    	    Thread.sleep(delay/1000);
			}
                    }
                } catch (Exception e) {
                    if (DEBUG) Log.d(TAG, "Exception while playing animation | name: " + name + " | exception: " + e);
                } finally {
                    if (StatusManager.isAllLedActive()) {
                        if (DEBUG) Log.d(TAG, "All LED active, pause playing animation | name: " + name);
                        while (StatusManager.isAllLedActive()) {}
                    }
                }
            }
            updateLedFrame(new float[5]);
            StatusManager.setCallLedActive(false);
            if (DEBUG) Log.d(TAG, "Done playing animation | name: " + name);
        });
    }

    public static void stopCall() {
        if (DEBUG) Log.d(TAG, "Disabling Call Animation");
        StatusManager.setCallLedEnabled(false);
    }

    public static void playEssential() {
        if (DEBUG) Log.d(TAG, "Playing Essential Animation");
        int led = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
        if (!StatusManager.isEssentialLedActive()) {
            submit(() -> {
                if (!check("essential", true))
                    return;
                
                StatusManager.setAnimationActive(true);

                try {
                    if (checkInterruption("charging")) throw new InterruptedException();
                    int[] steps = {1, 2, 4, 7};
                    for (int i : steps) {
                        if (checkInterruption("essential")) throw new InterruptedException();
                        updateLedSingle(led, Constants.getMaxBrightness() / 100 * i);
                        Thread.sleep(25);
                    }
                    Thread.sleep(250);
                } catch (InterruptedException e) {}

                StatusManager.setAnimationActive(false);
                StatusManager.setEssentialLedActive(true);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: essential");
            });
        } else {
            updateLedSingle(led, Constants.getMaxBrightness() / 100 * 7);
            return;
        }

    }

    public static void stopEssential() {
        if (DEBUG) Log.d(TAG, "Disabling Essential Animation");
        StatusManager.setEssentialLedActive(false);
        if (!StatusManager.isAnimationActive() && !StatusManager.isAllLedActive()) {
            int led = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
            updateLedSingle(led, 0);
        }
    }

    public static void playMusic(String name) {
        submit(() -> {
            float maxBrightness = (float) Constants.getMaxBrightness();
            float[] pattern = new float[5];

            switch (name) {
                case "low":
                    pattern[4] = maxBrightness;
                    break;
                case "mid_low":
                    pattern[3] = maxBrightness;
                    break;
                case "mid":
                    pattern[2] = maxBrightness;
                    break;
                case "mid_high":
                    pattern[0] = maxBrightness;
                    break;
                case "high":
                    pattern[1] = maxBrightness;
                    break;
                default:
                    if (DEBUG) Log.d(TAG, "Name doesn't match any zone, returning | name: " + name);
                    return;
            }

            try {
                updateLedFrame(pattern);
                Thread.sleep(90);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Exception while playing animation | name: music: " + name + " | exception: " + e);
            } finally {
                updateLedFrame(new float[5]);
                if (DEBUG) Log.d(TAG, "Done playing animation | name: " + name);
            }
        });
    }

    private static void updateLedFrame(String[] pattern) {
        updateLedFrame(Arrays.stream(pattern)
                .mapToInt(Integer::parseInt)
                .toArray());
    }

    private static void updateLedFrame(int[] pattern) {
        float[] floatPattern = new float[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            floatPattern[i] = (float) pattern[i];
        }
        updateLedFrame(floatPattern);
    }

    private static void updateLedFrame(float[] pattern) {
        //if (DEBUG) Log.d(TAG, "Updating pattern: " + pattern);
        float maxBrightness = (float) Constants.getMaxBrightness();
        int essentialLed = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
        if (StatusManager.isEssentialLedActive()) {
            if (pattern.length == 5) { // Phone (1) pattern
                if (pattern[1] < (maxBrightness / 100 * 7)) {
                    pattern[1] = maxBrightness / 100 * 7;
                }
            } else if (pattern.length == 33) { // Phone (2) pattern
                if (pattern[2] < (maxBrightness / 100 * 7)) {
                    pattern[2] = maxBrightness / 100 * 7;
                }
            }
        }
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = pattern[i] / maxBrightness * Constants.getBrightness();
        }
        FileUtils.writeFrameLed(pattern);
    }

    private static void updateLedSingle(int led, String brightness) {
        updateLedSingle(led, Float.parseFloat(brightness));
    }

    private static void updateLedSingle(int led, int brightness) {
        updateLedSingle(led, (float) brightness);
    }

    private static void updateLedSingle(int led, float brightness) {
        //if (DEBUG) Log.d(TAG, "Updating led | led: " + led + " | brightness: " + brightness);
        float maxBrightness = (float) Constants.getMaxBrightness();
        int essentialLed = ResourceUtils.getInteger("glyph_settings_notifs_essential_led");
        if (StatusManager.isEssentialLedActive()
                && led == essentialLed
                && brightness < (maxBrightness / 100 * 7)) {
            brightness = maxBrightness / 100 * 7;
        }
        FileUtils.writeSingleLed(led, brightness / maxBrightness * Constants.getBrightness());
    }
}
