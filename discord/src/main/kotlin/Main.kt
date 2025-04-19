package org.sinytra

import dev.kord.core.Kord
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.GuildMessageCommandInteractionCreateEvent
import dev.kord.core.on

suspend fun main() {
    val token = System.getenv("BOT_TOKEN")
        ?: throw RuntimeException("Missing BOT_TOKEN environment variable")

    val kord = Kord(token)

    val checkChatCmd = CheckCompatChatCommand()
    val checkChatCmdRegistered = checkChatCmd.register(kord)

    val checkMsgCmd = CheckCompatMessageCommand()
    val checkMsgCmdRegistered = checkMsgCmd.register(kord)

    kord.on<GuildChatInputCommandInteractionCreateEvent> { 
        if (interaction.invokedCommandId == checkChatCmdRegistered.id) {
            with(checkChatCmd) { handle() }
        }
    }

    kord.on<GuildMessageCommandInteractionCreateEvent> {
        if (interaction.invokedCommandId == checkMsgCmdRegistered.id) {
            with(checkMsgCmd) { handle() }
        }
    }

    kord.login()
}