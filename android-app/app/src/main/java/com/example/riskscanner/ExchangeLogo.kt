package com.example.riskscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal fun exchangeLogoUrl(id:Int):String{require(id>0);return "https://s2.coinmarketcap.com/static/img/exchanges/64x64/$id.png"}
internal fun decodeExchangeLogo(bytes:ByteArray):Bitmap?{
 if(bytes.size !in 8..131072)return null
 val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
 if(bounds.outWidth !in 1..256||bounds.outHeight !in 1..256)return null
 return BitmapFactory.decodeByteArray(bytes,0,bytes.size)
}
internal fun fetchExchangeLogo(id:Int):ByteArray{
 val c=URI(exchangeLogoUrl(id)).toURL().openConnection() as HttpsURLConnection
 try{c.connectTimeout=5000;c.readTimeout=5000;c.instanceFollowRedirects=false
  check(c.responseCode==200&&c.contentType?.substringBefore(';')=="image/png")
  return c.inputStream.use{it.kycLimitedBytes(131072)}
 }finally{c.disconnect()}
}
class ExchangeLogoCache(context:Context){
 private val directory=File(context.cacheDir,"exchange-logos-v1")
 fun load(id:Int,fetch:(Int)->ByteArray=::fetchExchangeLogo):Bitmap?{
  if(id<=0)return null
  val file=File(directory,"$id.png")
  runCatching{if(file.isFile&&file.length()<=131072&&System.currentTimeMillis()-file.lastModified() in 0..2_592_000_000L)decodeExchangeLogo(file.readBytes()) else null}.getOrNull()?.let{return it}
  return runCatching{val bytes=fetch(id);val bitmap=decodeExchangeLogo(bytes)?:return null
   runCatching{directory.mkdirs();file.writeBytes(bytes);val files=directory.listFiles().orEmpty();if(files.size>512)files.sortedBy{it.lastModified()}.take(files.size-512).forEach{it.delete()}}
   bitmap
  }.getOrNull()
 }
}
fun catalogExchange(exchanges:List<Exchange>,entry:CmcExchange)=exchanges.find{it.infoUrl.trimEnd('/')==entry.infoUrl.trimEnd('/')}?:Exchange(entry.name,entry.name.take(2),Color(0xFFD9B56D),infoUrl=entry.infoUrl,cmcId=entry.id)

@Composable fun ExchangeBrandLogo(exchange:Exchange,size:Int=42){
 val context=LocalContext.current
 var bitmap by remember(exchange.logo,exchange.cmcId){mutableStateOf(exchange.logo?.let{runCatching{decodeExchangeLogo(Base64.decode(it,Base64.DEFAULT))}.getOrNull()})}
 LaunchedEffect(exchange.logo,exchange.cmcId){if(bitmap==null&&exchange.cmcId>0)bitmap=withContext(Dispatchers.IO){ExchangeLogoCache(context).load(exchange.cmcId)}}
 Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF080D0C)),contentAlignment=Alignment.Center){
  val icon=bitmap
  if(icon!=null)Image(icon.asImageBitmap(),exchange.name+" 로고",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().padding(4.dp))
  else Text(exchange.mark,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
 }
}
