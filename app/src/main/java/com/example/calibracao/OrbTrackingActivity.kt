package com.example.calibracao

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.calibracao.ui.theme.CalibracaoTheme
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Scalar
import org.opencv.features2d.Features2d
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OrbTrackingActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private val orbDetector = ORB.create(500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        enableEdgeToEdge()
        setContent {
            CalibracaoTheme {
                var hasPermission by remember { 
                    mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) 
                }
                
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    hasPermission = it
                }

                LaunchedEffect(Unit) {
                    if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        if (hasPermission) {
                            OrbCameraPreview()
                        } else {
                            Text("Camera permission required", modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun OrbCameraPreview() {
        val context = LocalContext.current
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        LaunchedEffect(Unit) {
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val resultBitmap = processImageProxy(imageProxy)
                    if (resultBitmap != null) {
                        bitmap = resultBitmap
                    }
                } catch (e: Exception) {
                    Log.e("ORB", "Processing error", e)
                } finally {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@OrbTrackingActivity,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("ORB", "Use case binding failed", e)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "ORB Tracking Feed",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text("ORB Real-Time Tracker", color = androidx.compose.ui.graphics.Color.Green, style = MaterialTheme.typography.titleLarge)
                Text("Status: ${if(bitmap != null) "Active" else "Detecting..."}", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }

    private fun processImageProxy(image: ImageProxy): Bitmap? {
        // High-performance image conversion
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat) // Initialize mat with correct size/type
        
        image.planes[0].buffer.let { buffer ->
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            mat.put(0, 0, bytes)
        }

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        val keypoints = MatOfKeyPoint()
        orbDetector.detect(gray, keypoints)

        // Draw points on the original colorful frame
        val outMat = mat.clone()
        Features2d.drawKeypoints(mat, keypoints, outMat, Scalar(0.0, 255.0, 0.0, 255.0), Features2d.DrawMatchesFlags_DEFAULT)

        Utils.matToBitmap(outMat, bitmap)

        // Cleanup native memory immediately
        mat.release(); gray.release(); keypoints.release(); outMat.release()
        
        return rotateBitmap(bitmap, image.imageInfo.rotationDegrees.toFloat())
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) return source
        val matrix = android.graphics.Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
