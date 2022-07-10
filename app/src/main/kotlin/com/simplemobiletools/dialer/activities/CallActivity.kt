package com.simplemobiletools.dialer.activities

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.view.children
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.LOWER_ALPHA
import com.simplemobiletools.commons.helpers.MINUTE_SECONDS
import com.simplemobiletools.commons.helpers.isOreoMr1Plus
import com.simplemobiletools.commons.helpers.isOreoPlus
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.extensions.*
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.models.CallContact
import kotlinx.android.synthetic.main.activity_call.*
import kotlinx.android.synthetic.main.dialpad.*

class CallActivity : SimpleActivity() {
    companion object {
        private const val ANIMATION_DURATION = 250L
        fun getStartIntent(context: Context): Intent {
            val openAppIntent = Intent(context, CallActivity::class.java)
            openAppIntent.flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            return openAppIntent
        }
    }

    private var isSpeakerOn = false
    private var isMicrophoneOn = true
    private var isCallEnded = false
    private var callContact: CallContact? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var callDuration = 0
    private val callContactAvatarHelper by lazy { CallContactAvatarHelper(this) }
    private val callDurationHandler = Handler(Looper.getMainLooper())
    private var dragDownX = 0f
    private var stopAnimation = false
    private var viewsUnderDialpad = arrayListOf<Pair<View, Float>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        if (CallManager.getPhoneState() == NoCall) {
            finish()
            return
        }

        updateTextColors(call_holder)
        initButtons()

        audioManager.mode = AudioManager.MODE_IN_CALL

        addLockScreenFlags()

        CallManager.addListener(callCallback)

        updateCallContactInfo(CallManager.getPrimaryCall())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        updateState()
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callCallback)
        disableProximitySensor()
    }

    override fun onBackPressed() {
        if (dialpad_wrapper.isVisible()) {
            hideDialpad()
            return
        } else {
            super.onBackPressed()
        }

        val callState = CallManager.getState()
        if (callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING) {
            endCall()
        }
    }

    private fun initButtons() {
        if (config.disableSwipeToAnswer) {
            call_draggable.beGone()
            call_draggable_background.beGone()
            call_left_arrow.beGone()
            call_right_arrow.beGone()

            call_decline.setOnClickListener {
                endCall()
            }

            call_accept.setOnClickListener {
                acceptCall()
            }
        } else {
            handleSwipe()
        }

        call_toggle_microphone.setOnClickListener {
            toggleMicrophone()
        }

        call_toggle_speaker.setOnClickListener {
            toggleSpeaker()
        }

        call_dialpad.setOnClickListener {
            toggleDialpadVisibility()
        }

        dialpad_close.setOnClickListener {
            hideDialpad()
        }

        call_toggle_hold.setOnClickListener {
            toggleHold()
        }

        call_add.setOnClickListener {
            Intent(applicationContext, DialpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                startActivity(this)
            }
        }

        call_swap.setOnClickListener {
            CallManager.swap()
        }

        call_merge.setOnClickListener {
            CallManager.merge()
        }

        call_manage.setOnClickListener {
            startActivity(Intent(this, ConferenceActivity::class.java))
        }

        call_end.setOnClickListener {
            endCall()
        }

        dialpad_0_holder.setOnClickListener { dialpadPressed('0') }
        dialpad_1_holder.setOnClickListener { dialpadPressed('1') }
        dialpad_2_holder.setOnClickListener { dialpadPressed('2') }
        dialpad_3_holder.setOnClickListener { dialpadPressed('3') }
        dialpad_4_holder.setOnClickListener { dialpadPressed('4') }
        dialpad_5_holder.setOnClickListener { dialpadPressed('5') }
        dialpad_6_holder.setOnClickListener { dialpadPressed('6') }
        dialpad_7_holder.setOnClickListener { dialpadPressed('7') }
        dialpad_8_holder.setOnClickListener { dialpadPressed('8') }
        dialpad_9_holder.setOnClickListener { dialpadPressed('9') }

        dialpad_0_holder.setOnLongClickListener { dialpadPressed('+'); true }
        dialpad_asterisk_holder.setOnClickListener { dialpadPressed('*') }
        dialpad_hashtag_holder.setOnClickListener { dialpadPressed('#') }

        dialpad_wrapper.setBackgroundColor(getProperBackgroundColor())
        arrayOf(
            call_toggle_microphone, call_toggle_speaker, call_dialpad, dialpad_close,
            call_sim_image, call_toggle_hold, call_add, call_swap, call_merge, call_manage
        ).forEach {
            it.applyColorFilter(getProperTextColor())
        }

        arrayOf(
            call_toggle_microphone, call_toggle_speaker, call_dialpad,
            call_toggle_hold, call_add, call_swap, call_merge, call_manage
        ).forEach { imageView ->
            imageView.setOnLongClickListener {
                if (!imageView.contentDescription.isNullOrEmpty()) {
                    toast(imageView.contentDescription.toString())
                }
                true
            }
        }

        call_sim_id.setTextColor(getProperTextColor().getContrastColor())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() {
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var initialLeftArrowX = 0f
        var initialRightArrowX = 0f
        var initialLeftArrowScaleX = 0f
        var initialLeftArrowScaleY = 0f
        var initialRightArrowScaleX = 0f
        var initialRightArrowScaleY = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f

        call_accept.onGlobalLayout {
            minDragX = call_decline.left.toFloat()
            maxDragX = call_accept.left.toFloat()
            initialDraggableX = call_draggable.left.toFloat()
            initialLeftArrowX = call_left_arrow.x
            initialRightArrowX = call_right_arrow.x
            initialLeftArrowScaleX = call_left_arrow.scaleX
            initialLeftArrowScaleY = call_left_arrow.scaleY
            initialRightArrowScaleX = call_right_arrow.scaleX
            initialRightArrowScaleY = call_right_arrow.scaleY
            leftArrowTranslation = -call_decline.x
            rightArrowTranslation = call_decline.x

            call_left_arrow.applyColorFilter(getColor(R.color.md_red_400))
            call_right_arrow.applyColorFilter(getColor(R.color.md_green_400))

            startArrowAnimation(call_left_arrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
            startArrowAnimation(call_right_arrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
        }

        call_draggable.drawable.mutate().setTint(getProperTextColor())
        call_draggable_background.drawable.mutate().setTint(getProperTextColor())

        var lock = false
        call_draggable.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    call_draggable_background.animate().alpha(0f)
                    stopAnimation = true
                    call_left_arrow.animate().alpha(0f)
                    call_right_arrow.animate().alpha(0f)
                    lock = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    call_draggable.animate().x(initialDraggableX).withEndAction {
                        call_draggable_background.animate().alpha(0.2f)
                    }
                    call_draggable.setImageDrawable(getDrawable(R.drawable.ic_phone_down_vector))
                    call_draggable.drawable.mutate().setTint(getProperTextColor())
                    call_left_arrow.animate().alpha(1f)
                    call_right_arrow.animate().alpha(1f)
                    stopAnimation = false
                    startArrowAnimation(call_left_arrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
                    startArrowAnimation(call_right_arrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
                }
                MotionEvent.ACTION_MOVE -> {
                    call_draggable.x = Math.min(maxDragX, Math.max(minDragX, event.rawX - dragDownX))
                    when {
                        call_draggable.x >= maxDragX - 50f -> {
                            if (!lock) {
                                lock = true
                                call_draggable.performHapticFeedback()
                                acceptCall()
                            }
                        }
                        call_draggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                call_draggable.performHapticFeedback()
                                endCall()
                            }
                        }
                        call_draggable.x > initialDraggableX -> {
                            lock = false
                            call_draggable.setImageDrawable(getDrawable(R.drawable.ic_phone_green_vector))
                        }
                        call_draggable.x <= initialDraggableX -> {
                            lock = false
                            call_draggable.setImageDrawable(getDrawable(R.drawable.ic_phone_down_red_vector))
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimation(arrow: ImageView, initialX: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            x = initialX
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationX(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopAnimation) {
                        startArrowAnimation(this, initialX, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        dialpad_input.addCharacter(char)
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val drawable = if (isSpeakerOn) R.drawable.ic_speaker_on_vector else R.drawable.ic_speaker_off_vector
        call_toggle_speaker.setImageDrawable(getDrawable(drawable))
        audioManager.isSpeakerphoneOn = isSpeakerOn

        val newRoute = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        CallManager.inCallService?.setAudioRoute(newRoute)
        call_toggle_speaker.contentDescription = getString(if (isSpeakerOn) R.string.turn_speaker_off else R.string.turn_speaker_on)

        if (isSpeakerOn) {
            disableProximitySensor()
        } else {
            enableProximitySensor()
        }
    }

    private fun toggleMicrophone() {
        isMicrophoneOn = !isMicrophoneOn
        val drawable = if (isMicrophoneOn) R.drawable.ic_microphone_vector else R.drawable.ic_microphone_off_vector
        call_toggle_microphone.setImageDrawable(getDrawable(drawable))
        audioManager.isMicrophoneMute = !isMicrophoneOn
        CallManager.inCallService?.setMuted(!isMicrophoneOn)
        call_toggle_microphone.contentDescription = getString(if (isMicrophoneOn) R.string.turn_microphone_off else R.string.turn_microphone_on)
    }

    private fun toggleDialpadVisibility() {
        if (dialpad_wrapper.isVisible()) hideDialpad() else showDialpad()
    }

    private fun findVisibleViewsUnderDialpad(): Sequence<Pair<View, Float>> {
        return ongoing_call_holder.children.filter { it.isVisible() }.map { view -> Pair(view, view.alpha) }
    }

    private fun showDialpad() {
        dialpad_wrapper.animate().withStartAction { dialpad_wrapper.beVisible() }.alpha(1f)
        viewsUnderDialpad.clear()
        viewsUnderDialpad.addAll(findVisibleViewsUnderDialpad())
        viewsUnderDialpad.forEach { (view, _) ->
            view.run {
                animate().scaleX(0f).alpha(0f).withEndAction { beGone() }.duration = ANIMATION_DURATION
                animate().scaleY(0f).alpha(0f).withEndAction { beGone() }.duration = ANIMATION_DURATION
            }
        }
    }

    private fun hideDialpad() {
        dialpad_wrapper.animate().alpha(0f).withEndAction { dialpad_wrapper.beGone() }
        viewsUnderDialpad.forEach { (view, alpha) ->
            view.run {
                animate().withStartAction { beVisible() }.scaleX(1f).alpha(alpha).duration = ANIMATION_DURATION
                animate().withStartAction { beVisible() }.scaleY(1f).alpha(alpha).duration = ANIMATION_DURATION
            }
        }
    }

    private fun toggleHold() {
        val isOnHold = CallManager.toggleHold()
        val drawable = if (isOnHold) R.drawable.ic_pause_crossed_vector else R.drawable.ic_pause_vector
        call_toggle_hold.setImageDrawable(getDrawable(drawable))
        call_toggle_hold.contentDescription = getString(if (isOnHold) R.string.resume_call else R.string.hold_call)
        hold_status_label.beVisibleIf(isOnHold)
    }

    private fun updateOtherPersonsInfo(avatar: Bitmap?) {
        if (callContact == null) {
            return
        }

        caller_name_label.text = if (callContact!!.name.isNotEmpty()) callContact!!.name else getString(R.string.unknown_caller)
        if (callContact!!.number.isNotEmpty() && callContact!!.number != callContact!!.name) {
            caller_number.text = callContact!!.number

            if (callContact!!.numberLabel.isNotEmpty()) {
                caller_number.text = "${callContact!!.number} - ${callContact!!.numberLabel}"
            }
        } else {
            caller_number.beGone()
        }

        if (avatar != null) {
            caller_avatar.setImageBitmap(avatar)
        } else {
            caller_avatar.setImageDrawable(null)
        }
    }

    private fun getContactNameOrNumber(contact: CallContact): String {
        return contact.name.ifEmpty {
            contact.number.ifEmpty {
                getString(R.string.unknown_caller)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {
            val accounts = telecomManager.callCapablePhoneAccounts
            if (accounts.size > 1) {
                accounts.forEachIndexed { index, account ->
                    if (account == CallManager.getPrimaryCall()?.details?.accountHandle) {
                        call_sim_id.text = "${index + 1}"
                        call_sim_id.beVisible()
                        call_sim_image.beVisible()

                        val acceptDrawableId = when (index) {
                            0 -> R.drawable.ic_phone_one_vector
                            1 -> R.drawable.ic_phone_two_vector
                            else -> R.drawable.ic_phone_vector
                        }

                        val rippleBg = resources.getDrawable(R.drawable.ic_call_accept, theme) as RippleDrawable
                        val layerDrawable = rippleBg.findDrawableByLayerId(R.id.accept_call_background_holder) as LayerDrawable
                        layerDrawable.setDrawableByLayerId(R.id.accept_call_icon, getDrawable(acceptDrawableId))
                        call_accept.setImageDrawable(rippleBg)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun updateCallState(call: Call) {
        val state = call.getStateCompat()
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> endCall()
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_CONNECTING, Call.STATE_DIALING -> R.string.dialing
            else -> 0
        }

        if (statusTextId != 0) {
            call_status_label.text = getString(statusTextId)
        }

        call_manage.beVisibleIf(call.hasCapability(Call.Details.CAPABILITY_MANAGE_CONFERENCE))
        setActionButtonEnabled(call_swap, state == Call.STATE_ACTIVE)
        setActionButtonEnabled(call_merge, state == Call.STATE_ACTIVE)
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        if (phoneState is SingleCall) {
            updateCallState(phoneState.call)
            updateCallOnHoldState(null)
            val state = phoneState.call.getStateCompat()
            val isSingleCallActionsEnabled = (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                || state == Call.STATE_DISCONNECTING || state == Call.STATE_HOLDING)
            setActionButtonEnabled(call_toggle_hold, isSingleCallActionsEnabled)
            setActionButtonEnabled(call_add, isSingleCallActionsEnabled)
        } else if (phoneState is TwoCalls) {
            updateCallState(phoneState.active)
            updateCallOnHoldState(phoneState.onHold)
        }
    }

    private fun updateCallOnHoldState(call: Call?) {
        val hasCallOnHold = call != null
        if (hasCallOnHold) {
            getCallContact(applicationContext, call) { contact ->
                runOnUiThread {
                    on_hold_caller_name.text = getContactNameOrNumber(contact)
                }
            }
        }
        on_hold_status_holder.beVisibleIf(hasCallOnHold)
        controls_single_call.beVisibleIf(!hasCallOnHold)
        controls_two_calls.beVisibleIf(hasCallOnHold)
    }

    private fun updateCallContactInfo(call: Call?) {
        getCallContact(applicationContext, call) { contact ->
            if (call != CallManager.getPrimaryCall()) {
                return@getCallContact
            }
            callContact = contact
            val avatar = if (!call.isConference()) callContactAvatarHelper.getCallContactAvatar(contact) else null
            runOnUiThread {
                updateOtherPersonsInfo(avatar)
                checkCalledSIMCard()
            }
        }
    }

    private fun acceptCall() {
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        enableProximitySensor()
        incoming_call_holder.beGone()
        ongoing_call_holder.beVisible()
    }

    private fun callRinging() {
        incoming_call_holder.beVisible()
    }

    private fun callStarted() {
        enableProximitySensor()
        incoming_call_holder.beGone()
        ongoing_call_holder.beVisible()
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
    }

    private fun showPhoneAccountPicker() {
        if (callContact != null) {
            getHandleToUse(intent, callContact!!.number) { handle ->
                CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
            }
        }
    }

    private fun endCall() {
        CallManager.reject()
        disableProximitySensor()

        if (isCallEnded) {
            clearTraces() // #Koller
            finishAndRemoveTask()
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (ignored: Exception) {
        }

        isCallEnded = true
        if (callDuration > 0) {
            runOnUiThread {
                call_status_label.text = "${callDuration.getFormattedDuration()} (${getString(R.string.call_ended)})"
                Handler().postDelayed({
                    finishAndRemoveTask()
                }, 3000)
            }
        } else {
            call_status_label.text = getString(R.string.call_ended)
            finish()
        }
    }

    /**
     * #Koller Add: Clear all traces after making calls.
     */
    private fun clearTraces() {
        removeLastCall()
    }

    /**
     * #Koller Add: Remove the Last Call in call history.
     */
    private fun removeLastCall(phoneNoCheck: String = "", nameCheck: String = "") {
        val idsToRemove = ArrayList<Int>()
        var safeRemove = false

        if (phoneNoCheck.isNotEmpty() || nameCheck.isNotEmpty())
            safeRemove = true

        RecentsHelper(applicationContext).getRecentCalls(false) { recents ->
            val lastCall = recents[0]

            if ((safeRemove
                    && lastCall.name == nameCheck
                    && lastCall.phoneNumber == phoneNoCheck)
                || !safeRemove
            ) {
                Log.i("removeLastCall()", "Removing Last Call $lastCall")
                idsToRemove.add(lastCall.id)
                RecentsHelper(applicationContext).removeRecentCalls(idsToRemove) {
                    // nothing
                }
            }
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onPrimaryCallChanged(call: Call) {
            callDurationHandler.removeCallbacks(updateCallDurationTask)
            updateCallContactInfo(call)
            updateState()
        }
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            callDuration = CallManager.getPrimaryCall().getCallDuration()
            if (!isCallEnded) {
                call_status_label.text = callDuration.getFormattedDuration()
                callDurationHandler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (isOreoPlus()) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun enableProximitySensor() {
        if (!config.disableProximitySensor && (proximityWakeLock == null || proximityWakeLock?.isHeld == false)) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "com.simplemobiletools.dialer.pro:wake_lock")
            proximityWakeLock!!.acquire(60 * MINUTE_SECONDS * 1000L)
        }
    }

    private fun disableProximitySensor() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }
    }

    private fun setActionButtonEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }
}
