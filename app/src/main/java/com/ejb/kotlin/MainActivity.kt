package com.ejb.kotlin


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ejb.kotlin.databinding.ActivityMainBinding
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import java.nio.charset.StandardCharsets
import java.util.UUID


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private lateinit var mqttStatusIcon: ImageView
    private val SCREEN_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutos
    private val RECORD_REQUEST = 40
    private val TOPIC = "mensagens"   //  TÓPICO FIXO

    private val client = MqttClient.builder()
        .useMqttVersion3()
        .identifier("android-" + UUID.randomUUID().toString())
        .serverHost("kebnekaise.lmq.cloudamqp.com")    // coloque o host do seu broker
        .serverPort(8883)
        .sslWithDefaultConfig()
        .buildAsync()


    // Tempo de tela ocioso
    private val screenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val allowScreenLockRunnable = Runnable {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        println("🔒 Tela liberada para bloqueio após 10 minutos")
    }
    private fun keepScreenOnForSomeTime() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        println("🔓 Tela mantida ligada por 10 minutos")

        screenHandler.removeCallbacks(allowScreenLockRunnable)
        screenHandler.postDelayed(allowScreenLockRunnable, SCREEN_TIMEOUT_MS)
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
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


        binding.messagesLog.movementMethod = android.text.method.ScrollingMovementMethod()

    }

//    onResume
    override fun onResume() {
        super.onResume()
        // reconecta se a conexão MQTT estiver desconectada
        if (!client.state.isConnected) {
            connectMqtt()
        }
    }

    // ------------------------------ PERMISSÃO DE ÁUDIO
    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_REQUEST
            )
        }
    }

    // ------------------------------ CONFIGURAR Reconhecimento de Voz
    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) // ⛔ desativa parciais
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                println("🎤 Aguardando fala...")
            }

            override fun onBeginningOfSpeech() {
                println("🎤 Começou a falar...")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                println("🎤 Fala terminou. Parando imediatamente...")
                speechRecognizer.stopListening()   // ⛔ Interrompe na hora
            }


            override fun onError(error: Int) {
                println("❌ Erro no SpeechRecognizer: $error")
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.get(0) ?: ""

                println("🎤 Reconhecido: $text")
                publish(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        println("🎤 Iniciando reconhecimento...")
        speechRecognizer.startListening(speechIntent)
    }


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

//                    Icon muda para Online
                    mqttStatusIcon.setImageResource(R.drawable.wifion)

                    client.subscribeWith()
                        .topicFilter(TOPIC)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()

                    client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
                        val msg = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                        println("📥 Recebido: $msg")
                    }

                } else {
                    println("❌ Erro ao conectar: ${ex.message}")

//                    Icon muda para Offline
                    mqttStatusIcon.setImageResource(R.drawable.wifion)
                }
            }
    }

    private fun publish(message: String) {
        client.publishWith()
            .topic(TOPIC)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(message.toByteArray())
            .send()

        println("📤 Publicado via Voz: $message")
        addMessageToLog("EJB (voz): $message")
        binding.msgInput.text.clear()
    }

    private fun publishMessage() {
        val msg = binding.msgInput.text.toString()

        client.publishWith()
            .topic(TOPIC)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(msg.toByteArray())
            .send()

        println("Publicado: $msg")
        addMessageToLog("EJB: $msg")
        binding.msgInput.text.clear()
    }

//    private fun addMessageToLog(message: String) {
//        runOnUiThread {
//            val log = binding.messagesLog
//            log.append("\n$message")
//
//            // Scroll automático para o final
//            val scrollAmount = log.layout?.getLineTop(log.lineCount) ?: 0
//            if (scrollAmount > log.height) {
//                log.scrollTo(0, scrollAmount - log.height)
//            }
//        }
//    }
private fun addMessageToLog(message: String) {
    runOnUiThread {
        binding.messagesLog.append("\n$message")

        binding.messagesLog.post {
            binding.messagesScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}






}

