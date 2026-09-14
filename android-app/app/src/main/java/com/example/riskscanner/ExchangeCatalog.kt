package com.example.riskscanner

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

object ExchangeCatalog {
 fun load(context:Context):List<Exchange> {
  val data=JSONObject(context.assets.open("exchanges.json").bufferedReader().use{it.readText()}).getJSONArray("exchanges")
  return (0 until data.length()).map { i -> val e=data.getJSONObject(i)
   Exchange(e.getString("name"),e.getString("name").take(2),Color(0xFFD9B56D),e.getString("logoBase64"),e.getInt("rank"),e.getString("infoUrl"))
  } + Exchange("기타 거래소","+",Color(0xFFD9B56D))
 }
}
