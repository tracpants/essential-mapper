package com.aiden.essentialmapper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.runBlocking

/**
 * Intercepts the Essential Key and dispatches by tap count, driven by the persisted
 * config (single / double / triple → app package, the flashlight action, or nothing).
 *
 * The key reports keyCode=0 (KEYCODE_UNKNOWN) on the 3a Lite, so we MATCH ON scanCode
 * (250), not keyCode. Tap counting: each qualifying ACTION_DOWN bumps a counter and
 * (re)arms a debounce timer; when it fires we resolve on the final count.
 *
 * We return true for every event belonging to our key so the system doesn't also act
 * on it. Single-press is only ours once the Nothing "Essential" packages are disabled
 * via ADB (a one-time manual step); double/triple are ours regardless.
 */
class EssentialKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0

    private val resolveTaps = Runnable {
        val count = tapCount
        tapCount = 0
        dispatch(count)
    }

    // ---- Flashlight (torch) ----
    private val cameraManager by lazy { getSystemService(CameraManager::class.java) }
    private val torchCameraId: String? by lazy { findTorchCameraId() }
    @Volatile private var torchOn = false

    // Keeps torchOn in sync even if the torch is toggled from elsewhere (e.g. quick settings).
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) torchOn = enabled
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Not our key — let everything else pass through untouched.
        if (event.scanCode != TARGET_SCAN_CODE) return false

        // Count discrete presses on DOWN (repeat==0 ignores auto-repeat from a held key).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            tapCount++
            handler.removeCallbacks(resolveTaps)
            handler.postDelayed(resolveTaps, TAP_DEBOUNCE_MS)
            Log.i(TAG, "tap registered (running count=$tapCount)")
        }

        // Consume both DOWN and UP for our key.
        return true
    }

    private fun dispatch(count: Int) {
        // Re-read config on each event — cheap, and avoids any observer wiring.
        val target = Config.readBlocking(this).forCount(count)
        Log.i(TAG, "resolved tapCount=$count -> ${target ?: "none"}")
        when (target) {
            null -> { /* unmapped slot */ }
            Config.ACTION_FLASHLIGHT -> toggleTorch()
            else -> launchApp(target)
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "no launch intent for '$pkg' — dumping launchable packages:")
            dumpLaunchablePackages()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Log.i(TAG, "launched $pkg")
    }

    private fun dumpLaunchablePackages() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .sorted()
            .forEach { Log.i(TAG, "  launchable: $it") }
    }

    private fun findTorchCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "torch id lookup failed: $e")
        null
    }

    private fun toggleTorch() {
        val id = torchCameraId
        if (id == null) {
            Log.w(TAG, "no flash unit available")
            return
        }
        try {
            val next = !torchOn
            cameraManager.setTorchMode(id, next) // no CAMERA permission needed
            torchOn = next                        // callback will confirm/correct
            Log.i(TAG, "torch -> $next")
        } catch (e: Exception) {
            Log.w(TAG, "setTorchMode failed: $e")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Seed the chosen mapping on first run so taps work without opening the UI.
        runBlocking { Config.seedDefaultsIfEmpty(this@EssentialKeyService) }
        cameraManager.registerTorchCallback(torchCallback, handler)
        Log.i(TAG, "EssentialKeyService connected — scanCode=$TARGET_SCAN_CODE, debounce=${TAP_DEBOUNCE_MS}ms")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cameraManager.unregisterTorchCallback(torchCallback)
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "EMAP"

        // ---- Tunables (one obvious place) ----
        /** The Essential Key reports keyCode=0; this scanCode is its stable identity. */
        const val TARGET_SCAN_CODE = 250

        /** Window after the last tap before we resolve the count. Tune to taste. */
        const val TAP_DEBOUNCE_MS = 400L
    }
}
