// app/src/main/java/cn/piaoztsdy/touchfish/ChatService.kt
package cn.piaoztsdy.touchfish

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class ChatService : Service(), SocketClient.OnMessageReceived {

    private lateinit var socketClient: SocketClient
    private var serviceListener: ServiceListener? = null
    private var username: String = ""

    // Binder for clients to interact with this service
    inner class LocalBinder : Binder() {
        fun getService(): ChatService = this@ChatService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ChatService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverIp = intent?.getStringExtra("serverIp")
        val serverPort = intent?.getIntExtra("serverPort", 0)
        username = intent?.getStringExtra("username") ?: "User"

        if (serverIp != null && serverPort != null && serverPort != 0) {
            socketClient = SocketClient(this)
            socketClient.start(serverIp, serverPort, username)
        }

        return START_STICKY // Service will be restarted if it's killed by the system
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    override fun onDestroy() {
        super.onDestroy()
        socketClient.stop()
        Log.d("ChatService", "Service destroyed")
    }

    fun setServiceListener(listener: ServiceListener) {
        this.serviceListener = listener
    }

    // Pass messages to the UI or other components
    override fun onMessage(message: String) {
        serviceListener?.onMessage(message)
    }

    override fun onFileReceived(fileName: String, fileSize: Int) {
        serviceListener?.onFileReceived(fileName, fileSize)
    }

    override fun onFileProgress(fileData: ByteArray) {
        serviceListener?.onFileProgress(fileData)
    }

    override fun onFileEnd() {
        serviceListener?.onFileEnd()
    }

    override fun onSendFileProgress(progress: Float) {
        serviceListener?.onSendFileProgress(progress)
    }

    override fun onSendFileComplete() {
        serviceListener?.onSendFileComplete()
    }

    override fun onSendFileFailed(error: String) {
        serviceListener?.onSendFileFailed(error)
    }

    fun sendMessage(message: String) {
        socketClient.sendMessage(message)
    }

    fun sendFile(fileUri: android.net.Uri, fileName: String, fileData: ByteArray) {
        socketClient.sendFile(fileUri, fileName, fileData)
    }

    fun getUsername(): String {
        return username
    }

    interface ServiceListener : SocketClient.OnMessageReceived
}