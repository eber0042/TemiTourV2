package com.temi.temiTour

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.SttLanguage
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.listeners.OnDetectionStateChangedListener
import com.robotemi.sdk.listeners.OnDetectionDataChangedListener
import com.robotemi.sdk.listeners.OnMovementStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotLiftedListener
import com.robotemi.sdk.listeners.OnRobotDragStateChangedListener
import com.robotemi.sdk.listeners.OnTtsVisualizerWaveFormDataChangedListener
import com.robotemi.sdk.listeners.OnConversationStatusChangedListener
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.constants.HardButton
import com.robotemi.sdk.model.DetectionData
import com.robotemi.sdk.navigation.model.Position
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Singleton

data class TtsStatus(val status: TtsRequest.Status)
enum class DetectionStateChangedStatus(val state: Int) { // Why is it like this?
    DETECTED(state = 2),
    LOST(state = 1),
    IDLE(state = 0);

    companion object {
        fun fromState(state: Int): DetectionStateChangedStatus? = entries.find { it.state == state }
    }
}
data class DetectionDataChangedStatus( val angle: Double, val distance: Double)
enum class MovementType {
    SKID_JOY,
    TURN_BY,
    NONE
}
enum class MovementStatus {
    START,
    GOING,
    OBSTACLE_DETECTED,
    NODE_INACTIVE,
    CALCULATING,
    COMPLETE,
    ABORT
}
data class MovementStatusChangedStatus(
    val type: MovementType,   // Use the MovementType enum
    val status: MovementStatus  // Use the MovementStatus enum
)
data class Dragged(
    val state: Boolean
)
data class Lifted(
    val state: Boolean
)
data class AskResult(
    val result: String
)
data class WakeUp(
    val result: String
)
data class WaveForm(
    val result: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WaveForm

        return result.contentEquals(other.result)
    }

    override fun hashCode(): Int {
        return result.contentHashCode()
    }
}
data class ConversationStatus (
    val status: Int,
    val text: String
)
data class ConversationAttached (
    val isAttached: Boolean
)
enum class LocationState(val value:String) {
    START(value = "start"),
    CALCULATING(value = "calculating"),
    GOING(value = "going"),
    COMPLETE(value = "complete"),
    ABORT(value = "abort"),
    REPOSING(value = "reposing");

    companion object {
        fun fromLocationState(value: String): LocationState? = LocationState.entries.find { it.value == value }
    }
}


@Module
@InstallIn(SingletonComponent::class)
object RobotModule {
    @Provides
    @Singleton
    fun provideRobotController() = RobotController()
}

class RobotController():
    OnRobotReadyListener,
    OnDetectionStateChangedListener,
    Robot.TtsListener,
    OnDetectionDataChangedListener,
    OnMovementStatusChangedListener,
    OnRobotLiftedListener,
    OnRobotDragStateChangedListener,
    Robot.AsrListener,
    Robot.WakeupWordListener,
    OnTtsVisualizerWaveFormDataChangedListener,
    OnConversationStatusChangedListener,
    Robot.ConversationViewAttachesListener,
    OnGoToLocationStatusChangedListener
{
    private val robot = Robot.getInstance() //This is needed to reference the data coming from Temi

    // Setting up the Stateflows here
    private val _ttsStatus = MutableStateFlow( TtsStatus(status = TtsRequest.Status.PENDING) )
    val ttsStatus = _ttsStatus.asStateFlow()

    private val _detectionStateChangedStatus = MutableStateFlow(DetectionStateChangedStatus.IDLE)
    val detectionStateChangedStatus = _detectionStateChangedStatus.asStateFlow()

    private val _detectionDataChangedStatus = MutableStateFlow(DetectionDataChangedStatus(angle = 0.0, distance = 0.0))
    val detectionDataChangedStatus = _detectionDataChangedStatus.asStateFlow() // This can include talking state as well

    private val _movementStatusChangedStatus = MutableStateFlow(
        MovementStatusChangedStatus(
            MovementType.NONE, MovementStatus.NODE_INACTIVE
        )
    )
    val movementStatusChangedStatus = _movementStatusChangedStatus.asStateFlow() // This can include talking state as well

    private val _dragged = MutableStateFlow(Dragged(false))
    val dragged = _dragged.asStateFlow() // This can include talking state as well

    private val _lifted = MutableStateFlow(Lifted(false))
    val lifted = _lifted.asStateFlow() // This can include talking state as well

    private val _askResult = MutableStateFlow(AskResult(" "))
    val askResult = _askResult.asStateFlow()

    private val _wakeUp = MutableStateFlow(WakeUp("56"))
    val wakeUp = _wakeUp.asStateFlow()

    private val _waveform = MutableStateFlow(WaveForm(byteArrayOf(0)))
    val waveform = _waveform.asStateFlow()

    private val _conversationStatus = MutableStateFlow(ConversationStatus(status = 0, text = "56"))
    val conversationStatus = _conversationStatus.asStateFlow()

    private val _conversationAttached = MutableStateFlow(ConversationAttached(false))
    val conversationAttached = _conversationAttached.asStateFlow()

    private val _locationState = MutableStateFlow(LocationState.ABORT)
    val locationState = _locationState.asStateFlow()

    init {
        robot.addOnRobotReadyListener(this)
        robot.addTtsListener(this)
        robot.addOnDetectionStateChangedListener((this))
        robot.addOnDetectionDataChangedListener(this)
        robot.addOnMovementStatusChangedListener(this)
        robot.addOnRobotLiftedListener(this)
        robot.addOnRobotDragStateChangedListener(this)
        robot.addAsrListener(this)
        robot.addWakeupWordListener(this)
        robot.addOnTtsVisualizerWaveFormDataChangedListener(this)
        robot.addOnConversationStatusChangedListener(this)
        robot.addConversationViewAttachesListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
    }
    //********************************* General Functions
    suspend fun speak(speech: String, buffer: Long) {
        delay(buffer)
        val request = TtsRequest.create(
            speech = speech,
            isShowOnConversationLayer = false,
            showAnimationOnly = true,
        ) // Need to create TtsRequest
        robot.speak(request)
        delay(buffer)
    }

    suspend fun turnBy(degree: Int, speed: Float = 1f, buffer: Long) {
        delay(buffer)
        robot.turnBy(degree, speed)
        delay(buffer)
    }

    suspend fun tiltAngle(degree: Int, speed: Float = 1f, buffer: Long) {
        delay(buffer)
        robot.tiltAngle(degree, speed)
        delay(buffer)
    }

    fun listOfLocations() {
        Log.i("HOPE!", robot.locations.toString())
        Log.i("HOPE!", robot.wakeupWord)
        Log.i("HOPE!", robot.wakeupWordDisabled.toString())
    }

    fun goTo(location: String) {
        robot.goTo(location, noBypass = false)
    }

    fun goToPosition(position: Position) {
        robot.goToPosition(position)
    }

    fun askQuestion(question: String) {
        robot.askQuestion(question)
    }

    fun wakeUp() {
        robot.wakeup()
    }

    fun finishConversation() {
        robot.finishConversation()
    }
    fun getPosition(): Position {
        return robot.getPosition()
    }

    // Move these outside the function to maintain state across calls
    private val numberArray = (1..5).toMutableList()
    private var currentIndex = 0
    private var previousLastChoice = -1

    suspend fun textModelChoice(state: Int, buffer: Long) {
        // Function to get the next random number in the shuffled array
        fun getRandomChoice(): Int {
            if (currentIndex >= numberArray.size) {
                numberArray.shuffle()  // Reshuffle when the array is exhausted
                currentIndex = 0

                // Ensure the first choice isn't the same as the last choice from the previous array
                if (numberArray[0] == previousLastChoice) {
                    // Find a random index to swap with the first element
                    val swapIndex = (1 until numberArray.size).random()  // Get a random index (1..4)
                    val temp = numberArray[0]
                    numberArray[0] = numberArray[swapIndex]
                    numberArray[swapIndex] = temp
                }
            }

            val choice = numberArray[currentIndex]  // Get the current choice
            currentIndex++  // Move to the next index
            previousLastChoice = choice  // Update the last choice to the current choice

            return choice
        }

        val choice = getRandomChoice()  // Get a randomized choice

        when (state) {
            0 -> { // All answers correct
                Log.d("Quiz", "Perfect")
                when (choice) {
                    1 -> speak(speech = "Oh, you got it right? You want a medal or something?", buffer)
                    2 -> speak(speech = "Congratulations! You must be so proud... of answering a quiz question.", buffer)
                    3 -> speak(speech = "Wow, you did it! Now go do something actually challenging.", buffer)
                    4 -> speak(speech = "You got it right, big deal. Let’s not get carried away.", buffer)
                    5 -> speak(speech = "Perfect score, huh? Enjoy your moment of glory, it’s not lasting long.", buffer)
                }
            }

            1 -> { // Partially correct
                Log.d("Quiz", "Partial")
                when (choice) {
                    1 -> speak(speech = "Almost there... but not quite. Story of your life, huh?", buffer)
                    2 -> speak(speech = "Half right? So close, yet so far. Keep trying, maybe you'll get it one day.", buffer)
                    3 -> speak(speech = "Some of it was right, but seriously, you can do better than that.", buffer)
                    4 -> speak(speech = "You're halfway there! But no, that doesn't count as winning.", buffer)
                    5 -> speak(speech = "Partial credit? I mean, do you want a participation trophy or what?", buffer)
                }
            }

            2 -> { // All answers wrong
                Log.d("Quiz", "Incorrect")
                when (choice) {
                    1 -> speak(speech = "Wow. How did you manage to get that wrong? Even my dog knows that one.", buffer)
                    2 -> speak(speech = "Not a single answer right? Impressive... in all the wrong ways.", buffer)
                    3 -> speak(speech = "Oh, you really went for zero, huh? Bold strategy. Let’s see how it works out.", buffer)
                    4 -> speak(speech = "All wrong? I didn’t even think that was possible with how easy these questions are. And yet, here we are.", buffer)
                    5 -> speak(speech = "You do realize that you are meant to select the correct answers, right?", buffer)
                }
            }
        }
    }

    //********************************* General Data
    fun getPositionYaw(): Float
    {
        return robot.getPosition().yaw
    }

    fun volumeControl (volume: Int) {
        robot.volume = volume
    }
    //********************************* Override is below
    /**
     * Called when connection with robot was established.
     *
     * @param isReady `true` when connection is open. `false` otherwise.
     */
    override fun onRobotReady(isReady: Boolean) {

        if (!isReady) return
        robot.setDetectionModeOn(on = true, distance = 2.0f) // Set how far it can detect stuff
        robot.setKioskModeOn(on = false)
        robot.volume = 0// set volume to 4
//        robot.setHardButtonMode(HardButton.VOLUME, HardButton.Mode.DISABLED)
//        robot.setHardButtonMode(HardButton.MAIN, HardButton.Mode.DISABLED)
//        robot.setHardButtonMode(HardButton.POWER, HardButton.Mode.DISABLED)
//        robot.hideTopBar()

        robot.setHardButtonMode(HardButton.VOLUME, HardButton.Mode.ENABLED)
        robot.setHardButtonMode(HardButton.MAIN, HardButton.Mode.ENABLED)
        robot.setHardButtonMode(HardButton.POWER, HardButton.Mode.ENABLED)
        robot.showTopBar()

        robot.requestToBeKioskApp()
        robot.setKioskModeOn(false)
        Log.i("HOPE!", " In kiosk: ${robot.isKioskModeOn().toString()}")
    }

    override fun onTtsStatusChanged(ttsRequest: TtsRequest) {
//        Log.i("onTtsStatusChanged", "status: ${ttsRequest.status}")
        _ttsStatus.update {
            TtsStatus(status = ttsRequest.status)
        }
    }

    override fun onDetectionStateChanged(state: Int) {
        _detectionStateChangedStatus.update {
//            Log.d("DetectionState", "Detection state changed: ${DetectionStateChangedStatus.fromState(state)}")
            DetectionStateChangedStatus.fromState(state = state) ?: return@update it
        }
    }

    override fun onDetectionDataChanged(detectionData: DetectionData) {
        _detectionDataChangedStatus.update {
            DetectionDataChangedStatus(angle = detectionData.angle, distance = detectionData.distance)
        }
    }

    override fun onMovementStatusChanged(type: String, status: String) {
        _movementStatusChangedStatus.update { currentStatus ->
            // Convert the type and status to their respective enums
            val movementType = when (type) {
                "skidJoy" -> MovementType.SKID_JOY
                "turnBy" -> MovementType.TURN_BY
                else -> return@update currentStatus // If the type is unknown, return the current state
            }
            val movementStatus = when (status) {
                "start" -> MovementStatus.START
                "going" -> MovementStatus.GOING
                "obstacle detected" -> MovementStatus.OBSTACLE_DETECTED
                "node inactive" -> MovementStatus.NODE_INACTIVE
                "calculating" -> MovementStatus.CALCULATING
                "complete" -> MovementStatus.COMPLETE
                "abort" -> MovementStatus.ABORT
                else -> return@update currentStatus // If the status is unknown, return the current state
            }
            // Create a new MovementStatusChangedStatus from the enums
            MovementStatusChangedStatus(movementType, movementStatus)
        }
    }

    override fun onRobotLifted(isLifted: Boolean, reason: String) {
        _lifted.update {
            Lifted(isLifted)
        }
    }

    override fun onRobotDragStateChanged(isDragged: Boolean) {
        _dragged.update {
            Dragged(isDragged)
        }
    }

    override fun onAsrResult(asrResult: String, sttLanguage: SttLanguage) {
        _askResult.update {
            AskResult(asrResult)
        }
    }

    override fun onWakeupWord(wakeupWord: String, direction: Int) {
        _wakeUp.update {
            WakeUp(wakeupWord)
        }
    }

    override fun onTtsVisualizerWaveFormDataChanged(waveForm: ByteArray) {
        _waveform.update {
            WaveForm(waveForm)
        }
    }

    override fun onConversationStatusChanged(status: Int, text: String) {
        _conversationStatus.update {
            ConversationStatus(status, text)
        }
    }

    override fun onConversationAttaches(isAttached: Boolean) {
        _conversationAttached.update {
            ConversationAttached(isAttached)
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        _locationState.update {
            LocationState.fromLocationState(value = status) ?: return@update it
        }
    }
}