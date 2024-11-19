package com.temi.temiTour

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.provider.Settings.Global.getString
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.constants.HardButton
import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.navigation.model.SpeedLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.math.*
import androidx.lifecycle.viewModelScope
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.model.Model
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
    CHATGPT,
    TEMI_V2
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val robotController: RobotController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // These collect data from services in robotController
    private val ttsStatus = robotController.ttsStatus // Current speech state
    private val detectionStatus = robotController.detectionStateChangedStatus
    private val detectionData = robotController.detectionDataChangedStatus
    private val movementStatus = robotController.movementStatusChangedStatus
    private val lifted = robotController.lifted
    private val dragged = robotController.dragged
    private val askResult = robotController.askResult
    private val language = robotController.language
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
    public var playWaitMusic = false

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

    fun updateGifResource(resourceId: Int) {
        _gifResource.value = resourceId
    }

    // Function to toggle the GIF state
    fun toggleGif() {
        _shouldPlayGif.value = !_shouldPlayGif.value
    }

    //******************************************** Stuff for the tour
    // key word lists
    private val confirmation = listOf(
        "是", "好的", "行", "我愿意", "算我一个", "绝对没问题",
        "当然", "现在就", "走吧", "我会到的",
        "听起来不错", "我可以参加", "我准备好了", "就这么定了",
        "一定", "我在路上", "我会来"
    )

    private val reject = listOf(
        "不", "现在不行", "不能", "不参加", "赶不上",
        "不可能", "抱歉", "我有安排", "不去",
        "很遗憾不能", "我做不到", "遗憾地说不",
        "不行", "不用了", "我很忙", "我需要拒绝"
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
                    robotController.speak(context.getString(R.string.thank_you), buffer)
                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                }
            }
        }
    }

    private fun extractName(userResponse: String): String? {
        // Define common patterns for introducing names
//        val namePatterns = listOf(
//            "my name is ([A-Za-z]+)",  // e.g., "My name is John"
//            "i am ([A-Za-z]+)",        // e.g., "I am Alice"
//            "it's ([A-Za-z]+)",        // e.g., "It's Bob"
//            "this is ([A-Za-z]+)",     // e.g., "This is Sarah"
//            "call me ([A-Za-z]+)",     // e.g., "Call me Mike"
//            "name is ([A-Za-z]+)",
//            "is ([A-Za-z]+)",
//            "me ([A-Za-z]+)",
//            "i ([A-Za-z]+)",
//            "am ([A-Za-z]+)"
//        )

        val namePatterns = listOf(
            "我叫([\\u4e00-\\u9fa5]+)",  // e.g., "我叫小明"
            "我的名字是([\\u4e00-\\u9fa5]+)",  // e.g., "我的名字是李华"
            "我是([\\u4e00-\\u9fa5]+)",  // e.g., "我是张伟"
            "这是([\\u4e00-\\u9fa5]+)",  // e.g., "这是王芳"
            "叫我([\\u4e00-\\u9fa5]+)",  // e.g., "叫我小李"
            "名字是([\\u4e00-\\u9fa5]+)",  // e.g., "名字是陈琳"
            "是([\\u4e00-\\u9fa5]+)",  // e.g., "是刘强"
            "我([\\u4e00-\\u9fa5]+)",  // e.g., "我李杰"
            "叫([\\u4e00-\\u9fa5]+)",  // e.g., "叫韩梅"
            "名([\\u4e00-\\u9fa5]+)"  // e.g., "名赵云"
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
//        val singleWordPattern = Pattern.compile("^[A-Za-z]+$", Pattern.CASE_INSENSITIVE)
        val singleWordPattern = Pattern.compile("^[\\u4e00-\\u9fa5]+$", Pattern.CASE_INSENSITIVE)
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

    private fun tiltAngle(degree: Int) {
        robotController.tileAngle(degree)
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
                            else if (!setInterruptSystem) {
                                repeatSpeechFlag = false
                            }

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
                                    else if (!setInterruptSystem) {
                                        repeatSpeechFlag = false
                                    }

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
        if (setInterruptSystem) {
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
        } else {
            while (true) { // loop until to makes it to the start location
                Log.i("DEBUG!", "Has Gone To Location none: $hasGoneToLocation")

                if (!hasGoneToLocation) { robotController.goTo(location, backwards); Log.i("DEBUG!", "Hello none: $repeatGoToFlag ")}

                buffer()
                Log.i("DEBUG!", "Triggered? none: ")
                conditionGate({ goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT}, true)

                Log.i("DEBUG!", "Should exit none" + (hasGoneToLocation && !repeatGoToFlag).toString())

                if (hasGoneToLocation) break
                else if (goToLocationState == LocationState.COMPLETE) hasGoneToLocation = true

                buffer()

            }
        }

        // Log.i("DEBUG!", "THREE: $talkingInThreadFlag")
        if (speak != null) conditionGate({talkingInThreadFlag}, false)// conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)})

        // Log.i("DEBUG!", "$location: " + triggeredInterrupt)
        updateInterruptFlag("userMissing", false)
        updateInterruptFlag("userTooClose", false)
        updateInterruptFlag("deviceMoved", false)
    }

    private val apiKey = "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"

    private val openAI = OpenAI(apiKey)

    // Store response from GPT here, when used make null
    private var responseGPT: String? = null

    // Use this to tell system if waiting, Null is default or Error
    private var errorFlagGPT: Boolean = false

    private fun sendMessage(openAI: OpenAI, userResponse: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                // Define the model you want to use (GPT-3.5 or GPT-4)
                val modelId = ModelId("gpt-4o-mini")

                // Prepare the initial user message
                val chatMessages = mutableListOf(
                    chatMessage {
                        role = ChatRole.System
                        content = "You are an assistant embedded in a robot. Respond as sassy and snarky as possible to user queries. Ensure to keep responses very short so that it is not above 100 words. Ensure to respond in Mandarin (Chinese) and not english"
                    },
                    chatMessage {
                        role = ChatRole.User
                        content = userResponse
                    }
                )

                // Create the chat completion request
                val request = chatCompletionRequest {
                    model = modelId
                    messages = chatMessages
                }

                // Send the request and receive the response
                val response = openAI.chatCompletion(request)

                // Extract and log the model's response
                val modelResponse = response.choices.first().message.content.orEmpty()
                Log.d("DEBUG!", modelResponse)
                responseGPT = modelResponse
            } catch (e: Exception) {
                Log.e("DEBUG!", "Error sending message: ${e.message}")
                errorFlagGPT = true
            }
        }
    }

    init {

        // thread used for handling interrupt system
        viewModelScope.launch {
            launch {
                while(true) {
//                     Log.i("DEBUG!", "In misuse state: ${isMisuseState()}")
                     Log.i("DEBUG!", "Current Language: ${language.value}")
                    if ((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] == true)) {
                        conditionTimer({!((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || (isMisuseState()) && interruptFlags["deviceMoved"] == true)}, interruptTriggerDelay)
                        if ((yPosition != YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition != YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] != true)) continue
                        triggeredInterrupt = true
                        repeatSpeechFlag = true
                        repeatGoToFlag = true
                        // Log.i("DEBUG!", "Trigger Stopped")
                        stopMovement()
                    } else {
//                        Log.i("DEBUG!", "Trigger Stopped")
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
//                    Log.i("DEBUG!", "In start location")

//                    tourState(TourState.TESTING)
//                    tourState(TourState.CHATGPT)

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
                            context.getString(R.string.hi_there_would_you_like_to_take_a_tour_please_just_say_yes_or_no),
                            context.getString(R.string.ok_if_you_change_your_mind_feel_free_to_come_back_and_ask),
                            5000L,
                            context.getString(R.string.yay_i_am_so_excited),
                            context.getString(R.string.sorry_i_did_not_understand_what_you_said_could_you_repeat_yourself),
                            context.getString(R.string.you_do_not_have_to_ignore_me_i_have_feelings_too_you_know)
                        ) {
                            exitCaseCheckIfUserClose(
                                context.getString(R.string.i_will_now_begin_the_tour_please_follow_me),
                                context.getString(R.string.sorry_you_are_currently_too_close_to_me_may_you_please_take_a_couple_steps_back)
                            )
                        }

                        stateFinished()
                    }

                    TourState.ALTERNATE_START -> {
                        setMainButtonMode(true)
                        conditionGate({ followState != BeWithMeState.TRACK })
                        speak(context.getString(R.string.i_am_now_following_you))

                        val excitementPhrases = listOf(
                            context.getString(R.string.i_am_so_excited),
                            context.getString(R.string.i_can_t_wait_to_start_this_tour),
                            context.getString(R.string.this_is_going_to_be_so_much_fun),
                            context.getString(R.string.i_cannot_wait_to_show_them_around),
                            context.getString(R.string.i_can_not_wait_to_get_this_adventure_started)
                        )

                        // While loop to monitor the follow state and express excitement
                        while (followState != BeWithMeState.ABORT) {
                            // Select a random phrase from the list and speak it
                            val phrase = excitementPhrases.random()
                            speak(phrase)

                            // Check the condition and wait before the next statement
                            conditionTimer({ followState == BeWithMeState.ABORT }, 5)
                        }

                        speak(context.getString(R.string.thank_you_very_much_for_the_head_pats))

                        playMusic = true

                        setMainButtonMode(false)
                        goTo(
                            context.getString(R.string.greet_tour),
                            context.getString(R.string.hi_every_one_my_name_is_temi_and_i_will_be_the_one_conducting_this_tour_and_showing_you_our_engineering_department_i_am_very_excited_to_meet_you_all_today)
                        )

                        _gifResource.value  = R.drawable.how_talk

                        speak(context.getString(R.string.before_we_begin_i_would_like_to_let_everyone_know_that_i_am_able_to_recognise_speech_however_i_can_only_do_this_if_this_icon_pops_up), haveFace = false)
                        robotController.wakeUp() // This will start the listen mode
                        delay(3000)
                        robotController.finishConversation()
                        speak(context.getString(R.string.when_this_happens_please_respond_and_say_something_once_i_am_not_very_good_yet_at_recognizing_speech_so_if_you_say_something_to_quickly_or_too_many_times_i_will_get_confused_i_will_try_my_best_though), haveFace = false)
                        speak(context.getString(R.string.should_we_test_this_out_now), haveFace = false)

                        _gifResource.value  = R.drawable.idle

                        getUseConfirmation(
                            context.getString(R.string.is_everyone_ready_for_the_tour_please_just_say_yes_or_no),
                            context.getString(R.string.well_to_bad_we_are_doing_it_anyway),
                            5000L,
                            context.getString(R.string.yay_i_am_so_excited),
                            context.getString(R.string.sorry_i_did_not_understand_what_you_said_could_you_repeat_yourself),
                            context.getString(R.string.you_do_not_have_to_ignore_me_i_have_feelings_too_you_know)
                        ) {
                            exitCaseCheckIfUserClose(
                                context.getString(R.string.i_will_now_begin_the_tour_please_follow_me),
                                context.getString(R.string.sorry_you_are_currently_too_close_to_me_may_you_please_take_a_couple_steps_back)
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
                            context.getString(R.string.our_first_stop_is_room_r410_i_would_like_to_welcome_you_to_nyp_in_particular_our_engineering_department_in_this_tour_i_will_show_you_a_couple_of_the_facilities_that_we_have_to_help_our_students_pursue_their_goals_and_dreams)
                        )

                        goToSpeed(SpeedLevel.HIGH)

                        speak(context.getString(R.string.i_have_made_it_to_the_r410_front_door_location))

                        while (true) {
                            if (yPosition != YDirection.MISSING) {
                                if (yPosition == YDirection.CLOSE) {
                                    speak(context.getString(R.string.sorry_you_are_currently_too_close_to_me_may_you_please_take_a_couple_steps_back))
                                    conditionTimer({ yPosition != YDirection.CLOSE }, time = 5)

                                    if (yPosition != YDirection.CLOSE) {
                                        speak(context.getString(R.string.thank_you))
                                    }

                                } else {
                                    speak(context.getString(R.string.welcome_to_block_r_level_4_before_we_begin_i_would_like_to_explain_a_couple_of_my_capabilities))
                                    break
                                }
                            } else {
                                speak(context.getString(R.string.please_stand_in_front_of_me_and_i_will_begin_the_tour))
                                conditionTimer({ yPosition != YDirection.MISSING }, time = 2)

                                if (yPosition == YDirection.MISSING) {
                                    turnBy(180)
                                    speak(context.getString(R.string.sorry_i_need_you_to_stand_in_front_of_me_to_begin_the_tour))
                                    turnBy(180)

                                    conditionTimer({ yPosition != YDirection.MISSING }, time = 5)
                                }
                                if (yPosition != YDirection.MISSING) {
                                    speak(context.getString(R.string.thank_you))
                                }
                            }
                            buffer()
                        }

                        getUseConfirmation(
                            context.getString(R.string.if_you_are_ready_for_me_to_continue_please_say_yes_otherwise_say_no_and_i_will_wait),
                            context.getString(R.string.ok_i_will_wait_a_little_bit),
                            5000L,
                            context.getString(R.string.great_i_will_now_begin_my_demonstration),
                            context.getString(R.string.sorry_i_did_not_understand_what_you_said_could_you_repeat_yourself),
                            context.getString(R.string.sorry_i_must_ask_you_to_come_back)
                        )

                        stateFinished()
                    }

                    TourState.STAGE_1_B -> {

                        speak(context.getString(R.string.as_i_go_up_this_ramp_please_don_t_assist_me_i_may_struggle_a_bit_because_of_the_black_lines_on_the_ramp_i_rely_on_infrared_sensors_to_detect_sudden_drops_on_the_ground_to_avoid_falling_but_black_absorbs_infrared_light_more_than_other_colors_this_means_the_intensity_of_infrared_light_i_receive_back_is_lower_then_it_otherwise_would_be_this_can_make_it_seem_to_me_like_there_s_a_drop_which_is_why_i_have_difficulty_here_but_don_t_worry_i_m_big_and_strong_enough_to_handle_it_on_my_own), setConditionGate = false)

                        goTo("before ramp")
                        skidJoy(1.0F, 0.0F, 8)

                        goTo("middle ramp")
                        skidJoy(1.0F, 0.0F, 8)

                        goTo(
                            "r410 back door",
                            context.getString(R.string.now_that_we_have_that_covered_our_first_stop_is_room_r410_welcome_to_nyp_specifically_to_our_engineering_department_on_this_tour_i_ll_be_showing_you_some_of_the_facilities_we_have_that_support_our_students_in_pursuing_their_goals_and_dreams),
                            true
                        )

                        speak(context.getString(R.string.i_have_made_it_to_the_r410_back_door_location))

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

                        speak(context.getString(R.string.my_first_capability_one_that_you_might_have_noted_is_my_ability_to_detect_how_close_someone_is_in_front_of_me_for_this_example_i_want_everyone_to_be_far_away_from_me))

//                        // Get everyone to move far away from temi
                        while (true) {
                            if (yPosition == YDirection.FAR) {
                                speak(context.getString(R.string.great_this_distance_is_what_i_consider_you_to_be_far_away_from_me_can_i_have_one_person_move_a_little_bit_closer))
                                break
                            } else {
                                speak(context.getString(R.string.sorry_could_you_step_back_a_bit_more))
                                conditionTimer(
                                    { yPosition == YDirection.FAR || yPosition == YDirection.MISSING },
                                    time = 4
                                )

                                if (yPosition == YDirection.FAR || yPosition == YDirection.MISSING) {
                                    speak(context.getString(R.string.thank_you))
                                }
                            }
                            buffer()
                        }

                        // Get one person to move close to Temi
                        // Step 2: Get one person to move close to Temi at midrange
                        while (true) {
                            when (yPosition) {
                                YDirection.MIDRANGE -> {
                                    speak(context.getString(R.string.perfect_this_distance_is_my_midrange_please_stay_at_least_this_distance_to_allow_me_to_navigate_easily))
                                    break
                                }

                                YDirection.CLOSE -> {
                                    speak(context.getString(R.string.sorry_could_you_step_back_a_bit_more))
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 4)
                                }

                                YDirection.FAR, YDirection.MISSING -> {
                                    speak(context.getString(R.string.sorry_could_you_come_a_bit_closer))
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 4)
                                }
                            }
                            if (yPosition == YDirection.MIDRANGE) speak(context.getString(R.string.thank_you))
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

                        speak(context.getString(R.string.my_first_capability_one_that_you_might_have_noted_is_my_ability_to_detect_how_close_someone_is_in_front_of_me_for_this_example_i_want_everyone_to_be_far_away_from_me))

//                        // Get everyone to move far away from temi
                        while (true) {
                            if (yPosition == YDirection.FAR || yPosition == YDirection.MISSING) {
                                speak(context.getString(R.string.great_this_distance_is_what_i_consider_you_to_be_far_away_from_me_can_i_have_one_person_move_a_little_bit_closer_try_and_position_yourself_to_be_about_a_meter_away_from_me))
                                break
                            } else {
                                speak(context.getString(R.string.sorry_could_you_step_back_a_bit_more))
                                conditionTimer({ yPosition == YDirection.FAR }, time = 1)

                                if (yPosition == YDirection.FAR) {
                                    speak(context.getString(R.string.thank_you))
                                }
                            }
                            buffer()
                        }

                        // Get one person to move close to Temi
                        // Step 2: Get one person to move close to Temi at midrange
                        while (true) {
                            when (yPosition) {
                                YDirection.MIDRANGE -> {
                                    speak(context.getString(R.string.perfect_this_distance_is_my_midrange_please_stay_at_least_this_distance_to_allow_me_to_navigate_easily))
                                    break
                                }

                                YDirection.CLOSE -> {
                                    speak(context.getString(R.string.sorry_could_you_step_back_a_bit_more))
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 1)
                                }

                                YDirection.FAR, YDirection.MISSING -> {
                                    speak(context.getString(R.string.sorry_could_you_come_a_bit_closer))
                                    conditionTimer({ yPosition == YDirection.MIDRANGE }, time = 1)
                                }
                            }
                            if (yPosition == YDirection.MIDRANGE) speak(context.getString(R.string.thank_you))
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
                                        context.getString(R.string.the_lab_in_front_of_you_is_the_electrical_machines_drives_lab_electrical_machines_are_found_everywhere_in_our_daily_lives_either_serving_us_directly_or_assisting_us_in_performing_various_tasks_here_you_will_learn_the_latest_knowledge_and_skills_related_to_machines_and_drives_and_perform_simulations_using_industry_standard_software_and_technologies_such_as_those_from_festo_as_a_result_learners_will_gain_a_strong_understanding_of_the_different_drives_and_machines_suitable_for_various_applications)
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
                                        context.getString(R.string.in_front_of_you_is_the_mechatronics_systems_integration_lab_in_this_lab_students_will_acquire_the_skills_needed_to_program_microcontrollers_to_control_peripherals_a_microcontroller_is_a_small_computer_built_into_a_metal_oxide_semiconductor_integrated_circuit_it_is_the_heart_of_many_automatically_controlled_products_and_devices_such_as_implantable_medical_devices_smart_devices_sensors_and_more_with_the_advancement_of_technology_microcontrollers_have_become_an_integral_part_of_connecting_our_physical_environment_to_the_digital_world_thereby_improving_our_lives)
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
                                        context.getString(R.string.our_next_stop_is_the_nyp_trophy_cabinet_first_and_foremost_on_display_are_the_many_trophies_we_have_won_as_champions_in_various_robot_categories_at_the_annual_singapore_robotics_games_different_robots_such_as_legged_robots_and_snakes_were_designed_built_and_developed_in_house_to_participate_in_sprints_long_distance_races_and_even_entertainment_challenges)
                                    goTo(location, speak = script, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                }

                                "award exit" -> {
                                    script =
                                        context.getString(R.string.our_students_have_not_only_used_their_creativity_in_competitions_but_also_in_developing_products_to_solve_real_world_problems_and_address_industry_needs_on_display_you_can_see_examples_of_products_jointly_developed_by_both_students_and_staff_during_the_students_final_year_projects_over_a_3_month_period_students_are_tasked_with_designing_and_implementing_solutions_some_of_these_outputs_are_directly_translated_into_industry_projects_which_have_been_in_collaboration_with_seg_since_nyp_s_founding_in_1993)
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.trophy
                                    goTo(location, speak = script, haveFace = false, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "r405" -> {
                                    script =
                                        context.getString(R.string.in_front_of_you_is_the_robotic_automation_control_lab_in_this_lab_students_will_acquire_skills_to_program_robots_for_various_applications_such_as_handling_picking_and_palletizing_these_robots_are_commonly_found_in_factories_to_automate_simple_and_repetitive_tasks_that_would_otherwise_require_dedicated_resources_however_with_advancements_in_technology_robots_have_expanded_their_presence_from_manufacturing_industries_to_other_sectors_such_as_clinical_laboratories_agriculture_food_and_beverage_and_education_where_they_work_collaboratively_with_humans) +
                                                context.getString(R.string.in_addition_to_programming_robots_machine_vision_plays_an_important_role_in_robotic_systems_enabling_intelligent_decision_making_for_complex_tasks_students_will_learn_to_perform_identification_and_inspection_using_industrial_grade_vision_systems) +
                                                context.getString(R.string.with_these_skill_sets_students_can_pursue_careers_as_robotics_engineers_quality_control_engineers_or_system_engineers_where_robotic_systems_and_vision_technologies_are_deployed_in_various_applications)
                                    _shouldPlayGif.value = false
                                    _imageResource.value = R.drawable.r405
                                    goTo(location, speak = script, haveFace = false, backwards = backwards, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
                                    buffer()
                                    _shouldPlayGif.value = true
                                }

                                "r412" -> {
                                    script =
                                        context.getString(R.string.too_your_left_is_the_siemens_control_lab_where_our_learners_gain_knowledge_in_areas_such_as_pneumatics_sensors_and_programmable_logic_controllers_also_known_as_plcs_here_actions_like_pick_and_place_are_practiced_and_applied_hands_on_using_industry_standard_equipment_from_siemens_this_provides_our_students_with_first_hand_experience_with_the_technologies_and_skills_the_industry_is_seeking)
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
                        speak(context.getString(R.string.thank_you_for_taking_my_tour_it_was_great_being_able_to_meet_you_all))
                        if (userName != null) {
                            speak("Especially you $userName")
                        }
                        speak(context.getString(R.string.i_look_forward_to_meeting_you_all_next_time))

                        goTo("r410 front door")

//                        val goodbyePhrases = listOf(
//                            "Thank you for joining me today!",
//                            "I hope you had a wonderful time!",
//                            "It was a pleasure showing you around!",
//                            "Safe travels and goodbye!",
//                            "I can't wait to see you again!",
//                            "Take care and have a fantastic day!"
//                        )

                        val goodbyePhrases = listOf(
                            "感谢今天的陪伴！",  // "Thank you for joining me today!"
                            "希望你度过了一段愉快的时光！",  // "I hope you had a wonderful time!"
                            "很高兴为你介绍！",  // "It was a pleasure showing you around!"
                            "祝你一路顺风，再见！",  // "Safe travels and goodbye!"
                            "迫不及待想再见到你！",  // "I can't wait to see you again!"
                            "保重，祝你有个美好的一天！"  // "Take care and have a fantastic day!"
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

                        speak(context.getString(R.string.i_am_ready_for_the_next_tour))

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
////                        speak("My name is temi. How are you. Are you doing good. Wow, that sounds great.", setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionDeviceMoved = true, setInterruptConditionUSerToClose = true)
//                        goToSpeed(SpeedLevel.SLOW)
////                        goTo("test point 1")
////                        goTo("test point 2")
//                         speak(speak = "Way hay and up she rises", setInterruptSystem = true, setInterruptConditionUserMissing = false, setInterruptConditionUSerToClose = true, setInterruptConditionDeviceMoved = false)
//                        goTo("test point 1", speak = "What do you do with a drunken sailor. Put him in a long boat till his sober. Way hay and up she rises. What do you do with a drunken sailor. Shave his belly with a rusty razor." , backwards = true, setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
//                        // speak(speak = "What do you do with a drunken sailor. Stick him in a scupper with a hosepipe bottom. Way hay and up she rises", setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)
//                        goTo("test point 2", speak = "What do you do with a drunken sailor. Put him in a long boat till his sober. Way hay and up she rises. What do you do with a drunken sailor. Shave his belly with a rusty razor.", setInterruptSystem = true, setInterruptConditionUserMissing = true, setInterruptConditionUSerToClose = false, setInterruptConditionDeviceMoved = false)

//                        while(true) { buffer() }
//                        speak("李老师您好，您能理解吗？")
//                        listen()
//                        speak("You are speaking ${language.value}")
                    }

                    TourState.GET_USER_NAME -> {
                        // Stuff below gets userName
                        speak(context.getString(R.string.while_you_are_there_do_you_mind_if_i_ask_for_your_name))

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
                                    speak(
                                        context.getString(
                                            R.string.i_think_your_name_is_is_that_correct,
                                            userName
                                        ))

                                    while (true) {
                                        listen()

                                        when { // Confirmation gate based on user input
                                            containsPhraseInOrder(userResponse, reject, true) -> {
                                                speak(context.getString(R.string.okay_let_s_try_again))
                                                break
                                            }

                                            containsPhraseInOrder(
                                                userResponse,
                                                confirmation,
                                                true
                                            ) -> {
                                                speak(context.getString(R.string.great))
                                                gotName = true
                                                break
                                            }

                                            else -> {
                                                speak(context.getString(R.string.sorry_i_did_not_hear_you_clearly_could_you_confirm_your_name))
                                            }
                                        }

                                        buffer() // Buffer pause for user response
                                    }

                                    if (gotName) {
                                        break  // Exit main loop after confirmation
                                    }

                                } else {
                                    speak(context.getString(R.string.sorry_i_didn_t_catch_your_name_try_using_a_phrase_like_my_name_is))
                                }
                            } else {
                                speak(context.getString(R.string.sorry_i_didn_t_hear_you_could_you_repeat_yourself))
                            }
                            attempts++
                            buffer()  // Slight pause before the next attempt
                        }

                        if (userName == null) {
                            speak(context.getString(R.string.it_seems_i_couldn_t_get_your_name_feel_free_to_introduce_yourself_again_later))
                        } else {
                            speak(
                                context.getString(
                                    R.string.hi_there_my_name_is_temi_it_s_nice_to_meet_you,
                                    userName
                                ))
                        }

                        stateFinished()
                    }

                    TourState.CHATGPT -> {
                        shouldExit = false
                        var response: String? = null
                        while(true) {
                            speak(context.getString(R.string.i_will_start_listening))
                            listen()
                            if (userResponse != null && userResponse != " " ) {
                                response = userResponse
                                speak(
                                    context.getString(
                                        R.string.did_you_say_please_just_say_yes_or_no,
                                        userResponse
                                    ))
                                while(true) {
                                    listen()
                                    if(userResponse != null && userResponse != " " ) {
                                        when { // Condition gate based on what the user says
                                            containsPhraseInOrder(userResponse, reject, true) -> {
                                                speak(context.getString(R.string.sorry_lets_try_this_again))
                                                break
                                            }

                                            containsPhraseInOrder(userResponse, confirmation, true) -> {
                                                speak(context.getString(R.string.great_let_me_think_for_a_moment))
                                                shouldExit = true
                                                break
                                            }

                                            else -> {
                                                speak(context.getString(R.string.sorry_i_did_not_understand_you))
                                            }
                                        }
                                    }
                                    buffer()
                                }
                                if(shouldExit) break
                            } else {
                                speak(context.getString(R.string.sorry_i_had_an_issue_with_hearing_you))
                            }
                            buffer()
                        }

//                        speak("Give me a moment, I am thinking.")
                        Log.i("DEBUG!", response.toString())
                        response?.let { sendMessage(openAI, it) }

                        playWaitMusic = true
                        updateGifResource(R.drawable.thinking)

                        conditionGate({responseGPT == null})
                        Log.i("DEBUG!", responseGPT.toString())
//
                        delay(10000)
                        playWaitMusic = false
                        updateGifResource(R.drawable.idle)

                        speak(responseGPT.toString())
                        responseGPT = null
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

private fun <T> LiveData<T>.observe(mainViewModel: MainViewModel, observer: Observer<T>) {

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

