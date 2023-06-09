package org.tensorflow.lite.examples.poseestimation.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tensorflow.lite.examples.poseestimation.VisualizationUtils
import org.tensorflow.lite.examples.poseestimation.YuvToRgbConverter
import org.tensorflow.lite.examples.poseestimation.data.Person
import org.tensorflow.lite.examples.poseestimation.ml.MoveNetMultiPose
import org.tensorflow.lite.examples.poseestimation.ml.PoseClassifier
import org.tensorflow.lite.examples.poseestimation.ml.PoseDetector
import org.tensorflow.lite.examples.poseestimation.ml.TrackerType
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.os.Looper
import android.util.Size
import org.tensorflow.lite.examples.poseestimation.MainActivity
import java.io.DataOutputStream
import java.net.Socket


class CameraSource(
    private val surfaceView: SurfaceView,
    private val listener: CameraSourceListener? = null,
) {
    var latestPerson: Person? = null

    // Socket과 DataOutputStream을 멤버 변수로 추가
    private var socket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    // 라즈베리 파이로 데이터 전송
    private fun sendToRaspberryPi(message: String) {
        Thread {
            try {
                // 소켓과 DataOutputStream이 이미 열려 있는지 확인
                if (socket == null || socket!!.isClosed) {
                    socket = Socket("192.168.1.198", 5555)
                    dataOutputStream = DataOutputStream(socket!!.getOutputStream())
                }
                // 메시지를 전송하고 줄 바꿈 문자를 추가하여 메시지의 끝을 표시
                dataOutputStream?.writeBytes("$message\n")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // 앱이 시작될 때 Socket과 DataOutputStream을 초기화
    private fun start() {
        Thread {
            try {
                socket = Socket("192.168.1.198", 5555)
                dataOutputStream = DataOutputStream(socket!!.getOutputStream())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // 앱이 종료될 때 Socket과 DataOutputStream을 닫음
    fun stop() {
        try {
            dataOutputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun checkCameraSupport(): Boolean {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // Suppose you need to check support for ImageFormat.YUV_420_888 with a size of 1920x1080
        val outputSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
        outputSizes?.let { sizes ->
            return sizes.contains(Size(4032, 3024))
        }

        // If the outputSizes is null or the desired size isn't supported, return false
        return false
    }

    companion object {
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480

        /** Threshold for confidence score. */
        private const val MIN_CONFIDENCE = 0.3f
        private const val TAG = "Camera Source"
    }

    private val lock = Any()
    private var detector: PoseDetector? = null
    private var classifier: PoseClassifier? = null
    private var isTrackerEnabled = false
    private var yuvConverter: YuvToRgbConverter = YuvToRgbConverter(surfaceView.context)
    private lateinit var imageBitmap: Bitmap

    /** Frame count that have been processed so far in an one second interval to calculate FPS. */
    private var fpsTimer: Timer? = null
    private var frameProcessedInOneSecondInterval = 0
    private var framesPerSecond = 0

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = surfaceView.context
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** Readers used as buffers for camera still shots */
    private var imageReader: ImageReader? = null

    /** The [CameraDevice] that will be opened in this fragment */
    private var camera: CameraDevice? = null

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private var session: CameraCaptureSession? = null

    /** [HandlerThread] where all buffer reading operations run */
    private var imageReaderThread: HandlerThread? = null

    /** [Handler] corresponding to [imageReaderThread] */
    private var imageReaderHandler: Handler? = null
    private var cameraId: String = ""

    suspend fun initCamera() {
        checkCameraSupport()
        camera = openCamera(cameraManager, cameraId)
        imageReader =
            ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                if (!::imageBitmap.isInitialized) {
                    imageBitmap =
                        Bitmap.createBitmap(
                            PREVIEW_WIDTH,
                            PREVIEW_HEIGHT,
                            Bitmap.Config.ARGB_8888
                        )
                }
                yuvConverter.yuvToRgb(image, imageBitmap)
                // Create rotated version for portrait display
                val rotateMatrix = Matrix()
                rotateMatrix.postRotate(90.0f)

                val rotatedBitmap = Bitmap.createBitmap(
                    imageBitmap, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    rotateMatrix, false
                )
                processImage(rotatedBitmap)
                image.close()
            }
        }, imageReaderHandler)

        imageReader?.surface?.let { surface ->
            session = createSession(listOf(surface))
            val cameraRequest = camera?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )?.apply {
                addTarget(surface)
            }
            cameraRequest?.build()?.let {
                session?.setRepeatingRequest(it, null, null)
            }
        }
    }

    private suspend fun createSession(targets: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            camera?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) =
                    cont.resume(captureSession)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    cont.resumeWithException(Exception("Session error"))
                }
            }, null)
        }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resumeWithException(Exception("Camera error"))
                }
            }, imageReaderHandler)
        }

    fun prepareCamera() {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // We don't use a front facing camera in this sample.
            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null &&
                cameraDirection == CameraCharacteristics.LENS_FACING_FRONT
            ) {
                continue
            }
            this.cameraId = "0"
        }
    }

    fun setDetector(detector: PoseDetector) {
        synchronized(lock) {
            if (this.detector != null) {
                this.detector?.close()
                this.detector = null
            }
            this.detector = detector
        }
    }

    fun setClassifier(classifier: PoseClassifier?) {
        synchronized(lock) {
            if (this.classifier != null) {
                this.classifier?.close()
                this.classifier = null
            }
            this.classifier = classifier
        }
    }


    /**
     * Set Tracker for Movenet MuiltiPose model.
     */
    fun setTracker(trackerType: TrackerType) {
        isTrackerEnabled = trackerType != TrackerType.OFF
        (this.detector as? MoveNetMultiPose)?.setTracker(trackerType)
    }

    fun resume() {
        imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
        imageReaderHandler = Handler(imageReaderThread!!.looper)
        fpsTimer = Timer()
        fpsTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    framesPerSecond = frameProcessedInOneSecondInterval
                    frameProcessedInOneSecondInterval = 0
                }
            },
            0,
            1000
        )
    }

    fun close() {

        session?.close()
        session = null
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        stopImageReaderThread()
        detector?.close()
        detector = null
        classifier?.close()
        classifier = null
        fpsTimer?.cancel()
        fpsTimer = null
        frameProcessedInOneSecondInterval = 0
        framesPerSecond = 0
    }

    // process image
    // process image
    private fun processImage(bitmap: Bitmap) {
        val persons = mutableListOf<Person>()
        var classificationResult: List<Pair<String, Float>>? = null
        synchronized(lock) {
            detector?.estimatePoses(bitmap)?.let {
                persons.addAll(it)
                // if the model only returns one item, allow running the Pose classifier.
                if (persons.isNotEmpty()) {
                    classifier?.run {
                        classificationResult = classify(persons[0])
                    }
                }
            }
        }
        frameProcessedInOneSecondInterval++
        if (frameProcessedInOneSecondInterval == 1) {
            // send fps to view
            listener?.onFPSListener(framesPerSecond)
        }
        // if the model returns only one item, show that item's score.
        if (persons.isNotEmpty()) {
            listener?.onDetectedInfo(persons[0].score, classificationResult)
        }
        if (persons.isNotEmpty()) {
            start()
            val person = persons[0]
            if (MainActivity.adjustMode) {
                if(person.score > MIN_CONFIDENCE) {
                    val (ankle, shoulder) = person.getAnkleAndShoulder()

                    if (ankle != null) {
                        sendToRaspberryPi("1")
                        val targetAnkleY = bitmap.height.toFloat() - 60f
                        if (ankle.y < targetAnkleY) {
                            showToast("발목이 하단으로부터 ${targetAnkleY - ankle.y}만큼 위에 있습니다. 아래로 이동해주세요.")
                        } else {
                            showToast("발목이 하단으로부터 ${ankle.y - targetAnkleY}만큼 아래에 있습니다. 위로 이동해주세요.")
                        }
                    }
                }

//                if (shoulder != null) {
//                    val targetShoulderY = bitmap.height * 1 / 3f
//                    if (shoulder.y < targetShoulderY) {
//                        showToast("어깨가 상단 2/3 지점으로부터 ${targetShoulderY - shoulder.y}만큼 위에 있습니다. 아래로 이동해주세요.")
//                    } else {
//                        showToast("어깨가 상단 2/3 지점으로부터 ${shoulder.y - targetShoulderY}만큼 아래에 있습니다. 위로 이동해주세요.")
//                    }
//                }
            } else {
                val center = person.getCenter()

                if (center != null) {
                    val targetX = bitmap.width / 2f
                    val targetY = bitmap.height / 2f
                    val distanceX = targetX - center.x
                    val distanceY = targetY - center.y
                    if(person.score > MIN_CONFIDENCE){
                        showToast("객체가 중앙으로부터 X: ${distanceX}, Y: ${distanceY}만큼 떨어져 있습니다.")
                        sendToRaspberryPi("2")
                    }
                }
            }
        }

        visualize(persons, bitmap)
    }

    // In CameraSource class

    //    showToast 메소드가 메인 스레드에서 호출되면 즉시 onDistanceUpdate 메소드를 호출하고,
    //    그렇지 않으면 Handler를 사용하여 메인 스레드에서 실행되도록 예약합니다. 이렇게 하면 딜레이를 최소화할 수 있습니다.
    private fun showToast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.onDistanceUpdate(message)
        } else {
            Handler(Looper.getMainLooper()).post {
                listener?.onDistanceUpdate(message)
            }
        }
    }

    private fun visualize(persons: List<Person>, bitmap: Bitmap) {

        val outputBitmap = VisualizationUtils.drawBodyKeypoints(
            bitmap,
            persons.filter { it.score > MIN_CONFIDENCE }, isTrackerEnabled
        )

        val holder = surfaceView.holder
        val surfaceCanvas = holder.lockCanvas()
        surfaceCanvas?.let { canvas ->
            val screenWidth: Int
            val screenHeight: Int
            val left: Int
            val top: Int

            if (canvas.height > canvas.width) {
                val ratio = outputBitmap.height.toFloat() / outputBitmap.width
                screenWidth = canvas.width
                left = 0
                screenHeight = (canvas.width * ratio).toInt()
                top = (canvas.height - screenHeight) / 2
            } else {
                val ratio = outputBitmap.width.toFloat() / outputBitmap.height
                screenHeight = canvas.height
                top = 0
                screenWidth = (canvas.height * ratio).toInt()
                left = (canvas.width - screenWidth) / 2
            }
            val right: Int = left + screenWidth
            val bottom: Int = top + screenHeight

            canvas.drawBitmap(
                outputBitmap, Rect(0, 0, outputBitmap.width, outputBitmap.height),
                Rect(left, top, right, bottom), null
            )
            surfaceView.holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun stopImageReaderThread() {
        imageReaderThread?.quitSafely()
        try {
            imageReaderThread?.join()
            imageReaderThread = null
            imageReaderHandler = null
        } catch (e: InterruptedException) {
            Log.d(TAG, e.message.toString())
        }
    }

    interface CameraSourceListener {
        fun onFPSListener(fps: Int)

        fun onDetectedInfo(personScore: Float?, poseLabels: List<Pair<String, Float>>?)

        fun onDistanceUpdate(message: String)
    }
}
