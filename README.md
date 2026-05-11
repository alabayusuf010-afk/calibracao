# Calibracao: Computer Vision & 3D Tracking Toolkit

## Project Overview
**Calibracao** is a high-performance Android application designed for precision camera calibration and real-time feature tracking. Developed as a professional-grade toolkit, it leverages the **OpenCV 4.12.0** library and **Android CameraX** to bridge the gap between raw mobile sensor data and mathematical 3D spatial awareness.

This project was engineered to solve two core challenges in computer vision:
1.  **Geometric Sensor Calibration**: Eliminating lens distortion and calculating the intrinsic matrix ($K$).
2.  **Dynamic Feature Tracking**: Utilizing the ORB (Oriented FAST and Rotated BRIEF) algorithm for real-time environment mapping.

---

## Technical Specifications

### 1. Camera Calibration (Photogrammetry Engine)
The calibration module implements **Zhang's Method** for estimating camera intrinsics and extrinsics.
-   **Pattern Support**: 9x6 Interior Corner Chessboard.
-   **Algorithm**: `Calib3d.calibrateCamera` with sub-pixel refinement via `Imgproc.cornerSubPix`.
-   **Output**: 
    -   **Intrinsic Matrix ($K$)**: $f_x, f_y, c_x, c_y$.
    -   **Distortion Coefficients**: $k_1, k_2$ (Radial distortion correction).
    -   **RMS Error Calculation**: Real-time reprojection error monitoring.
-   **Persistence**: Data is exported to `calibration_result.json` for external 3D modeling use.

### 2. ORB Real-Time Tracker (SLAM Foundation)
The tracking module provides a robust, rotation-invariant feature detection system.
-   **Detector**: ORB (Oriented FAST and Rotated BRIEF).
-   **Performance**: Optimized for 30+ FPS on mid-range ARM mobile processors.
-   **Threading**: Asynchronous image analysis using `java.util.concurrent.Executors` to prevent UI thread blocking.
-   **Visualization**: Real-time overlay of detected keypoints on the RGBA camera feed.

---

## Engineering Implementation Details

### Memory Management
To handle high-resolution (12MP+) mobile photography without `OutOfMemoryError` (OOM), the toolkit utilizes a custom **Sub-sampled Bitmap Decoder**. It dynamically calculates the optimal `inSampleSize` to process images at a target width of 1024px, ensuring stability without sacrificing calibration accuracy.

### Native Resource Handling
Since OpenCV operates in the native (C++) layer, the application strictly manages native memory. Every `Mat` object is explicitly released via `.release()` in `finally` blocks to prevent native memory leaks that typical Java garbage collection cannot manage.

### Modern Android Architecture
-   **UI Framework**: Jetpack Compose (Declarative UI).
-   **Camera API**: CameraX (Modern, lifecycle-aware camera handling).
-   **Concurrency**: Kotlin Coroutines for non-blocking I/O and background computation.

---

## Usage Instructions

### Performing Calibration
1.  Print a 9x6 chessboard pattern (A4 size recommended).
2.  Capture 15-20 images from varied angles, ensuring the board reaches the corners of the frame.
3.  Select all photos in the app; the engine will automatically process and count valid detections.
4.  Finalize to compute the matrix and view the undistorted preview.

### Running the Tracker
1.  Grant Camera permissions.
2.  Point the device at any textured surface.
3.  Observe green keypoints sticking to physical surfaces, indicating successful feature extraction.

---

## Project Structure
-   `com.example.calibracao.MainActivity`: Entry point and navigation dashboard.
-   `com.example.calibracao.ModeloCameraActivity`: The calibration engine and persistence logic.
-   `com.example.calibracao.OrbTrackingActivity`: Real-time CameraX and ORB integration.
-   `com.example.calibracao.ui.theme`: Material3 design system implementation.

---

## Developer Note
This toolkit was built with a focus on **Numerical Stability** and **Computational Efficiency**. It serves as a production-ready starting point for developers building Augmented Reality (AR), SLAM, or 3D Reconstruction applications.
