/*
 * Futon - Silent IME Service for Unicode Text Input
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package me.fleey.futon.service

import android.inputmethodservice.InputMethodService
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Silent IME Service for injecting Unicode text.
 */
class FutonImeService : InputMethodService() {

  private var serverSocket: LocalServerSocket? = null
  private var listenerThread: Thread? = null
  private val isRunning = AtomicBoolean(false)
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "FutonImeService created")
  }

  override fun onCreateInputView(): View? {
    // No UI - return null for invisible IME
    return null
  }

  override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
    super.onStartInput(attribute, restarting)
    Log.d(TAG, "onStartInput: restarting=$restarting")
    startSocketListener()
  }

  override fun onBindInput() {
    super.onBindInput()
    Log.d(TAG, "onBindInput")
    startSocketListener()
  }

  override fun onFinishInput() {
    super.onFinishInput()
    Log.d(TAG, "onFinishInput")
  }

  override fun onWindowShown() {
    super.onWindowShown()
    Log.d(TAG, "IME window shown")
  }

  override fun onWindowHidden() {
    super.onWindowHidden()
    Log.d(TAG, "IME window hidden")
  }

  private fun startSocketListener() {
    if (isRunning.getAndSet(true)) {
      // Already running
      return
    }

    listenerThread = thread(name = "FutonImeSocket") {
      try {
        // Close existing socket if any
        serverSocket?.close()

        serverSocket = LocalServerSocket(SOCKET_NAME)
        Log.i(TAG, "Socket server started: $SOCKET_NAME")

        while (isRunning.get()) {
          try {
            val socket = serverSocket?.accept()
            if (socket != null) {
              handleInput(socket)
            }
          } catch (e: Exception) {
            if (isRunning.get()) {
              Log.e(TAG, "Error accepting socket: ${e.message}")
              // Brief delay before retry
              Thread.sleep(100)
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Socket server error: ${e.message}")
      } finally {
        isRunning.set(false)
      }
    }
  }

  private fun stopSocketListener() {
    isRunning.set(false)
    try {
      serverSocket?.close()
      serverSocket = null
    } catch (e: Exception) {
      Log.w(TAG, "Error closing socket: ${e.message}")
    }
    listenerThread?.interrupt()
    listenerThread = null
  }

  private fun handleInput(socket: LocalSocket) {
    try {
      socket.soTimeout = SOCKET_TIMEOUT_MS

      socket.inputStream.use { inputStream ->
        val buffer = ByteArray(MAX_BUFFER_SIZE)
        val bytesRead = inputStream.read(buffer)

        if (bytesRead > 0) {
          val text = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
          Log.d(TAG, "Received: ${text.length} chars")

          val latch = java.util.concurrent.CountDownLatch(1)
          var success = false

          mainHandler.post {
            val ic = currentInputConnection
            Log.d(TAG, "InputConnection: ${ic != null}")
            success = commitTextToInput(text)
            latch.countDown()
          }

          latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)

          socket.outputStream.use { out ->
            out.write(if (success) 0 else 1)
            out.flush()
          }
          Log.d(TAG, "Response sent: success=$success")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling input: ${e.message}")
    } finally {
      try {
        socket.close()
      } catch (e: Exception) {
        // Ignore
      }
    }
  }

  private fun commitTextToInput(text: String): Boolean {
    val ic = currentInputConnection
    if (ic == null) {
      Log.w(TAG, "No InputConnection, waiting...")
      // Wait briefly for InputConnection to become available
      Thread.sleep(100)
      val retryIc = currentInputConnection
      if (retryIc == null) {
        Log.e(TAG, "Still no InputConnection after retry")
        return false
      }
      return commitWithConnection(retryIc, text)
    }
    return commitWithConnection(ic, text)
  }

  private fun commitWithConnection(ic: android.view.inputmethod.InputConnection, text: String): Boolean {
    return try {
      ic.beginBatchEdit()
      val result = ic.commitText(text, 1)
      ic.endBatchEdit()

      if (result) {
        Log.i(TAG, "Committed: ${text.length} chars")
      } else {
        Log.w(TAG, "commitText returned false")
      }
      result
    } catch (e: Exception) {
      Log.e(TAG, "commitText error: ${e.message}")
      false
    }
  }

  override fun onDestroy() {
    Log.i(TAG, "FutonImeService destroying")
    stopSocketListener()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "FutonImeService"
    private const val SOCKET_NAME = "futon_ime_socket"
    private const val MAX_BUFFER_SIZE = 8192
    private const val SOCKET_TIMEOUT_MS = 5000
  }
}
