package com.example.riskscanner

import org.json.JSONArray
import org.json.JSONObject

internal val binanceAuditPaths=setOf("/sapi/v1/account/apiRestrictions","/api/v3/account","/sapi/v1/account/status","/sapi/v1/account/apiTradingStatus")
internal fun signedBinanceAudit(account:LinkedExchangeAccount,path:String,now:Long):AccountAuditRequest{
 require(account.provider==AccountProvider.BINANCE&&path in binanceAuditPaths&&now>0);validateAccountCredentials(account)
 val query=(if(path=="/api/v3/account")"omitZeroBalances=true&" else "")+"recvWindow=5000&timestamp=$now"
 return AccountAuditRequest(account.provider,"https://api.binance.com$path?$query&signature=${auditHmac(account.secret,query)}",mapOf("X-MBX-APIKEY" to account.apiKey))
}

data class ApiRestrictions(val accountStatus:String,val canTrade:Boolean?,val canDeposit:Boolean?,val canWithdraw:Boolean?,val apiLocked:Boolean?,val plannedRecoverTime:Long?,val exchangeUpdatedAt:Long?,val thresholds:Map<String,Long>,val unavailable:List<String>){
 val requiresFollowup get()=apiLocked==true||canTrade==false||canDeposit==false||canWithdraw==false||accountStatus.isNotEmpty()&&accountStatus!="Normal"
 fun json()=JSONObject().put("source","binance_api").put("scope","reported_account_and_api_permissions").put("actualCauseConfirmed",false).put("accountStatus",accountStatus).put("canTrade",canTrade?:JSONObject.NULL).put("canDeposit",canDeposit?:JSONObject.NULL).put("canWithdraw",canWithdraw?:JSONObject.NULL).put("apiLocked",apiLocked?:JSONObject.NULL).put("plannedRecoverTime",plannedRecoverTime?:JSONObject.NULL).put("exchangeUpdatedAt",exchangeUpdatedAt?:JSONObject.NULL).put("thresholds",JSONObject(thresholds)).put("unavailable",JSONArray(unavailable))
 companion object{fun parse(j:JSONObject):ApiRestrictions{
  require(j.getString("source")=="binance_api"&&j.getString("scope")=="reported_account_and_api_permissions"&&!j.getBoolean("actualCauseConfirmed"))
  fun flag(key:String):Boolean?=if(j.isNull(key))null else (j.get(key) as? Boolean?:error("Invalid flag"))
  fun time(key:String):Long?=if(j.isNull(key))null else j.getLong(key).also{require(it>=0)}
  val t=j.getJSONObject("thresholds");val thresholds=t.keys().asSequence().associateWith{require(it in setOf("GCR","IFER","UFR"));t.getLong(it).also{v->require(v>=0)}}
  val u=j.getJSONArray("unavailable");require(u.length()<=2);val unavailable=(0 until u.length()).map{u.getString(it).also{v->require(v.length<=220)}}
  return ApiRestrictions(j.getString("accountStatus").also{require(it.length<=300)},flag("canTrade"),flag("canDeposit"),flag("canWithdraw"),flag("apiLocked"),time("plannedRecoverTime"),time("exchangeUpdatedAt"),thresholds,unavailable)
 }}
}

internal fun requireBinanceReadOnly(j:JSONObject){
 if(j.has("code"))auditApiError(AccountProvider.BINANCE,j.opt("code").toString())
 val writes=setOf("enableWithdrawals","enableInternalTransfer","enableMargin","enableFutures","permitsUniversalTransfer","enableVanillaOptions","enableSpotAndMarginTrading")
 if(j.opt("enableReading")!=true||writes.any{j.opt(it)!=false}||j.keys().asSequence().any{(it.startsWith("enable")||it.startsWith("permits"))&&it !in setOf("enableReading","enableFixReadOnly")&&j.opt(it)!=false})throw AccountAuditError("write_key","Binance의 거래·출금·이체 권한을 모두 끈 읽기 전용 키만 연결할 수 있습니다")
}
internal fun queryBinanceAudit(account:LinkedExchangeAccount,now:Long,transport:(AccountAuditRequest)->JSONObject):ApiKycStatus{
 val permissions=transport(signedBinanceAudit(account,"/sapi/v1/account/apiRestrictions",now));requireBinanceReadOnly(permissions)
 val info=transport(signedBinanceAudit(account,"/api/v3/account",System.currentTimeMillis()))
 if(info.has("code"))auditApiError(AccountProvider.BINANCE,info.opt("code").toString())
 val uid=info.get("uid").toString();require(validExchangeUid(uid))
 if(account.uid.isNotEmpty()&&account.uid!=uid)throw AccountAuditError("uid_mismatch","연결한 UID와 Binance 응답이 다릅니다. 결과를 적용하지 않았습니다")
 fun flag(key:String)=info.opt(key) as? Boolean
 val unavailable=mutableListOf<String>()
 fun optional(path:String,read:(JSONObject)->Unit){try{val j=transport(signedBinanceAudit(account,path,System.currentTimeMillis()));if(j.has("code"))auditApiError(AccountProvider.BINANCE,j.opt("code").toString());read(j)}catch(e:Exception){if(e is AccountAuditError&&e.kind=="rate_limit")throw e;unavailable.add((if(path.endsWith("apiTradingStatus"))"API 거래 제한" else "계정 상태")+": "+(if(e is AccountAuditError)e.message.orEmpty() else "응답 확인 실패 · 미확인"))}}
 var status="";var locked:Boolean?=null;var recover:Long?=null;var updated:Long?=null;var thresholds=emptyMap<String,Long>()
 optional("/sapi/v1/account/status"){val raw=it.get("data");require(raw is String&&raw.isNotBlank()&&raw.length<=300);status=raw}
 optional("/sapi/v1/account/apiTradingStatus"){
  val d=it.getJSONObject("data");val l=d.get("isLocked");require(l is Boolean)
  val r=if(d.isNull("plannedRecoverTime"))null else d.getLong("plannedRecoverTime").also{v->require(v>=0)}
  val u=if(d.isNull("updateTime"))null else d.getLong("updateTime").also{v->require(v>=0)}
  val t=d.optJSONObject("triggerCondition");val values=setOf("GCR","IFER","UFR").filter{t?.has(it)==true}.associateWith{t!!.getLong(it).also{v->require(v>=0)}}
  locked=l;recover=r;updated=u;thresholds=values
 }
 val restrictions=ApiRestrictions(status,flag("canTrade"),flag("canDeposit"),flag("canWithdraw"),locked,recover,updated,thresholds,unavailable)
 return ApiKycStatus(uid,"","","unknown","api_account","verified_read_only",System.currentTimeMillis(),restrictions)
}
