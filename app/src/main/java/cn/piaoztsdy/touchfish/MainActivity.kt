// app/src/main/java/cn/piaoztsdy/touchfish/MainActivity.kt
package cn.piaoztsdy.touchfish

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ChatService.ServiceListener {

    // Login View Components
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var connectButton: Button

    // Chat View Components
    private lateinit var chatLayout: ConstraintLayout
    private lateinit var messageLog: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var fileButton: Button
    private lateinit var settingsButton: ImageButton

    // Chat service related
    private var chatService: ChatService? = null
    private var isBound = false
    private lateinit var serviceIntent: Intent
    private val mainHandler = Handler(Looper.getMainLooper())

    private val NOTIFICATION_CHANNEL_ID = "chat_notifications"
    private val NOTIFICATION_ID = 1

    private var receivedFileSize = 0
    private var receivedFileBytes = mutableListOf<ByteArray>()
    private var isReceivingFile = false
    private var currentFileName = ""
    private var isNotificationsEnabled = true
    private var themeColor = Color.parseColor("#6200EE") // Default Purple

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予通知权限，可能无法接收新消息通知。", Toast.LENGTH_LONG).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            sendFile(it)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ChatService.LocalBinder
            chatService = binder.getService()
            isBound = true
            chatService?.setServiceListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            chatService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        createNotificationChannel()

        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        usernameInput = findViewById(R.id.usernameInput)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener {
            val serverIp = ipInput.text.toString()
            val serverPortStr = portInput.text.toString()
            val username = usernameInput.text.toString()

            if (serverIp.isEmpty() || serverPortStr.isEmpty() || username.isEmpty()) {
                Toast.makeText(this, "所有字段都必须填写！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val serverPort = serverPortStr.toIntOrNull()
            if (serverPort == null) {
                Toast.makeText(this, "端口号必须是数字！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            serviceIntent = Intent(this, ChatService::class.java).apply {
                putExtra("serverIp", serverIp)
                putExtra("serverPort", serverPort)
                putExtra("username", username)
            }
            startService(serviceIntent)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            setupChatView()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::serviceIntent.isInitialized) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && isBound) {
            stopService(serviceIntent)
        }
    }

    private fun setupChatView() {
        setContentView(R.layout.activity_chat)
        chatLayout = findViewById(R.id.chatLayout)
        messageLog = findViewById(R.id.messageLog)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        fileButton = findViewById(R.id.fileButton)
        settingsButton = findViewById<ImageButton>(R.id.settingsButton)

        applyTheme()

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        messageInput.setOnKeyListener(View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendMessageFromInput()
                return@OnKeyListener true
            }
            false
        })

        sendButton.setOnClickListener {
            sendMessageFromInput()
        }

        fileButton.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun sendMessageFromInput() {
        val message = messageInput.text.toString().trim()
        if (message.isNotEmpty()) {
            val fullMsg = "${chatService?.getUsername() ?: "User"}: $message"
            chatService?.sendMessage(fullMsg)
            messageInput.text.clear()
        }
    }

    private fun sendFile(fileUri: Uri) {
        Thread {
            try {
                contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val fileName = getFileName(fileUri)
                    val fileData = inputStream.readBytes()
                    chatService?.sendFile(fileUri, fileName, fileData)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onSendFileFailed("选择文件失败: ${e.message}")
                }
            }
        }.start()
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        result = cursor.getString(displayNameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown_file"
    }

    // region ChatService.ServiceListener Implementation

    override fun onMessage(message: String) {
        mainHandler.post {
            val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val formattedMessage = "[$formattedTime] $message"
            messageLog.append("$formattedMessage\n")

            val scrollAmount = messageLog.layout.getLineTop(messageLog.lineCount) - messageLog.height
            if (scrollAmount > 0) {
                messageLog.scrollTo(0, scrollAmount)
            } else {
                messageLog.scrollTo(0, 0)
            }

            if (isNotificationsEnabled && !message.startsWith("${chatService?.getUsername() ?: "User"}:")) {
                showNotification(message)
            }
        }
    }

    override fun onFileReceived(fileName: String, fileSize: Int) {
        mainHandler.post {
            isReceivingFile = true
            currentFileName = fileName
            receivedFileSize = fileSize
            receivedFileBytes.clear()

            AlertDialog.Builder(this)
                .setTitle("接收到新文件")
                .setMessage("是否接收文件 '$fileName' (${String.format("%.1f", fileSize / (1024.0 * 1024.0))}MB)?")
                .setPositiveButton("接收") { _, _ ->
                    onMessage("[系统提示] 正在接收文件: $fileName")
                }
                .setNegativeButton("取消") { _, _ ->
                    isReceivingFile = false
                    onMessage("[系统提示] 已取消接收文件: $fileName")
                }
                .show()
        }
    }

    override fun onFileProgress(fileData: ByteArray) {
        mainHandler.post {
            if (isReceivingFile) {
                receivedFileBytes.add(fileData)
                val currentSize = receivedFileBytes.sumOf { it.size }
                val progress = (currentSize.toFloat() / receivedFileSize) * 100

                val editableText = SpannableStringBuilder(messageLog.text)
                val lastLineStartIndex = editableText.lastIndexOf("\n")
                if (lastLineStartIndex != -1) {
                    editableText.delete(lastLineStartIndex, editableText.length)
                }

                val progressMessage = String.format("[系统提示] 文件接收进度: %.1f%%\n", progress)
                editableText.append(progressMessage)

                messageLog.text = editableText

                val scrollAmount = messageLog.layout.getLineTop(messageLog.lineCount) - messageLog.height
                if (scrollAmount > 0) {
                    messageLog.scrollTo(0, scrollAmount)
                } else {
                    messageLog.scrollTo(0, 0)
                }
            }
        }
    }

    override fun onFileEnd() {
        mainHandler.post {
            if (isReceivingFile) {
                isReceivingFile = false
                AlertDialog.Builder(this)
                    .setTitle("文件接收完成")
                    .setMessage("文件 '$currentFileName' 已接收完成，是否保存？")
                    .setPositiveButton("保存") { _, _ ->
                        saveFile(currentFileName)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    override fun onSendFileProgress(progress: Float) {
        mainHandler.post {
            val progressMessage = "文件发送进度: ${String.format("%.1f", progress)}%"
            val editableText = SpannableStringBuilder(messageLog.text)
            val lastLineStartIndex = editableText.lastIndexOf("\n")
            if (lastLineStartIndex != -1) {
                editableText.delete(lastLineStartIndex, editableText.length)
            }
            editableText.append(progressMessage)
            messageLog.text = editableText
            messageLog.scrollTo(0, messageLog.layout.getLineTop(messageLog.lineCount) - messageLog.height)
        }
    }

    override fun onSendFileComplete() {
        mainHandler.post {
            onMessage("[系统提示] 文件发送完成。")
        }
    }

    override fun onSendFileFailed(error: String) {
        mainHandler.post {
            onMessage("[系统提示] $error")
        }
    }

    // endregion

    private fun saveFile(fileName: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, fileName)

        try {
            FileOutputStream(file).use { fos ->
                for (chunk in receivedFileBytes) {
                    fos.write(chunk)
                }
            }
            onMessage("[系统提示] 文件已保存到 Downloads/$fileName")
        } catch (e: Exception) {
            onMessage("[系统提示] 保存文件失败: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "聊天室通知"
            val descriptionText = "接收聊天室新消息的通知"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(message: String) {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("新消息")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(NOTIFICATION_ID, builder.build())
        }
    }

    // Theme and Settings
    private fun applyTheme() {
        // 设置聊天界面背景色
        chatLayout.setBackgroundColor(themeColor)

        // 设置按钮颜色
        sendButton.setBackgroundColor(themeColor)
        fileButton.setBackgroundColor(themeColor)
        settingsButton.setBackgroundColor(themeColor)
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("设置")

        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        builder.setView(view)

        val notificationsSwitch = view.findViewById<Button>(R.id.notifications_switch)
        val purpleButton = view.findViewById<Button>(R.id.purple_button)
        val blueButton = view.findViewById<Button>(R.id.blue_button)
        val greenButton = view.findViewById<Button>(R.id.green_button)

        notificationsSwitch.text = if (isNotificationsEnabled) "通知已开启" else "通知已关闭"
        notificationsSwitch.setOnClickListener {
            isNotificationsEnabled = !isNotificationsEnabled
            notificationsSwitch.text = if (isNotificationsEnabled) "通知已开启" else "通知已关闭"
            Toast.makeText(this, "通知已${if (isNotificationsEnabled) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
        }

        purpleButton.setOnClickListener {
            themeColor = Color.parseColor("#6200EE")
            applyTheme()
        }

        blueButton.setOnClickListener {
            themeColor = Color.parseColor("#007BFF")
            applyTheme()
        }

        greenButton.setOnClickListener {
            themeColor = Color.parseColor("#28A745")
            applyTheme()
        }

        builder.setPositiveButton("确定") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }
}