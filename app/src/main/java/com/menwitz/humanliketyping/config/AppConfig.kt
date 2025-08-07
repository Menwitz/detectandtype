package com.menwitz.humanliketyping.config

data class AppConfig(
    val inputIds:       List<String> = emptyList(),
    val sendButtonIds:  List<String> = emptyList(),
    val messageTextIds: List<String> = emptyList(),      // incoming-bubble TextViews
    val inputClass:     String       = "android.widget.EditText"
)

object AppRegistry {
    val map: Map<String, AppConfig> = mapOf(
        /* ───────────── Chat messengers ─────────────────────────────── */
        "com.whatsapp" to AppConfig(
            inputIds       = listOf("com.whatsapp:id/entry"),
            sendButtonIds  = listOf("com.whatsapp:id/send"),
            messageTextIds = listOf("com.whatsapp:id/message_text")
        ),
        "org.telegram.messenger" to AppConfig(
            inputIds       = listOf("org.telegram.messenger:id/chat_edit_text"),
            sendButtonIds  = listOf("org.telegram.messenger:id/chat_send_button"),
            messageTextIds = listOf("org.telegram.messenger:id/message_text")
        ),
        "com.google.android.apps.messaging" to AppConfig(
            inputIds       = listOf("com.google.android.apps.messaging:id/compose_message_text"),
            sendButtonIds  = listOf("com.google.android.apps.messaging:id/send_message_button_icon"),
            messageTextIds = listOf("com.google.android.apps.messaging:id/message_text")
        ),

        /* ───────────── Dating apps ─────────────────────────────────── */
        "com.tinder" to AppConfig(
            inputIds       = listOf("com.tinder:id/messageComposerEditText"),
            sendButtonIds  = listOf("com.tinder:id/sendMessageButton"),
            messageTextIds = listOf("com.tinder:id/message_body")
        ),
        "com.bumble.app" to AppConfig(
            inputIds       = listOf("com.bumble.app:id/message_input_field"),
            sendButtonIds  = listOf("com.bumble.app:id/action_send_message"),
            messageTextIds = listOf("com.bumble.app:id/message_text")
        ),
        "co.hinge.app" to AppConfig(
            inputIds       = listOf("co.hinge.app:id/message_input_edittext"),
            sendButtonIds  = listOf("co.hinge.app:id/send_button"),
            messageTextIds = listOf("co.hinge.app:id/message_text")
        )
    )
}