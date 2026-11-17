package com.namakamu.mocap

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.util.concurrent.Executors

class MocapPlugin(godot: Godot) : GodotPlugin(godot) {

    private var landmarker: PoseLandmarker? = null
    private var analysis: ImageAnalysis? = null
    private val analyzerThread = Executors.newSingleThreadExecutor()

    override fun getPluginName() = "MocapPlugin"

    override fun getPluginSignals(): Set<SignalInfo> =
        setOf(SignalInfo("pose_frame", String::class.java))

    @UsedByGodot
    fun initPose() {
        val act = activity ?: return
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        landmarker = PoseLandmarker.createFromOptions(act, options)
    }

    @UsedByGodot
    fun startRecording() {
        val act = activity ?: return
        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            val provider = future.get()

            analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis!!.setAnalyzer(analyzerThread) { proxy ->
                val ts = System.currentTimeMillis()
                val bitmap = proxy.toBitmap()
                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = landmarker?.detectForVideo(mpImage, ts)
                val lm = result?.landmarks()?.firstOrNull()

                if (lm != null) {
                    val sb = StringBuilder("{\"t\":$ts,\"p\":[")
                    lm.forEachIndexed { i, p ->
                        if (i > 0) sb.append(',')
                        sb.append("[${p.x()},${p.y()},${p.z()},${p.visibility().orElse(0f)}]")
                    }
                    sb.append("]}")
                    val json = sb.toString()
                    act.runOnUiThread { emitSignal("pose_frame", json) }
                }
                proxy.close()
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                act as LifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis!!
            )
        }, ContextCompat.getMainExecutor(act))
    }

    @UsedByGodot
    fun stopRecording() {
        val act = activity ?: return
        act.runOnUiThread {
            ProcessCameraProvider.getInstance(act).get().unbindAll()
        }
    }
}
