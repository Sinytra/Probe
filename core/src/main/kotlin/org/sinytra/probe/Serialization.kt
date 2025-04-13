package org.sinytra.probe

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.sinytra.probe.model.ModRepository

fun Application.configureSerialization(repository: ModRepository) {
    install(ContentNegotiation) {
        json()
    }

    routing {
        route("/api/v1/mods") {
            get {
                val mods = repository.allMods()
                call.respond(mods)
            }
        }
    }
}
