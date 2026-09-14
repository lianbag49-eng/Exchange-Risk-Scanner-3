package com.example.riskscanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import org.json.JSONArray
import org.json.JSONObject

class RecordStorage(context:Context) {
 private val prefs=context.getSharedPreferences("ers_records",Context.MODE_PRIVATE)
 private fun key():SecretKey {val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};return (store.getKey("ers_records_v1",null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("ers_records_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()}
 fun save(records:List<Record>){
  val data=JSONArray();records.take(200).forEach{r->val s=r.snapshot;data.put(JSONObject().put("exchange",r.exchange.name).put("name",r.name).put("uid",r.uid).put("country",r.country).put("worker",r.worker).put("time",r.time).put("score",r.result.score).put("level",r.result.level).put("signals",JSONArray().apply{r.result.signals.forEach{put(JSONObject().put("label",it.label).put("value",it.value).put("points",it.points).put("triggered",it.triggered).put("advice",it.advice).put("checked",it.checked))}}).put("snapshot",JSONObject().put("deviceCountry",s.deviceCountry).put("timezoneId",s.timezoneId).put("locale",s.locale).put("networkType",s.networkType).put("vpnActive",s.vpnActive).put("rootedSuspected",s.rootedSuspected).put("emulatorSuspected",s.emulatorSuspected).put("developerOptions",s.developerOptions).put("adbEnabled",s.adbEnabled).put("secureLockScreen",s.secureLockScreen).put("securityPatch",s.securityPatch).put("proxyConfigured",s.proxyConfigured).put("networkValidated",s.networkValidated?:JSONObject.NULL).put("deviceModel",s.deviceModel)))}
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val blob=cipher.iv+cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8));check(prefs.edit().putString("data",Base64.encodeToString(blob,Base64.NO_WRAP)).commit()){"저장 실패"}
 }
 fun load(exchanges:List<Exchange>):List<Record>{val text=prefs.getString("data",null)?:return emptyList();val blob=Base64.decode(text,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,blob.copyOfRange(0,12)));val data=JSONArray(String(cipher.doFinal(blob.copyOfRange(12,blob.size)),Charsets.UTF_8));return (0 until data.length()).map{i->val r=data.getJSONObject(i);val s=r.getJSONObject("snapshot");val signals=r.getJSONArray("signals");Record(exchanges.find{it.name==r.getString("exchange")}?:exchanges.last(),r.getString("name"),r.getString("uid"),r.getString("country"),RiskResult(r.getInt("score"),r.getString("level"),(0 until signals.length()).map{j->val x=signals.getJSONObject(j);RiskSignal(x.getString("label"),x.getString("value"),x.getInt("points"),x.getBoolean("triggered"),x.optString("advice"),x.optBoolean("checked",x.optString("value")!="확인 불가"))}),DeviceSnapshot(s.getString("deviceCountry"),s.getString("timezoneId"),s.getString("locale"),s.getString("networkType"),s.getBoolean("vpnActive"),s.getBoolean("rootedSuspected"),s.getBoolean("emulatorSuspected"),s.getBoolean("developerOptions"),s.getBoolean("adbEnabled"),s.getBoolean("secureLockScreen"),s.getString("securityPatch"),s.optBoolean("proxyConfigured"),if(s.isNull("networkValidated")) null else s.getBoolean("networkValidated"),s.optString("deviceModel")),r.getString("time"),r.optString("worker"))}}
}
