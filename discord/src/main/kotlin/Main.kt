package org.sinytra

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.GuildMessageCommandInteractionCreateEvent
import dev.kord.core.on
import kotlin.io.path.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

suspend fun main() {
    val token = System.getenv("BOT_TOKEN")
        ?: throw RuntimeException("Missing BOT_TOKEN environment variable")

    val config = readConfig()
    val allowedChannels = config.getLongList("allowedChannels").map(::Snowflake)

    val kord = Kord(token)

    val checkChatCmd = CheckCompatChatCommand()
    val checkChatCmdRegistered = checkChatCmd.register(kord)

    val checkMsgCmd = CheckCompatMessageCommand()
    val checkMsgCmdRegistered = checkMsgCmd.register(kord)

    kord.on<GuildChatInputCommandInteractionCreateEvent> {
        if (!isAllowedChannel(allowedChannels, interaction.channelId)) {
            if (allowedChannels.isNotEmpty() && !allowedChannels.contains(interaction.channelId)) {
                if (allowedChannels.size == 1) {
                    val channel = allowedChannels.first().value
                    interaction.respondEphemeral {
                        content = "Please head over to <#$channel> to use this command."
                    }
                } else {
                    interaction.respondEphemeral {
                        content = "This command can not be used in this channel."
                    }
                }

            }
        }

        if (interaction.invokedCommandId == checkChatCmdRegistered.id) {
            with(checkChatCmd) { handle() }
        }
    }

    kord.on<GuildMessageCommandInteractionCreateEvent> {
        if (interaction.invokedCommandId == checkMsgCmdRegistered.id) {
            with(checkMsgCmd) { handle(!isAllowedChannel(allowedChannels, interaction.channelId)) }
        }
    }

    kord.login()
}

private fun isAllowedChannel(whitelist: List<Snowflake>, id: Snowflake): Boolean {
    return whitelist.isEmpty() || whitelist.contains(id)
}

private fun readConfig(): Config {
    val customConfig = System.getProperty("org.sinytra.probe.discord.config_path")?.let(::Path)
    if (customConfig != null && customConfig.exists()) {
        return customConfig.bufferedReader().use(ConfigFactory::parseReader)
    }

    val configFile = CheckCompatChatCommand::class.java.getResource("/application.conf")
        ?: throw RuntimeException("Missing configuration file")

    return configFile.openStream().bufferedReader().use(ConfigFactory::parseReader)
}