package com.temi.temiTour

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotemi.sdk.TtsRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.*

// Track state
enum class State {
    TALK,          // Testing talking feature
    DISTANCE,      // Track distance of user
    ANGLE,
    CONSTRAINT_FOLLOW,
    TEST_MOVEMENT,
    DETECTION_LOGIC,
    TEST,
    NULL
}
// Track Y distance
enum class YDirection {
    FAR,
    MIDRANGE,
    CLOSE,
    MISSING
}
// Track X distance
enum class XDirection {
    LEFT,
    RIGHT,
    MIDDLE,
    GONE

}
// Y based movement
enum class YMovement {
    CLOSER,
    FURTHER,
    NOWHERE
}
// X based movement
enum class XMovement {
    LEFTER,
    RIGHTER,
    NOWHERE
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val robotController: RobotController,
) : ViewModel() {

    // These collect data from services in robotController
    private val ttsStatus = robotController.ttsStatus // Current speech state
    private val detectionStatus = robotController.detectionStateChangedStatus
    private val detectionData = robotController.detectionDataChangedStatus
    private val movementStatus = robotController.movementStatusChangedStatus
    private val lifted = robotController.lifted
    private val dragged = robotController.dragged


    private val buffer = 100L // Used to create delay need to unsure systems work
    private var stateMode = State.NULL // Keep track of system state
    private val defaultAngle =
        180.0 // 180 + round(Math.toDegrees(robotController.getPositionYaw().toDouble())) // Default angle Temi will go to.
    private val boundary = 90.0 // Distance Temi can turn +/- the default angle
    private var userRelativeDirection =
        XDirection.GONE // Used for checking direction user was lost

    // Bellow is the data used to keep track of movement
    private var previousUserAngle = 0.0
    private var currentUserAngle = 0.0
    private var xPosition = XDirection.GONE
    private var xMotion = XMovement.NOWHERE

    private var previousUserDistance = 0.0
    private var currentUserDistance = 0.0
    private var yPosition = YDirection.MISSING
    private var yMotion = YMovement.NOWHERE

    private var int: Int = 0 // Temi has a system to decide speech based type, this is used to...
    // Select type

    private var say = "Hello, World"

    init {
        robotController.listOfLocations()

        viewModelScope.launch {
            while (true) {
                when (stateMode) {
                    State.TALK -> { // Need to work on this
                    }

                    State.DISTANCE -> TODO()
                    State.ANGLE -> TODO()
                    State.CONSTRAINT_FOLLOW -> {
                        //' check to see if the state is not in misuse
                        if (!dragged.value.state && !lifted.value.state) {

                            val currentAngle =
                                180 + round(
                                    Math.toDegrees(
                                        robotController.getPositionYaw().toDouble()
                                    )
                                )
                            val userRelativeAngle =
                                round(Math.toDegrees(detectionData.value.angle)) / 1.70
                            val turnAngle = (userRelativeAngle).toInt()

                            // Use this to determine which direction the user was lost in
                            when {
                                userRelativeAngle > 0 -> {
                                    userRelativeDirection = XDirection.LEFT
                                }

                                userRelativeAngle < 0 -> {
                                    userRelativeDirection = XDirection.RIGHT
                                }

                                else -> {
                                    // Do nothing
                                }
                            }

                            // This method will allow play multiple per detection
                            var isDetected = false
                            var isLost = false

                            // Launch a coroutine to monitor detectionStatus
                            val job = launch {
                                detectionStatus.collect { status ->
                                    when (status) {
                                        DetectionStateChangedStatus.DETECTED -> {
                                            isDetected = true
                                            isLost = false
                                            buffer()
                                        }

                                        DetectionStateChangedStatus.LOST -> {
                                            isDetected = false
                                            isLost = true
                                            buffer()
                                        }

                                        else -> {
                                            isDetected = false
                                            isLost = false
                                            buffer()
                                        }
                                    }
                                }
                            }

//                        Log.i("Movement", movementStatus.value.status.toString())


                            fun normalizeAngle(angle: Double): Double {
                                var normalizedAngle =
                                    angle % 360  // Ensure the angle is within 0-360 range

                                if (normalizedAngle < 0) {
                                    normalizedAngle += 360  // Adjust for negative angles
                                }

                                return normalizedAngle
                            }

                            val lowerBound = normalizeAngle(defaultAngle - boundary)
                            val upperBound = normalizeAngle(defaultAngle + boundary)

                            // Helper function to calculate the adjusted turn angle that keeps within the bounds
                            fun clampTurnAngle(
                                currentAngle: Double,
                                targetTurnAngle: Double
                            ): Double {
                                val newAngle = normalizeAngle(currentAngle + targetTurnAngle)

                                return when {
                                    // If the new angle is within the bounds, return the target turn angle
                                    lowerBound < upperBound && newAngle in lowerBound..upperBound -> targetTurnAngle
                                    lowerBound > upperBound && (newAngle >= lowerBound || newAngle <= upperBound) -> targetTurnAngle

                                    // Otherwise, return the angle that brings it closest to the boundary
                                    lowerBound < upperBound -> {
                                        if (newAngle < lowerBound) lowerBound + 1 - currentAngle
                                        else upperBound - 1 - currentAngle
                                    }

                                    else -> {
                                        if (abs(upperBound - currentAngle) < abs(lowerBound - currentAngle)) {
                                            upperBound - 1 - currentAngle
                                        } else {
                                            lowerBound + 1 - currentAngle
                                        }
                                    }
                                }
                            }

                            // Now clamp the turn angle before turning the robot
                            val adjustedTurnAngle =
                                clampTurnAngle(currentAngle, turnAngle.toDouble())


                            if (abs(adjustedTurnAngle) > 0.1 && yPosition != YDirection.CLOSE) {  // Only turn if there's a meaningful adjustment to make
                                robotController.turnBy(adjustedTurnAngle.toInt(), 1f, buffer)
                            } else if (isLost && (currentAngle < defaultAngle + boundary && currentAngle > defaultAngle - boundary)) {
                                // Handles condition when the user is lost
                                when (userRelativeDirection) {
                                    XDirection.LEFT -> {
                                        robotController.turnBy(45, 0.1f, buffer)
                                        userRelativeDirection = XDirection.GONE
                                    }

                                    XDirection.RIGHT -> {
                                        robotController.turnBy(-45, 0.1f, buffer)
                                        userRelativeDirection = XDirection.GONE
                                    }

                                    else -> {
                                        // Do nothing
                                    }
                                }
                            } else if (!isDetected && !isLost) {
                                // Handles conditions were the robot has detected someone
                                val angleThreshold = 2.0 // Example threshold, adjust as needed

                                if (abs(defaultAngle - currentAngle) > angleThreshold) {
                                    robotController.turnBy(
                                        getDirectedAngle(
                                            defaultAngle,
                                            currentAngle
                                        ).toInt(), 1f, buffer
                                    )
                                    conditionGate({
                                        movementStatus.value.status !in listOf(
                                            MovementStatus.COMPLETE,
                                            MovementStatus.ABORT
                                        )
                                    }, movementStatus.value.status.toString())
                                }
                            }
                            // Ensure to cancel the monitoring job if the loop finishes
                            job.cancel()
                        }
                    }
                    State.TEST_MOVEMENT -> TODO()
                    State.DETECTION_LOGIC -> TODO()
                    State.TEST -> TODO()
                    State.NULL -> {}
                }
                buffer()
            }
        }

        // Control speech based on user when in constant state
        viewModelScope.launch {
            while (true) {
                while (!lifted.value.state && !dragged.value.state) {
                    while (stateMode == State.CONSTRAINT_FOLLOW) {
                        var isDetected = false

                        // Launch a coroutine to monitor detectionStatus
                        val job = launch {
                            detectionStatus.collect { status ->
                                if (status == DetectionStateChangedStatus.DETECTED) {
                                    isDetected = true
                                    buffer()
                                } else {
                                    isDetected = false
                                }
                            }
                        }

                        when (xPosition) {
                            XDirection.LEFT -> {
                                when (yPosition) {
                                    YDirection.FAR -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're quite far to my left!",
                                                buffer
                                            )
                                        }
                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.MIDRANGE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're in the midrange on my left.",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.CLOSE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're close on my left!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.MISSING -> {
                                        // No action needed for MISSING
                                    }
                                }

                                when (xMotion) {
                                    XMovement.LEFTER -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're moving further to my left!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    XMovement.RIGHTER -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're moving to my right!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    XMovement.NOWHERE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're staying still on my left.",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }
                                }
                            }

                            XDirection.RIGHT -> {
                                when (yPosition) {
                                    YDirection.FAR -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're quite far to my right!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.MIDRANGE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're in the midrange on my right.",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.CLOSE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're close on my right!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.MISSING -> {
                                        // No action needed for MISSING
                                    }
                                }

                                when (xMotion) {
                                    XMovement.LEFTER -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're moving to my left!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    XMovement.RIGHTER -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're moving further to my right!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    XMovement.NOWHERE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're staying still on my right.",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }
                                }
                            }

                            XDirection.MIDDLE -> {
                                when (yPosition) {
                                    YDirection.FAR -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're far away from me!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.MIDRANGE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're in the midrange relative to me.",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.CLOSE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're close to me!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    YDirection.MISSING -> {
                                        // No action needed for MISSING
                                    }
                                }

                                when (xMotion) {
                                    XMovement.LEFTER -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're moving to my left!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    XMovement.RIGHTER -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're moving to my right!",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }

                                    XMovement.NOWHERE -> {
                                        if (stateMode == State.CONSTRAINT_FOLLOW) {
                                            robotController.speak(
                                                "You're staying still in the middle.",
                                                buffer
                                            )
                                        }

                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                        conditionTimer({ !isDetected }, time = 20)
                                    }
                                }
                            }

                            XDirection.GONE -> {
                                // No action needed for GONE
                            }
                        }

                        // Now handle the YDirection movement


                        buffer()
                        job.cancel()
                    }
                    buffer()
                }
                buffer()
            }
        }

        // Title head of Temi based on how far away the user is.
        viewModelScope.launch {
            var closeTrigger = false
            while (true) {
                while (!dragged.value.state && !lifted.value.state) {
                    if (stateMode == State.CONSTRAINT_FOLLOW) {
                        when (yPosition) {
                            YDirection.FAR -> {
                                robotController.tiltAngle(10, 1f, buffer)
                                closeTrigger = false
                            }

                            YDirection.MIDRANGE -> {
                                robotController.tiltAngle(25, 1f, buffer)
                                closeTrigger = false
                            }

                            YDirection.CLOSE -> {
                                if (!closeTrigger) {
                                    robotController.tiltAngle(30, 1f, buffer)
                                    closeTrigger = true
                                }
                            }

                            YDirection.MISSING -> {
                                robotController.tiltAngle(-5, 1f, buffer)
                                closeTrigger = false
                            }
                        }
                    } else {
                        if (!closeTrigger) {
                            robotController.tiltAngle(50, 1f, buffer)
                            closeTrigger = true
                        }
                    }
                    buffer()
                }
                buffer()
            }
        }

        // x-detection
        viewModelScope.launch { // Used to get state for x-direction and motion
            while (true) {
                // This method will allow play multiple per detection
                var isDetected = false

                // Launch a coroutine to monitor detectionStatus
                val job = launch {
                    detectionStatus.collect { status ->
                        if (status == DetectionStateChangedStatus.DETECTED) {
                            isDetected = true
                            buffer()
                        } else {
                            isDetected = false
                        }
                    }
                }

                previousUserAngle = currentUserAngle
                delay(500L)
                currentUserAngle = detectionData.value.angle

//                Log.i("currentUserAngle", (currentUserAngle).toString())
//                Log.i("previousUserAngle", (previousUserAngle).toString())
//                Log.i("Direction", (currentUserAngle - previousUserAngle).toString())

                if (isDetected && previousUserDistance != 0.0) { //&& previousUserDistance != 0.0 && previousUserDistance == currentUserDistance) {
                    // logic for close or far position
//                    Log.i("STATE", (yPosition).toString())
                    xPosition = when {
                        currentUserAngle > 0.1 -> {
                            XDirection.LEFT
                        }

                        currentUserAngle < -0.1 -> {
                            XDirection.RIGHT
                        }

                        else -> {
                            XDirection.MIDDLE
                        }
                    }
                } else {
                    xPosition = XDirection.GONE
                }

                if (isDetected && previousUserAngle != 0.0 && previousUserAngle != currentUserAngle) {

                    when (yPosition) {
                        YDirection.FAR -> {
                            xMotion = when {
                                currentUserAngle - previousUserAngle > 0.07 -> XMovement.LEFTER
                                currentUserAngle - previousUserAngle < -0.07 -> XMovement.RIGHTER
                                else -> XMovement.NOWHERE
                            }
                        }

                        YDirection.MIDRANGE -> {
                            xMotion = when {
                                currentUserAngle - previousUserAngle > 0.12 -> XMovement.LEFTER
                                currentUserAngle - previousUserAngle < -0.12 -> XMovement.RIGHTER
                                else -> XMovement.NOWHERE
                            }
                        }

                        YDirection.CLOSE -> {
                            xMotion = when {
                                currentUserAngle - previousUserAngle > 0.17 -> XMovement.LEFTER
                                currentUserAngle - previousUserAngle < -0.17 -> XMovement.RIGHTER
                                else -> XMovement.NOWHERE
                            }
                        }

                        YDirection.MISSING -> {
                            XMovement.NOWHERE
                        }
                    }
                }

//                Log.i("STATE", (xMotion).toString())

                job.cancel()
            }
        }

        // y-detection
        viewModelScope.launch { // Used to get state for y-direction and motion
            while (true) {
                // This method will allow play multiple per detection
                var isDetected = false

                // Launch a coroutine to monitor detectionStatus
                val job = launch {
                    detectionStatus.collect { status ->
                        if (status == DetectionStateChangedStatus.DETECTED) {
                            isDetected = true
                            buffer()
                        } else {
                            isDetected = false
                        }
                    }
                }

                previousUserDistance = currentUserDistance
                delay(500L)
                currentUserDistance = detectionData.value.distance

//                Log.i("currentUserAngle", (currentUserDistance).toString())
//                Log.i("previousUserAngle", (previousUserDistance).toString())
//                Log.i("Direction", (currentUserDistance - previousUserDistance).toString())

                if (isDetected && previousUserDistance != 0.0) { //&& previousUserDistance != 0.0 && previousUserDistance == currentUserDistance) {
                    // logic for close or far position
                    yPosition = when {
                        currentUserDistance < 0.90 -> {
                            YDirection.CLOSE
                        }

                        currentUserDistance < 1.35 -> {
                            YDirection.MIDRANGE
                        }

                        else -> {
                            YDirection.FAR
                        }
                    }
                } else {
                    yPosition = YDirection.MISSING
                }

                if (isDetected && previousUserDistance != 0.0 && previousUserDistance != currentUserDistance) { //&& previousUserDistance != 0.0 && previousUserDistance == currentUserDistance) {
                    yMotion = when {
                        currentUserDistance - previousUserDistance > 0.01 -> {
                            YMovement.FURTHER
                        }

                        currentUserDistance - previousUserDistance < -0.01 -> {
                            YMovement.CLOSER
                        }

                        else -> {
                            YMovement.NOWHERE
                        }
                    }
                }
//                Log.i("STATE", (yMotion).toString())

                job.cancel()
            }
        }
    }

    //**************************Functions for the ViewModel
    suspend fun speech(text: String) {
        robotController.speak(text, buffer)
        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
        conditionTimer({ !(dragged.value.state || lifted.value.state) }, time = 2)
    }
    //**************************Functions for the View
    fun resultSpeech(
        int: Int = 0,
        say: String = "Hello, World"
    ) {

    }

    // Allows the changing of the system state
    fun change() {
        stateMode = State.NULL
    }

    // Allows view to check is the Temi is in a misuse state
    fun isMisuseState(): Boolean {
        // Log.i("State", (dragged.value.state || lifted.value.state).toString())
        return (dragged.value.state || lifted.value.state)
    }

    // Control the volume of the temi
    fun volumeControl(volume: Int) {
        robotController.volumeControl(volume)
    }

    //**************************System Function
    private suspend fun buffer() {
        // Increase buffer time to ensure enough delay between checks
        delay(this.buffer)
    }

    private suspend fun conditionTimer(trigger: () -> Boolean, time: Int) {
        if (!trigger()) {
            for (i in 1..time) {
                buffer()
//            Log.i("Trigger", trigger().toString())
                if (trigger()) {
                    break
                }
            }
        }
    }

    private suspend fun conditionGate(trigger: () -> Boolean, log: String = "Null") {
        // Loop until the trigger condition returns false
        while (trigger()) {
//        Log.i("ConditionGate", "Trigger: $log")
            buffer() // Pause between checks to prevent busy-waiting
        }
//    Log.i("ConditionGate", "End")
    }

    private fun getDirectedAngle(a1: Double, a2: Double): Double {
        var difference = a1 - a2
        // Normalize the angle to keep it between -180 and 180 degrees
        if (difference > 180) difference -= 360
        if (difference < -180) difference += 360
        return difference
    }
}
