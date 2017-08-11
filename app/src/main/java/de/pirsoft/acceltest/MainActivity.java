package de.pirsoft.acceltest;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class MainActivity extends AppCompatActivity {
	/**
	 * Whether or not the system UI should be auto-hidden after
	 * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
	 */
	private static final boolean AUTO_HIDE = true;

	/**
	 * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
	 * user interaction before hiding the system UI.
	 */
	private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

	/**
	 * Some older devices needs a small delay between UI widget updates
	 * and a change of the status and navigation bar.
	 */
	private static final int UI_ANIMATION_DELAY = 300;

	private static final int SIM_INTERVAL = 15;

	private LinearLayout mControlsView;
	private MyGLSurfaceView mGLView;

	private final Handler mHideHandler = new Handler();
	private final Runnable mHidePart2Runnable = new Runnable() {
		@SuppressLint("InlinedApi")
		@Override
		public void run() {
			// Delayed removal of status and navigation bar

			// Note that some of these constants are new as of API 16 (Jelly Bean)
			// and API 19 (KitKat). It is safe to use them, as they are inlined
			// at compile-time and do nothing on earlier devices.
			mGLView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
				| View.SYSTEM_UI_FLAG_FULLSCREEN
				| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
				| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
		}
	};
	private final Runnable mShowPart2Runnable = new Runnable() {
		@Override
		public void run() {
			// Delayed display of UI elements
			ActionBar actionBar = getSupportActionBar();
			if (actionBar != null) {
				actionBar.show();
			}
			mControlsView.setVisibility(View.VISIBLE);
		}
	};
	private boolean mVisible;
	private final Runnable mHideRunnable = new Runnable() {
		@Override
		public void run() {
			hide();
		}
	};
	/**
	 * Touch listener to use for in-layout UI controls to delay hiding the
	 * system UI. This is to prevent the jarring behavior of controls going away
	 * while interacting with activity UI.
	 */
	private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
		@Override
		public boolean onTouch(View view, MotionEvent motionEvent) {
			if (AUTO_HIDE) {
				delayedHide(AUTO_HIDE_DELAY_MILLIS);
			}
			return false;
		}
	};

	private SensorManager mSensorManager;
	private DisplayManager mDisplayManager;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		mVisible = true;
		mControlsView = (LinearLayout)findViewById(R.id.fullscreen_content_controls);
		mGLView = (MyGLSurfaceView)findViewById(R.id.fullscreen_content);


		// Set up the user interaction to manually show or hide the system UI.
		mGLView.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				toggle();
			}
		});

		// Upon interacting with UI controls, delay any scheduled hide()
		// operations to prevent the jarring behavior of controls going away
		// while interacting with the UI.
		findViewById(R.id.dummy_button).setOnTouchListener(mDelayHideTouchListener);

		mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
		/*
		List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
		StringBuilder textbuilder = new StringBuilder();
		for(int i = 0; i < sensors.size(); i++) {
			textbuilder.append(sensors.get(i).getStringType())
				.append(": ")
				.append(sensors.get(i).getName())
				.append("(")
				.append(sensors.get(i).getVendor())
				.append(")\n");
		}
		*/
		/*
		Nexus 5 VM:
		.accelerometer
		.magnetic_field
		.orientation
		.ambient_temperature
		.proximity
		.light
		.pressure
		.relative_humidity

		missing all composite sensors.

		Sony Xperia Z3c:
		.accelerometer: BMA2X2 Accel/Temp/Double-tap (BOSCH)
		.magnetic_field: AK09911 (AKM)
		.magnetic_field_uncalibrated: AK09911 (AKM)
		.gyroscope: BMG160 (BOSCH)
		.gyroscope_uncalibrated: BMG160 (BOSCH)
		.proximity: APDS-9930/QPDS-T930 (Avago)
		.light: APDS-9930/QPDS-T930 (Avago)
		.pressure: BMP280 (BOSCH)

		composite sensors:
		.gravity: Gravity(QTI)
		.linear_acceleration: Linear Acceleration(QTI)  <<<< interesting
		.rotation_vector: Rotation Vector(QTI)          <<<< need this if above is not in the phones coordinate system
		.step_detector: Step Detector(QTI)
		.step_counter: Step Counter(QTI)
		.significant_motion: Significatn Motion(QTI)
		.game_rotation_vector: Game Rotation Vector(QTI)
		.geomagnetic_rotation_vector: GeoMagnetic Rotation Vector(QTI)
		.orientation: Orientation(QTI)
		.tilt_detector: Tilt Detector(QTI)
		 */
		//mContentView.setText(textbuilder.toString());
		mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
	}

	private void toggle() {
		if (mVisible) {
			hide();
		} else {
			show();
		}
	}

	private void hide() {
		// Hide UI first
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.hide();
		}
		mControlsView.setVisibility(View.GONE);
		mVisible = false;

		// Schedule a runnable to remove the status and navigation bar after a delay
		mHideHandler.removeCallbacks(mShowPart2Runnable);
		mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
	}

	@SuppressLint("InlinedApi")
	private void show() {
		// Show the system bar
		mGLView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
			| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
		mVisible = true;

		// Schedule a runnable to display UI elements after a delay
		mHideHandler.removeCallbacks(mHidePart2Runnable);
		mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
	}

	/**
	 * Schedules a call to hide() in [delay] milliseconds, canceling any
	 * previously scheduled calls.
	 */
	private void delayedHide(int delayMillis) {
		mGLView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
			| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
		mVisible = true;

		mHideHandler.removeCallbacks(mHideRunnable);
		mHideHandler.postDelayed(mHideRunnable, delayMillis);
	}

	private final Handler mSimHandler = new Handler();
	private final float mRotation[] = new float[3];
	private final float mAcceleration[] = new float[4];

	private long lastSimTime = 0;

	private final Runnable mSimRunnable = new Runnable() {
		private final float[] scr1 = new float[16];
		private final float[] scr2 = new float[16];
		private final float[] scr3 = new float[16];
		private final float[] speed = {0.f,0.f,0.f,0.f};
		private final float[] position = {0.f,0.f,0.f,0.f};
		private final float springconstant = 400.f;
		private final float mass = 10.f;
		private final float friction = 0.7f;

		@SuppressLint("InlinedApi")
		@Override
		public void run() {
			long time = SystemClock.uptimeMillis();
			if (lastSimTime == 0)
				lastSimTime = time;
			long deltatime = time - lastSimTime;
			if (deltatime > 10000)
				deltatime = 0;
			lastSimTime = time;
			//schedule next loop
			if (2*SIM_INTERVAL - deltatime > 0)
				mSimHandler.postDelayed(mSimRunnable, 2*SIM_INTERVAL - deltatime);
			else
				mSimHandler.postDelayed(mSimRunnable, 0);

			//okay, so now do the maths. first, we fetch the rotation matrix.
			SensorManager.getRotationMatrixFromVector(scr1, mRotation);
			//for rotation matrices, inverting is transposing
			scr3[0]  = scr1[0]; scr3[1]  = scr1[4]; scr3[2]  = scr1[8];  scr3[3]  = scr1[12];
			scr3[4]  = scr1[1]; scr3[5]  = scr1[5]; scr3[6]  = scr1[9];  scr3[7]  = scr1[13];
			scr3[8]  = scr1[2]; scr3[9]  = scr1[6]; scr3[10] = scr1[10]; scr3[11] = scr1[14];
			scr3[12] = scr1[3]; scr3[13] = scr1[7]; scr3[14] = scr1[11]; scr3[15] = scr1[15];

			//now see that we rotate the acceleration vector to match, and use it
			//to translate the ball around
			Matrix.multiplyMV(scr2, 0, scr3, 0, mAcceleration, 0);
			//scr2 is the force vector in the local earth surface coordinate system.
			//in rest, points upwards. This is the force^Wacceleration we apply at our
			// spring, so there is an equal and opposite force^Wacceleration at the
			// ball, i.E. -scr2

			//force by spring: distance*springconstant, which is -position*springconstant
			//the mass converts the acceleration into a force: F=m*a
			//F_spring = -position*springconstant
			//F_external = -scr2*mass
			//F_speed = -speed * friction
			//F_ball = F_spring + F_external + F_speed
			//a_ball = F_ball / mass
			//so, everything pulled together:
			//a_ball = -position * springconstant / mass - scr2 + speed * friction / mass

			for(int i = 0; i < 3; i++) {
				float a = -position[i]* (springconstant / mass) - scr2[i]
					- speed[i] * friction;
				speed[i] += a * deltatime * 0.001f;

				position[i] += speed[i] * deltatime * 0.001f;
			}

			Matrix.translateM(scr1, 0, position[0], position[1], position[2]);

			mGLView.setTransform(scr1);
			mGLView.requestRender();
		}
	};

	private SensorEventListener mSensorEventListener = new SensorEventListener() {

		@Override
		public void onSensorChanged(SensorEvent event) {
			if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
				System.arraycopy(event.values, 0, mAcceleration,
					0, 3);
				mAcceleration[3] = 0.f;//direction vector: fourth component is 0.
			} else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
				System.arraycopy(event.values, 0, mRotation,
					0, mRotation.length);
			}
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}
	};

	private DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
		@Override
		public void onDisplayAdded(int displayId) {
		}

		@Override
		public void onDisplayChanged(int displayId) {
			Display d = mGLView.getDisplay();
			mGLView.updateScreenRotation(d);
			Point size = new Point();
			d.getSize(size);
			if(size.x > size.y) {
				//landscape
				FrameLayout.LayoutParams p = (FrameLayout.LayoutParams)mControlsView.getLayoutParams();
				p.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
				p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
				p.height= ViewGroup.LayoutParams.MATCH_PARENT;
				mControlsView.setLayoutParams(p);
				//mControlsView.setOrientation(LinearLayout.VERTICAL);
			} else
			{
				//portrait
				FrameLayout.LayoutParams p = (FrameLayout.LayoutParams)mControlsView.getLayoutParams();
				p.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
				p.width = ViewGroup.LayoutParams.MATCH_PARENT;
				p.height= ViewGroup.LayoutParams.WRAP_CONTENT;
				mControlsView.setLayoutParams(p);
				//mControlsView.setOrientation(LinearLayout.HORIZONTAL);
			}
		}

		@Override
		public void onDisplayRemoved(int displayId) {
		}
	};

	@Override
	protected void onStart() {
		super.onStart();

	        mSensorManager.registerListener(mSensorEventListener,
		        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                        SensorManager.SENSOR_DELAY_GAME, SensorManager.SENSOR_DELAY_GAME);
		mSensorManager.registerListener(mSensorEventListener,
			mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
			SensorManager.SENSOR_DELAY_GAME, SensorManager.SENSOR_DELAY_GAME);

                mDisplayManager.registerDisplayListener(mDisplayListener, null);

		// Trigger the initial hide() shortly after the activity has been
		// shown again, to briefly remind the user that UI controls
		// are available.
		lastSimTime = 0;
		delayedHide(100);
		mSimHandler.postDelayed(mSimRunnable, SIM_INTERVAL);
	}

	@Override
	protected void onStop() {
		super.onStop();
		mSimHandler.removeCallbacks(mSimRunnable);
		mSensorManager.unregisterListener(mSensorEventListener);
		mDisplayManager.unregisterDisplayListener(mDisplayListener);
	}
}
