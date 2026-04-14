package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.core.MediaType

object ProblemMediaType {
    const val APPLICATION_PROBLEM_JSON = "application/problem+json"
    val APPLICATION_PROBLEM_JSON_TYPE: MediaType = MediaType.valueOf(APPLICATION_PROBLEM_JSON)
}
