package edu.nick.anafiuploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.pilotingitf.FlightPlanPilotingItf
import com.parrot.drone.groundsdk.facility.AutoConnection
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var groundSdk: GroundSdk
    private var drone: Drone? = null
    private var flightPlanRef: Ref<FlightPlanPilotingItf>? = null
    private var flightPlan: FlightPlanPilotingItf? = null
    private var selectedPlan: File? = null
    private lateinit var connectionStatus: TextView
    private lateinit var planStatus: TextView
    private lateinit var uploadButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val chooseFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPlan(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        connectionStatus = findViewById(R.id.connectionStatus)
        planStatus = findViewById(R.id.planStatus)
        uploadButton = findViewById(R.id.uploadButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        findViewById<Button>(R.id.selectButton).setOnClickListener { chooseFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        uploadButton.setOnClickListener { selectedPlan?.let { fflightPlan?.uploadFlightPlan(it) } }
        startButton.setOnClickListener { flightPlan?.activate(FlightPlanPilotingItf.Interpreter.LEGACY, true) }
        stopButton.setOnClickListener { flightPlan?.stop() }
        groundSdk = ManagedGroundSdk.obtainSession(this)
    }

    override fun onStart() {
        super.onStart()
        groundSdk.getFacility(AutoConnection::class.java) { auto ->
            auto ?: return@getFacility
            if (auto.status != AutoConnection.Status.STARTED) auto.start()
            if (drone?.uid != auto.drone?.uid) {
                flightPlanRef?.close()
                drone = auto.drone
                connectionStatus.text = drone?.let { "Connected to ${it.name}" } ?: "Looking for ANAFI USA…"
                monitorFlightPlan()
            }
        }
    }

    private fun monitorFlightPlan() {
        flightPlanRef = drone?.getPilotingItf(FlightPlanPilotingItf::class.java) { plan ->
            flightPlan = plan
            if (plan == null) {
                planStatus.text = "FlightPlan unavailable"
                uploadButton.isEnabled = false
                startButton.isEnabled = false
                stopButton.isEnabled = false
                return@getPilotingItf
            }
            planStatus.text = "Upload: ${plan.latestUploadState}; State: ${plan.state}"
            uploadButton.isEnabled = selectedPlan != null
            startButton.isEnabled = plan.state.toString().equals("IDLE", ignoreCase = true)
            stopButton.isEnabled = plan.state.toString().equals("ACTIVE", ignoreCase = true) || plan.isPaused
        }
    }

    private fun importPlan(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val destination = File(cacheDir, "selected-plan.mavlink")
                destination.outputStream().use { input.copyTo(it) }
                selectedPlan = destination
                planStatus.text = "Selected: ${uri.lastPathSegment ?: "MAVLink plan"}"
                uploadButton.isEnabled = flightPlan != null
            }
        } catch (error: Exception) {
            planStatus.text = "Could not read file: ${error.message}"
        }
    }

    override fun onStop() {
        flightPlanRef?.close()
        flightPlanRef = null
        flightPlan = null
        super.onStop()
    }
}
