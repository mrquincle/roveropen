package org.almende.roveropen;

import java.util.List;

import org.almende.roveropen.RoverOpenActivity;
import org.almende.roveropen.AccelerometerListener;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Android Accelerometer Sensor Manager Archetype
 * @author antoine vianey
 * under GPL v3 : http://www.gnu.org/licenses/gpl-3.0.html
 */
public class AccelerometerManager {

	// interval in nanoseconds between sending accelerometer data
	private static long interval =  500000000L;
//	private static long interval = 1000000000L; //one second
	private static Sensor sensor;
	private static SensorManager sensorManager;
	private static AccelerometerListener listener;

	private static Boolean supported;
	private static boolean running = false;

	/**
	 * Returns true if the manager is listening to orientation changes
	 */
	public static boolean isListening()
	{
		return running;
	}

	/**
	 * Unregisters listeners
	 */
	public static void stopListening()
	{
		running = false;
		try {
			if ( sensorManager != null && sensorEventListener != null ) {
				sensorManager.unregisterListener( sensorEventListener );
			}
		} catch ( Exception e ) {}
	}

	/**
	 * Returns true if at least one Accelerometer sensor is available
	 */
	public static boolean isSupported()
	{
		if (supported == null) {
			if (RoverOpenActivity.getContext() != null) {
				sensorManager = (SensorManager) RoverOpenActivity.getContext().
						getSystemService(Context.SENSOR_SERVICE);
				List<Sensor> sensors = sensorManager.getSensorList(
						Sensor.TYPE_ACCELEROMETER);
				supported = new Boolean( sensors.size() > 0 );
			} else {
				supported = Boolean.FALSE;
			}
		}
		return supported;
	}

	/**
	 * Configure the listener
	 */
	public static void configure( long interval )
	{
		AccelerometerManager.interval = interval;
	}

	/**
	 * Registers a listener and starts listening
	 */
	public static void startListening( AccelerometerListener accelerometerListener )
	{
		sensorManager = (SensorManager) RoverOpenActivity.getContext().
				getSystemService( Context.SENSOR_SERVICE );
		List<Sensor> sensors = sensorManager.getSensorList( Sensor.TYPE_ACCELEROMETER );
		if ( sensors.size() > 0 ) {
			sensor = sensors.get(0);
			running = sensorManager.registerListener(
					sensorEventListener, sensor, 
					SensorManager.SENSOR_DELAY_GAME );
			listener = accelerometerListener;
		}
	}

	/**
	 * Configures threshold and interval
	 * And registers a listener and starts listening
	 */
	public static void startListening( AccelerometerListener accelerometerListener, long interval )
	{
		configure( interval );
		startListening( accelerometerListener );
	}

	/**
	 * The listener that listen to events from the accelerometer listener
	 */
	private static SensorEventListener sensorEventListener = 
			new SensorEventListener() {

		private long now;
		private long timeDiff;
		private long lastUpdate = 0;
		private float x, y, z;

		public void onAccuracyChanged( Sensor sensor, int accuracy ) {}

		public void onSensorChanged( SensorEvent event )
		{
			// use the event time stamp as reference so the manager's precision
			// won't depend on the AccelerometerListener implementation
			// processing time
			now = event.timestamp;

			x = event.values[0];
			y = event.values[1];
			z = event.values[2];

			boolean tx = false;

			if ( lastUpdate == 0 ) {
				lastUpdate = now;
			} else {
				timeDiff = now - lastUpdate;
				if ( timeDiff >= interval ) {
					tx = true;
					lastUpdate = now;
				}
			}

			// trigger change event
			listener.onAccelerationChanged( x, y, z, tx );
		}

	};

}
