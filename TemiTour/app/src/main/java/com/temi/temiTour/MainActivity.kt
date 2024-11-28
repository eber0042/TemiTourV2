package com.temi.temiTour

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.model.Model
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if the permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            // If the permission is not granted, request it
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            return // Exit early if permission is not granted
        }

        enableEdgeToEdge()
        setContent {
            Surface(
            ) {
                MainPage(this, this)
            }
        }
    }
}

class AudioPlayer(context: Context, private val mediaResId: Int) {
    private val mediaPlayer: MediaPlayer = MediaPlayer.create(context, mediaResId).apply {
        isLooping = true  // Set looping to true so it repeats automatically
    }

    // Function to play the audio
    fun play() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
        }
    }

    // Function to stop the audio and reset to the beginning
    fun stop() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            mediaPlayer.seekTo(0)
        }
    }

    // Function to release the media player resources
    fun release() {
        mediaPlayer.release()
    }

    // Function to set the volume (value between 0.0f and 1.0f for both left and right channels)
    fun setVolume(leftVolume: Float, rightVolume: Float) {
        mediaPlayer.setVolume(leftVolume, rightVolume)
    }

    // Function to set the volume to a specific level (for example, 0.5 for 50% volume)
    fun setVolumeLevel(volumeLevel: Float) {
        val volume = volumeLevel.coerceIn(0.0f, 1.0f)  // Ensure the value is between 0.0 and 1.0
        mediaPlayer.setVolume(
            volume,
            volume
        )  // Set the same volume for both left and right channels
    }

    // Function to play the audio only once
    fun playOnce() {
        // Reset looping and seek to the beginning
        mediaPlayer.isLooping = false
        mediaPlayer.seekTo(0)

        // Start the audio
        mediaPlayer.start()

        // Stop the media player when playback is complete
        mediaPlayer.setOnCompletionListener {
            it.seekTo(0) // Reset to the beginning
            it.setOnCompletionListener(null) // Remove listener to avoid memory leaks
        }
    }
}

@Composable
fun MainPage(context: Context, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    val viewModel: MainViewModel = hiltViewModel()
    val themeMusic = AudioPlayer(context, R.raw.greeting1)
    val waitMusic = AudioPlayer(context, R.raw.wait_music)
    val barkSound = AudioPlayer(context, R.raw.bark) // Load the barking sound
    themeMusic.setVolumeLevel(0.15F)

    // Track changes in the imageResource and shouldPlayGif state
    val shouldPlayGif by viewModel.shouldPlayGif.collectAsState()  // Assuming you're using StateFlow or LiveData for shouldPlayGif
    val imageResource by viewModel.image.collectAsState()  // Assuming you're using StateFlow or LiveData for imageResource
    val gifResource by viewModel.gif.collectAsState()  // Assuming you're using StateFlow or LiveData for imageResource

    LaunchedEffect(Unit) {
        while (true) {
            if (viewModel.playMusic) {
                themeMusic.play()
            } else {
                themeMusic.stop()
            }

            if (viewModel.playWaitMusic) {
                waitMusic.play()
            } else {
                waitMusic.stop()
            }
            delay(100L)
        }
    }

    // Determine the image resource based on shouldPlayGif
    val gifEnabledLoader = ImageLoader.Builder(LocalContext.current)
        .components {
            if (SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    // Conditionally set the image resource based on shouldPlayGif
    val image = if (shouldPlayGif) gifResource else imageResource

    AsyncImage(
        model = image, // Resource or URL of the image/GIF
        contentDescription = if (shouldPlayGif) "Animated GIF" else "Static Image",
        imageLoader = gifEnabledLoader,
        modifier = Modifier
            .fillMaxSize() // Fill the whole screen
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (gifResource == R.drawable.sleep1) {
                            // Play the barking sound when gifResource is R.raw.sleepy
                            barkSound.playOnce()
                        } else {
                            // Continue with normal behavior
                            // You can define additional actions here if needed
                        }
                    }
                )
            },
        contentScale = ContentScale.Crop // Crop to fit the entire screen
    )
}