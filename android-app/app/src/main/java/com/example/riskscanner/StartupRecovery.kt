package com.example.riskscanner

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** A bounded public error code, never an upstream message or credential. */
internal class StartupRequestException(val code:String):Exception(startupError(code))

internal fun startupStopsBatch(error:Exception):Boolean = error is StartupRequestException && error.code in setOf(
 "provider_credentials", "provider_quota", "provider_rate_limit", "provider_model_access",
 "provider_schema_configuration", "provider_output_configuration", "provider_search_configuration",
 "provider_unavailable", "provider_timeout", "startup_daily_limit", "startup_session_limit",
 "startup_session_expired", "startup_busy", "startup_not_deployed"
)

/** Retry only a server rejection that happened before work began, or renew an
 * expired narrow session once. Each request callback must issue a NEW requestId.
 * Unknown network outcomes and provider failures are not replayed by the app. */
internal suspend fun <T> withStartupRecovery(
 renew:suspend()->Unit,
 pause:suspend(Long)->Unit={delay(it)},
 request:suspend()->T
):T {
 var renewed=false
 var busyRetries=0
 while(true){
  currentCoroutineContext().ensureActive()
  try{return request()}catch(e:StartupRequestException){
   when {
    e.code=="startup_session_expired"&&!renewed->{renewed=true;renew()}
    e.code=="startup_busy"&&busyRetries<2->{busyRetries++;pause(1000L*busyRetries)}
    else->throw e
   }
  }
 }
}
