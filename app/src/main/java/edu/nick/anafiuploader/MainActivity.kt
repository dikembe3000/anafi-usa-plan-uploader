package edu.nick.anafiuploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.Gps
import com.parrot.drone.groundsdk.device.pilotingitf.FlightPlanPilotingItf
import com.parrot.drone.groundsdk.facility.AutoConnection
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var groundSdk: GroundSdk
    private var drone: Drone? = null
    private var droneStateRef: Ref<DeviceState>? = null
    private var droneGpsRef: Ref<Gps>? = null
    private var flightPlanRef: Ref<FlightPlanPilotingItf>? = null
    private var flightPlan: FlightPlanPilotingItf? = null
    private var selectedPlan: File? = null
    private lateinit var connectionStatus: TextView
    private lateinit var internetStatus: TextView
    private lateinit var selectionStatus: TextView
    private lateinit var droneGpsStatus: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var planStatus: TextView
    private lateinit var uploadButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var map: MapView
    private lateinit var route: Polyline
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            runOnUiThread { updateInternetStatus() }
        }
        override fun onLost(network: Network) {
            runOnUiThread { updateInternetStatus() }
        }
    }

    private val satelliteTiles = object : OnlineTileSourceBase(
        "Esri World Imagery", 1, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"), "Esri"
    ) {
        override fun getTileURLString(tileIndex: Long): String =
            getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/" +
                MapTileIndex.getY(tileIndex) + "/" + MapTileIndex.getX(tileIndex)
    }

    private val chooseFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importPlan(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        setupMap()
        connectionStatus = findViewById(R.id.connectionStatus)
        internetStatus = findViewById(R.id.internetStatus)
        selectionStatus = findViewById(R.id.selectionStatus)
        droneGpsStatus = findViewById(R.id.droneGpsStatus)
        cameraStatus = findViewById(R.id.cameraStatus)
        planStatus = findViewById(R.id.planStatus)
        uploadButton = findViewById(R.id.uploadButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        findViewById<Button>(R.id.selectButton).setOnClickListener { chooseFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        findViewById<Button>(R.id.refreshStatusButton).setOnClickListener { refreshDroneStatus() }
        uploadButton.setOnClickListener { selectedPlan?.let { flightPlan?.uploadFlightPlan(it) } }
        startButton.setOnClickListener { flightPlan?.activate(FlightPlanPilotingItf.Interpreter.LEGACY, true) }
        stopButton.setOnClickListener { flightPlan?.stop() }
        groundSdk = ManagedGroundSdk.obtainSession(this)
        updateInternetStatus()
    }

    private fun setupMap() {
        // ArcGIS uses z/y/x ordering, unlike the z/x/y ordering used by OpenStreetMap tiles.
        map.setTileSource(satelliteTiles)
        map.setMultiTouchControls(true)
        route = Polyline().apply { outlinePaint.color = Color.rgb(103, 58, 183); outlinePaint.strokeWidth = 7f }
        map.overlays.add(route)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) showMyLocation()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION)
    }

    private fun showMyLocation() {
        map.overlays.add(MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply { enableMyLocation() })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) showMyLocation()
    }

    override fun onStart() {
        super.onStart()
        (getSystemService(ConnectivityManager::class.java)).registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
        updateInternetStatus()
        groundSdk.getFacility(AutoConnection::class.java) { auto ->
            auto ?: return@getFacility
            if (auto.status != AutoConnection.Status.STARTED) auto.start()
            if (drone?.uid != auto.drone?.uid) {
                flightPlanRef?.close()
                droneStateRef?.close()
                flightPlan = null
                drone = auto.drone
                connectionStatus.text = drone?.let { "Drone detected: ${it.name} — checking link…" } ?: "Looking for ANAFI USA…"
            }
            // The Android file picker pauses this activity. Recreate listeners when returning,
            // even when GroundSDK is still reporting the same drone UID.
            if (droneStateRef == null) monitorDroneState()
            if (droneGpsRef == null) monitorDroneGps()
            if (flightPlanRef == null) monitorFlightPlan()
        }
    }

    private fun monitorDroneState() {
        droneStateRef = drone?.getState { state ->
            val name = drone?.name ?: "ANAFI USA"
            connectionStatus.text = if (state?.connectionState == DeviceState.ConnectionState.CONNECTED) {
                "Drone link: connected to $name"
            } else {
                "Drone link: ${state?.connectionState ?: "unavailable"} ($name)"
            }
        }
    }

    private fun monitorDroneGps() {
        droneGpsRef = drone?.getInstrument(Gps::class.java) { gps ->
            if (gps == null) {
                droneGpsStatus.text = "Drone GPS: instrument not available yet"
                return@getInstrument
            }
            val verticalAccuracy = gps.verticalAccuracy
                ?.let { "%.1f m".format(it) }
                ?: "not reported"
            val horizontalAccuracy = gps.lastKnownLocation
                ?.accuracy
                ?.let { "%.1f m".format(it) }
                ?: "not reported"
            val fixed = gps.fixed?.let { if (it) "yes" else "no" } ?: "unknown"
            val satellites = gps.satelliteCount?.toString() ?: "not reported"
            droneGpsStatus.text = "Drone GPS: fixed $fixed • " +
                "satellites $satellites • horizontal $horizontalAccuracy • vertical $verticalAccuracy"
        }
    }

    private fun refreshDroneStatus() {
        droneGpsRef?.close(); droneGpsRef = null
        flightPlanRef?.close(); flightPlanRef = null; flightPlan = null
        droneGpsStatus.text = "Drone GPS: refreshing…"
        planStatus.text = "Refreshing FlightPlan status…"
        monitorDroneGps()
        monitorFlightPlan()
    }

    private fun updateInternetStatus() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        internetStatus.text = when {
            capabilities == null -> "Internet: no active connection — satellite map cannot load"
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                "Internet: connected — satellite imagery loading"
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                "Internet: network found, but Android cannot verify internet access"
            else -> "Internet: active network has no internet access"
        }
    }

    private fun monitorFlightPlan() {
        flightPlanRef = drone?.getPilotingItf(FlightPlanPilotingItf::class.java) { plan ->
            flightPlan = plan
            renderFlightPlanState(plan)
        }
    }

    private fun renderFlightPlanState(plan: FlightPlanPilotingItf?) {
        if (plan == null) {
            planStatus.text = "FlightPlan interface not yet available. Keep the drone connected; this app will update automatically."
            uploadButton.isEnabled = false; startButton.isEnabled = false; stopButton.isEnabled = false
            return
        }
        val reasons = plan.unavailabilityReasons.joinToString().ifBlank { "none" }
        planStatus.text = "Upload: ${plan.latestUploadState} • State: ${plan.state} • Blocked by: $reasons"
        uploadButton.isEnabled = selectedPlan != null
        startButton.isEnabled = plan.state.toString().equals("IDLE", ignoreCase = true)
        stopButton.isEnabled = plan.state.toString().equals("ACTIVE", ignoreCase = true) || plan.isPaused
    }

    private fun importPlan(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val destination = File(cacheDir, "selected-plan.mavlink")
                destination.outputStream().use { input.copyTo(it) }
                selectedPlan = destination
                selectionStatus.text = "Selected: ${uri.lastPathSegment ?: "MAVLink plan"}"
                drawMission(destination)
                renderFlightPlanState(flightPlan)
            }
        } catch (error: Exception) { selectionStatus.text = "Could not read file: ${error.message}" }
    }

    private fun drawMission(file: File) {
        val missionLines = file.readLines().dropWhile { !it.startsWith("QGC WPL") }.drop(1)
        val points = missionLines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val isGeographicWaypoint = parts.size >= 12 && (parts[2] == "0" || parts[2] == "3")
            if (!isGeographicWaypoint) null else parts[8].toDoubleOrNull()?.let { lat -> parts[9].toDoubleOrNull()?.let { lon ->
                if (lat == 0.0 && lon == 0.0) null else GeoPoint(lat, lon)
            } }
        }
        renderCameraSummary(missionLines)
        route.setPoints(points)
        if (points.isNotEmpty()) {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 64)
            selectionStatus.text = "Selected: ${file.name} • ${points.size} map points"
        } else selectionStatus.text = "Selected: ${file.name} • no map waypoints found"
        map.invalidate()
    }

    private fun renderCameraSummary(missionLines: List<String>) {
        val cameraCommands = missionLines.mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 12) null else fields
        }
        val triggerDistances = cameraCommands.mapNotNull { fields ->
            if (fields[3] == "206") fields[4].toDoubleOrNull()?.takeIf { it > 0.0 } else null
        }
        val hasCaptureMode = cameraCommands.any { it[3] == "50001" }
        val hasCaptureAction = cameraCommands.any { it[3] == "2000" || it[3] == "2001" }
        cameraStatus.text = when {
            triggerDistances.isNotEmpty() -> {
                val spacing = "%.2f".format(triggerDistances.first())
                val mode = if (hasCaptureMode || hasCaptureAction) {
                    " Camera capture commands are included."
                } else {
                    " Set the ANAFI camera's photo mode and image settings before starting."
                }
                "Mission camera: distance-triggered photos every $spacing m.$mode"
            }
            hasCaptureMode || hasCaptureAction -> "Mission camera: capture commands found in this MAVLink file."
            else -> "Mission camera: no photo-trigger command found in this MAVLink file."
        }
    }

    override fun onStop() {
        flightPlanRef?.close(); flightPlanRef = null; flightPlan = null
        droneStateRef?.close(); droneStateRef = null
        droneGpsRef?.close(); droneGpsRef = null
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    companion object { private const val LOCATION_PERMISSION = 1001 }
}
