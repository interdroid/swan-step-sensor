package interdroid.swan.step_sensor;

import interdroid.swan.sensors.AbstractConfigurationActivity;
import interdroid.swan.sensors.AbstractVdbSensor;
import interdroid.vdb.content.avro.AvroContentProviderProxy;

import java.util.Calendar;

import android.content.ContentValues;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;

// link to android library: vdb-avro

/**
 * A sensor for steps
 * 
 * @author roelof &lt;rkemp@cs.vu.nl&gt;
 * 
 */
public class StepSensor extends AbstractVdbSensor {

	/**
	 * The configuration activity for this sensor.
	 */
	public static class ConfigurationActivity extends
			AbstractConfigurationActivity {

		@Override
		public final int getPreferencesXML() {
			return R.xml.step_preferences;
		}

	}

	/**
	 * The min_steps configuration.
	 */
	public static final String MIN_STEPS_CONFIG = "min_steps";

	/**
	 * The min_time configuration.
	 */
	public static final String MIN_TIME_CONFIG = "min_time";

	/**
	 * The today field.
	 */
	public static final String TODAY_FIELD = "today";

	/**
	 * The schema for this sensor.
	 */
	public static final String SCHEME = getSchema();

	/**
	 * The provider for this sensor.
	 */
	public static class Provider extends AvroContentProviderProxy {

		/**
		 * Construct the provider for this sensor.
		 */
		public Provider() {
			super(SCHEME);
		}

	}

	/**
	 * @return the schema for this sensor.
	 */
	private static String getSchema() {
		String scheme = "{'type': 'record', 'name': 'step', "
				+ "'namespace': 'interdroid.swan.step_sensor.step',"
				+ "\n'fields': [" + SCHEMA_TIMESTAMP_FIELDS + "\n{'name': '"
				+ TODAY_FIELD + "', 'type': 'int'}" + "\n]" + "}";
		return scheme.replace('\'', '"');
	}

	@Override
	public final String[] getValuePaths() {
		return new String[] { TODAY_FIELD };
	}

	@Override
	public void initDefaultConfiguration(final Bundle defaults) {
		defaults.putInt(MIN_STEPS_CONFIG, 1);
		defaults.putInt(MIN_TIME_CONFIG, 10000);
	}

	@Override
	public final String getScheme() {
		return SCHEME;
	}

	@Override
	public void onConnected() {
		mSteps = PreferenceManager.getDefaultSharedPreferences(this).getInt(
				"steps", 0);
		mPreviousStepTimestamp = PreferenceManager.getDefaultSharedPreferences(
				this).getLong("previousStep", 0);
		mWakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
				.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "step-sensor");
		mWakeLock.acquire();
	}

	@Override
	public final void register(final String id, final String valuePath,
			final Bundle configuration) {
		updateGlobalConfiguration();
		if (registeredConfigurations.size() == 1) {
			startListening();
		}
	}

	@Override
	public final void unregister(final String id) {
		updateGlobalConfiguration();
		if (registeredConfigurations.size() == 0) {
			stopListening();
		}
	}

	@Override
	public final void onDestroySensor() {
		// persist steps today
		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.putInt("steps", mSteps)
				.putLong("previousStep", mPreviousStepTimestamp).commit();
		mWakeLock.release();
	}

	/**
	 * Data Storage Helper Method.
	 * 
	 * @param today
	 *            value for today
	 */
	private void storeReading(int today) {
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		values.put(TODAY_FIELD, today);
		putValues(values, now);
	}

	/**
	 * =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=- Sensor Specific Implementation
	 * =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
	 */

	private static final double THRESHOLD = 13.0;
	private static final long INTER_STEP_TIME = 300;

	private WakeLock mWakeLock;
	private int mSteps;
	private int mMinSteps;
	private int mMinTime;
	private long mPreviousStepTimestamp = 0;
	private long mPreviousUpdateTimestamp = 0;
	private int mPreviousSteps = 0;

	private SensorEventListener mListener = new SensorEventListener() {

		@Override
		public void onSensorChanged(SensorEvent event) {
			// compute the SVM (signal vector magnitude)
			double svm = Math.sqrt(event.values[0] * event.values[0]
					+ event.values[1] * event.values[1] + event.values[2]
					* event.values[2]);
			if (svm > THRESHOLD
					&& System.currentTimeMillis() > mPreviousStepTimestamp
							+ INTER_STEP_TIME) {
				increaseStep();
			}

		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			// we ignore this
		}
	};

	private boolean sameDay(long t1, long t2) {
		Calendar cal1 = Calendar.getInstance();
		Calendar cal2 = Calendar.getInstance();
		cal1.setTimeInMillis(t1);
		cal2.setTimeInMillis(t2);
		return cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
	}

	private void increaseStep() {
		new Thread() {
			public void run() {
				stopListening();
				try {
					sleep(INTER_STEP_TIME);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				startListening();
			}
		}.start();
		if (!sameDay(mPreviousStepTimestamp, System.currentTimeMillis())) {
			mSteps = 0;
		}
		mSteps++;
		mPreviousStepTimestamp = System.currentTimeMillis();
		if (mPreviousUpdateTimestamp + mMinTime < System.currentTimeMillis()
				&& mSteps - mPreviousSteps >= mMinSteps) {
			mPreviousUpdateTimestamp = System.currentTimeMillis();
			mPreviousSteps = mSteps;
			storeReading(mSteps);
		}
	}

	private void updateGlobalConfiguration() {
		int minSteps = Integer.MAX_VALUE;
		int minTime = Integer.MAX_VALUE;
		for (Bundle configuration : registeredConfigurations.values()) {
			if (configuration.containsKey(MIN_STEPS_CONFIG)) {
				minSteps = Math.min(minSteps,
						configuration.getInt(MIN_STEPS_CONFIG));
			}
			if (configuration.containsKey(MIN_TIME_CONFIG)) {
				minTime = Math.min(minTime,
						configuration.getInt(MIN_TIME_CONFIG));
			}
		}
		if (minSteps == Integer.MAX_VALUE) {
			minSteps = mDefaultConfiguration.getInt(MIN_STEPS_CONFIG);
		}
		if (minTime == Integer.MAX_VALUE) {
			minTime = mDefaultConfiguration.getInt(MIN_TIME_CONFIG);
		}
		mMinSteps = minSteps;
		mMinTime = minTime;
	}

	private void startListening() {
		SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
		Sensor accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		sm.registerListener(mListener, accelerometer,
				SensorManager.SENSOR_DELAY_UI);
	}

	private void stopListening() {
		SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
		sm.unregisterListener(mListener);
	}

}