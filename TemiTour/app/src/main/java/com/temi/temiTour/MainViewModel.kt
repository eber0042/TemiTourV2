package com.temi.temiTour

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.constants.HardButton
import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.navigation.model.SpeedLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.regex.Pattern
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
    START_LOCATION,
    IDLE,
    ALTERNATE_START,
    RAMP,
    STAGE_1,
    STAGE_1_B,
    STAGE_1_1,
    STAGE_1_1_B,
    STAGE_1_2,
    STAGE_1_2_B,
    TOUR_END,
    TERMINATE,
    NULL,
    TESTING,
    GET_USER_NAME,
    TEMI_V2
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
    private val beWithMeStatus = robotController.beWithMeState


    private val buffer = 100L // Used to create delay need to unsure systems work
    private var stateMode = State.NULL // Keep track of system state
    private var defaultAngle =
        0.0 // 180 + round(Math.toDegrees(robotController.getPositionYaw().toDouble())) // Default angle Temi will go to.
    private var boundary = 90.0 // Distance Temi can turn +/- the default angle
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

    public var playMusic = false

    // StateFlow for controlling whether to play a GIF or static image
    private val _shouldPlayGif = MutableStateFlow(true)
    val shouldPlayGif: StateFlow<Boolean> = _shouldPlayGif

    // StateFlow for holding the current image resource
    private val _imageResource = MutableStateFlow(R.drawable.oip)
    val image: StateFlow<Int> = _imageResource

    // StateFlow for holding the current image resource
    private val _gifResource = MutableStateFlow(R.drawable.idle)
    val gif: StateFlow<Int> = _gifResource

    // Function to update the image resource
    fun updateImageResource(resourceId: Int) {
        _imageResource.value = resourceId
    }

    // Function to toggle the GIF state
    fun toggleGif() {
        _shouldPlayGif.value = !_shouldPlayGif.value
    }

    //******************************************** Stuff for the tour
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

    // ************************************************************************************************ STUFF FOR THE TOUR
    // Keep track of the systems current state
    private var tourState = TourState.NULL
    private var isTourStateFinished = false

    private var speechUpdatedValue: String? = null
    private var userResponse: String? = null

    private var isAttached = false
    private var goToLocationState = LocationState.ABORT
    private var movementState = MovementStatus.ABORT

    private var shouldExit = false // Flag to determine if both loops should exit

    private var userName: String? = null

    private var followState = BeWithMeState.CALCULATING
    
    private var triggeredInterrupt: Boolean = false
    // Define a mutable map to hold interrupt flags
    private val interruptFlags = mutableMapOf(
        "userMissing" to false,
        "userTooClose" to false,
        "deviceMoved" to false
    )
    private var repeatSpeechFlag: Boolean = false
    private var talkingInThreadFlag = false
    private var repeatGoToFlag: Boolean = false
    private var interruptTriggerDelay = 10

    private suspend fun idleSystem(idle: Boolean) {
        while (idle) {
            buffer()
        } // set this and run to turn of the program
    }

    private suspend fun initiateTour() {
        // This is the initialisation for the tour, should only be run through once
        robotController.listOfLocations()

        goToSpeed(SpeedLevel.HIGH)
        userName = null

        val job = viewModelScope.launch {
            conversationAttached.collect { status ->
                isAttached = status.isAttached
            }
        }

        val job1 = viewModelScope.launch {
            locationState.collect { value ->
                goToLocationState = value
                //Log.i("START!", "$goToLocationState")
            }
        }

        val job2 = viewModelScope.launch {
            movementStatus.collect { value ->
                movementState = value.status
                // Log.i("START!", "$movementState")
            }
        }

        val job3 = viewModelScope.launch {
            beWithMeStatus.collect { value ->
                followState = value
                // Log.i("START!", "$movementState")
            }
        }

//        job.cancel()
//        job1.cancel()
//        job2.cancel()
        // job3.cancel()
    }

    private suspend fun tourState(newTourState: TourState) {
        tourState = newTourState
        Log.i("INFO!", "$tourState")
        conditionGate({ !isTourStateFinished })
        isTourStateFinished = false
    }

    private suspend fun  setCliffSensorOn(sensorOn: Boolean) {
        robotController.setCliffSensorOn(sensorOn)
    }

    private fun stateFinished() {
        isTourStateFinished = true
        stateMode = State.NULL
    }

    private fun stateMode(state: State) {
        stateMode = state
    }

    private suspend fun basicSpeak(
        speak: String?,
        setConditionGate: Boolean = true,
        haveFace: Boolean = true
    ) {
        if (speak != null) {
            robotController.speak(
                speak,
                buffer,
                haveFace
            )
            if (setConditionGate) conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
        }

    }

    private suspend fun forcedSpeak(speak: String?) {
        while (ttsStatus.value.status != TtsRequest.Status.STARTED) {
            if (speak != null) {
                robotController.speak(
                    speak,
                    buffer
                )
            }
            buffer()
        }
        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
    }

    private suspend fun listen() {
        robotController.wakeUp() // This will start the listen mode
        conditionGate({ isAttached }) // Wait until listen mode completed

        // Make sure the speech value is updated before using it
        userResponse =
            speechUpdatedValue // Store the text from listen mode to be used
        speechUpdatedValue =
            null // clear the text to null so show that it has been used
    }

    private suspend fun turnBy(degree: Int) {
        robotController.turnBy(degree, buffer = buffer)
        conditionGate({ movementState != MovementStatus.COMPLETE && movementState != MovementStatus.ABORT })
    }

    private fun setMainButtonMode(isEnabled: Boolean) {
        robotController.setMainButtonMode(isEnabled)
    }

    private suspend fun getUseConfirmation(
        initialQuestion: String? = null,
        rejected: String? = null,
        afterRejectedDelay: Long = 0L,
        confirmed: String? = null,
        notUnderstood: String? = null,
        ignored: String? = null,
        exitCase: (suspend () -> Unit)? = null
    ) {
        shouldExit = false

        while (true) {
            if (xPosition != XDirection.GONE) { // Check if there is a user present
                // Check if there is an initial question
                speak(initialQuestion)

                while (true) {
                    listen()

                    when { // Condition gate based on what the user says
                        containsPhraseInOrder(userResponse, reject, true) -> {
                            speak(rejected)
                            delay(afterRejectedDelay)
                            break
                        }

                        containsPhraseInOrder(userResponse, confirmation, true) -> {
                            speak(confirmed)
                            shouldExit = true
                            break
                        }

                        else -> {
                            if (yPosition != YDirection.MISSING) {

                                speak(notUnderstood)

                            } else {

                                forcedSpeak(ignored)

                                break
                            }
                        }
                    }
                    buffer()
                }

                if (shouldExit) {
                    exitCase?.invoke() // Calls exitCase if it’s not null
                    break
                }

            }
            buffer()
        }
    }

    private suspend fun exitCaseCheckIfUserClose(
        notClose: String? = null,
        close: String? = null
    ) {
        while (true) {
            if (yPosition != YDirection.CLOSE) {
                if (!notClose.isNullOrEmpty()) {
                    speak(notClose)
                }
                break
            } else {
                if (!close.isNullOrEmpty()) {
                    speak(close)
                }
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
    }

    private fun extractName(userResponse: String): String? {
        // Define common patterns for introducing names
        val namePatterns = listOf(
            "my name is ([A-Za-z]+)",  // e.g., "My name is John"
            "i am ([A-Za-z]+)",        // e.g., "I am Alice"
            "it's ([A-Za-z]+)",        // e.g., "It's Bob"
            "this is ([A-Za-z]+)",     // e.g., "This is Sarah"
            "call me ([A-Za-z]+)",     // e.g., "Call me Mike"
            "name is ([A-Za-z]+)",
            "is ([A-Za-z]+)",
            "me ([A-Za-z]+)",
            "i ([A-Za-z]+)",
            "am ([A-Za-z]+)"
        )

        // Iterate over each pattern to try to match the user's response
        for (pattern in namePatterns) {
            val regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
            val matcher = regex.matcher(userResponse)

            // If a pattern matches, return the extracted name
            if (matcher.find()) {
                return matcher.group(1) // The name will be in the first capturing group
            }
        }

        // If no pattern matches, check if the userResponse is a single word and return it
        val singleWordPattern = Pattern.compile("^[A-Za-z]+$", Pattern.CASE_INSENSITIVE)
        val singleWordMatcher = singleWordPattern.matcher(userResponse.trim())

        return if (singleWordMatcher.matches()) userResponse.trim() else null
    }


    private fun goToSpeed(speedLevel: SpeedLevel) {
        robotController.setGoToSpeed(speedLevel)
    }

    suspend fun skidJoy(x: Float, y: Float, repeat: Int) {
        for (i in 1..repeat) {
            robotController.skidJoy(x, y)
            delay(500)
        }
    }

    // Function to update an interrupt flag value
    fun updateInterruptFlag(flag: String, value: Boolean) {
        if (interruptFlags.containsKey(flag)) {
            interruptFlags[flag] = value
        } else {
            println("Flag $flag does not exist in the interruptFlags map.")
        }
    }

    private fun stopMovement() {
        robotController.stopMovement()
    }

    private suspend fun speak(
        speak: String?,
        setConditionGate: Boolean = true,
        haveFace: Boolean = true,
        setInterruptSystem: Boolean = false,
        setInterruptConditionUserMissing: Boolean = false,
        setInterruptConditionUSerToClose: Boolean = false,
        setInterruptConditionDeviceMoved: Boolean = false
    ) {
        if (speak != null) {
            // Split the input text into sentences based on common sentence-ending punctuation
            val sentences = speak.split(Regex("(?<=[.!?])\\s+"))

//            // change the flags as needed
//            updateInterruptFlag("userMissing", setInterruptConditionUserMissing)
//            updateInterruptFlag("userTooClose", setInterruptConditionUSerToClose)
//            updateInterruptFlag("deviceMoved", setInterruptConditionDeviceMoved)

            if (setConditionGate) {
                for (sentence in sentences) {
                    if (sentence.isNotBlank()) {
                        do {
                            // Log.i("DEBUG!", sentence)
                            // set the repeat flag to false once used
                            if (setInterruptSystem && repeatSpeechFlag) repeatSpeechFlag = false

                            // Speak each sentence individually
                            // Log.i("DEBUG!", "repeatSpeechFlag: $repeatSpeechFlag")
                            robotController.speak(
                                sentence.trim(),
                                buffer,
                                haveFace
                            )

                            // Wait for each sentence to complete before moving to the next
                                conditionGate ({
                                    ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)
                                })
                        } while (repeatSpeechFlag)
                    }
                }

                updateInterruptFlag("userMissing", false)
                updateInterruptFlag("userTooClose", false)
                updateInterruptFlag("deviceMoved", false)
            } else {
                if (!talkingInThreadFlag) {
                    viewModelScope.launch {
                        // Log.i("DEBUG!", "In the thread!s")
                        talkingInThreadFlag = true
                        for (sentence in sentences) {
                            // Log.i("DEBUG!", "$sentence")
                            if (sentence.isNotBlank()) {
                                do {
                                    // Log.i("DEBUG!", sentence)
                                    // set the repeat flag to false once used
                                    if (setInterruptSystem && repeatSpeechFlag) repeatSpeechFlag = false

                                    // Speak each sentence individually
                                    // Log.i("DEBUG!", "repeatSpeechFlag: $repeatSpeechFlag")
                                    robotController.speak(
                                        sentence.trim(),
                                        buffer,
                                        haveFace
                                    )

                                    // Wait for each sentence to complete before moving to the next
                                    conditionGate ({
                                        ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)
                                    })
                                } while (repeatSpeechFlag)
                            }
                        }
                        talkingInThreadFlag = false
                    }
                }
            }

        }
    }

//    || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved

    // There is a bug were it gets stuck after, detecting someone
    // It will tilt the screen up and down and will not stop.
    private suspend fun goTo(
        location: String,
        speak: String? = null,
        haveFace: Boolean = true,
        backwards: Boolean = false,
        setInterruptSystem: Boolean = false,
        setInterruptConditionUserMissing: Boolean = false,
        setInterruptConditionUSerToClose: Boolean = false,
        setInterruptConditionDeviceMoved: Boolean = false
    ) {

        updateInterruptFlag("userMissing", setInterruptConditionUserMissing)
        updateInterruptFlag("userTooClose", setInterruptConditionUSerToClose)
        updateInterruptFlag("deviceMoved", setInterruptConditionDeviceMoved)

        var hasGoneToLocation = false

        speak(speak, false, haveFace = haveFace, setInterruptSystem, setInterruptConditionUserMissing, setInterruptConditionUSerToClose, setInterruptConditionDeviceMoved) // *******************************************

//        updateInterruptFlag("userMissing", setInterruptConditionUserMissing)
//        updateInterruptFlag("userTooClose", setInterruptConditionUSerToClose)
//        updateInterruptFlag("deviceMoved", setInterruptConditionDeviceMoved)

        while (true) { // loop until to makes it to the start location
             Log.i("DEBUG!", "Has Gone To Location: $hasGoneToLocation")

            if (!triggeredInterrupt && !hasGoneToLocation) { robotController.goTo(location, backwards); Log.i("DEBUG!", "Hello: $repeatGoToFlag ")}

            buffer()
              Log.i("DEBUG!", "Triggered?: ")
            conditionGate({ goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved) })

             Log.i("DEBUG!", "Should exit " + (hasGoneToLocation && !repeatGoToFlag).toString())

            if (hasGoneToLocation && !repeatGoToFlag) break
            else if (goToLocationState == LocationState.COMPLETE) hasGoneToLocation = true

            if (repeatGoToFlag) repeatGoToFlag = false
            buffer()

        }
         Log.i("DEBUG!", "THREE: $talkingInThreadFlag")
        if (speak != null) conditionGate({talkingInThreadFlag}, false)// conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)})

         Log.i("DEBUG!", "$location: " + triggeredInterrupt)
        updateInterruptFlag("userMissing", false)
        updateInterruptFlag("userTooClose", false)
        updateInterruptFlag("deviceMoved", false)
    }

    init {

        // thread used for handling interrupt system
        viewModelScope.launch {
            launch {
                while(true) {
//                     Log.i("DEBUG!", "In misuse state: ${isMisuseState()}")
//                     Log.i("DEBUG!", "Flag: $yPosition")
                    if ((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] == true)) {
                        conditionTimer({!((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || (isMisuseState()) && interruptFlags["deviceMoved"] == true)}, interruptTriggerDelay)
                        if ((yPosition != YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition != YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] != true)) continue
                        triggeredInterrupt = true
                        repeatSpeechFlag = true
                        repeatGoToFlag = true
                        // Log.i("DEBUG!", "Trigger Stopped")
                        stopMovement()
                    } else {
                       // Log.i("DEBUG!", "Trigger Stopped")
                        triggeredInterrupt = false
                    }
                    buffer()
                }
            }

            while (true) {
                while (triggeredInterrupt) {
                    // conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
//                    when {
//                        interruptFlags["deviceMoved"] == true && isMisuseState()-> robotController.speak("Hey, do not touch me.", buffer)
//                        interruptFlags["userMissing"] == true && yPosition == YDirection.MISSING -> robotController.speak("Hey, I am not done with my speech.", buffer)
//                        interruptFlags["userTooClose"] == true && yPosition == YDirection.CLOSE -> robotController.speak("Hey, you are too close.", buffer)
//                        else -> {}
//                    }
//                    conditionGate ({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                    conditionTimer({!triggeredInterrupt}, 1)
                }
                buffer()
            }
        }
        
        // script for Temi for the Tour
        viewModelScope.launch {
            idleSystem(false)
            initiateTour()

            launch { // Use this to handle the stateflow changes for tour
                while (true) { // This will loop the states
                    Log.i("DEBUG!", "In start location")
//                    tourState(TourState.TESTING)

                    tourState(TourState.START_LOCATION)
                    tourState(TourState.ALTERNATE_START)
                    tourState(TourState.STAGE_1_B)
                    tourState(TourState.STAGE_1_1_B)
                    tourState(TourState.GET_USER_NAME)
                    tourState(TourState.STAGE_1_2_B)
                    tourState(TourState.TOUR_END)

//                    tourState(TourState.IDLE)
//                    tourState(TourState.STAGE_1)
//                    tourState(TourState.STAGE_1_1)
//                    tourState(TourState.GET_USER_NAME)
//                    tourState(TourState.TOUR_END)
                }
            }

            // This should never be broken out of if the tour is meant to be always running.
            while (true) {
                when (tourState) {
                    TourState.START_LOCATION -> {
                        playMusic = false
                        goTo("home base")
                       // Log.i("DEBUG!", "Trying")
                        stateFinished()
                    }

                    TourState.IDLE -> {
                        stateMode(State.CONSTRAINT_FOLLOW)

                        getUseConfirmation(
                            "Hi there, would you like to take a tour? Please just say yes or no.",
                            "Ok, if you change your mind feel free to come back and ask.",
                            5000L,
                            "Yay, I am so excited",
                            "Sorry, I did not understand what you said. Could you repeat yourself.",
                            "You do not have to ignore me. I have feelings too you know."
                        ) {
                            exitCaseCheckIfUserClose(
                                "I will now begin the tour. Please follow me!",
                                "Sorry, you are currently too close to me, may you please take a couple steps back?"
                            )
                        }

                        stateFinished()
                    }

                    TourState.ALTERNATE_START -> {
                        setMainButtonMode(true)
                        conditionGate({ followState != BeWithMeState.TRACK })
                        speak("I am now following you")

                        val excitementPhrases = listOf(
                            "I am so excited!",
                            "I can’t wait to start this tour!",
                            "This is going to be so much fun!",
                            "I cannot wait to show them around!",
                            "I can not wait to get this adventure started!",
                            "I am so excited!"
                        )

                        // While loop to monitor the follow state and express excitement
                        while (followState != BeWithMeState.ABORT) {
                            // Select a random phrase from the list and speak it
                            val phrase = excitementPhrases.random()
                            speak(phrase)

                            // Check the condition and wait before the next statement
                            conditionTimer({ followState == BeWithMeState.ABORT }, 5)
                        }

                        speak("Thank you very much for the head pats")

                        playMusic = true

                        setMainButtonMode(false)
                        goTo(
                            "greet tour",
                            "Hi every one, my name is Temi and I will be the one conducting this tour and showing you our engineering department. I am very excited to meet you all today. "
                        )

                        _gifResource.value  = R.drawable.how_talk

                        speak("Before we begin, I would like to let everyone know that I am able to recognise speech. However, I can only do this if this icon pops up.", haveFace = false)
                        robotController.wakeUp() // This will start the listen mode
                        delay(3000)
                        robotController.finishConversation()
                        speak("When this happens, please respond and say something once. I am not very good yet at recognizing speech, so if you say something to quickly or too many times I will get confused. I will try my best though.", haveFace = false)
                        speak("Should we test this out now?", haveFace = false)

                        _gifResource.value  = R.drawable.idle

                        getUseConfirmation(
                            "Is everyone ready for the tour? Please just say yes or no!",
                            "Well to bad, we are doing it anyway",
                            5000L,
                            "Yay, I am so excited",
                            "Sorry, I did not understand what you said. Could you repeat yourself.",
                            "You do not have to ignore me. I have feelings too you know."
                        ) {
                            exitCaseCheckIfUserClose(
                                "I will now begin the tour. Please follow me!",
                                "Sorry, you are currently too close to me, may you please take a couple steps back?"
                            )
                        }



                        stateFinished()
                    }

                    TourState.RAMP -> {

                    }

                    TourState.STAGE_1 -> {
                        goToSpeed(SpeedLevel.MEDIUM)

                        goTo(
                            "r410 front door",
                            "Our first stop is room R410. I would like to welcome you to NYP. In particular, our engineering department. In this tour I will show you a couple of the facilities that we have to help our students pursue their goals and dreams."
                        )

                        goToSpeed(SpeedLevel.HIGH)

                        speak("I have made it to the r410 front door location")

                        while (true) {
                            if (yPosition != YDirection.MISSING) {
                                if (yPosition == YDirection.CLOSE) {
                                    speak("Sorry, you are a bit too close for my liking, could you take a couple steps back?")
                                    conditionTimer({ yPosition != YDirection.CLOSE }, time = 5)

                                    if (yPosition != YDirection.CLOSE) {
                                        speak("Thank you")
                                    }

                                } else {
                                    speak("Welcome to block R level 4. Before we begin I would like to explain a couple of my capabilities.")
                                    break
                                }
                            } else {
                                speak("Please Stand in front of me and I will begin the tour.")
                                conditionTimer({ yPosition != YDirection.MISSING }, time = 2)

                                if (yPosition == YDirection.MISSING) {
                                    turnBy(180)
                                    speak("Sorry, I need you to stand in front of me to begin the tour.")
                                    turnBy(180)

                                    conditionTimer({ yPosition != YDirection.MISSING }, time = 5)
                                }
                                if (yPosition != YDirection.MISSING) {
                                    speak("Thank You")
                                }
                            }
                            buffer()
                        }

                        getUseConfirmation(
                            "If you are ready for me to continue please say Yes. Otherwise, say no and I will wait.",
                            "Ok, I will wait a little bit.",
                            5000L,
                            "Great, I will now begin my demonstration",
                            "Sorry, I did not understand what you said. Could you repeat yourself.",
                            "Sorry, I must ask you to come back."
                        )

                        stateFinished()
                    }

                    TourState.STAGE_1_B -> {

                        speak(" As I go up this ramp, please don’t assist me. I may struggle a bit because of the black lines on the ramp. I rely on infrared sensors to detect sudden drops on the ground to avoid falling, but black absorbs infrared light more than other colors. This means the intensity of infrared light I receive back is lower then it otherwise would be. This can make it seem to me like there’s a drop, which is why I have difficulty here. But don’t worry, I’m big and strong enough to handle it on my own!", setConditionGate = false)

                        goTo("before ramp")
                        skidJoy(1.0F, 0.0F, 8)

                        goTo("middle ramp")
                        skidJoy(1.0F, 0.0F, 8)

                        goTo(
                            "r410 back door",
                                    "Now that we have that covered, our first stop is room R410. Welcome to NYP, specifically to our engineering department! On this tour, I’ll be showing you some of the facilities we have that support our students in pursuing their goals and dreams.\"",
                            true
                        )

                        speak("I have made it to the r410 back door location")

                        stateFinished()
                    }

                    TourState.STAGE_1_1 -> { //**************************************************************
                        // Check if everyone is ready
                        shouldExit = false

                        /*
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
                            } else {
                                robotController.speak(
                                    "Sorry, I need you to remain in front of me for this tour",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                            }
                            buffer()
                        }
                         */

                        speak("My first capability, one that you might have noted, is my ability to detect how close someone is in front of me. For this example, I want everyone to be far away from me")

//                        // Get everyone to move far away from temi
                        while (true) {
                            if (yPosition == YDirection.FAR) {
                                speak("Great, this distance is what I consider you to be far away from me. Can I have one person move a little bit closer?")
                                break
                            } else {
                                speak("Sorry, Could you step back a bit more.")
                                conditionTimer(
                                    { yPosition == YDirection.FAR || yPosition == YDirection.MISSING },
                                    time = 4
                                )

                                if (yPosition == YDirection.FAR || yPosition == YDirection.MISSING) {
                                    speak("Thank you")
                                }
                            }
                            buffer()
                        }

                        // Get one person to move close to Temi
                        // Step 2: Get one person to move close to Temi at midrange
                        while (true) {
                            when (yPosition) {
                                YDirection.MIDRANGE -> {
                                    speak("Perfect, this distance is my Midrange. Please stay at least this distance to allow me to navigate easily.")
                                    break
                                }

                                YDirection.CLOSE -> {
                                    speak("Sorry, could you step back a bit more.")
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 4)
                                }

                                YDirection.FAR, YDirection.MISSING -> {
                                    speak("Sorry, could you come a bit closer.")
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 4)
                                }
                            }
                            if (yPosition == YDirection.MIDRANGE) speak("Thank you")
                            buffer()
                        }

                        stateFinished()
                    }

                    TourState.STAGE_1_1_B -> {
                        // Check if everyone is ready
                        shouldExit = false

                        /*
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
                            } else {
                                robotController.speak(
                                    "Sorry, I need you to remain in front of me for this tour",
                                    buffer
                                )
                                conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                            }
                            buffer()
                        }
                         */

                        speak("My first capability, one that you might have noted, is my ability to detect how close someone is in front of me. For this example, I want everyone to be far away from me")

//                        // Get everyone to move far away from temi
                        while (true) {
                            if (yPosition == YDirection.FAR || yPosition == YDirection.MISSING) {
                                speak("Great, this distance is what I consider you to be far away from me. Can I have one person move a little bit closer? Try and position yourself to be about a meter away from me.")
                                break
                            } else {
                                speak("Sorry, Could you step back a bit more.")
                                conditionTimer({ yPosition == YDirection.FAR }, time = 1)

                                if (yPosition == YDirection.FAR) {
                                    speak("Thank you")
                                }
                            }
                            buffer()
                        }

                        // Get one person to move close to Temi
                        // Step 2: Get one person to move close to Temi at midrange
                        while (true) {
                            when (yPosition) {
                                YDirection.MIDRANGE -> {
                                    speak("Perfect, this distance is my Midrange. Please stay at least this distance to allow me to navigate easily.")
                                    break
                                }

                                YDirection.CLOSE -> {
                                    speak("Sorry, could you step back a bit more.")
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 1)
                                }

                                YDirection.FAR, YDirection.MISSING -> {
                                    speak("Sorry, could you come a bit closer.")
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 1)
                                }
                            }
                            if (yPosition == YDirection.MIDRANGE) speak("Thank you")
                            buffer()
                        }

                        stateFinished()
                    }

                    TourState.STAGE_1_2 -> TODO()

                    TourState.STAGE_1_2_B -> {
                        val locations = listOf(
                            Pair("r417", true),
                            Pair("r416", true),
                            Pair("trophy cabinet 1", true),
                            Pair("award exit", true),
                            Pair("r405", true),
                            Pair("r412", true),
                            Pair("r407", true),
                            Pair("r410 poster spot", true)
                        )

// Cycle through each location with its direction and custom script
                        for ((location, backwards) in locations) {
                            // Use a `when` expression to determine the script for each location
                            var script = "hello"
                            when (location) {
                                "r417" -> {
                                    delay(1000)
                                    script =
                                        "The lab in front of you is the Electrical Machines & Drives Lab. Electrical machines are found everywhere in our daily lives, either serving us directly or assisting us in performing various tasks. Here, you will learn the latest knowledge and skills related to machines and drives, and perform simulations using industry-standard software and technologies, such as those from FESTO. As a result, learners will gain a strong understanding of the different drives and machines suitable for various applications."
                                    // goTo(location)
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.r417
                                    // speak(script, haveFace = false)
                                    goTo(location, script, haveFace = false, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "r416" -> {
                                    delay(1000)
                                    script =
                                        "In front of you is the Mechatronics Systems Integration Lab. In this lab, students will acquire the skills needed to program microcontrollers to control peripherals. A microcontroller is a small computer built into a metal-oxide-semiconductor integrated circuit. It is the heart of many automatically controlled products and devices, such as implantable medical devices, smart devices, sensors, and more. With the advancement of technology, microcontrollers have become an integral part of connecting our physical environment to the digital world, thereby improving our lives."
                                    goTo(location, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.r416
                                    speak(script, haveFace = false, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "trophy cabinet 1" -> {
                                    delay(1000)
                                    script =
                                        "Our next stop is the NYP trophy cabinet. First and foremost on display are the many trophies we have won as champions in various robot categories at the annual Singapore Robotics Games. Different robots, such as legged robots and snakes, were designed, built, and developed in-house to participate in sprints, long-distance races, and even entertainment challenges."
                                    goTo(location, speak = script, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                }

                                "award exit" -> {
                                    script =
                                        "Our students have not only used their creativity in competitions but also in developing products to solve real-world problems and address industry needs. On display, you can see examples of products jointly developed by both students and staff during the students' final-year projects. Over a 3-month period, students are tasked with designing and implementing solutions. Some of these outputs are directly translated into industry projects, which have been in collaboration with SEG since NYP's founding in 1993."
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.trophy
                                    goTo(location, speak = script, haveFace = false, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "r405" -> {
                                    script =
                                        "In front of you is the Robotic Automation & Control Lab. In this lab, students will acquire skills to program robots for various applications, such as handling, picking, and palletizing. These robots are commonly found in factories to automate simple and repetitive tasks that would otherwise require dedicated resources. However, with advancements in technology, robots have expanded their presence from manufacturing industries to other sectors such as clinical laboratories, agriculture, food and beverage, and education, where they work collaboratively with humans. \n" +
                                                "In addition to programming robots, machine vision plays an important role in robotic systems, enabling intelligent decision-making for complex tasks. Students will learn to perform identification and inspection using industrial-grade vision systems. \n" +
                                                "With these skill sets, students can pursue careers as Robotics Engineers, Quality Control Engineers, or System Engineers, where robotic systems and vision technologies are deployed in various applications."
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.r405
                                    goTo(location, speak = script, haveFace = false, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "r412" -> {
                                    script =
                                        "Too your left is the Siemens Control Lab, where our learners gain knowledge in areas such as pneumatics, sensors, and Programmable Logic Controllers (also known as PLCs). Here, actions like 'pick and place' are practiced and applied hands-on using industry-standard equipment from Siemens. This provides our students with first-hand experience with the technologies and skills the industry is seeking."
                                    goTo(location, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.r412
                                    speak(script, haveFace = false, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "r410 poster spot" -> {
                                    goTo(location, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                }

                                else -> {}
                            }
                        }

                        stateFinished()
                    }

                    TourState.TOUR_END -> {
                        speak("Thank you for taking my tour, it was great being able to meet you all.")
                        if (userName != null) {
                            speak("Especially you $userName")
                        }
                        speak("I look forward to meeting you all next time.")

                        goTo("r410 front door")

                        val goodbyePhrases = listOf(
                            "Thank you for joining me today!",
                            "I hope you had a wonderful time!",
                            "It was a pleasure showing you around!",
                            "Safe travels and goodbye!",
                            "I can't wait to see you again!",
                            "Take care and have a fantastic day!"
                        )

                        // While loop to monitor the follow state and express excitement
                        var repeat = 0

                        while (repeat != 10) {
                            repeat++
                            // Select a random phrase from the list and speak it
                            val phrase = goodbyePhrases.random()
                            speak(phrase)

                            // Check the condition and wait before the next statement
                            conditionTimer({ followState == BeWithMeState.SEARCH }, 5)
                        }

                        goTo("home base")

                        speak("I am ready for the next tour")

                        stateFinished()
                    }

                    TourState.TERMINATE -> {
                        while (true) {
                            buffer()
                        }
                        // This is to add a stopping point in the code
                    }

                    TourState.NULL -> {
//                        val locations = listOf(
//                            Pair("r410 back door", false),
//                            Pair("r417", true),
//                            Pair("r416", true),
//                            Pair("r405", true),
//                            Pair("r412", true),
//                            Pair("r406", true),
//                            Pair("r407", true),
//                            Pair("r411", true)
//                        )
//
//// Cycle through each location with its direction
//                        for ((location, backwards) in locations) {
//                            speak(location)
//                            goTo(location, backwards = backwards)
//                        }
                    }

                    TourState.TESTING -> {
//                        speak("My name is temi. How are you. Are you doing good. Wow, that sounds great.", setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionDeviceMoved = true, setInterruptConditionUSerToClose = true)
                        goToSpeed(SpeedLevel.SLOW)
                        //speak(speak = "What do you do with a drunken sailor. Put him in the bed with the captains daughter. Way hay and up she rises", setInterruptSystem = true, setInterruptConditionUserMissing = false, setInterruptConditionUSerToClose = true, setInterruptConditionDeviceMoved = false)
                        goTo("test point 1", speak = "What do you do with a drunken sailor. Put him in a long boat till his sober. Way hay and up she rises. What do you do with a drunken sailor. Shave his belly with a rusty razor. Way hay and up she rises. What do you do with a drunken sailor. Put him in a long boat till his sober. Way hay and up she rises. What do you do with a drunken sailor. Shave his belly with a rusty razor. Way hay and up she rises", backwards = true, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                        // speak(speak = "What do you do with a drunken sailor. Stick him in a scupper with a hosepipe bottom. Way hay and up she rises", setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                        goTo("test point 2", speak = "What do you do with a drunken sailor. Put him in a long boat till his sober. Way hay and up she rises. What do you do with a drunken sailor. Shave his belly with a rusty razor. Way hay and up she rises. What do you do with a drunken sailor. Put him in a long boat till his sober. Way hay and up she rises. What do you do with a drunken sailor. Shave his belly with a rusty razor. Way hay and up she rises", setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                    }

                    TourState.GET_USER_NAME -> {
                        // Stuff below gets userName
                        speak("While you are there, do you mind if I ask for your name?")

                        var attempts = 0
                        userName = null
                        while (true) {
                            if (attempts > 5) break
                            listen()
                            if (userResponse != null) {
                                if (containsPhraseInOrder(userResponse, reject, true)) {
                                    break
                                }

                                userName = extractName(userResponse!!)
                                if (userName != null) {
                                    var gotName = false
                                    speak("I think your name is $userName, is that correct?")

                                    while (true) {
                                        listen()

                                        when { // Confirmation gate based on user input
                                            containsPhraseInOrder(userResponse, reject, true) -> {
                                                speak("Okay, let’s try again.")
                                                break
                                            }

                                            containsPhraseInOrder(
                                                userResponse,
                                                confirmation,
                                                true
                                            ) -> {
                                                speak("Great!")
                                                gotName = true
                                                break
                                            }

                                            else -> {
                                                speak("Sorry, I did not hear you clearly. Could you confirm your name?")
                                            }
                                        }

                                        buffer() // Buffer pause for user response
                                    }

                                    if (gotName) {
                                        break  // Exit main loop after confirmation
                                    }

                                } else {
                                    speak("Sorry, I didn’t catch your name. Try using a phrase like, 'my name is...'")
                                }
                            } else {
                                speak("Sorry, I didn’t hear you. Could you repeat yourself?")
                            }
                            attempts++
                            buffer()  // Slight pause before the next attempt
                        }

                        if (userName == null) {
                            speak("It seems I couldn't get your name. Feel free to introduce yourself again later.")
                        } else {
                            speak("Hi there, $userName. My name is Temi. It's nice to meet you.")
                        }

                        stateFinished()
                    }

                    TourState.TEMI_V2 -> {

                    }
                }
                buffer()
            }
        }
        // *********************************************************************************************** DO NOT WORRY ABOUT ANYTHING DOWN HERE!
        //******************************************** Do not worry about the other launches.
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
                                    })
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
                    speechUpdatedValue = status.result
                    speech = status.result
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

    //**************************Functions for the ViewModel <- these are the ones that you should car about
//    suspend fun speech(text: String) {
//        robotController.speak(text, buffer)
//        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
//        conditionTimer({ !(dragged.value.state || lifted.value.state) }, time = 2)
//    }

    //**************************Functions for the View  <- Don't worry about this for the tour
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
                delay(1000)
//            Log.i("Trigger", trigger().toString())
                if (trigger()) {
                    break
                }
            }
        }
    }

    private suspend fun conditionGate(trigger: () -> Boolean, log: Boolean = false) {
        // Loop until the trigger condition returns false
        while (trigger()) {
        if (log) Log.i("DEBUG!", "Trigger: ${trigger()}")
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

