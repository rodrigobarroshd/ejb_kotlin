package com.ejb.kotlin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ejb.kotlin.databinding.ActivityMainBinding
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private lateinit var mqttStatusIcon: ImageView
    private val SCREEN_TIMEOUT_MS = 10 * 60 * 1000L
    private val RECORD_REQUEST = 40
    private val TOPIC = "mensagens"
    private val sentMessages = mutableSetOf<String>()

    private val client = MqttClient.builder()
        .useMqttVersion3()
        .identifier("android-" + UUID.randomUUID().toString())
        .serverHost("kebnekaise.lmq.cloudamqp.com")
        .serverPort(8883)
        .sslWithDefaultConfig()
        .buildAsync()

    private val screenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val allowScreenLockRunnable = Runnable {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun keepScreenOnForSomeTime() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenHandler.removeCallbacks(allowScreenLockRunnable)
        screenHandler.postDelayed(allowScreenLockRunnable, SCREEN_TIMEOUT_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(binding.root)

        requestAudioPermission()
        setupSpeechRecognizer()
        mqttStatusIcon = binding.mqttStatusIcon
        connectMqtt()

        binding.btnVoice.setOnClickListener {
            keepScreenOnForSomeTime()
            startListening()
        }

        binding.btnPublish.setOnClickListener {
            keepScreenOnForSomeTime()
            publishMessage()
        }

        // No onCreate
//        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
//            val rect = android.graphics.Rect()
//            binding.root.getWindowVisibleDisplayFrame(rect)
//            val screenHeight = binding.root.rootView.height
//            val keypadHeight = screenHeight - rect.bottom
//
//            if (keypadHeight > screenHeight * 0.15) {
//                binding.chatContainer.post {
//                    binding.chatScroll.fullScroll(android.view.View.FOCUS_DOWN)
//                }
//            }
//        }
    }

    override fun onResume() {
        super.onResume()
        if (!client.state.isConnected) {
            connectMqtt()
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_REQUEST
            )
        }
    }

    // ------------------------------ CHAT BUBBLE
    private fun addChatBubble(message: String, isSent: Boolean) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isSent) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 6, 0, 6) }
            }

            val bubble = TextView(this).apply {
                text = message
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(24, 16, 24, 8)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = if (isSent)
                        floatArrayOf(18f, 18f, 0f, 0f, 18f, 18f, 18f, 18f)
                    else
                        floatArrayOf(0f, 0f, 18f, 18f, 18f, 18f, 18f, 18f)
                    setColor(if (isSent) Color.parseColor("#DCF8C6") else Color.WHITE)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = if (isSent) 120 else 0
                    marginEnd = if (isSent) 0 else 120
                }
            }

            val timeView = TextView(this).apply {
                text = if (isSent) "$time ✓" else time
                textSize = 11f
                setTextColor(Color.GRAY)
                gravity = if (isSent) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = if (isSent) 0 else 8
                    marginEnd = if (isSent) 8 else 0
                    topMargin = 2
                }
            }

            wrapper.addView(bubble)
            wrapper.addView(timeView)
            binding.chatContainer?.addView(wrapper)
            binding.chatContainer?.post {
                binding.chatScroll?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    // ------------------------------ SPEECH
    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { speechRecognizer.stopListening() }
            override fun onError(error: Int) { println("❌ Erro: $error") }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.get(0) ?: ""
                publish(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        speechRecognizer.startListening(speechIntent)
    }

    // ------------------------------ MQTT
    private fun connectMqtt() {
        val username = "xzkmpjmo:xzkmpjmo"
        val password = "jd0wDw9y8kyuKMkpHOWokeWqm-k1UIep"

        client.connectWith()
            .simpleAuth()
            .username(username)
            .password(password.toByteArray())
            .applySimpleAuth()
            .send()
            .whenComplete { _, ex ->
                if (ex == null) {
                    println("Conectado ao MQTT! 🚀")
                    runOnUiThread { mqttStatusIcon.setImageResource(R.drawable.wifion) }

                    client.subscribeWith()
                        .topicFilter(TOPIC)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()

                    client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
                        val msg = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                        if (sentMessages.contains(msg)) {
                            sentMessages.remove(msg)
                            return@publishes
                        }
                        println("📥 Recebido: $msg")
                        addChatBubble(msg, isSent = false)  // ← balão esquerdo
                    }
                } else {
                    println("❌ Erro ao conectar: ${ex.message}")
                    runOnUiThread { mqttStatusIcon.setImageResource(R.drawable.wifioff) }
                }
            }
    }

    private fun publish(message: String) {
        if (message.isBlank()) return
        sentMessages.add(message)
        client.publishWith()
            .topic(TOPIC)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(message.toByteArray())
            .send()
        addChatBubble(message, isSent = true)  // ← balão direito
        binding.msgInput.text.clear()
    }

    private fun publishMessage() {
        val msg = binding.msgInput.text.toString()
        if (msg.isBlank()) return
        sentMessages.add(msg)
        client.publishWith()
            .topic(TOPIC)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(msg.toByteArray())
            .send()
        addChatBubble(msg, isSent = true)  // ← balão direito
        binding.msgInput.text.clear()
    }
}