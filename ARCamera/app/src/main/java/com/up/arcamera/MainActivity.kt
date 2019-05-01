package com.up.arcamera

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.support.annotation.GuardedBy
import android.support.annotation.Nullable
import android.util.Log
import com.up.arcamera.helper.DisplayRotationHelper
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.view.MotionEvent
import android.view.GestureDetector
import kotlinx.android.synthetic.main.activity_main.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import android.opengl.GLES20
import android.os.Build
import android.support.annotation.RequiresApi
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.up.arcamera.helper.CameraPermissionHelper
import com.up.arcamera.helper.FullScreenHelper
import com.up.arcamera.helper.SnackbarHelper
import com.up.arcamera.rendering.BackgroundRenderer
import com.up.arcamera.rendering.ObjectRenderer
import com.up.arcamera.rendering.PlaneRenderer
import com.up.arcamera.rendering.PointCloudRenderer
import java.io.IOException


class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    lateinit var displayRotationHelper: DisplayRotationHelper
    lateinit var gestureDetector: GestureDetector

    // Lock needed for synchronization.
    private val singleTapAnchorLock = Any()

    // Tap handling and UI. This app allows you to place at most one anchor.
    @GuardedBy("singleTapAnchorLock")
    private var queuedSingleTap: MotionEvent? = null


    //AR core componentes
    @Nullable
    @GuardedBy("singleTapAnchorLock")
    private var anchor: Anchor? = null

    private var installRequested: Boolean = false
    private var session: Session? = null

    // Tap handling and UI. This app allows you to place at most one anchor.
    private val snackbarHelper = SnackbarHelper()

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private val backgroundRenderer = BackgroundRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val planeRenderer = PlaneRenderer()
    private val virtualObject = ObjectRenderer()
    private val virtualObjectShadow = ObjectRenderer()
    lateinit var surfaceView: GLSurfaceView

    // Matrices pre-allocated here to reduce the number of allocations on every frame draw.
    private val anchorMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val colorCorrectionRgba = FloatArray(4)

    @GuardedBy("singleTapAnchorLock")
    private var appAnchorState = AppAnchorState.NONE

    private enum class AppAnchorState {
        NONE,
        HOSTING,
        HOSTED
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (session == null) return

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session)

        try {
            session!!.setCameraTextureName(backgroundRenderer.getTextureId())

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update() as Frame
            val camera = frame.getCamera() as Camera
            val cameraTrackingState = camera.getTrackingState() as TrackingState
            checkUpdatedAnchor()

            // Handle taps.
            handleTapOnDraw(cameraTrackingState, frame)

            // Draw background.
            backgroundRenderer.draw(frame)

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) return


            // Get projection and camera matrices.
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            // Visualize tracked points.
            val pointCloud = frame.acquirePointCloud()
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewMatrix, projectionMatrix)

            // Application is responsible for releasing the point cloud resources after
            // using it.
            pointCloud.release()

            // Visualize planes.
            planeRenderer.drawPlanes(
                session!!.getAllTrackables(Plane::class.java),
                    camera.getDisplayOrientedPose(),
                    projectionMatrix)

            // Visualize anchor.
            var shouldDrawAnchor: Boolean = false
            synchronized(singleTapAnchorLock) {
                if (anchor != null && anchor!!.getTrackingState() == TrackingState.TRACKING) {
                    frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0)

                    // Get the current pose of an Anchor in world space. The Anchor pose is updated
                    // during calls to session.update() as ARCore refines its estimate of the world.
                    anchor!!.getPose().toMatrix(anchorMatrix, 0)
                    shouldDrawAnchor = true
                }
            }
            if (shouldDrawAnchor) {
                val scaleFactor = 1.0f.toFloat()
                frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0)

                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba)
                virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba)
            }
        } catch (t:Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e("TAG", "Exception on the OpenGL thread", t);
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
         GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this)
      planeRenderer.createOnGlThread(/*context=*/ this, "models/trigrid.png")
      pointCloudRenderer.createOnGlThread(/*context=*/ this)

      virtualObject.createOnGlThread(/*context=*/ this, "models/andy.obj", "models/andy.png")
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)

      virtualObjectShadow.createOnGlThread(
          /*context=*/ this, "models/andy_shadow.obj", "models/andy_shadow.png")
      virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
    } catch (ex: IOException) {
      Log.e("TAG", "Failed to read an asset file", ex)
    }
    }


    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)

        displayRotationHelper = DisplayRotationHelper(/*context=*/ this)

        // Set up tap listener.
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    synchronized(singleTapAnchorLock) {
                        queuedSingleTap = e
                    }
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }
            })


        surfaceView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true)
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        installRequested = false

        // Initialize the "Clear" button. Clicking it will clear the current anchor, if it exists.
        clear_button.setOnClickListener {
            synchronized(singleTapAnchorLock) {
                setNewAnchor(null)
            }
            Toast.makeText(this, "FOI", Toast.LENGTH_SHORT).show()
        }

    }

    /** Sets the new anchor in the scene.  */
    @GuardedBy("singleTapAnchorLock")
    private fun setNewAnchor(newAnchor: Anchor?) {
        anchor?.detach()
        anchor = newAnchor
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var messageId = -1
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                    }
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                session = Session(this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                messageId = R.string.snackbar_arcore_unavailable
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                messageId = R.string.snackbar_arcore_too_old
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                messageId = R.string.snackbar_arcore_sdk_too_old
                exception = e
            } catch (e: Exception) {
                messageId = R.string.snackbar_arcore_exception
                exception = e
            }

            if (exception != null) {
                snackbarHelper.showError(this, getString(messageId))
                Log.e("TAG", "Exception creating session", exception)
                return
            }

            // Create default config and check if supported.
            val config = Config(session)
            config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED)
            session!!.configure(config)
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable))
            session = null
            return
        }

        surfaceView.onResume()
        displayRotationHelper.onResume()

    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onPause() {
        super.onPause()

        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session!!.pause();
        }
    }

    /** Handles a single tap during a {@link #onDrawFrame(GL10)} call. */
    private fun handleTapOnDraw(currentTrackingState: TrackingState, currentFrame: Frame) {
        synchronized(singleTapAnchorLock) {
            if (anchor == null
                && queuedSingleTap != null
                && currentTrackingState == TrackingState.TRACKING
                && appAnchorState == AppAnchorState.NONE
            ) {
                for (hit: HitResult in currentFrame.hitTest(queuedSingleTap)) {
                    if (shouldCreateAnchorWithHit(hit)) {
                        //val newAnchor = hit.createAnchor()
                        val newAnchor = session!!.hostCloudAnchor(hit.createAnchor())
                        setNewAnchor(newAnchor)

                        // Add some UI to show you that the anchor is being hosted.
                        appAnchorState = AppAnchorState.HOSTING // Add this line.
                        snackbarHelper.showMessage(this, "Now hosting anchor...") // Add this line.
                        break
                    }
                }
            }
            queuedSingleTap = null
        }
    }
        /**
         * Returns {@code true} if and only if {@code hit} can be used to create an anchor.
         *
         * <p>Checks if a plane was hit and if the hit was inside the plane polygon, or if an oriented
         * point was hit. We only want to create an anchor if the hit satisfies these conditions.
         */
        fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
            val trackable = hit.getTrackable()
            if (trackable is Plane) {
                // Check if any plane was hit, and if it was hit inside the plane polygon
                return ((trackable).isPoseInPolygon(hit.getHitPose() as Pose))
            } else if (trackable is Point) {
                // Check if an oriented point was hit.
                return (trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            return false
        }

    private fun checkUpdatedAnchor() {
        synchronized (singleTapAnchorLock) {
            if (appAnchorState != AppAnchorState.HOSTING) {
              return;
            }
            val cloudState = anchor!!.getCloudAnchorState() as Anchor.CloudAnchorState
            if (cloudState.isError()) {
              snackbarHelper.showMessageWithDismiss(this, "Error hosting anchor: " + cloudState);
              appAnchorState = AppAnchorState.NONE;
            } else if (cloudState == Anchor.CloudAnchorState.SUCCESS) {
              snackbarHelper.showMessageWithDismiss(
                  this, "Anchor hosted successfully! Cloud ID: " + anchor!!.getCloudAnchorId());
              appAnchorState = AppAnchorState.HOSTED;
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
            FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}