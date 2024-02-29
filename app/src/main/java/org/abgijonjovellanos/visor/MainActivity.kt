package org.abgijonjovellanos.visor

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.abgijonjovellanos.visor.ui.theme.ABGijonJovellanosTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import android.util.Base64
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.State
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ABGijonJovellanosTheme {
                AppContent(this)
            }
        }
    }
}

@Composable
fun AppContent(activity: Activity) {
    // Estado para controlar qué pantalla mostrar
    var currentScreen by remember { mutableStateOf("Splash") }
    // Estado para la imagen recibida
    val imageBitmapState = remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // WebSocket client
    val webSocketClient = remember { WebSocketClient() }

    when (currentScreen) {
        "Splash" -> SplashScreen(onTimeout = {
            // Inicializar la conexión WebSocket después de la SplashScreen
            webSocketClient.connect(activity, "wss://dou-server.duckdns.org:9999", imageBitmapState)
            currentScreen = "Main"
        })
        "Main" -> MainScreen(onButtonClicked = { message ->
            if (message == "BALL") {
                webSocketClient.sendMessage(message)
                currentScreen = "ImageScreen"
            } else {
                webSocketClient.sendMessage(message)
                currentScreen = "ListScreen"
            }
        })
        "ImageScreen" -> imageBitmapState.value?.let { bitmap ->
            ImageScreen(bitmap, onBack = {
                webSocketClient.sendMessage("VOLVER")
                currentScreen = "Main"
            })
        }
        "ListScreen" -> ListScreen(velocities = webSocketClient.topVelocities, onSelectVelocity = { velocity ->
            webSocketClient.requestImageForVelocity(velocity)
            currentScreen = "ImageScreen"
        })
    }
}

@Composable
fun ListScreen(velocities: List<Velocity>, onSelectVelocity: (Velocity) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Top 10 Velocidades",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn {
            items(velocities) { velocity ->
                VelocityItem(velocity, onSelectVelocity)
            }
        }
    }
}

@Composable
fun VelocityItem(velocity: Velocity, onSelectVelocity: (Velocity) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectVelocity(velocity) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "Velocidad: ${velocity.speed} km/h, Fecha: ${velocity.date}")
        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_forward),
            contentDescription = "Detalle",
            modifier = Modifier.size(24.dp)
        )
    }
}

class WebSocketClient {
    private var webSocket: WebSocket? = null
    var topVelocities: List<Velocity> = listOf()

    fun connect(activity: Activity, url: String, imageBitmapState: MutableState<android.graphics.Bitmap?>) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.startsWith("TOP_VELOCITIES:") -> {
                        SoundPlayer(activity).playSound(R.raw.new_velocity_sound)
                    }

                    text.startsWith("IMAGE_VELOCITY:") -> {
                        // Procesar y mostrar la imagen de una velocidad específica
                    }

                    text.startsWith("NEW_TOP_VELOCITY:") -> {
                        SoundPlayer(activity).playSound(R.raw.new_velocity_sound)
                    }

                    else -> {
                        // Manejar la imagen procesada regularmente
                        val imageBytes = Base64.decode(text, Base64.DEFAULT)
                        val decodedImage =
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        activity.runOnUiThread {
                            imageBitmapState.value = decodedImage
                        }
                    }
                }
            }
        })
    }

    fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    fun requestTopVelocities() {
        webSocket?.send("REQUEST_TOP_VELOCITIES")
    }

    fun requestImageForVelocity(velocity: Velocity) {
        webSocket?.send("REQUEST_IMAGE_VELOCITY:${velocity.imageName}") // Asegúrate de que Velocity tenga un identificador único
    }
}

@Composable
fun ImageScreen(imageBitmap: Bitmap, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        WebSocketImage(imageBitmap = imageBitmap) // Usar directamente el bitmap
        Button(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Text("VOLVER")
        }
    }
}

@Composable
fun WebSocketImage(imageBitmap: Bitmap) {
    Image(
        bitmap = imageBitmap.asImageBitmap(), contentDescription = null,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    // Mostrar el logo durante 3 segundos y luego ejecutar onTimeout
    LaunchedEffect(Unit) {
        delay(3000)
        onTimeout()
    }

    // Mientras tanto, mostrar el logo
    Image(
        painter = painterResource(id = R.drawable.logo),
        contentDescription = "Logo",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
    )
}

@Composable
fun MainScreen(onButtonClicked: (String) -> Unit) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp

    // Determinar si la pantalla está en modo horizontal o vertical
    val isLandscape = screenWidth > screenHeight

    if (isLandscape) {
        // Pantalla horizontal: Botones uno al lado del otro
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = { onButtonClicked("BALL") }, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = R.drawable.boton3),
                    contentDescription = "Jugador tirando a puerta",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = { onButtonClicked("BEST") }, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = R.drawable.boton4),
                    contentDescription = "Mejores lanzamientos",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else {
        // Pantalla vertical: Botones uno encima del otro
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = { onButtonClicked("BALL") }, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = R.drawable.boton3),
                    contentDescription = "Jugador tirando a puerta",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onButtonClicked("BEST") }, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = R.drawable.boton4),
                    contentDescription = "Mejores lanzamientos",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

data class Velocity(val speed: Float, val date: String, val imageName: String)

class SoundPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun playSound(resourceId: Int) {
        // Asegurarse de que no haya otra reproducción en curso
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }

        mediaPlayer = MediaPlayer.create(context, resourceId)
        mediaPlayer?.start()

        // Opcional: Liberar el recurso una vez que el sonido haya terminado de reproducirse
        /*mediaPlayer?.setOnCompletionListener {
            it.release()
        }*/
    }
}