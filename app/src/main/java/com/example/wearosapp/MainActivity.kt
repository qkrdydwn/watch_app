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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var database: FirebaseDatabase
    private lateinit var sensorManager: SensorManager
    private lateinit var heartRateSensor: Sensor
    private lateinit var gyroscopeSensor: Sensor
    private lateinit var accelerometerSensor: Sensor
    
    private var isCollecting = false
    private val handler = Handler(Looper.getMainLooper())
    
    // 데이터 버퍼
    private val heartRateBuffer = mutableListOf<Float>()
    private val gyroscopeBuffer = mutableListOf<Triple<Float, Float, Float>>()
    private val accelerometerBuffer = mutableListOf<Triple<Float, Float, Float>>()
    
    // 데이터 전송 주기 (밀리초)
    private val heartRateInterval = 1000L
    private val sensorInterval = 100L
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSensorCollection()
        } else {
            Toast.makeText(this, "센서 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Firebase 초기화
            Firebase.initialize(this)
            database = Firebase.database
            
            // 센서 초기화
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            if (heartRateSensor == null || gyroscopeSensor == null || accelerometerSensor == null) {
                Toast.makeText(this, "필요한 센서를 찾을 수 없습니다", Toast.LENGTH_LONG).show()
                return
            }
            
            setContent {
                WearApp(database, this)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "앱 초기화 중 오류 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
    }

    override fun onPause() {
        super.onPause()
        stopSensorCollection()
    }

    private fun checkAndRequestPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED -> {
                startSensorCollection()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
            }
        }
    }

    private fun startSensorCollection() {
        try {
            isCollecting = true
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME)
            
            // 주기적으로 데이터 전송
            startPeriodicDataTransmission()
        } catch (e: Exception) {
            Toast.makeText(this, "센서 데이터 수집 시작 중 오류 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopSensorCollection() {
        isCollecting = false
        try {
            sensorManager.unregisterListener(this)
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Toast.makeText(this, "센서 데이터 수집 중지 중 오류 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPeriodicDataTransmission() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isCollecting) {
                    sendBufferedData()
                    handler.postDelayed(this, heartRateInterval)
                }
            }
        }, heartRateInterval)
    }

    private fun sendBufferedData() {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val dataRef = database.getReference("wear_data")
            
            // 심박수 데이터 전송
            if (heartRateBuffer.isNotEmpty()) {
                val avgHeartRate = heartRateBuffer.average()
                val data = mapOf(
                    "timestamp" to timestamp,
                    "type" to "heart_rate",
                    "value" to avgHeartRate
                )
                dataRef.push().setValue(data)
                heartRateBuffer.clear()
            }
            
            // 자이로스코프 데이터 전송
            if (gyroscopeBuffer.isNotEmpty()) {
                val avgGyro = gyroscopeBuffer.reduce { acc, triple ->
                    Triple(
                        acc.first + triple.first,
                        acc.second + triple.second,
                        acc.third + triple.third
                    )
                }
                val count = gyroscopeBuffer.size.toFloat()
                val data = mapOf(
                    "timestamp" to timestamp,
                    "type" to "gyroscope",
                    "x" to avgGyro.first / count,
                    "y" to avgGyro.second / count,
                    "z" to avgGyro.third / count
                )
                dataRef.push().setValue(data)
                gyroscopeBuffer.clear()
            }
            
            // 가속도 데이터 전송
            if (accelerometerBuffer.isNotEmpty()) {
                val avgAccel = accelerometerBuffer.reduce { acc, triple ->
                    Triple(
                        acc.first + triple.first,
                        acc.second + triple.second,
                        acc.third + triple.third
                    )
                }
                val count = accelerometerBuffer.size.toFloat()
                val data = mapOf(
                    "timestamp" to timestamp,
                    "type" to "accelerometer",
                    "x" to avgAccel.first / count,
                    "y" to avgAccel.second / count,
                    "z" to avgAccel.third / count
                )
                dataRef.push().setValue(data)
                accelerometerBuffer.clear()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "데이터 전송 중 오류 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isCollecting) return

        try {
            when (event.sensor.type) {
                Sensor.TYPE_HEART_RATE -> {
                    synchronized(heartRateBuffer) {
                        heartRateBuffer.add(event.values[0])
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    synchronized(gyroscopeBuffer) {
                        gyroscopeBuffer.add(Triple(event.values[0], event.values[1], event.values[2]))
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    synchronized(accelerometerBuffer) {
                        accelerometerBuffer.add(Triple(event.values[0], event.values[1], event.values[2]))
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "센서 데이터 처리 중 오류 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                Toast.makeText(this, "센서 데이터가 신뢰할 수 없습니다", Toast.LENGTH_SHORT).show()
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                Toast.makeText(this, "센서 정확도가 낮습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun WearApp(database: FirebaseDatabase, activity: MainActivity) {
    val navController = rememberSwipeDismissableNavController()
    
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(database, activity)
        }
    }
}

@Composable
fun HomeScreen(database: FirebaseDatabase, activity: MainActivity) {
    val context = LocalContext.current
    var isCollecting by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "센서 데이터 수집",
            style = MaterialTheme.typography.title1
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                isCollecting = !isCollecting
                if (isCollecting) {
                    activity.startSensorCollection()
                    Toast.makeText(context, "데이터 수집 시작", Toast.LENGTH_SHORT).show()
                } else {
                    activity.stopSensorCollection()
                    Toast.makeText(context, "데이터 수집 중지", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isCollecting) "수집 중지" else "수집 시작")
        }
    }
} 