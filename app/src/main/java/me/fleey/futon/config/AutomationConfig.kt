/*
 * Futon - Futon Daemon Client
 * Copyright (C) 2025 Fleey
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.fleey.futon.config

/**
 * Centralized configuration for automation engine.
 */
object AutomationConfig {

  /**
   * Coordinate optimization settings.
   */
  object CoordinateOptimization {
    const val MAX_DISTANCE = 150
  }

  /**
   * Hot path settings.
   */
  object HotPath {
    const val DEFAULT_AI_FALLBACK_THRESHOLD = 5

    const val MIN_CONFIDENCE = 0.7f
  }

  /**
   * Daemon recovery settings.
   */
  object DaemonRecovery {
    const val MAX_RESTART_ATTEMPTS = 3

    const val INITIAL_BACKOFF_MS = 200L

    const val MAX_BACKOFF_MS = 1600L
  }

  /**
   * AI request settings.
   */
  object AIRequest {
    const val DEFAULT_TIMEOUT_MS = 180_000L

    const val MIN_RETRY_DELAY_MS = 1000L

    const val MAX_RETRY_DELAY_MS = 30_000L
  }

  /**
   * Action execution settings.
   */
  object ActionExecution {
    const val DEFAULT_TAP_DURATION_MS = 100L

    const val DEFAULT_SWIPE_DURATION_MS = 300L

    const val DEFAULT_WAIT_DURATION_MS = 1000L

    const val MAX_INPUT_RETRIES = 3

    const val APP_LAUNCH_DELAY_MS = 500L
  }

  /**
   * Common button keywords for target element matching.
   */
  object ButtonKeywords {
    val CHINESE = listOf(
      "发送", "确定", "确认", "取消", "返回", "下一步", "上一步",
      "提交", "保存", "删除", "编辑", "添加", "搜索", "登录", "注册",
      "同意", "拒绝", "允许", "禁止", "开始", "结束", "完成",
      "是", "否", "好", "好的", "知道了", "我知道了",
    )

    val ENGLISH = listOf(
      "Send", "OK", "Cancel", "Back", "Next", "Previous",
      "Submit", "Save", "Delete", "Edit", "Add", "Search", "Login", "Register",
      "Accept", "Decline", "Allow", "Deny", "Start", "Stop", "Done", "Finish",
      "Yes", "No", "Got it", "Confirm",
    )

    val ALL = CHINESE + ENGLISH
  }

  /**
   * Input field detection patterns.
   */
  object InputFieldPatterns {
    val CLASS_PATTERNS = listOf(
      "EditText",
      "AutoCompleteTextView",
      "SearchView",
      "TextInputEditText",
      "AppCompatEditText",
      "SearchAutoComplete",
    )

    val HINT_PATTERNS = listOf(
      "发消息", "输入消息", "输入", "发送", "说点什么",
      "type a message", "message", "input", "send", "write",
      "搜索", "search", "请输入", "编辑",
    )
  }

  /**
   * Logging settings.
   */
  object Logging {
    /** Maximum execution logs to keep */
    const val MAX_EXECUTION_LOGS = 50

    /** Maximum action history entries per task */
    const val MAX_ACTION_HISTORY = 100
  }
}
