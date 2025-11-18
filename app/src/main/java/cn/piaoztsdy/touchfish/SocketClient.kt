// app/src/main/java/cn/piaoztsdy/touchfish/SocketClient.kt

package cn.piaoztsdy.touchfish

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class SocketClient(private val listener: OnMessageReceived) {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var username: String = ""

    private var receivingFile = false
    private var currentFile = JSONObject()

    private val FILE_START = "FILE_START"
    private val FILE_DATA = "FILE_DATA"
    private val FILE_END = "FILE_END"

    fun start(serverIp: String, serverPort: Int, username: String) {
        this.username = username
        thread(start = true) {
            try {
                // 连接服务器
                socket = Socket(serverIp, serverPort)

                // 启用心跳包
                socket?.tcpNoDelay = true
                socket?.keepAlive = true

                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                writer = BufferedWriter(OutputStreamWriter(socket?.getOutputStream()))

                // 发送加入聊天室的消息
                sendMessage("用户 $username 加入聊天室。")

                // 启动消息接收循环
                receiveMessages()

            } catch (e: Exception) {
                Log.e("SocketClient", "连接错误: ${e.message}")
                listener.onMessage("[系统提示] 连接服务器失败: ${e.message}")
                stop()
            }
        }
    }

    fun stop() {
        try {
            if (socket?.isClosed == false) {
                sendMessage("用户 $username 离开了聊天室。")
                socket?.close()
            }
        } catch (e: Exception) {
            Log.e("SocketClient", "关闭错误: ${e.message}")
        }
    }

    fun sendMessage(message: String) {
        thread {
            try {
                // Python客户端通过`\n`来分割消息，所以我们需要在末尾添加
                writer?.write(message + "\n")
                writer?.flush()
            } catch (e: Exception) {
                Log.e("SocketClient", "发送消息错误: ${e.message}")
                listener.onMessage("[系统提示] 消息发送失败: ${e.message}")
            }
        }
    }

    private fun receiveMessages() {
        var buffer = StringBuilder()
        while (socket?.isClosed == false) {
            try {
                val charCode = reader?.read()
                // 使用 let 安全地处理可空类型
                charCode?.let {
                    val char = it.toChar()
                    buffer.append(char)
                    if (char == '\n') {
                        val fullMessage = buffer.toString().trim()
                        buffer.clear()

                        // 处理文件传输或普通消息
                        if (!handleFileMessage(fullMessage)) {
                            // 不是文件传输，当作普通消息处理
                            listener.onMessage(fullMessage)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketClient", "接收消息错误: ${e.message}")
                listener.onMessage("[系统提示] 连接已断开.")
                stop()
                break
            }
        }
    }

    private fun handleFileMessage(message: String): Boolean {
        try {
            val jsonObject = JSONObject(message)
            val type = jsonObject.getString("type")

            when (type) {
                FILE_START -> {
                    receivingFile = true
                    currentFile = jsonObject
                    val fileName = currentFile.getString("name")
                    val fileSize = currentFile.getInt("size")
                    listener.onFileReceived(fileName, fileSize)
                    return true
                }
                FILE_DATA -> {
                    if (receivingFile) {
                        val base64Data = jsonObject.getString("data")
                        val fileData = Base64.decode(base64Data, Base64.DEFAULT)
                        listener.onFileProgress(fileData)
                    }
                    return true
                }
                FILE_END -> {
                    if (receivingFile) {
                        receivingFile = false
                        listener.onFileEnd()
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            // 如果解析失败，说明这不是一个文件传输消息
            return false
        }
        return false
    }

    fun sendFile(fileUri: android.net.Uri, fileName: String, fileData: ByteArray) {
        thread {
            try {
                // 发送文件开始标记
                val startInfo = JSONObject().apply {
                    put("type", FILE_START)
                    put("name", fileName)
                    put("size", fileData.size)
                }
                writer?.write(startInfo.toString() + "\n")
                writer?.flush()

                // 分块发送文件数据
                val chunkSize = 1024
                var sentSize = 0
                while (sentSize < fileData.size) {
                    val end = minOf(sentSize + chunkSize, fileData.size)
                    val chunk = fileData.sliceArray(sentSize until end)
                    val base64Chunk = Base64.encodeToString(chunk, Base64.DEFAULT)

                    val dataInfo = JSONObject().apply {
                        put("type", FILE_DATA)
                        put("data", base64Chunk)
                    }
                    writer?.write(dataInfo.toString() + "\n")
                    writer?.flush()

                    sentSize += chunk.size
                    val progress = (sentSize.toFloat() / fileData.size) * 100
                    listener.onSendFileProgress(progress)
                }

                // 发送文件结束标记
                val endInfo = JSONObject().apply {
                    put("type", FILE_END)
                }
                writer?.write(endInfo.toString() + "\n")
                writer?.flush()

                listener.onSendFileComplete()
            } catch (e: Exception) {
                Log.e("SocketClient", "文件发送错误: ${e.message}")
                listener.onSendFileFailed("文件发送失败: ${e.message}")
            }
        }
    }

    interface OnMessageReceived {
        fun onMessage(message: String)
        fun onFileReceived(fileName: String, fileSize: Int)
        fun onFileProgress(fileData: ByteArray)
        fun onFileEnd()
        fun onSendFileProgress(progress: Float)
        fun onSendFileComplete()
        fun onSendFileFailed(error: String)
    }
}