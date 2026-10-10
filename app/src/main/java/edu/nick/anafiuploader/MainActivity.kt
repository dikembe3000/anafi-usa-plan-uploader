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
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.pilotingitf.FlightPlanPilotingItf
import com.parrot.drone.groundsdk.facility.AutoConnection
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var groundSdk: GroundSdk
    private var drone: Drone? = null
    private var flightPlanRef: Ref<FlightPlanPilotingItf>? = null
    private var flightPlan: FlightPlanPilotingItf? = null
    private var selectedPlan: File? = null
    private lateinit var connectionStatus: TextView
    private lateinit var internetStatus: TextView
    private lateinit var selectionStatus: TextView
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
        planStatus = findViewById(R.id.planStatus)
        uploadButton = findViewById(R.id.uploadButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        findViewById<Button>(R.id.selectButton).setOnClickListener { chooseFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        uploadButton.setOnClickListener { selectedPlan?.let { flightPlan?.uploadFlightPlan(it) } }
        startButton.setOnClickListener { flightPlan?.activate(FlightPlanPilotingItf.Interpreter.LEGACY, true) }
        stopButton.setOnClickListener { flightPlan?.stop() }
        groundSdk = ManagedGroundSdk.obtainSession(this)
        updateInternetStatus()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
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
                flightPlan = null
                drone = auto.drone
                connectionStatus.text = drone?.let { "Connected to ${it.name}" } ?: "Looking for ANAFI USA…"
                monitorFlightPlan()
            }
        }
    }

    private fun updateInternetStatus() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        internetStatus.text = when {
            capabilities == null -> "Internet: no active connection — satellite map cannot load"
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                "Internet: connected — satellite map should load"
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
        val points = file.readLines().dropWhile { !it.startsWith("QGC WPL") }.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val isGeographicWaypoint = parts.size >= 12 && (parts[2] == "0" || parts[2] == "3")
            if (!isGeographicWaypoint) null else parts[8].toDoubleOrNull()?.let { lat -> parts[9].toDoubleOrNull()?.let { lon ->
                if (lat == 0.0 && lon == 0.0) null else GeoPoint(lat, lon)
            } }
        }
        route.setPoints(points)
        if (points.isNotEmpty()) {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 64)
            selectionStatus.text = "Selected: ${file.name} • ${points.size} map points"
        } else selectionStatus.text = "Selected: ${file.name} • no map waypoints found"
        map.invalidate()
    }

    override fun onStop() {
        flightPlanRef?.close(); flightPlanRef = null; flightPlan = null
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    companion object { private const val LOCATION_PERMISSION = 1001 }
}
