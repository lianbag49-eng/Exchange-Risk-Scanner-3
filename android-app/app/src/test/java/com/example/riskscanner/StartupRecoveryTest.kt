package com.example.riskscanner

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StartupRecoveryTest {
 @Test fun busyRetriesAreBounded() = runBlocking {
  var calls=0;val pauses=mutableListOf<Long>()
  try{withStartupRecovery(renew={fail("must not renew")},pause={pauses.add(it)}){calls++;throw StartupRequestException("startup_busy")};fail("expected failure")}
  catch(e:StartupRequestException){assertEquals("startup_busy",e.code)}
  assertEquals(3,calls);assertEquals(listOf(1000L,2000L),pauses)
 }
 @Test fun expiredSessionRenewsOnlyOnce() = runBlocking {
  var calls=0;var renewals=0
  try{withStartupRecovery(renew={renewals++},pause={fail("must not wait")}){calls++;throw StartupRequestException("startup_session_expired")};fail("expected failure")}
  catch(e:StartupRequestException){assertEquals("startup_session_expired",e.code)}
  assertEquals(2,calls);assertEquals(1,renewals)
 }
 @Test fun transientRecoveryCanReturnTheRealResult() = runBlocking {
  var calls=0;var renewals=0
  val result=withStartupRecovery(renew={renewals++},pause={}){calls++;if(calls==1)throw StartupRequestException("startup_busy");if(calls==2)throw StartupRequestException("startup_session_expired");"validated-result"}
  assertEquals("validated-result",result);assertEquals(3,calls);assertEquals(1,renewals)
 }
 @Test fun quotaAndConfigurationNeverReplay() = runBlocking {
  for(code in listOf("provider_quota","provider_credentials","provider_model_access","provider_search_configuration","provider_rate_limit")){
   var calls=0
   try{withStartupRecovery(renew={fail("must not renew")},pause={fail("must not wait")}){calls++;throw StartupRequestException(code)};fail("expected failure")}
   catch(e:StartupRequestException){assertEquals(code,e.code);assertTrue(startupStopsBatch(e))}
   assertEquals(1,calls)
  }
 }
 @Test fun cancellationIsNeverConvertedIntoAReviewFailure() = runBlocking {
  var calls=0
  try{withStartupRecovery(renew={},pause={}){calls++;throw CancellationException("test cancellation")};fail("expected cancellation")}
  catch(e:CancellationException){assertEquals("test cancellation",e.message)}
  assertEquals(1,calls)
 }
 private fun reports(count:Int)=List(count){PreflightReport(DiagnosticApp("test.invented.exchange$it","Invented exchange $it","test",true),0L,emptyList(),emptyList(),"","")}
 private fun exchanges(count:Int)=List(count){CmcExchange(it+1,"Invented exchange $it","invented-$it","active")}
 @Test fun sharedQuotaFailureStopsAllRemainingBatchesWithoutFakeSuccess() = runBlocking {
  val reports=reports(121);val exchanges=exchanges(6);var requests=0;var policyRequests=0
  val failedApps=mutableListOf<String>();val failedExchanges=mutableListOf<Int>()
  val gateway=object:StartupGateway{
   override suspend fun connect(){}
   override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>{requests++;throw StartupRequestException("provider_quota")}
   override suspend fun policy(exchange:CmcExchange):StartupPolicy{policyRequests++;error("must not call")}
  }
  runStartupReview(reports,exchanges,gateway,{fail("must not invent result")},{fail("must not invent policy")},{_,a,e->failedApps.addAll(a);failedExchanges.addAll(e)},{})
  assertEquals(1,requests);assertEquals(0,policyRequests);assertEquals(reports.map{it.id},failedApps);assertEquals(exchanges.map{it.id},failedExchanges)
 }
 @Test fun isolatedBatchFailureStillAttemptsOtherBatches() = runBlocking {
  var requests=0;val failed=mutableListOf<String>();val reports=reports(121)
  val gateway=object:StartupGateway{
   override suspend fun connect(){}
   override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview>{requests++;error("isolated fixture failure")}
   override suspend fun policy(exchange:CmcExchange):StartupPolicy=error("not requested")
  }
  runStartupReview(reports,emptyList(),gateway,{fail("must not invent result")},{fail("not requested")},{_,a,_->failed.addAll(a)},{})
  assertEquals(3,requests);assertEquals(reports.map{it.id},failed)
 }
 @Test fun searchConfigurationStopsRemainingExchangesWithoutDroppingThem() = runBlocking {
  var calls=0;val exchanges=exchanges(6);val failed=mutableListOf<Int>()
  val gateway=object:StartupGateway{
   override suspend fun connect(){}
   override suspend fun preflight(reports:List<PreflightReport>):List<AiPreflightReview> = error("not requested")
   override suspend fun policy(exchange:CmcExchange):StartupPolicy{calls++;throw StartupRequestException("provider_search_configuration")}
  }
  runStartupReview(emptyList(),exchanges,gateway,{fail("not requested")},{fail("must not invent result")},{_,_,e->failed.addAll(e)},{})
  assertEquals(1,calls);assertEquals(exchanges.map{it.id},failed)
 }
}
