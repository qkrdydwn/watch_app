package com.example.wearosapp

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var database: FirebaseDatabase
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private val isCollecting = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val heartRateBuffer = mutableListOf<Int>()
    private val gyroscopeBuffer = mutableListOf<Triple<Float, Float, Float>>()
    private val accelerometerBuffer = mutableListOf<Triple<Float, Float, Float>>()
    private val heartRateLock = Object()
    private val gyroscopeLock = Object()
    private val accelerometerLock = Object()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Firebase.initialize(this)
        database = Firebase.database
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupSensors()

        setContent {
            WearApp()
        }
    }

    @Composable
    fun WearApp() {
        val navController = rememberSwipeDismissableNavController()
        
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "main"
        ) {
            composable("main") {
                MainScreen(
                    isCollecting = isCollecting.get(),
                    onStartStop = { 
                        if (isCollecting.get()) {
                            stopSensorCollection()
                        } else {
                            startSensorCollection()
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun MainScreen(
        isCollecting: Boolean,
        onStartStop: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = onStartStop,
                modifier = Modifier.size(48.dp)
            ) {
                Text(if (isCollecting) "Stop" else "Start")
            }
        }
    }

    private fun setupSensors() {
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (heartRateSensor == null || gyroscopeSensor == null || accelerometerSensor == null) {
            Toast.makeText(this, "Some sensors are not available", Toast.LENGTH_SHORT).show()
        }
    }

    fun startSensorCollection() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        isCollecting.set(true)
        heartRateBuffer.clear()
        gyroscopeBuffer.clear()
        accelerometerBuffer.clear()

        heartRateSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscopeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        startDataTransmission()
    }

    fun stopSensorCollection() {
        isCollecting.set(false)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        sendBufferedData()
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BODY_SENSORS),
            SENSOR_PERMISSION_REQUEST_CODE
        )
    }

    private fun startDataTransmission() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isCollecting.get()) {
                    sendBufferedData()
                    handler.postDelayed(this, TRANSMISSION_INTERVAL)
                }
            }
        }, TRANSMISSION_INTERVAL)
    }

    private fun sendBufferedData() {
        val timestamp = System.currentTimeMillis()
        val dataRef = database.getReference("sensor_data").push()

        synchronized(heartRateLock) {
            if (heartRateBuffer.isNotEmpty()) {
                dataRef.child("heart_rate").setValue(heartRateBuffer)
                heartRateBuffer.clear()
            }
        }

        synchronized(gyroscopeLock) {
            if (gyroscopeBuffer.isNotEmpty()) {
                dataRef.child("gyroscope").setValue(gyroscopeBuffer)
                gyroscopeBuffer.clear()
            }
        }

        synchronized(accelerometerLock) {
            if (accelerometerBuffer.isNotEmpty()) {
                dataRef.child("accelerometer").setValue(accelerometerBuffer)
                accelerometerBuffer.clear()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isCollecting.get()) return

        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                synchronized(heartRateLock) {
                    heartRateBuffer.add(event.values[0].toInt())
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(gyroscopeLock) {
                    gyroscopeBuffer.add(Triple(event.values[0], event.values[1], event.values[2]))
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(accelerometerLock) {
                    accelerometerBuffer.add(Triple(event.values[0], event.values[1], event.values[2]))
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensorCollection()
    }

    companion object {
        private const val SENSOR_PERMISSION_REQUEST_CODE = 100
        private const val TRANSMISSION_INTERVAL = 1000L // 1 second
    }
} 