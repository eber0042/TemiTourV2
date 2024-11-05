package com.temi.temiTour

import android.content.Context
import android.media.MediaPlayer
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(
            ) {
                MainPage(this)
            }
        }
    }
}

class AudioPlayer(context: Context, private val mediaResId: Int) {
    private val mediaPlayer: MediaPlayer = MediaPlayer.create(context, mediaResId)

    fun play() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
        }
    }

    fun stop() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            mediaPlayer.seekTo(0)
        }
    }

    fun release() {
        mediaPlayer.release()
    }
}

@Composable
fun MainPage(context: Context) {
    val viewModel: MainViewModel = hiltViewModel()
    val context = LocalContext.current

    val gifEnabledLoader = ImageLoader.Builder(context)
        .components {
            if ( SDK_INT >= 28 ) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }.build()

    AsyncImage(
        model = R.drawable.idle, // URL or resource of the GIF
        contentDescription = "GIF image",
        imageLoader = gifEnabledLoader,
        modifier = Modifier.fillMaxSize(), // Fill the whole screen
        contentScale = ContentScale.Crop // Crop to fit the entire screen
    )
}