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
    var isChatGPT = false
    private val timer = Stopwatch()

    private var messageToSend = "Hello from client!"

    public fun changeBlueState(state: Boolean?) {
        gate = state  // Use postValue instead of setValue
    }
    //******************************************* Connect Client:

    // check out if there is a timeout for the socket
// Function to handle Bluetooth communication
    @SuppressLint("MissingPermission")
    private suspend fun handleConnectionClient(socket: BluetoothSocket) {
        val outputStream = socket.outputStream
        val inputStream = socket.inputStream

        try {
            var sent = true
            while (true) {
                // Example: Write data to the server
                if (gate == null && sent == true) {
                    messageToSend = "IDLE"
                } else if (gate == false) {
                    messageToSend = "END"
                    gate = null
                    sent = false
                } else {
                    messageToSend = "ERROR"
                }
                outputStream.write(messageToSend.toByteArray())
                if (messageToSend == "END") {sent = true; Log.i("BluetoothClient", "Sent: $messageToSend")}
                if (gate == false) gate = null

                // Read response from server
                val buffer = ByteArray(1024)
//                Log.i("BluetoothClient", "Waiting for message...")
                val bytes = inputStream.read(buffer)
                val response = String(buffer, 0, bytes)
                if (response == "END") {gate = true; Log.i("BluetoothClient", "Received: $response")}
            }
        } catch (e: IOException) {
            Log.e("BluetoothClient", "Error during communication: ${e.message}")
        } catch (e: InterruptedException) {
            Log.e("BluetoothClient", "Client thread interrupted: ${e.message}")
        } finally {
            try {
                socket.close()
                Log.i("BluetoothClient", "Socket closed.")
            } catch (e: IOException) {
                Log.e("BluetoothClient", "Error closing socket: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startBluetoothClient(context: Context) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BluetoothClient", "Bluetooth is not enabled or not available.")
            return
        }

        var isDeviceFound = false
        val discoveredDevices = mutableSetOf<BluetoothDevice>()
        val targetDeviceName = "NYP BOA"

        // Start discovery
        fun startDiscovery() {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
            Log.i("BluetoothClient", "Started discovery.")
        }

        // Stop discovery
        fun stopDiscovery(adapter: BluetoothAdapter) {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            Log.i("BluetoothClient", "Stopped discovery.")
        }

        // Register a BroadcastReceiver for device discovery
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (BluetoothDevice.ACTION_FOUND == action) {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        Log.i("BluetoothClient", "Device found: ${it.name} - ${it.address}")
                        if (it.name == targetDeviceName && !discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                            Log.i(
                                "BluetoothClient",
                                "TARGET Device found: ${it.name} - ${it.address}"
                            )
                            isDeviceFound = true
                            stopDiscovery(bluetoothAdapter)
                        }
                    }
                }
            }
        }

        // Register receiver
        context.applicationContext.registerReceiver(receiver, filter)

        // Continuous discovery loop
        withContext(Dispatchers.IO) {
            while (!isDeviceFound) {
                startDiscovery()

// Give the discovery process time to find devices
                for (i in 0 until 120) { // Bluetooth discovery typically takes up to 12 seconds
                    if (isDeviceFound) {
                        Log.i("BluetoothClient", "Device found, exiting discovery loop.")
                        break // Exit early if a device is found
                    }

                    delay(100)
                }

                if (!isDeviceFound) {
                    Log.i("BluetoothClient", "Restarting discovery...")
                    stopDiscovery(bluetoothAdapter)
                }
            }
        }

        // Unregister receiver after the target device is found
        context.applicationContext.unregisterReceiver(receiver)

        while (true) {
            // Connect to the found device
            if (discoveredDevices.isNotEmpty()) {
                Log.i("BluetoothClient", "Attempting connection to the target device.")
                withContext(Dispatchers.IO) {
                    try {
                        val socket = discoveredDevices.first().createInsecureRfcommSocketToServiceRecord(
                            UUID.fromString("27c32b80-3a56-4331-8667-718a84776241") // Replace with your UUID
                        )
                        socket.connect()
                        Log.i("BluetoothClient!", "Connected to ${discoveredDevices.first().name} - ${discoveredDevices.first().address}")

                        // Handle communication here
                        handleConnectionClient(socket)

                    } catch (e: IOException) {
                        Log.e("BluetoothClient!", "Error connecting to device: ${e.message}. Will delay 3 seconds before reattempt.")
                    }
                }
            } else {
                Log.e("BluetoothClient", "No target device found.")
            }

            delay(3000)
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
                    isConnected = true

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
                        sent = false
                    } else if (isChatGPT) {
                        messageToSend = "GPT"
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

        // Server listening for client connections in a separate thread
        Thread {
            while (true) {
                var serverSocket: BluetoothServerSocket? = null
                try {
                    serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                        "MyCustomService", uuid
                    )

                    while (true) {
                        try {
                            Log.i("BluetoothServer", "Server socket created. Waiting for connections...")
                            val socket = serverSocket.accept() // Block until a connection is made
                            Log.i("BluetoothServer", "Connection accepted from ${socket.remoteDevice.name} - ${socket.remoteDevice.address}")

                            // Handle the connection in a separate function
                            handleConnectionServer(socket)
                        } catch (e: IOException) {
                            Log.e("BluetoothServer", "Error accepting connection: ${e.message}")
                            // Continue to listen for new connections even if there's an error
                            continue
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