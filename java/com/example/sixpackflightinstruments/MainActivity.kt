package com.example.sixpackflightinstruments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileWriter
import java.util.*
import kotlin.math.*
// --- CONFIGURACIÓN DE COLORES PROFESIONALES ---
val CessnaOrange = Color(0xFFFF9800)
val DarkPanel = Color(0xFF121212)
val NightRed = Color(0xFFFF3D00)
val MaroonRed = Color(0xFF8B0000)
val SkyBlue = Color(0xFF2196F3)
val GroundBrown = Color(0xFF795548)
val NightSky = Color(0xFF0D1B2A)
val NightGround = Color(0xFF1B1B1B)
data class FlightData(
    val speed: Float = 0f, val altitude: Double = 0.0, val roll: Float = 0f, val pitch: Float = 0f, val heading: Float = 0f,
    val vsi: Float = 0f, val isNightMode: Boolean = false, val gpsStatus: String = "GPS: SEARCHING",
    val isLogging: Boolean = false, val maxSpeed: Float = 0f, val maxAlt: Double = 0.0,
    val rollOffset: Float = 0f, val pitchOffset: Float = 0f, val kollsmanOffset: Float = 0f,
    val isSimulation: Boolean = true, val isDemoMode: Boolean = false
)
class MainActivity : ComponentActivity(), SensorEventListener, LocationListener, TextToSpeech.OnInitListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var tts: TextToSpeech? = null
    private val _state = mutableStateOf(FlightData())
    private var lastAltRaw = 0.0
    private var lastTimeVsi = 0L
    private var lastSensorTimestamp = 0L
    private var rawRoll = 0f
    private var rawPitch = 0f
    private var fRoll = 0f
    private var fPitch = 0f
    private var fHeading = 0f
    private var logFileWriter: FileWriter? = null
    private var lastTtsTime = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        tts = TextToSpeech(this, this)
        setContent {
            val s by _state
            val config = LocalConfiguration.current
            val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            Surface(Modifier.fillMaxSize(), color = DarkPanel) {
                var showSplash by remember { mutableStateOf(true) }
                val isTablet = config.smallestScreenWidthDp >= 600
                val screenWidth = config.screenWidthDp.dp
                val screenHeight = config.screenHeightDp.dp
                val columns = if (isLandscape) 3 else 2
                val rows = if (isLandscape) 2 else 3
                val maxInstSize = if (isTablet) 450.dp else 240.dp
                val internalFontFactor = if (isTablet) 0.08f else 0.095f
                val buttonFontSize = if (isTablet) 15.sp else 9.sp
                val verticalMargin = if (isLandscape) (if (isTablet) 140.dp else 90.dp) else 185.dp
                val instSize = minOf(
                    (screenWidth - 32.dp) / columns,
                    (screenHeight - verticalMargin) / rows
                ).coerceIn(110.dp, maxInstSize)
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
                    if (p[Manifest.permission.ACCESS_FINE_LOCATION] == true) startGps()
                }
                var demoAltitude by remember { mutableDoubleStateOf(0.0) }
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    delay(1200)
                    showSplash = false
                    while(true) {
                        if (s.isDemoMode) {
                            demoAltitude += 0.5
                            _state.value = _state.value.copy(altitude = demoAltitude, vsi = 600f)
                            if (demoAltitude > 12000) demoAltitude = 0.0
                        }
                        checkSafetyAlerts(_state.value)
                        if (_state.value.isLogging) logData(_state.value)
                        delay(50)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    FlightDashboard(
                        s, instSize, isLandscape, buttonFontSize, internalFontFactor, isTablet,
                        onNightToggle = { _state.value = _state.value.copy(isNightMode = !_state.value.isNightMode) },
                        onCalibrate = { recalibrateSensors() },
                        onKollsmanChange = { delta -> _state.value = _state.value.copy(kollsmanOffset = _state.value.kollsmanOffset + delta) },
                        onLoggingToggle = { toggleLogging() },
                        onDemoToggle = {
                            val nextMode = !s.isDemoMode
                            if(nextMode) demoAltitude = s.altitude
                            _state.value = s.copy(isDemoMode = nextMode)
                        }
                    )
                    if (showSplash) SplashScreen()
                }
            }
        }
    }
    private fun recalibrateSensors() { _state.value = _state.value.copy(rollOffset = rawRoll, pitchOffset = rawPitch) }
    private fun toggleLogging() {
        if (!_state.value.isLogging) {
            try {
                val file = File(getExternalFilesDir(null), "flight_log_${System.currentTimeMillis()}.csv")
                logFileWriter = FileWriter(file)
                logFileWriter?.write("Timestamp,Alt(ft),Speed(kts),Roll,Pitch,Heading\n")
                _state.value = _state.value.copy(isLogging = true, maxSpeed = _state.value.speed, maxAlt = _state.value.altitude)
            } catch (e: Exception) { Log.e("Logger", "Error", e) }
        } else {
            _state.value = _state.value.copy(isLogging = false)
            try { logFileWriter?.close(); logFileWriter = null } catch (e: Exception) {}
        }
    }
    private fun logData(s: FlightData) {
        try { logFileWriter?.write("${System.currentTimeMillis()},${s.altitude},${s.speed},${s.roll},${s.pitch},${s.heading}\n") } catch (e: Exception) {}
    }
    private fun checkSafetyAlerts(s: FlightData) {
        val now = System.currentTimeMillis()
        if (now - lastTtsTime < 4500) return
        if (abs(s.roll) > 50f && abs(s.roll) < 130f) {
            tts?.speak("Bank Angle", TextToSpeech.QUEUE_FLUSH, null, "bank")
            lastTtsTime = now
        } else if (s.altitude < 1200 && s.vsi < -1200) {
            tts?.speak("Altitude", TextToSpeech.QUEUE_FLUSH, null, "alt")
            lastTtsTime = now
        }
    }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US }
    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startGps()
    }
    override fun onPause() { super.onPause() ; sensorManager.unregisterListener(this) ; locationManager.removeUpdates(this) }
    override fun onSensorChanged(event: SensorEvent) {
        lastSensorTimestamp = System.currentTimeMillis()
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rMat = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rMat, event.values)
            val remapped = FloatArray(9)
            SensorManager.remapCoordinateSystem(rMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            val orient = FloatArray(3)
            SensorManager.getOrientation(remapped, orient)
            rawRoll = Math.toDegrees(orient[2].toDouble()).toFloat()
            rawPitch = Math.toDegrees(orient[1].toDouble()).toFloat()
            val targetHdg = (Math.toDegrees(orient[0].toDouble()).toFloat() + 360) % 360
            val cr = (rawRoll - _state.value.rollOffset)
            val cp = -(rawPitch - _state.value.pitchOffset)
            fRoll = fRoll * 0.85f + cr * 0.15f
            fPitch = fPitch * 0.85f + cp * 0.15f
            val dH = (targetHdg - fHeading + 540) % 360 - 180
            fHeading = (fHeading + dH * 0.1f + 360) % 360
            _state.value = _state.value.copy(roll = fRoll, pitch = fPitch, heading = fHeading, isSimulation = false)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
        }
    }
    override fun onLocationChanged(l: Location) {
        val now = System.currentTimeMillis()
        val altRaw = l.altitude * 3.28084
        val spdKts = if (l.hasSpeed() && l.speed > 0.5f) l.speed * 1.94384f else 0f
        if (lastTimeVsi != 0L) {
            val dt = (now - lastTimeVsi) / 1000f
            if (dt > 0) {
                val instVsi = ((altRaw - lastAltRaw) / dt).toFloat() * 60f
                _state.value = _state.value.copy(vsi = _state.value.vsi * 0.6f + instVsi * 0.4f)
            }
        }
        lastAltRaw = altRaw
        lastTimeVsi = now
        if (_state.value.isDemoMode) return
        
        // --- HARDWARE GPS SMOOTHING (Low-Pass Filter) ---
        // Aplicamos un filtro matemático suave en tiempo real en lugar de una animación de UI.
        // Esto previene el jitter de alta frecuencia pero permite cambios sutiles (ej: subir escaleras).
        val currentAlt = _state.value.altitude - _state.value.kollsmanOffset // Obtenemos el valor puro anterior
        val smoothedAlt = currentAlt * 0.85 + altRaw * 0.15 // 85% viejo, 15% nuevo

        _state.value = _state.value.copy(
            speed = spdKts, altitude = smoothedAlt + _state.value.kollsmanOffset,
            gpsStatus = "GPS: OK", maxSpeed = maxOf(_state.value.maxSpeed, spdKts), maxAlt = maxOf(_state.value.maxAlt, smoothedAlt + _state.value.kollsmanOffset)
        )
    }
}
@Composable
fun SplashScreen() {
    val config = LocalConfiguration.current
    val isTablet = config.smallestScreenWidthDp >= 600
    val titleFs = if (isTablet) 72.sp else 48.sp
    val subTitleFs = if (isTablet) 24.sp else 18.sp
    Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SIX PACK", color=Color.White, fontSize=titleFs, fontWeight=FontWeight.ExtraBold)
            Text("FLIGHT INSTRUMENTS", color=CessnaOrange, fontSize=subTitleFs, fontWeight=FontWeight.Bold, modifier=Modifier.offset(y=(-8).dp))
            Spacer(Modifier.height(40.dp))
            CircularProgressIndicator(color=CessnaOrange, modifier=Modifier.size(if(isTablet) 48.dp else 32.dp), strokeWidth=if(isTablet) 4.dp else 3.dp)
            Spacer(Modifier.height(16.dp))
            Text("CALIBRATING SYSTEMS...", color=Color.Gray, fontSize=if(isTablet) 16.sp else 12.sp)
        }
    }
}
@Composable
fun LegalDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("AVISO LEGAL / CRÉDITOS", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("LEGAL NOTICE / CREDITS", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.Gray)
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(Modifier.verticalScroll(scrollState)) {
                // SECCIÓN ADVERTENCIA (ROJO)
                Text(
                    "ADVERTENCIA: Esta aplicación es únicamente para fines recreativos y educativos. " +
                            "NO DEBE usarse para navegación real o seguridad de vuelo. El desarrollador no se hace responsable " +
                            "por el uso indebido o accidentes derivados del uso de esta herramienta.",
                    color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "WARNING: This app is for recreational and educational use only. " +
                            "DO NOT use for real-world navigation or flight safety. The developer is not responsible " +
                            "for misuse or accidents.",
                    color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Gray.copy(0.3f)))
                Spacer(Modifier.height(16.dp))

                // SECCIÓN NOTA DE USO (BILINGÜE)
                Text("NOTA DE LICENCIA Y USO", color = CessnaOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("LICENSE AND USE NOTE", color = CessnaOrange.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Para asegurar que este proyecto ayude a otros estudiantes y entusiastas de la aviación, he formalizado el uso del código bajo la Licencia MIT.\n\n" +
                            "¿Qué significa esto?\n" +
                            "✅ Puedes ver, estudiar y usar el código para tus proyectos.\n" +
                            "✅ Solo pido que mantengas mi crédito como autor original.\n" +
                            "🛡️ El software se entrega \"tal cual\" para fines recreativos.",
                    fontSize = 12.sp, color = Color.White
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "To ensure this project helps other aviation students and enthusiasts, I have formalized the use of the code under the MIT License.\n\n" +
                            "What does this mean?\n" +
                            "✅ You can view, study and use the code for your projects.\n" +
                            "✅ I only ask that you keep my credit as the original author.\n" +
                            "🛡️ The software is delivered \"as is\" for recreational purposes.",
                    fontSize = 11.sp, color = Color.LightGray
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Gray.copy(0.3f)))
                Spacer(Modifier.height(16.dp))

                // SECCIÓN LICENCIA MIT FORMAL (ESPAÑOL)
                Text("LICENCIA MIT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Copyright (c) 2026 Jerry Agüero Rímola\n\n" +
                            "Por la presente se concede permiso, de forma gratuita, a cualquier persona que obtenga una copia de este software y de los archivos de documentación asociados (el \"Software\"), para utilizar el Software sin restricción, incluyendo, sin limitación, los derechos a usar, copiar, modificar, fusionar, publicar, distribuir, sublicenciar y/o vender copias del Software, sujeto a las siguientes condiciones:\n\n" +
                            "El aviso de copyright anterior y este aviso de permiso se incluirán en todas las copias o partes sustanciales del Software.\n\n" +
                            "EL SOFTWARE SE PROPORCIONA \"TAL CUAL\", SIN GARANTÍA DE NINGÚN TIPO, EXPRESA O IMPLÍCITA, INCLUYENDO, PERO NO LIMITADO A, GARANTÍAS DE COMERCIABILIDAD, IDONEIDAD PARA UN PROPÓSITO PARTICULAR Y NO INFRACCIÓN. EN NINGÚN CASO LOS AUTORES O TITULARES DEL COPYRIGHT SERÁN RESPONSABLES DE NINGUNA RECLAMACIÓN, DAÑOS U OTRA RESPONSABILIDAD, YA SEA EN UNA ACCIÓN DE CONTRATO, AGRAVIO O DE OTRO MODO, QUE SURJA DE, FUERA DE O EN CONEXIÓN CON EL SOFTWARE O EL USO U OTROS TRATOS EN EL SOFTWARE.",
                    fontSize = 10.sp, color = Color.Gray
                )
                Spacer(Modifier.height(16.dp))

                // SECCIÓN MIT LICENSE FORMAL (INGLÉS)
                Text("MIT LICENSE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Copyright (c) 2026 Jerry Agüero Rímola\n\n" +
                            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\n" +
                            "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\n" +
                            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.",
                    fontSize = 10.sp, color = Color.Gray
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Gray.copy(0.3f)))
                Spacer(Modifier.height(16.dp))

                // SECCIÓN CRÉDITOS Y HASHTAGS
                Text("Desarrollado con pasión por Jerry Aguero Rimola & Antigravity AI.", fontSize = 13.sp, color = Color.White)
                Text("Developed with passion by Jerry Aguero Rimola & Antigravity AI.", fontSize = 12.sp, color = Color.White.copy(0.8f))
                Spacer(Modifier.height(8.dp))
                Text("#OpenSource #LicenciaMIT #JerryAguero #Aprendizaje #Aviacion #kotlin", fontSize = 10.sp, color = CessnaOrange)
                Spacer(Modifier.height(8.dp))
                Text("Inspirado en los instrumentos clásicos Cessna 172.", fontSize = 11.sp, color = Color.Gray)
                Text("Inspired by classic Cessna 172 instruments.", fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("ENTENDIDO / UNDERSTOOD", fontWeight = FontWeight.Bold, color = Color(0xFFFFC107))
            }
        },
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White,
        textContentColor = Color.LightGray
    )
}

@Composable
fun FlightDashboard(s: FlightData, instSize: Dp, isLandscape: Boolean, btnFs: TextUnit, fontFactor: Float, isTablet: Boolean, onNightToggle: () -> Unit, onCalibrate: () -> Unit, onKollsmanChange: (Float) -> Unit, onLoggingToggle: () -> Unit, onDemoToggle: () -> Unit) {
    var showLegal by remember { mutableStateOf(false) }
    if (showLegal) LegalDialog { showLegal = false }

    // Generar posiciones una sola vez para ahorrar CPU
    val starPositions = remember {
        val rnd = java.util.Random(10)
        List(2000) { Offset(rnd.nextFloat(), rnd.nextFloat()) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DarkPanel)
            .drawBehind {
                // Dibujar las "estrellas" usando posiciones cacheadas
                val color = Color.Black.copy(0.25f)
                starPositions.forEach { pos ->
                    drawCircle(color, 1.2f, Offset(pos.x * size.width, pos.y * size.height))
                }
            }
    ) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (!isLandscape) {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            InstrumentFrame("AIRSPEED", "KNOTS", instSize, s.isNightMode) { AirspeedGauge(s.speed, s.isNightMode, fontFactor) }
                            InstrumentFrame("ATTITUDE", "VACUUM", instSize, s.isNightMode) { AttitudeGauge(s.pitch, s.roll, s.isNightMode, fontFactor, isTablet) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            InstrumentFrame("ALTIMETER", "FEET", instSize, s.isNightMode) {
                                Box(Modifier.clickable { onDemoToggle() }) {
                                    AltimeterGauge(s.altitude, s.kollsmanOffset, s.isNightMode, fontFactor)
                                    Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) {
                                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable { onKollsmanChange(10f) }, Alignment.Center) { Text("+", color = Color.White, fontSize = 11.sp) }
                                        Spacer(Modifier.height(4.dp))
                                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable { onKollsmanChange(-10f) }, Alignment.Center) { Text("-", color = Color.White, fontSize = 11.sp) }
                                    }
                                }
                            }
                            InstrumentFrame("TURN COORD", "NO PITCH INFO", instSize, s.isNightMode) { TurnGauge(s.roll, s.isNightMode, fontFactor) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            InstrumentFrame("HEADING", "VACUUM", instSize, s.isNightMode) { HeadingGauge(s.heading, s.isNightMode, fontFactor, isTablet) }
                            InstrumentFrame("VSI", "100 FT PER MIN", instSize, s.isNightMode) { VsiGauge(s.vsi, s.isNightMode, fontFactor, isTablet) }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            InstrumentFrame("AIRSPEED", "KNOTS", instSize, s.isNightMode) { AirspeedGauge(s.speed, s.isNightMode, fontFactor) }
                            InstrumentFrame("ATTITUDE", "VACUUM", instSize, s.isNightMode) { AttitudeGauge(s.pitch, s.roll, s.isNightMode, fontFactor, isTablet) }
                            InstrumentFrame("ALTIMETER", "FEET", instSize, s.isNightMode) {
                                Box(Modifier.clickable { onDemoToggle() }) {
                                    AltimeterGauge(s.altitude, s.kollsmanOffset, s.isNightMode, fontFactor)
                                    Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) {
                                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable { onKollsmanChange(10f) }, Alignment.Center) { Text("+", color = Color.White, fontSize = 12.sp) }
                                        Spacer(Modifier.height(8.dp))
                                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable { onKollsmanChange(-10f) }, Alignment.Center) { Text("-", color = Color.White, fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            InstrumentFrame("TURN COORD", "NO PITCH", instSize, s.isNightMode) { TurnGauge(s.roll, s.isNightMode, fontFactor) }
                            InstrumentFrame("HEADING", "VACUUM", instSize, s.isNightMode) { HeadingGauge(s.heading, s.isNightMode, fontFactor, isTablet) }
                            InstrumentFrame("VSI", "100 FT/M", instSize, s.isNightMode) { VsiGauge(s.vsi, s.isNightMode, fontFactor, isTablet) }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                    ProButton(if (s.isNightMode) "DAY" else "NIGHT", if (s.isNightMode) Color.Yellow else NightRed, btnFs, onNightToggle)
                    Spacer(Modifier.width(10.dp))
                    ProButton("LEVEL", CessnaOrange, btnFs, onCalibrate)
                    Spacer(Modifier.width(10.dp))
                    ProButton(if (s.isLogging) "STOP LOG" else "START LOG", if (s.isLogging) Color.Red else Color.Green, btnFs, onLoggingToggle)
                    Spacer(Modifier.width(10.dp))
                    ProButton("INFO", Color.White, btnFs, { showLegal = true })
                }
                if (s.isLogging) {
                    Spacer(Modifier.height(4.dp))
                    Text("REC: MAX ${s.maxSpeed.toInt()}kts | ${s.maxAlt.toInt()}ft", color = NightRed, fontSize = (btnFs.value * 0.9f).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
fun ProButton(text: String, color: Color, fontSize: TextUnit, onClick: () -> Unit) {
    val paddingSide = if (fontSize.value > 10f) 18.dp else 12.dp
    val paddingVer = if (fontSize.value > 10f) 12.dp else 8.dp
    Box(Modifier.clip(CircleShape).background(color.copy(0.15f)).clickable { onClick() }.padding(horizontal=paddingSide, vertical=paddingVer)) {
        Text(text, color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}
@Composable
fun InstrumentFrame(label: String, sublabel: String, frameSize: Dp, isNight: Boolean, content: @Composable () -> Unit) {
    val themeColor = if (isNight) NightRed else Color.White
    val frameBg = if (isNight) Color(0xFF1A1A1A) else Color(0xFF222222)
    val circleSize = frameSize * 0.94f
    val screwOffset = (frameSize.value * 0.42f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(frameSize).background(frameBg, RoundedCornerShape(frameSize * 0.05f))) {
            Box(Modifier.size(circleSize).align(Alignment.Center).background(
                Brush.linearGradient(listOf(if(isNight) Color(0xFF300000) else Color(0xFF404040), Color.Black)), CircleShape
            ).padding(2.dp).background(Color.Black, CircleShape).clip(CircleShape)) { content() }
            listOf(Offset(-screwOffset,-screwOffset), Offset(screwOffset,-screwOffset), Offset(-screwOffset,screwOffset), Offset(screwOffset,screwOffset)).forEach { pos ->
                Canvas(Modifier.align(Alignment.Center).offset(pos.x.dp, pos.y.dp).size(frameSize * 0.06f)) {
                    val sc = size.minDimension
                    drawCircle(if(isNight) Color(0xFF400000) else Color(0xFF555555))
                    drawLine(Color.Black, Offset(sc*0.25f, sc*0.5f), Offset(sc*0.75f, sc*0.5f), sc*0.12f)
                    drawLine(Color.Black, Offset(sc*0.5f, sc*0.25f), Offset(sc*0.5f, sc*0.75f), sc*0.12f)
                }
            }
        }
        Text(label, color = themeColor, fontSize = (frameSize.value * 0.052f).sp, fontWeight = FontWeight.ExtraBold)
        Text(sublabel, color = themeColor.copy(0.6f), fontSize = (frameSize.value * 0.038f).sp)
    }
}
@Composable
fun AirspeedGauge(kts: Float, isNight: Boolean, fontFactor: Float) {
    val c = if (isNight) NightRed else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2 * 0.9f
        if (!isNight) {
            drawArc(Color.White, 150f, 60f, false, Offset(center.x-r, center.y-r), Size(r*2, r*2), style = Stroke(8f))
            drawArc(Color.Green, 150f, 150f, false, Offset(center.x-r*0.95f, center.y-r*0.95f), Size(r*1.9f, r*1.9f), style = Stroke(8f))
            drawArc(Color.Yellow, 300f, 50f, false, Offset(center.x-r*0.95f, center.y-r*0.95f), Size(r*1.9f, r*1.9f), style = Stroke(8f))
            drawLine(Color.Red, Offset(center.x+r*0.92f*cos(Math.toRadians(350.0)).toFloat(), center.y+r*0.92f*sin(Math.toRadians(350.0)).toFloat()), Offset(center.x+r*cos(Math.toRadians(350.0)).toFloat(), center.y+r*sin(Math.toRadians(350.0)).toFloat()), 8f)
        }
        for (i in 0..240 step 20) {
            val a = i * 1.3f + 120f ; val rad = Math.toRadians(a.toDouble())
            drawLine(c, Offset(center.x+r*0.82f*cos(rad).toFloat(), center.y+r*0.82f*sin(rad).toFloat()), Offset(center.x+r*cos(rad).toFloat(), center.y+r*sin(rad).toFloat()), 2.5f)
            if (i % 40 == 0) drawIntoCanvas {
                val p = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD }
                it.nativeCanvas.drawText(i.toString(), center.x+r*0.62f*cos(rad).toFloat(), center.y+r*0.62f*sin(rad).toFloat()+12f*size.minDimension/200f, p)
            }
        }
        rotate(degrees = kts * 1.3f + 120f, pivot = center) { drawLine(c, center, Offset(center.x + r * 0.92f, center.y), 6f, StrokeCap.Round) }
        drawCircle(if(isNight) MaroonRed else Color.Gray, 6f, center)
    }
}
@Composable
fun AttitudeGauge(pitch: Float, roll: Float, isNight: Boolean, fontFactor: Float, isTablet: Boolean) {
    val c = if (isNight) NightRed else Color.White
    val sky = if (isNight) NightSky else SkyBlue
    val ground = if (isNight) NightGround else GroundBrown
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2
        clipPath(Path().apply { addOval(Rect(center, r)) }) {
            rotate(degrees = -roll, pivot = center) {
                val off = pitch * 4.5f
                drawRect(sky, Offset(0f, -size.height + center.y + off), Size(size.width, size.height*2))
                drawRect(ground, Offset(0f, center.y + off), Size(size.width, size.height))
                for (i in listOf(-60, -30, 0, 30, 60)) { drawLine(c.copy(0.25f), Offset(center.x+i, center.y+off), Offset(center.x+i*2.2f, size.height), 2f) }
                drawLine(c, Offset(0f, center.y+off), Offset(size.width, center.y+off), 4f)
                for (i in listOf(-20, -10, 10, 20)) {
                    val y = center.y + off - (i * 4.5f)
                    drawLine(c, Offset(center.x-35f*size.minDimension/200f, y), Offset(center.x+35f*size.minDimension/200f, y), 3.5f)
                    drawIntoCanvas {
                        val hOff = r * 0.52f
                        val pSize = if (isTablet) size.minDimension*fontFactor*0.8f else size.minDimension*fontFactor*0.85f
                        val p = Paint().apply { color=c.toArgb(); textSize=pSize; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD }
                        it.nativeCanvas.drawText(abs(i).toString(), center.x-hOff, y+6f*size.minDimension/200f, p)
                        it.nativeCanvas.drawText(abs(i).toString(), center.x+hOff, y+6f*size.minDimension/200f, p)
                    }
                }
            }
        }
        rotate(degrees = -roll, pivot = center) {
            listOf(10, 20, 30, 45, 60).forEach { angle ->
                val rR = Math.toRadians((angle - 90).toDouble()); val rL = Math.toRadians((-angle - 90).toDouble())
                drawLine(c, Offset(center.x+(r*0.85f)*cos(rR).toFloat(), center.y+(r*0.85f)*sin(rR).toFloat()), Offset(center.x+r*cos(rR).toFloat(), center.y+r*sin(rR).toFloat()), 3.5f)
                drawLine(c, Offset(center.x+(r*0.85f)*cos(rL).toFloat(), center.y+(r*0.85f)*sin(rL).toFloat()), Offset(center.x+r*cos(rL).toFloat(), center.y+r*sin(rL).toFloat()), 3.5f)
            }
        }
        drawArc(c, 210f, 120f, false, style = Stroke(2.5f), topLeft = Offset(center.x-r*0.85f, center.y-r*0.85f), size = Size(r*1.7f, r*1.7f))
        val tri = Path().apply { moveTo(center.x, center.y-r+12f); lineTo(center.x-10f, center.y-r+32f); lineTo(center.x+10f, center.y-r+32f); close() }
        drawPath(tri, if(isNight) NightRed else Color.White)
        val wing = Path().apply { moveTo(center.x-70f, center.y-2f); lineTo(center.x-30f, center.y-2f); lineTo(center.x-30f, center.y+10f) }
        drawPath(wing, if(isNight) NightRed else CessnaOrange, style = Stroke(12f, cap = StrokeCap.Round))
        val wingR = Path().apply { moveTo(center.x+70f, center.y-2f); lineTo(center.x+30f, center.y-2f); lineTo(center.x+30f, center.y+10f) }
        drawPath(wingR, if(isNight) NightRed else CessnaOrange, style = Stroke(12f, cap = StrokeCap.Round))
        drawCircle(if(isNight) NightRed else CessnaOrange, 7f, center)
    }
}
@Composable
fun AltimeterGauge(alt: Double, kOffset: Float, isNight: Boolean, fontFactor: Float) {
    val c = if (isNight) NightRed else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2 * 0.9f
        for (i in 0..9) {
            val a = Math.toRadians((i*36f-90f).toDouble())
            drawIntoCanvas {
                val p = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor*1.1f; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD }
                it.nativeCanvas.drawText(i.toString(), center.x+r*0.82f*cos(a).toFloat(), center.y+r*0.82f*sin(a).toFloat()+10f*size.minDimension/200f, p)
            }
            for (j in 1..4) {
                val sa = Math.toRadians((i*36f + j*7.2f - 90f).toDouble())
                drawLine(c, Offset(center.x+r*0.92f*cos(sa).toFloat(), center.y+r*0.92f*sin(sa).toFloat()), Offset(center.x+r*cos(sa).toFloat(), center.y+r*sin(sa).toFloat()), 1.5f)
            }
        }
        val drumW = r * 0.82f
        val drumH = r * 0.42f // Total height of the box
        // Raise the box slightly and center it better
        val drumRect = Rect(center.x - drumW/2, center.y - r * 0.42f, center.x + drumW/2, center.y + r * 0.02f)
        val centerY = (drumRect.top + drumRect.bottom) / 2
        drawRoundRect(Color.Black, drumRect.topLeft, drumRect.size, CornerRadius(6f, 6f))
        drawRoundRect(c.copy(0.4f), drumRect.topLeft, drumRect.size, CornerRadius(6f, 6f), style = Stroke(2.5f))
        clipRect(drumRect.left, drumRect.top, drumRect.right, drumRect.bottom) {
            val altSafe = alt.coerceAtLeast(0.0)
            val digitWidth = drumW / 6f // 5 digits + 1 comma
            val vals = IntArray(5)
            val offsets = FloatArray(5)
            var lastPull = (altSafe % 1.0).toFloat()
            for (i in 0..4) {
                val divisor = 10.0.pow(i.toDouble())
                val digit = ((altSafe / divisor).toInt() % 10)
                vals[4 - i] = digit
                offsets[4 - i] = lastPull
                lastPull = ((digit.toDouble() + lastPull - 9.0).coerceIn(0.0, 1.0)).toFloat()
            }
            val p = Paint().apply {
                color=android.graphics.Color.WHITE;
                textSize=drumH*0.75f;
                textAlign=Paint.Align.CENTER;
                typeface=Typeface.MONOSPACE;
                isFakeBoldText=true
            }
            val metrics = p.fontMetrics
            val baselineOffset = (metrics.ascent + metrics.descent) / 2
            val scrollStep = drumH * 0.9f
            for (idx in 0..4) {
                val digit = vals[idx]
                val offset = offsets[idx]
                // X Position: Index 0,1 then Comma at 2, then 2,3,4 at slots 3,4,5
                val slotIdx = if (idx > 1) idx + 1 else idx
                val x = drumRect.left + slotIdx * digitWidth + digitWidth/2
                // Vertical position: Centered when offset is 0
                val drawY = centerY - baselineOffset + (offset * scrollStep)
                drawIntoCanvas {
                    it.nativeCanvas.drawText(digit.toString(), x, drawY, p)
                    if (offset > 0.001f) {
                        it.nativeCanvas.drawText(((digit + 1) % 10).toString(), x, drawY - scrollStep, p)
                    }
                }
            }
            drawIntoCanvas {
                val cp = Paint().apply { color=android.graphics.Color.WHITE; textSize=drumH*0.75f; textAlign=Paint.Align.CENTER }
                it.nativeCanvas.drawText(",", drumRect.left + 2 * digitWidth + digitWidth/2, centerY - baselineOffset, cp)
            }
        }
        val kY = center.y + r*0.48f
        val inhg = 29.92 + (kOffset / 1000.0)
        val mb = (inhg * 33.8639).toInt()
        drawIntoCanvas {
            val p = Paint().apply { color=c.copy(0.7f).toArgb(); textSize=r*0.12f; textAlign=Paint.Align.CENTER }
            it.nativeCanvas.drawText("MB", center.x - r*0.45f, kY - r*0.14f, p)
        }
        drawRect(Color.Black, Offset(center.x - r*0.65f, kY), Size(r*0.42f, r*0.20f))
        drawIntoCanvas {
            val p = Paint().apply { color=android.graphics.Color.WHITE; textSize=r*0.16f; textAlign=Paint.Align.CENTER; typeface=Typeface.MONOSPACE }
            it.nativeCanvas.drawText(mb.toString(), center.x - r*0.44f, kY + r*0.16f, p)
        }
        drawIntoCanvas {
            val p = Paint().apply { color=c.copy(0.7f).toArgb(); textSize=r*0.12f; textAlign=Paint.Align.CENTER }
            it.nativeCanvas.drawText("IN HG", center.x + r*0.45f, kY - r*0.14f, p)
        }
        drawRect(Color.Black, Offset(center.x + r*0.23f, kY), Size(r*0.45f, r*0.20f))
        drawIntoCanvas {
            val p = Paint().apply { color=android.graphics.Color.WHITE; textSize=r*0.16f; textAlign=Paint.Align.CENTER; typeface=Typeface.MONOSPACE }
            it.nativeCanvas.drawText(String.format("%.2f", inhg), center.x + r*0.45f, kY + r*0.16f, p)
        }
        rotate(degrees = (alt % 1000).toFloat()*0.36f - 90f, pivot = center) {
            val needlePath = Path().apply {
                moveTo(center.x + r*0.12f, center.y)
                lineTo(center.x + r*0.92f, center.y - 5f)
                lineTo(center.x + r*0.92f, center.y + 5f)
                close()
            }
            drawPath(needlePath, c)
            drawLine(c, center, Offset(center.x + r*0.92f, center.y), 5f, StrokeCap.Round)
        }
        drawCircle(Color.Black, r*0.1f, center)
        drawCircle(if(isNight) MaroonRed else Color.LightGray, 7f, center)
    }
}
@Composable
fun TurnGauge(roll: Float, isNight: Boolean, fontFactor: Float) {
    val c = if (isNight) NightRed else Color.White
    val boxColor = if (isNight) NightRed.copy(0.2f) else Color.White.copy(0.2f)
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2
        val refOffset = r * 0.62f
        listOf(-20f, 20f).forEach { ang ->
            rotate(degrees = ang, pivot = center) {
                drawLine(c, Offset(center.x - refOffset, center.y - 4f), Offset(center.x - refOffset, center.y + 4f), 3f)
                drawLine(c, Offset(center.x + refOffset, center.y - 4f), Offset(center.x + refOffset, center.y + 4f), 3f)
            }
        }
        val wingSpan = r * 0.60f
        rotate(degrees = roll.coerceIn(-25f, 25f), pivot = center) {
            drawLine(c, Offset(center.x - wingSpan, center.y - 2f), Offset(center.x + wingSpan, center.y - 2f), 8f, StrokeCap.Round)
            drawLine(c, center, Offset(center.x, center.y - (r * 0.15f)), 8f)
        }
        val boxWidth = r * 0.90f
        val boxHeight = r * 0.20f
        val boxY = center.y + (r * 0.40f)
        drawArc(color = boxColor, startAngle = 45f, sweepAngle = 90f, useCenter = false, topLeft = Offset(center.x - boxWidth/2, boxY), size = Size(boxWidth, boxHeight * 2.5f), style = Stroke(width = boxHeight, cap = StrokeCap.Round))
        val markOff = r * 0.12f
        drawLine(c, Offset(center.x - markOff, boxY + 2f), Offset(center.x - markOff, boxY + boxHeight), 2f)
        drawLine(c, Offset(center.x + markOff, boxY + 2f), Offset(center.x + markOff, boxY + boxHeight), 2f)
        val bX = (roll * 0.55f).coerceIn(-(boxWidth*0.4f), (boxWidth*0.4f))
        drawCircle(c, r * 0.09f, Offset(center.x + bX, boxY + boxHeight/2))
        drawIntoCanvas {
            val p = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor*0.8f; typeface=Typeface.DEFAULT_BOLD }
            it.nativeCanvas.drawText("L", center.x - r * 0.72f, boxY + boxHeight * 0.5f, p)
            it.nativeCanvas.drawText("R", center.x + r * 0.72f, boxY + boxHeight * 0.5f, p)
            val p2 = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor*0.55f; textAlign=Paint.Align.CENTER }
            it.nativeCanvas.drawText("2 MIN.", center.x, boxY + boxHeight * 2.2f, p2)
        }
    }
}
@Composable
fun HeadingGauge(hdg: Float, isNight: Boolean, fontFactor: Float, isTablet: Boolean) {
    val c = if (isNight) NightRed else Color.White ; val card = if (isNight) NightRed else CessnaOrange
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2 * 0.95f
        rotate(degrees = -hdg, pivot = center) {
            for (i in 0..35) {
                val ang = Math.toRadians((i*10f-90f).toDouble()) ; val isM = i % 3 == 0
                drawLine(c, Offset(center.x+r*0.84f*cos(ang).toFloat(), center.y+r*0.84f*sin(ang).toFloat()), Offset(center.x+r*cos(ang).toFloat(), center.y+r*sin(ang).toFloat()), if(isM) 4.5f else 2.5f)
                if (isM) {
                    val label = when(i) { 0->"N"; 9->"E"; 18->"S"; 27->"W"; else->i.toString() }
                    drawIntoCanvas { d ->
                        val textPosRadius = if (isTablet) r - 28f*size.minDimension/200f else r - 24f*size.minDimension/200f
                        d.save(); d.rotate(i*10f, center.x+textPosRadius*cos(ang).toFloat(), center.y+textPosRadius*sin(ang).toFloat())
                        val p = Paint().apply { color=if(i%9==0 && !isNight) android.graphics.Color.YELLOW else c.toArgb(); textSize=size.minDimension*fontFactor; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD }
                        d.nativeCanvas.drawText(label, center.x+textPosRadius*cos(ang).toFloat(), center.y+textPosRadius*sin(ang).toFloat()+12f*size.minDimension/200f, p)
                        d.restore()
                    }
                }
            }
        }
        val ship = Path().apply { moveTo(center.x, center.y-48f); lineTo(center.x-38f, center.y+28f); lineTo(center.x, center.y+12f); lineTo(center.x+38f, center.y+28f); close() }
        drawPath(ship, c, style = Stroke(4f))
        drawRect(card, Offset(center.x-4.5f, center.y-r), Size(9f, 16f))
    }
}
@Composable
fun VsiGauge(vsi: Float, isNight: Boolean, fontFactor: Float, isTablet: Boolean) {
    val c = if (isNight) NightRed else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2 * 0.9f
        listOf(-20, -15, -10, -5, 0, 5, 10, 15, 20).forEach { i ->
            val ang = 180f + (i/20f*160f)
            val rad = Math.toRadians(ang.toDouble())
            val len = if (i % 5 == 0) r*0.15f else r*0.08f
            drawLine(c, Offset(center.x+(r-len)*cos(rad).toFloat(), center.y+(r-len)*sin(rad).toFloat()), Offset(center.x+r*cos(rad).toFloat(), center.y+r*sin(rad).toFloat()), if(i%5==0) 4f else 2f)
            val radText = if (isTablet) r - 36f*size.minDimension/200f else r - 28f*size.minDimension/200f
            val vOff = when { i < 0 -> 0f; i == 0 -> 6f; else -> 12f }
            if (i % 5 == 0) drawIntoCanvas {
                val p = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor*1.05f; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD }
                it.nativeCanvas.drawText(abs(i).toString(), center.x + radText*cos(rad).toFloat(), center.y + radText*sin(rad).toFloat() + vOff*size.minDimension/200f, p)
            }
        }
        drawIntoCanvas {
            val p = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor*0.8f; textAlign=Paint.Align.CENTER }
            it.nativeCanvas.drawText("UP", center.x - r*0.32f, center.y - r*0.14f, p)
            it.nativeCanvas.drawText("DN", center.x - r*0.32f, center.y + r*0.25f, p)
            val p2 = Paint().apply { color=c.toArgb(); textSize=size.minDimension*fontFactor*0.56f; textAlign=Paint.Align.CENTER }
            it.nativeCanvas.drawText("VERTICAL", center.x+r*0.28f, center.y-r*0.14f, p2)
            it.nativeCanvas.drawText("SPEED", center.x+r*0.32f, center.y+r*0.12f, p2)
        }
        val needleA = 180f + (vsi.coerceIn(-2000f, 2000f)/2000f*160f)
        rotate(degrees = needleA, pivot = center) { drawLine(c, center, Offset(center.x+r*0.9f, center.y), 7f, StrokeCap.Round) }
        drawCircle(if(isNight) MaroonRed else Color.Gray, 8f, center)
    }
}