package se.t8m2vq.nedrakning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import se.t8m2vq.nedrakning.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.roundToInt

// Målet. Byt dessa två rader för att peka om appen.
const val TARGET_LAT = 58.483
const val TARGET_LON = 11.683

private const val UPDATE_INTERVAL_MS = 1000L
private const val UPDATE_DISTANCE_M = 0f

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var lastLocation: Location? = null
    private var declination = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Utjämnad enhetsriktning i grader från sant norr. */
    private var heading = 0f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLocationUpdates() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        binding.status.text = getString(R.string.status_waiting)
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            binding.status.text = getString(R.string.status_gps_off)
            return
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                UPDATE_DISTANCE_M,
                this
            )
        } catch (e: SecurityException) {
            showPermissionDenied()
        }
    }

    private fun showPermissionDenied() {
        binding.status.text = getString(R.string.status_no_permission)
        binding.deltaNorth.text = getString(R.string.dash)
        binding.deltaEast.text = getString(R.string.dash)
    }

    // --- Plats ---

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        declination = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            location.time
        ).declination
        render()
    }

    @Deprecated("Krävs av LocationListener på API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderDisabled(provider: String) {
        binding.status.text = getString(R.string.status_gps_off)
    }

    override fun onProviderEnabled(provider: String) {
        startLocationUpdates()
    }

    // --- Sensor ---

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val magneticNorth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        heading = normalize(magneticNorth + declination)
        render()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Rendering ---

    private fun render() {
        val location = lastLocation ?: return

        val north = signedDelta(
            location.latitude, location.longitude,
            TARGET_LAT, location.longitude,
            TARGET_LAT > location.latitude
        )
        val east = signedDelta(
            location.latitude, location.longitude,
            location.latitude, TARGET_LON,
            TARGET_LON > location.longitude
        )

        binding.deltaNorth.text = formatMeters(north)
        binding.deltaEast.text = formatMeters(east)

        val results = FloatArray(2)
        Location.distanceBetween(
            location.latitude, location.longitude, TARGET_LAT, TARGET_LON, results
        )
        val distance = results[0].roundToInt()
        val bearing = results[1]

        binding.needle.rotation = normalize(bearing - heading)
        binding.status.text = getString(
            R.string.status_fix,
            distance,
            location.accuracy.roundToInt()
        )
    }

    /**
     * Avstånd i meter längs en axel, med tecken. Positivt = målet ligger
     * norrut/österut om mig.
     */
    private fun signedDelta(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        positive: Boolean
    ): Int {
        val results = FloatArray(1)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, results)
        val meters = results[0].roundToInt()
        return if (positive) meters else -meters
    }

    private fun formatMeters(value: Int): String = when {
        value == 0 -> "0 m"
        value > 0 -> "+$value m"
        else -> "−${abs(value)} m"
    }

    /** Vinkel till intervallet [-180, 180). */
    private fun normalize(degrees: Float): Float {
        var d = degrees % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
