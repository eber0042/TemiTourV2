package com.temi.temiTour

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.navigation.model.Position
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
    TOUR,
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

enum class TourState {
    IDLE,
    STAGE_1,
    STAGE_1_1,
    TERMINATE
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
    private val askResult = robotController.askResult
    private val wakeUp = robotController.wakeUp
    private val waveForm = robotController.waveform
    private val conversationStatus = robotController.conversationStatus
    private val conversationAttached = robotController.conversationAttached
    private val locationState = robotController.locationState


    private val buffer = 100L // Used to create delay need to unsure systems work
    private var stateMode = State.NULL // Keep track of system state
    private val defaultAngle =
        0.0 // 180 + round(Math.toDegrees(robotController.getPositionYaw().toDouble())) // Default angle Temi will go to.
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

    // key word lists
    private val confirmation = listOf(
        "yes", "sure", "okay", "I’m in", "count me in", "definitely",
        "absolutely", "right now", "let's go", "I’ll be there",
        "sounds great", "I can make it", "I'm ready", "let's do it",
        "for sure", "on my way", "I'll come"
    )
    private val reject = listOf(
        "no", "not now", "can't", "not attending", "can't make it",
        "not possible", "sorry", "I have plans", "not going",
        "unfortunately not", "I can't do it", "regretfully no",
        "pass", "no thanks", "I’m busy", "I need to decline"
    )

    // Function to check for keywords or phrases
    private fun containsPhraseInOrder(
        userResponse: String?,
        phrases: List<String>,
        ignoreCase: Boolean = true
    ): Boolean {
        if (userResponse.isNullOrEmpty()) {
            return false // Return false if the response is null or empty
        }

        // Check for each phrase in the user response
        return phrases.any { phrase ->
            val words = phrase.split(" ")
            var userWords = userResponse.split(" ")

            var lastIndex = -1
            for (word in words) {
                // Find the word in user response
                lastIndex = userWords.indexOfFirst {
                    if (ignoreCase) {
                        it.equals(word, ignoreCase = true)
                    } else {
                        it == word
                    }
                }
                if (lastIndex == -1) return@any false // Word not found
                // Ensure the next word is after the last found word
                userWords = userWords.drop(lastIndex + 1)
            }
            true // All words were found in order
        }
    }

    // Keep track of the systems current state
    private var tourState = TourState.IDLE
    private var speechUpdatedValue: String? = null

    init {
        // script for Temi for the Tour
        viewModelScope.launch {
            // robotController.goTo("r410 front door")
            while (false) {
                buffer()
            } // set this an run to turn of the program
            // This is the initialisation for the tour, should only be run through once
            robotController.listOfLocations()
            var isAttached = false
            var goToLocationState = LocationState.ABORT
            var movementState = MovementStatus.ABORT

            // variables used for setting the position checker against thresholds
            val targetPosition =
                Position(x = -1.858921F, y = -8.655042F, yaw = -3.06656F, tiltAngle = 19)
            val checker = PositionChecker(
                targetPosition,
                xThreshold = 0.2F,
                yThreshold = 0.2F,
                yawThreshold = 0.2F
            )

            var userResponse: String? = null
            val job = launch {
                conversationAttached.collect { status ->
                    isAttached = status.isAttached
                }
            }

            val job1 = launch {
                locationState.collect { value ->
                    goToLocationState = value
                    //Log.i("START!", "$goToLocationState")
                }
            }

            val job2 = launch {
                movementStatus.collect { value ->
                    movementState = value.status
                    // Log.i("START!", "$movementState")
                }
            }

            // System is to issue warnings to not interfere with the Temi during testing
            /*
                        val job2 = launch {
                var warningIssued = false
                while(true) {
                    if (xPosition != XDirection.GONE) {
                        robotController.speak("Hi there, I am Temi. I am currently under going testing. Please stay out of my way.", buffer)
                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                        warningIssued = true
                        conditionTimer({ xPosition == XDirection.GONE }, time = 20)
                        if (xPosition != XDirection.GONE) {
                            robotController.speak("Sorry, but may you please not interfere with my testing", buffer)
                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                            conditionTimer({ xPosition == XDirection.GONE }, time = 50)
                        }
                    } else if (warningIssued) {
                        robotController.speak("Thank you for your cooperation", buffer)
                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                        warningIssued = false
                    }
                    buffer()
                }
            }
             */


            var shouldExit = false // Flag to determine if both loops should exit

            buffer()
            // Going to location for starting the tour
            Log.i("START!", robotController.getPosition().toString())
            if (!checker.isApproximatelyClose(robotController.getPosition())) {
                robotController.speak(
                    "I am not currently at the tour start spot. Going there now.",
                    buffer
                )

                while (true) { // loop until to makes it to the start location
                    robotController.goTo("tour start spot")
                    delay(1000L)
                    conditionGate({ goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT })
                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                    if (goToLocationState != LocationState.ABORT) {
                        break
                    }
                    buffer()
                }
            }


            // This should never be broken out of if the tour is meant to be always running.
            while (true) {
                when (tourState) {
                    TourState.IDLE -> {
                        stateMode = State.CONSTRAINT_FOLLOW
                        while (true) { // System to get reply from user
                            if (xPosition != XDirection.GONE) { // Check if there is a user present

                                robotController.speak(
                                    "Hi there, would you like to take a tour? Please just say yes or no.",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })

                                while (true) {
                                    robotController.wakeUp() // This will start the listen mode
                                    conditionGate({ isAttached }) // Wait until listen mode completed
                                    // Log.i("START!", "isAttached: $isAttached")

                                    // Make sure the speech value is updated before using it
                                    userResponse =
                                        speechUpdatedValue // Store the text from listen mode to be used
                                    speechUpdatedValue =
                                        null // clear the text to null so show that it has been used
                                    // Log.i("START!", "userResponse: $userResponse")

                                    when { // Condition gate based on what the user says
                                        containsPhraseInOrder(userResponse, reject, true) -> {
                                            robotController.speak(
                                                "Ok, if you change your mind feel free to come back and ask.",
                                                buffer
                                            )
                                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            delay(5000L)
                                            break
                                        }

                                        containsPhraseInOrder(userResponse, confirmation, true) -> {
                                            robotController.speak("Yay, I am so excited", buffer)
                                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            shouldExit = true
                                            break
                                        }

                                        else -> {
                                            if (yPosition != YDirection.MISSING) {
                                                robotController.speak(
                                                    "Sorry, I did not understand what you said. Could you repeat yourself.",
                                                    buffer
                                                )
                                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            } else {
                                                while (ttsStatus.value.status != TtsRequest.Status.STARTED) {
                                                    robotController.speak(
                                                        "You do not have to ignore me. I have feelings too you know.",
                                                        buffer
                                                    )
                                                    buffer()
                                                }
                                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                                break
                                            }
                                        }
                                    }
                                    buffer()
                                }
                                if (shouldExit) {
                                    while (true) {
                                        if (yPosition != YDirection.CLOSE) {
                                            robotController.speak(
                                                "I will now begin the tour. Please follow me!",
                                                buffer
                                            )
                                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            break
                                        } else {
                                            robotController.speak(
                                                "Sorry, you are currently too close to me, may you please take a couple steps back?",
                                                buffer
                                            )
                                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            conditionTimer(
                                                { yPosition != YDirection.CLOSE },
                                                time = 50
                                            )
                                            if (yPosition != YDirection.CLOSE) {
                                                robotController.speak(" Thank you", buffer)
                                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            }
                                        }
                                    }
                                    break
                                }
                            }
                            buffer()
                        }

                        stateMode = State.NULL
                        tourState = TourState.STAGE_1
                    }

                    TourState.STAGE_1 -> {
                        robotController.speak(
                            "Our first stop is room R410. I would like to welcome you to NYP. In particular, our engineering department. In this tour I will show you a couple of the facilities that we have to help our students pursue their goals and dreams.",
                            buffer
                        )

                        robotController.goTo("r410 front door")
                        // while(true) {buffer()}

                        conditionGate({ goToLocationState != LocationState.COMPLETE })
                        // robotController.turnBy(-15, buffer = buffer)
                        // conditionGate({ movementState != MovementStatus.COMPLETE && movementState != MovementStatus.ABORT})

                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                        robotController.speak(
                            "I have made it to the r410 front door location",
                            buffer
                        )
                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })

                        // Check if the user is in front
                        while (true) {
                            if (yPosition != YDirection.MISSING) {
                                if (yPosition == YDirection.CLOSE) {
                                    robotController.speak(
                                        "Sorry, you are a bit too close for my liking, could you take a couple steps back?",
                                        buffer
                                    )
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                    conditionTimer({ yPosition != YDirection.CLOSE }, time = 50)
                                    if (yPosition != YDirection.CLOSE) {
                                        robotController.speak(" Thank you", buffer)
                                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                    }
                                } else {
                                    robotController.speak(
                                        "Welcome to block R level 4. Before we begin I would like to explain a couple of my capabilities. If you are ready for me to continue please say Yes or No.",
                                        buffer
                                    )
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                    break
                                }
                            } else {
                                robotController.speak(
                                    "Please Stand in front of me and I will begin the tour.",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                conditionTimer({ yPosition != YDirection.MISSING }, time = 20)
                                if (yPosition == YDirection.MISSING) {
                                    robotController.turnBy(179, buffer = buffer)
                                    conditionGate({ movementState != MovementStatus.COMPLETE && movementState != MovementStatus.ABORT })

                                    robotController.speak(
                                        "Sorry, I need you to stand in front of me to begin the tour.",
                                        buffer
                                    )
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })

                                    robotController.turnBy(179, buffer = buffer)
                                    conditionGate({ movementState != MovementStatus.COMPLETE && movementState != MovementStatus.ABORT })
                                    conditionTimer({ yPosition != YDirection.MISSING }, time = 50)
                                }
                                if (yPosition != YDirection.MISSING) {
                                    robotController.speak(" Thank you", buffer)
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                }
                            }
                            buffer()
                        }
                        tourState = TourState.STAGE_1_1
                    }

                    TourState.STAGE_1_1 -> { //**************************************************************
                        // Check if everyone is ready
                        shouldExit = false
                        while (true) {
                            if (xPosition != XDirection.GONE) {
                                if (containsPhraseInOrder(userResponse, reject, true)) {
                                    robotController.speak(
                                        "Ok, I have waited for a bit. Is everyone ready now?",
                                        buffer
                                    )
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                }

                                while (true) {
                                    robotController.wakeUp()
                                    conditionGate({ isAttached })

//                                    // Make sure the speech value is updated before using it
//                                    while (!speechUpdatedFlag) { buffer() }
                                    // Make sure the speech value is updated before using it
                                    userResponse =
                                        speechUpdatedValue // Store the text from listen mode to be used
                                    speechUpdatedValue =
                                        null // clear the text to null so show that it has been used

                                    when {
                                        containsPhraseInOrder(userResponse, reject, true) -> {
                                            robotController.speak(
                                                "Ok, I will wait a little bit.",
                                                buffer
                                            )
                                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            delay(5000L)
                                            break
                                        }

                                        containsPhraseInOrder(userResponse, confirmation, true) -> {
                                            robotController.speak("Great", buffer)
                                            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            shouldExit = true
                                            break
                                        }

                                        else -> {
                                            if (yPosition != YDirection.MISSING) {
                                                robotController.speak(
                                                    "Sorry, I did not understand what you said. Could you repeat yourself.",
                                                    buffer
                                                )
                                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                            } else {
                                                break
                                            }
                                        }
                                    }
                                    buffer()
                                }
                                if (shouldExit) {
                                    break
                                }
                            }
                            else {
                                robotController.speak(
                                    "Sorry, I need you to remain in front of me for this tour",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                            }
                            buffer()
                        }

                        robotController.speak(
                            "My first capability, one that you might have noted, is my ability to detect how close someone is in front of me. For this example, I want everyone to be far away from me",
                            buffer
                        )
                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })

                        // Get everyone to move far away from temi
                        while (true) {
                            if (yPosition == YDirection.FAR) {
                                robotController.speak(
                                    "Great, this distance is what I consider you to be far away from me. Can I have one person move a little bit closer?",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                break
                            } else {
                                robotController.speak(
                                    "Sorry, Could you step back a bit more.",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                conditionTimer({ yPosition == YDirection.FAR }, time = 40)

                                if (yPosition == YDirection.FAR) {
                                    robotController.speak(" Thank you", buffer)
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                }
                            }
                            buffer()
                        }

                        // Get one person to move close to Temi
                        while (true) {
                            if (yPosition == YDirection.MIDRANGE) {
                                robotController.speak(
                                    "Perfect, this distance is my Midrange. For the duration of this I must ask you to say at least this distance away from me. If not, I will have difficulties trying to navigate around.",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                break
                            } else { // If too close
                                if (yPosition == YDirection.CLOSE) {
                                    robotController.speak(
                                        "Sorry, Could you step back a bit more.",
                                        buffer
                                    )
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                    conditionTimer({ yPosition == YDirection.CLOSE }, time = 40)
                                } else { // if two far or missing
                                    robotController.speak("Sorry, Could you come a bit closer.", buffer)
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                    conditionTimer(
                                        { yPosition == YDirection.FAR || yPosition == YDirection.MISSING },
                                        time = 40
                                    )
                                }

                                if (yPosition == YDirection.MIDRANGE) { // if they are the correct distance
                                    robotController.speak("Thank you", buffer)
                                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                                }
                            }
                            buffer()
                        }

                        robotController.speak("Made it to the end!", buffer)
                        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                        tourState = TourState.TERMINATE
                    }

                    TourState.TERMINATE -> {
                        while (true) {
                            buffer()
                        }
                        // This is to add a stopping point in the code
                    }
                }
                buffer()
            }

            job.cancel()
            job1.cancel()
            job2.cancel()
//        robotController.askQuestion("How are you?")
//            robotController.wakeUp()
//            stateMode =State.TOUR
        }

        viewModelScope.launch {
            while (true) {
//                Log.i("HOPE!", askResult.value.toString())
//                Log.i("HOPE!", conversationAttached.value.toString())
//                Log.i("HOPE!", conversationStatus.value.toString())
//                Log.i("HOPE!", wakeUp.value.toString())
//                Log.i("HOPE!", waveForm.value.toString())

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
                    State.TOUR -> {
                        //robotController.goTo("home base")
                        // robotController.askQuestion("How are you?")
                    }
                }
                buffer()
            }
        }

        // Control speech based on user when in constant state
        /*
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
         */

        /*
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
         */

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
                        currentUserDistance < 1.0 -> {
                            YDirection.CLOSE
                        }

                        currentUserDistance < 1.5 -> {
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

        // End Conversation after it gets and updates its value
        viewModelScope.launch {
            var speech: String? = null

            val job = launch {
                askResult.collect { status ->
//                    robotController.finishConversation()
                    speechUpdatedValue = status.result
                    speech = status.result
                    Log.i("START!", "$speech")
                }
            }

            while (true) {
                if (conversationAttached.value.isAttached) {
                    speech = null
                    while (speech == null) {
                        buffer()
                    }
                    robotController.finishConversation()
                }
                buffer()
            }

            job.cancel()
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

class PositionChecker(
    private val targetPosition: Position,
    private var xThreshold: Float = 0.1F,
    private var yThreshold: Float = 0.1F,
    private var yawThreshold: Float = 0.1F,
    private var checkYaw: Boolean = false // default to checking yaw
) {
    // Adjust x and y threshold
    fun setPositionThreshold(xThreshold: Float, yThreshold: Float) {
        this.xThreshold = xThreshold
        this.yThreshold = yThreshold
    }

    // Adjust yaw threshold
    fun setYawThreshold(yawThreshold: Float) {
        this.yawThreshold = yawThreshold
    }

    // Enable or disable yaw checking
    fun enableYawCheck(enable: Boolean) {
        this.checkYaw = enable
    }

    // Normalize an angle to be within 0-360 degrees
    private fun normalizeAngle(angle: Float): Float {
        var normalizedAngle = angle % 360
        if (normalizedAngle < 0) normalizedAngle += 360
        return normalizedAngle
    }

    // Check if yaw is approximately close with wrap-around consideration
    private fun isYawApproximatelyClose(
        currentYaw: Float,
        targetYaw: Float,
        threshold: Float
    ): Boolean {
        val normalizedCurrentYaw = normalizeAngle(currentYaw)
        val normalizedTargetYaw = normalizeAngle(targetYaw)

        val lowerBound = normalizeAngle(normalizedTargetYaw - threshold)
        val upperBound = normalizeAngle(normalizedTargetYaw + threshold)

        return if (lowerBound < upperBound) {
            normalizedCurrentYaw in lowerBound..upperBound
        } else {
            normalizedCurrentYaw >= lowerBound || normalizedCurrentYaw <= upperBound
        }
    }

    // Check if the current position is approximately close to the target position
    fun isApproximatelyClose(currentPosition: Position): Boolean {
        val xClose = abs(currentPosition.x - targetPosition.x) <= xThreshold
        val yClose = abs(currentPosition.y - targetPosition.y) <= yThreshold

        val yawClose = if (checkYaw) {
            isYawApproximatelyClose(currentPosition.yaw, targetPosition.yaw, yawThreshold)
        } else {
            true // if yaw checking is disabled, consider it "close" by default
        }

        return xClose && yClose && yawClose
    }
}

