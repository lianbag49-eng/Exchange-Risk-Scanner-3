package com.example.riskscanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class ExchangeAccountStorage(context:Context) {
 private val prefs=context.getSharedPreferences("ers_exchange_connections",Context.MODE_PRIVATE)
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  return store.getKey("ers_exchange_connections_v1",null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
   init(KeyGenParameterSpec.Builder("ers_exchange_connections_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun save(accounts:List<LinkedExchangeAccount>){
  require(accounts.size<=100)
  accounts.forEach{LinkedExchangeAccount.parse(it.json())}
  val data=JSONObject().put("version",1).put("accounts",JSONArray().apply{accounts.forEach{put(it.json())}}).toString().toByteArray(Charsets.UTF_8)
  try{
   val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val blob=cipher.iv+cipher.doFinal(data)
   check(prefs.edit().putString("encrypted",Base64.encodeToString(blob,Base64.NO_WRAP)).commit()){"계정 연결 저장 실패"}
  }finally{data.fill(0)}
 }
 fun load():List<LinkedExchangeAccount>{
  val stored=prefs.getString("encrypted",null)?:return emptyList();val blob=Base64.decode(stored,Base64.NO_WRAP);require(blob.size in 29..2_000_000)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,blob.copyOfRange(0,12)))
  val data=cipher.doFinal(blob.copyOfRange(12,blob.size))
  try{val json=JSONObject(String(data,Charsets.UTF_8));require(json.getInt("version")==1);val list=json.getJSONArray("accounts");require(list.length()<=100);return (0 until list.length()).map{LinkedExchangeAccount.parse(list.getJSONObject(it))}}
  finally{data.fill(0)}
 }
}
