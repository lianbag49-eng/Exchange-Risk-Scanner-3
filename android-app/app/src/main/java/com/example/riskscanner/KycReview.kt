package com.example.riskscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

data class KycReview(val requestId:String,val imageSha256:String,val reviewedAt:String,val model:String,val assessment:String,val displayedStatus:String,val matches:Map<String,String>,val issues:List<String>,val humanDecision:String="unreviewed") {
 fun json():JSONObject=JSONObject().put("requestId",requestId).put("imageSha256",imageSha256).put("reviewedAt",reviewedAt).put("model",model).put("assessment",assessment).put("displayedStatus",displayedStatus).put("matches",JSONObject(matches)).put("issues",JSONArray(issues)).put("officialVerified",false).put("authenticity","not_verified").put("humanDecision",humanDecision)
 companion object {
  fun parse(j:JSONObject):KycReview {
   require(!j.getBoolean("officialVerified")&&j.getString("authenticity")=="not_verified"){"공식 검증 여부를 확인할 수 없는 응답입니다"}
   val assessment=j.getString("assessment");require(assessment in listOf("insufficient","review_required","no_mismatch_detected"))
   val status=j.getString("displayedStatus");require(status in listOf("approved","pending","rejected","not_verified","unknown"))
   val hash=j.getString("imageSha256");require(hash.matches(Regex("[0-9a-f]{64}")))
   val m=j.getJSONObject("matches");val matches=listOf("exchange","uid","country").associateWith{m.getString(it).also{v->require(v in listOf("match","mismatch","unreadable"))}}
   val a=j.getJSONArray("issues");require(a.length()<=20);val issues=(0 until a.length()).map{a.getString(it).also{x->require(x in kycLabels)}}
   val decision=j.getString("humanDecision");require(decision in listOf("unreviewed","follow_up","official_check_requested"))
   if(assessment=="no_mismatch_detected")require(matches.values.all{it=="match"}&&issues.isEmpty()&&status=="approved")
   return KycReview(j.getString("requestId").take(64),hash,j.getString("reviewedAt").take(40),j.getString("model").take(80),assessment,status,matches,issues,decision)
  }
 }
}
val kycLabels=mapOf("insufficient" to "판독 근거 부족","review_required" to "추가 확인 필요","no_mismatch_detected" to "대조 항목 불일치 미검출","approved" to "승인 표시","pending" to "심사 중 표시","rejected" to "거절 표시","not_verified" to "미인증 표시","unknown" to "판독 불가","match" to "일치","mismatch" to "불일치","unreadable" to "판독 불가","blurry" to "이미지가 흐림","cropped" to "화면 일부가 잘림","glare" to "빛 반사","small_text" to "글자가 너무 작음","possible_edit" to "편집 의심 단서 · 위조 확정 아님","conflicting_text" to "화면 내 문구 충돌","exchange_mismatch" to "거래소 이름 불일치","exchange_unreadable" to "거래소 이름 판독 불가","uid_mismatch" to "UID 불일치","uid_unreadable" to "UID 판독 불가","country_mismatch" to "KYC 국가 불일치","country_unreadable" to "KYC 국가 판독 불가","unsupported_document" to "KYC 상태 화면 필요 · 신분증 진위 검사 미지원","readability_limited" to "읽을 수 있는 정보 부족","status_requires_review" to "인증 상태 추가 확인 필요","unreviewed" to "담당자 미검토","follow_up" to "추가 증빙 요청","official_check_requested" to "공식 확인 요청")
fun kycText(code:String)=kycLabels[code]?:"확인 필요"
fun kycEndpoint(base:String):URI {val u=URI(base.trim().trimEnd('/'));require(u.scheme=="https"&&!u.host.isNullOrBlank()&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null){"HTTPS 검토 서버 주소를 입력하세요"};return URI(u.toString()+"/v1/kyc/reviews")}
fun InputStream.kycLimitedBytes(limit:Int):ByteArray {val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=limit){"파일 크기 제한을 초과했습니다"};out.write(buffer,0,n)};return out.toByteArray()}
fun prepareKycImage(context:Context,uri:Uri):ByteArray {
 val raw=context.contentResolver.openInputStream(uri)?.use{it.kycLimitedBytes(12*1024*1024)}?:error("이미지를 읽지 못했습니다")
 val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(raw,0,raw.size,bounds);require(bounds.outWidth>0&&bounds.outHeight>0){"지원되는 이미지를 선택하세요"}
 var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
 val bitmap=BitmapFactory.decodeByteArray(raw,0,raw.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("이미지 판독 실패")
 raw.fill(0);return try{ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.JPEG,88,out);out.toByteArray().also{require(it.size<=4*1024*1024){"이미지를 줄여 다시 선택하세요"}}}}finally{bitmap.recycle()}
}
fun requestKycReview(base:String,token:String,image:ByteArray,record:Record):KycReview {
 require(token.trim().length>=32){"검토 서버 접속 토큰을 입력하세요"}
 val id=UUID.randomUUID().toString();val hash=MessageDigest.getInstance("SHA-256").digest(image).joinToString(""){"%02x".format(it)}
 val body=JSONObject().put("requestId",id).put("consent",true).put("expected",JSONObject().put("exchange",record.name).put("uid",record.uid).put("country",record.country)).put("image",JSONObject().put("mimeType","image/jpeg").put("data",Base64.encodeToString(image,Base64.NO_WRAP)))
 val connection=kycEndpoint(base).toURL().openConnection() as HttpsURLConnection
 try{connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=60000;connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer "+token.trim());connection.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
  val code=connection.responseCode
  if(code!=200)error(when(code){401->"서버 접속 토큰을 확인하세요";429->"요청이 많습니다. 잠시 후 직접 다시 시도하세요";413->"이미지가 너무 큽니다";409->"이미 요청된 검토입니다";422->"이 이미지는 AI 검토를 완료하지 못했습니다";else->"AI 검토를 완료하지 못했습니다 · HTTP $code"})
  val json=connection.inputStream.use{String(it.kycLimitedBytes(65536),Charsets.UTF_8)};return KycReview.parse(JSONObject(json)).also{require(it.requestId==id&&it.imageSha256==hash){"첨부 이미지와 응답이 일치하지 않습니다"}}
 }finally{connection.disconnect()}
}
