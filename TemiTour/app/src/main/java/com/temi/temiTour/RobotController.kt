package com.temi.temiTour

import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.listeners.OnDetectionStateChangedListener
import com.robotemi.sdk.listeners.OnDetectionDataChangedListener
import com.robotemi.sdk.listeners.OnMovementStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotLiftedListener
import com.robotemi.sdk.listeners.OnRobotDragStateChangedListener
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.constants.HardButton
import com.robotemi.sdk.model.DetectionData
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
    OnRobotDragStateChangedListener
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

    init {
        robot.addOnRobotReadyListener(this)
        robot.addTtsListener(this)
        robot.addOnDetectionStateChangedListener((this))
        robot.addOnDetectionDataChangedListener(this)
        robot.addOnMovementStatusChangedListener(this)
        robot.addOnRobotLiftedListener(this)
        robot.addOnRobotDragStateChangedListener(this)

//        robot.setTrackUserOn()
    }
    //********************************* General Functions
    suspend fun speak(speech: String, buffer: Long) {
        delay(buffer)
        val request = TtsRequest.create(
            speech = speech,
            isShowOnConversationLayer = false,
            showAnimationOnly = false,
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
}