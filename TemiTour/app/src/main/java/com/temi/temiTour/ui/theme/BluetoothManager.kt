package com.temi.temiTour.ui.theme

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
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Singleton
import kotlin.concurrent.thread
import java.util.Timer
import kotlin.concurrent.schedule

@Module
@InstallIn(SingletonComponent::class)
object BlueModule {
    @Provides
    @Singleton
    fun provideBlue() = BluetoothManager()
}

// Connection may need time to stabilse.
class BluetoothManager {
//    private val _bluetoothState = MutableLiveData<Boolean?>(null)
//    val bluetoothState: LiveData<Boolean?> = _bluetoothState

    // true is for opening a gate in the viewmodel while false for the manager
    var gate: Boolean? = null
    var isConnected = false
    var shouldDisconnectFromServer = false
    private val timer = Stopwatch()

    private var messageToSend = "Hello from client!"

    public fun changeBlueState(state: Boolean?) {
        gate = state  // Use postValue instead of setValue
    }
    //******************************************* Connect Client:

    // check out if there is a timeout for the socket
// Function to handle Bluetooth communication
    @SuppressLint("MissingPermission")
    private suspend fun handleConnectionClient(socket: BluetoothSocket): Boolean {
        val outputStream = socket.outputStream
        val inputStream = socket.inputStream
        var isConnected = true // Connection status

        try {
            var sent = true
            while (isConnected) {
                // Write data to the server
                val messageToSend = when {
                    gate == null && sent -> "IDLE"
                    gate == false -> {
                        sent = false
                        "END"
                    }
                    else -> "ERROR"
                }
                outputStream.write(messageToSend.toByteArray())
                Log.i("BluetoothClient", "Sent: $messageToSend")

                if (messageToSend == "END") {
                    sent = true
                    gate = null
                }

                // Read response from server
                val buffer = ByteArray(1024)
                val bytes = inputStream.read(buffer)
                val response = String(buffer, 0, bytes)
                Log.i("BluetoothClient", "Received: $response")

                if (response == "END") {
                    gate = true
                }
            }
        } catch (e: IOException) {
            isConnected = false
            Log.e("BluetoothClient", "Communication error: ${e.message}")
        } finally {
            try {
                socket.close()
                Log.i("BluetoothClient", "Socket closed.")
            } catch (e: IOException) {
                Log.e("BluetoothClient", "Error closing socket: ${e.message}")
            }
        }

        return false // Signal that the connection is no longer active
    }

    // Function to connect to a Bluetooth device
    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        return try {
            val socket = device.createInsecureRfcommSocketToServiceRecord(
                UUID.fromString("27c32b80-3a56-4331-8667-718a84776241") // Replace with your UUID
            )
            socket.connect()
            Log.i("BluetoothClient", "Connected to ${device.name} - ${device.address}")

            // Pass the socket to handle connection
            handleConnectionClient(socket)
        } catch (e: IOException) {
            Log.e("BluetoothClient", "Error connecting to device: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startBluetoothClient(context: Context) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BluetoothClient", "Bluetooth is not enabled or not available.")
            return
        }

        // Replace with the name of your target device
        val targetDeviceName = "NYP BOA"
        var targetDevice: BluetoothDevice? = null

        // Look for the target device in paired devices
        val pairedDevices = bluetoothAdapter.bondedDevices
        for (device in pairedDevices) {
            if (device.name == targetDeviceName) {
                targetDevice = device
                break
            }
        }

        if (targetDevice == null) {
            Log.e("BluetoothClient", "Target device $targetDeviceName not found in paired devices.")
            return
        }

        while (true) {
            try {
                Log.i("BluetoothClient", "Attempting to connect to ${targetDevice.name}...")
                val isConnected = withContext(Dispatchers.IO) {
                    connectToDevice(targetDevice)
                }

                if (isConnected) {
                    Log.i("BluetoothClient", "Connection established. Communication started.")
                    break // Exit the loop if the connection is successful
                } else {
                    Log.e("BluetoothClient", "Connection attempt failed. Retrying...")
                }

            } catch (e: Exception) {
                Log.e("BluetoothClient", "Error during connection attempt: ${e.message}")
            }

            delay(3000) // Wait before retrying
        }
    }

    //******************************************* Connect Server:
    @SuppressLint("MissingPermission")
    private fun handleConnectionServer(socket: BluetoothSocket) {
        try {
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream

            var sent = true

            while (true) {
                try {


                    // Read data sent by the client
                    val buffer = ByteArray(1024)
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break // Break the loop if the connection is closed
                    val receivedMessage = String(buffer, 0, bytesRead)
                    if (receivedMessage == "END") {gate = true; Log.i("BluetoothServer", "Received: $receivedMessage")}

                    // Respond to the client
                    if (gate == null && sent == true) {
                        messageToSend = "IDLE"
                    } else if (gate == false) {
                        messageToSend = "END"
                        gate = null
                        sent == false
                    } else {
                        messageToSend = "ERROR"
                    }
                    outputStream.write(messageToSend.toByteArray())
                    if (messageToSend == "END") {sent = true; Log.i("BluetoothServer", "Sent: $messageToSend")}


                } catch (e: IOException) {
                    Log.e("BluetoothServer", "Error during data transfer: ${e.message}")
                    break // Exit loop on error
                }
            }
        } catch (e: IOException) {
            Log.e("BluetoothServer", "Connection error: ${e.message}")
        } finally {
            try {
                socket.close()
                Log.i("BluetoothServer", "Socket closed.")
            } catch (e: IOException) {
                Log.e("BluetoothServer", "Error closing socket: ${e.message}")
            } finally {
                isConnected = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBluetoothServer() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val uuid = UUID.fromString("27c32b80-3a56-4331-8667-718a84776241") // Match this UUID with your client

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BluetoothServer", "Bluetooth is not enabled or available.")
            return
        }

        Thread {
            while (true) {
                var serverSocket: BluetoothServerSocket? = null
                try {
                    serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                        "MyCustomService",
                        uuid
                    )

                    while (true) {
                        try {
                            isConnected = false
                            Log.i("BluetoothServer", "Server socket created. Waiting for connections...")
                            val socket = serverSocket.accept() // Block until a connection is made
                            Log.i(
                                "BluetoothServer",
                                "Connection accepted from ${socket.remoteDevice.name} - ${socket.remoteDevice.address}"
                            )
                            isConnected = true

                            handleConnectionServer(socket) // Handle the connection
                        } catch (e: IOException) {
                            Log.e("BluetoothServer", "Error accepting connection: ${e.message}")
                            break // Exit the loop if there's an error
                        }
                    }
                } catch (e: IOException) {
                    Log.e("BluetoothServer", "Error setting up server socket: ${e.message}")
                } finally {
                    try {
                        serverSocket?.close()
                        Log.i("BluetoothServer", "Server socket closed.")
                    } catch (e: IOException) {
                        Log.e("BluetoothServer", "Error closing server socket: ${e.message}")
                    } finally {
                        isConnected = false
                    }
                }
            }
        }.start()
    }
//*******************************************
}


class Stopwatch {
    private var startTime: Long = 0
    private var elapsedTime: Long = 0
    private var running: Boolean = false

    fun start() {
        if (!running) {
            startTime = System.currentTimeMillis() - elapsedTime
            running = true
        }
    }

    fun stop() {
        if (running) {
            elapsedTime = System.currentTimeMillis() - startTime
            running = false
            println("Elapsed Time: ${elapsedTime / 1000} seconds")
        }
    }

    fun reset() {
        elapsedTime = 0
        if (running) {
            startTime = System.currentTimeMillis()
        }
    }

    fun getElapsedTime(): String {
        return "${elapsedTime / 1000} seconds"
    }
}