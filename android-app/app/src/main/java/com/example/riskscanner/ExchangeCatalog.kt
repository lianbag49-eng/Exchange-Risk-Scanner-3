package com.example.riskscanner

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

object ExchangeCatalog {
 fun load(context:Context):List<Exchange> {
  val data=JSONObject(context.assets.open("exchanges.json").bufferedReader().use{it.readText()}).getJSONArray("exchanges")
  val featured=(0 until data.length()).map { i -> val e=data.getJSONObject(i)
   Exchange(e.getString("name"),e.getString("name").take(2),Color(0xFFD9B56D),e.getString("logoBase64"),e.getInt("rank"),e.getString("infoUrl"))
  }
  val directory=CmcDirectoryStore(context).load()
  val ids=directory.entries.associateBy{it.infoUrl.trimEnd('/')}
  val branded=featured.map{it.copy(cmcId=ids[it.infoUrl.trimEnd('/')]?.id?:0)}
  val byUrl=branded.associateBy{it.infoUrl.trimEnd('/')}
  val byName=branded.associateBy{it.name.lowercase(java.util.Locale.ROOT)}
  val all=directory.entries.map{e->byUrl[e.infoUrl.trimEnd('/')]?:byName[e.name.lowercase(java.util.Locale.ROOT)]?:Exchange(e.name,e.name.take(2),Color(0xFFD9B56D),infoUrl=e.infoUrl,cmcId=e.id)}
  return (branded+all).distinctBy{it.infoUrl.trimEnd('/')}.plus(Exchange("기타 거래소","+",Color(0xFFD9B56D)))
 }
}
