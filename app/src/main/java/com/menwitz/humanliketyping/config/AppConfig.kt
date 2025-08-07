
package com.menwitz.humanliketyping.config

data class AppConfig(
    val inputIds: List<String> = emptyList(),
    val sendButtonIds: List<String> = emptyList(),
    val inputClass: String = "android.widget.EditText"
)

object AppRegistry {
    val map: Map<String, AppConfig> = mapOf(
        "com.whatsapp" to AppConfig(
            inputIds      = listOf("com.whatsapp:id/entry"),
            sendButtonIds = listOf("com.whatsapp:id/send")
        ),
        "org.telegram.messenger" to AppConfig(
            inputIds      = listOf("org.telegram.messenger:id/edit_text"),
            sendButtonIds = listOf("org.telegram.messenger:id/button_send")
        ),
        "com.google.android.apps.messaging" to AppConfig(
            inputIds      = listOf("com.google.android.apps.messaging:id/compose_message_text"),
            sendButtonIds = listOf("com.google.android.apps.messaging:id/send_message_button_icon")
        ),
        "com.tinder" to AppConfig(),
        "com.bumble.app" to AppConfig(),
        "co.hinge.app" to AppConfig()

    )
}