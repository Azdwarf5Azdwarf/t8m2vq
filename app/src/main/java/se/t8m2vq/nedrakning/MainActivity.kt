package se.t8m2vq.nedrakning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import se.t8m2vq.nedrakning.databinding.ActivityMainBinding
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

// Startmalet, tills nagot annat matas in i falten. Byt dessa tva rader for
// att flytta det appen borjar med.
const val TARGET_LAT = 58.483
const val TARGET_LON = 11.683

private const val UPDATE_INTERVAL_MS = 1000L
private const val UPDATE_DISTANCE_M = 0f

private const val PREFS = "nedrakning"
private const val KEY_LAT = "target_lat"
private const val KEY_LON = "target_lon"
private const val KEY_NAME = "target_name"

/** "58.483, 11.683" och "58.483 11.683" — punkt som decimaltecken. */
private val DOT_PAIR = Regex("""^(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)$""")

/** "58,483 11,683" — komma som decimaltecken, da kravs annan avskiljare. */
private val COMMA_PAIR = Regex("""^(-?\d+,\d+)\s*[;\s]\s*(-?\d+,\d+)$""")

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var lastLocation: Location? = null
    private var declination = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Utjamnad enhetsriktning i grader fran sant norr. */
    private var heading = 0f

    private var targetLat = TARGET_LAT
    private var targetLon = TARGET_LON
    private var targetName: String? = null

    private val geocodeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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

        loadTarget()
        showTarget()

        binding.setTarget.setOnClickListener { submitTarget() }
        binding.targetInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitTarget()
                true
            } else {
                false
            }
        }

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

    override fun onDestroy() {
        super.onDestroy()
        geocodeExecutor.shutdownNow()
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

    // --- Malet ---

    private fun loadTarget() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull()
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            targetLat = lat
            targetLon = lon
            targetName = prefs.getString(KEY_NAME, null)
        }
    }

    private fun saveTarget() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAT, targetLat.toString())
            .putString(KEY_LON, targetLon.toString())
            .putString(KEY_NAME, targetName)
            .apply()
    }

    private fun showTarget() {
        val name = targetName
        val where = if (name.isNullOrBlank()) {
            getString(R.string.target_coords, targetLat, targetLon)
        } else {
            getString(R.string.target_named, name, targetLat, targetLon)
        }
        binding.targetLabel.text = getString(R.string.target_current, where)
    }

    /** Las falten, tolka det som star dar och satt malet. */
    private fun submitTarget() {
        val raw = binding.targetInput.text.toString().trim()
        if (raw.isEmpty()) return
        hideKeyboard()

        val coords = parseCoordinates(raw)
        if (coords != null) {
            val (lat, lon) = coords
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                binding.status.text = getString(R.string.error_range)
                return
            }
            applyTarget(lat, lon, null)
            return
        }

        if (!Geocoder.isPresent()) {
            binding.status.text = getString(R.string.error_no_geocoder)
            return
        }
        lookUpPlace(raw)
    }

    /**
     * Tva tal i falten tolkas som latitud och longitud. Ar det inte tva tal
     * far det vara ett ortnamn i stallet, och da returneras null.
     */
    private fun parseCoordinates(raw: String): Pair<Double, Double>? {
        DOT_PAIR.matchEntire(raw)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return lat to lon
        }
        COMMA_PAIR.matchEntire(raw)?.let { m ->
            val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            return lat to lon
        }
        return null
    }

    /** Geocoder blockerar och gar over natet, sa den far en egen trad. */
    private fun lookUpPlace(query: String) {
        binding.status.text = getString(R.string.target_searching, query)
        val geocoder = Geocoder(this, Locale.getDefault())
        geocodeExecutor.execute {
            val result = try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)?.firstOrNull()
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (result == null) {
                    binding.status.text = getString(R.string.error_not_found, query)
                } else {
                    applyTarget(result.latitude, result.longitude, query)
                }
            }
        }
    }

    private fun applyTarget(lat: Double, lon: Double, name: String?) {
        targetLat = lat
        targetLon = lon
        targetName = name
        saveTarget()
        showTarget()
        binding.targetInput.text.clear()
        binding.status.text = getString(R.string.target_set, binding.targetLabel.text)
        render()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.targetInput.windowToken, 0)
        binding.targetInput.clearFocus()
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

    @Deprecated("Kravs av LocationListener pa API < 29")
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
            targetLat, location.longitude,
            targetLat > location.latitude
        )
        val east = signedDelta(
            location.latitude, location.longitude,
            location.latitude, targetLon,
            targetLon > location.longitude
        )

        binding.deltaNorth.text = formatMeters(north)
        binding.deltaEast.text = formatMeters(east)

        val results = FloatArray(2)
        Location.distanceBetween(
            location.latitude, location.longitude, targetLat, targetLon, results
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
     * Avstand i meter langs en axel, med tecken. Positivt = malet ligger
     * norrut/osterut om mig.
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
