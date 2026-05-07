package com.example.calibracao

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.calibracao.ui.theme.CalibracaoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale

class ModeloCameraActivity : ComponentActivity() {
    private val TAG = "CalibracaoExpert"
    private val gridWidth = 9
    private val gridHeight = 6
    private val squareSizeMm = 25.0f

    private val objectPoints = mutableListOf<Mat>()
    private val imagePoints = mutableListOf<Mat>()
    private var imageSize: Size? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        enableEdgeToEdge()
        setContent {
            CalibracaoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CalibrationWorkspace(
                        modifier = Modifier.padding(innerPadding),
                        onProcessUris = { uris, onProgress -> 
                            processMultipleImages(uris, onProgress) 
                        },
                        onRunCalibration = { runCalibration() },
                        onReset = { resetInternal() }
                    )
                }
            }
        }
    }

    private suspend fun processMultipleImages(
        uris: List<Uri>, 
        onProgress: (Int, Int, Bitmap?, List<Offset>) -> Unit
    ) {
        withContext(Dispatchers.Default) {
            for ((index, uri) in uris.withIndex()) {
                val bitmap = decodeSampledBitmap(uri, 1024) ?: continue
                val corners = detectCorners(bitmap)
                
                withContext(Dispatchers.Main) {
                    if (corners != null) {
                        onProgress(index + 1, uris.size, bitmap, corners)
                    } else {
                        onProgress(index + 1, uris.size, null, emptyList())
                    }
                }
            }
        }
    }

    private fun decodeSampledBitmap(uri: Uri, targetWidth: Int): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                options.inSampleSize = calculateInSampleSize(options, targetWidth)
                options.inJustDecodeBounds = false
                contentResolver.openInputStream(uri)?.use { input2 ->
                    BitmapFactory.decodeStream(input2, null, options)
                }
            }
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, targetWidth: Int): Int {
        var inSampleSize = 1
        if (options.outWidth > targetWidth) {
            val halfWidth = options.outWidth / 2
            while (halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun detectCorners(bitmap: Bitmap): List<Offset>? {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        
        val corners = MatOfPoint2f()
        val found = Calib3d.findChessboardCorners(
            gray, Size(gridWidth.toDouble(), gridHeight.toDouble()), corners,
            Calib3d.CALIB_CB_ADAPTIVE_THRESH + Calib3d.CALIB_CB_NORMALIZE_IMAGE
        )

        return if (found) {
            val tc = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.001)
            Imgproc.cornerSubPix(gray, corners, Size(11.0, 11.0), Size(-1.0, -1.0), tc)
            
            imagePoints.add(corners)
            objectPoints.add(generateObjectPoints())
            imageSize = gray.size()
            
            val points = corners.toArray().map { Offset(it.x.toFloat(), it.y.toFloat()) }
            mat.release(); gray.release()
            points
        } else {
            mat.release(); gray.release(); corners.release()
            null
        }
    }

    private fun generateObjectPoints(): Mat {
        val obj = Mat(gridHeight * gridWidth, 1, CvType.CV_32FC3)
        for (i in 0 until gridHeight) {
            for (j in 0 until gridWidth) {
                obj.put(i * gridWidth + j, 0, (j * squareSizeMm).toDouble(), (i * squareSizeMm).toDouble(), 0.0)
            }
        }
        return obj
    }

    private fun runCalibration(): CalibrationOutput? {
        if (imagePoints.size < 3) return null
        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        val distCoeffs = MatOfDouble()
        val rvecs = mutableListOf<Mat>()
        val tvecs = mutableListOf<Mat>()
        
        val rms = Calib3d.calibrateCamera(objectPoints, imagePoints, imageSize!!, cameraMatrix, distCoeffs, rvecs, tvecs)
        val output = CalibrationOutput(
            rms = rms, fx = cameraMatrix.get(0, 0)[0], fy = cameraMatrix.get(1, 1)[0],
            cx = cameraMatrix.get(0, 2)[0], cy = cameraMatrix.get(1, 2)[0],
            k1 = distCoeffs.get(0, 0)[0], k2 = distCoeffs.get(0, 1)[0]
        )
        persistCalibration(output)
        return output
    }

    private fun persistCalibration(data: CalibrationOutput) {
        try {
            val json = JSONObject().apply {
                put("fx", data.fx); put("fy", data.fy); put("cx", data.cx); put("cy", data.cy)
                put("k1", data.k1); put("k2", data.k2); put("rms", data.rms)
            }
            File(filesDir, "calibration_result.json").writeText(json.toString())
        } catch (e: Exception) { Log.e(TAG, "Persistence failure", e) }
    }

    private fun resetInternal() {
        objectPoints.forEach { it.release() }; objectPoints.clear()
        imagePoints.forEach { it.release() }; imagePoints.clear()
        imageSize = null
    }
}

data class CalibrationOutput(val rms: Double, val fx: Double, val fy: Double, val cx: Double, val cy: Double, val k1: Double, val k2: Double)

@Composable
fun CalibrationWorkspace(
    modifier: Modifier = Modifier, 
    onProcessUris: suspend (List<Uri>, (Int, Int, Bitmap?, List<Offset>) -> Unit) -> Unit, 
    onRunCalibration: () -> CalibrationOutput?, 
    onReset: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var lastImg by remember { mutableStateOf<Bitmap?>(null) }
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var count by remember { mutableIntStateOf(0) }
    var processing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CalibrationOutput?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            processing = true
            scope.launch {
                onProcessUris(uris) { current, total, bitmap, detectedPoints ->
                    progressText = "Processing $current / $total"
                    if (bitmap != null) {
                        lastImg = bitmap
                        points = detectedPoints
                        count++
                    }
                    if (current == total) {
                        processing = false
                        progressText = ""
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Precision Camera Calibration", style = MaterialTheme.typography.headlineMedium)
        
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("Images Loaded: $count", style = MaterialTheme.typography.titleMedium)
                if (processing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
                        Text(progressText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f), enabled = !processing) { Text("Select All Photos") }
                    Button(onClick = { onReset(); count = 0; lastImg = null; points = emptyList(); result = null; preview = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reset") }
                }
            }
        }

        lastImg?.let { b ->
            Canvas(modifier = Modifier.fillMaxWidth().height(280.dp).padding(vertical = 8.dp)) {
                drawImage(b.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                val sx = size.width / b.width; val sy = size.height / b.height
                points.forEach { p -> drawCircle(Color.Cyan, radius = 3f, center = Offset(p.x * sx, p.y * sy)) }
            }
        }

        Button(onClick = { result = onRunCalibration(); if(result != null && lastImg != null) preview = applyUndistortExpert(lastImg!!, result!!) }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = count >= 3 && !processing) {
            Text("Finalize Calibration")
        }

        result?.let { res ->
            Spacer(Modifier.height(16.dp))
            ResultRow("RMS Error", String.format(Locale.US, "%.4f px", res.rms))
            ResultRow("Focal (fx, fy)", "${res.fx.toInt()}, ${res.fy.toInt()}")
            
            preview?.let { pb ->
                Text("Undistorted Preview", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    drawImage(pb.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                }
            }
        }
    }
}

@Composable
fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray); Text(value, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

fun applyUndistortExpert(b: Bitmap, res: CalibrationOutput): Bitmap {
    val src = Mat(); Utils.bitmapToMat(b, src)
    val dst = Mat()
    val k = Mat(3, 3, CvType.CV_64F).apply {
        put(0,0, res.fx); put(0,1, 0.0); put(0,2, res.cx)
        put(1,0, 0.0); put(1,1, res.fy); put(1,2, res.cy)
        put(2,0, 0.0); put(2,1, 0.0); put(2,2, 1.0)
    }
    Calib3d.undistort(src, dst, k, MatOfDouble(res.k1, res.k2, 0.0, 0.0))
    val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(dst, out)
    src.release(); dst.release(); k.release()
    return out
}
